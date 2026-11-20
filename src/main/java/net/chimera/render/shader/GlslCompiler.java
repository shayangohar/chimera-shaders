// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.shaderpack.GlslVendorExtensions;
import net.chimera.shaderpack.PackDevice;
import net.chimera.shaderpack.SpirvDeviceRequirements;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Compiles GLSL to SPIR-V for Vulkan 1.2. The source goes to shaderc from the heap, since its CharSequence
 * overloads use LWJGL's 64 KB thread stack, which a pack's stage outgrows.
 */
public final class GlslCompiler {
    private GlslCompiler() {}

    /** As the host compiles its own shaders. */
    public static void optimize(long options) {
        Shaderc.shaderc_compile_options_set_optimization_level(options,
                Shaderc.shaderc_optimization_level_performance);
    }

    /**
     * {@code kind} is a {@code shaderc_glsl_*_shader} constant. A module needing a feature the device lacks
     * fails here, as on a GL driver without it, and never reaches the driver. The caller frees the result with
     * {@link MemoryUtil#memFree}.
     *
     * @throws IllegalStateException with shaderc's report, or what the device lacks
     */
    public static ByteBuffer compile(String name, String source, int kind, LongConsumer configure) {
        PackDevice device = PackDevice.current();
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer text = MemoryUtil.memUTF8(GlslVendorExtensions.forVulkan(source, device), false);
        long result = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            configure.accept(options);
            result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, stack.UTF8(name), stack.UTF8("main"),
                    options);
            if (result == 0L) throw new IllegalStateException(name + ": shaderc returned no result");
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                String report = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException(report == null ? name + ": compilation failed" : report);
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            List<String> missing = device.missing(SpirvDeviceRequirements.of(bytes), stage(kind));
            if (!missing.isEmpty()) {
                throw new IllegalStateException(name + ": needs " + String.join(", ", missing)
                        + ", which the device was created without");
            }
            ByteBuffer module = MemoryUtil.memAlloc(bytes.remaining());
            MemoryUtil.memCopy(bytes, module);
            return module;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            MemoryUtil.memFree(text);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static int stage(int kind) {
        return switch (kind) {
            case Shaderc.shaderc_glsl_vertex_shader -> PackDevice.STAGE_VERTEX;
            case Shaderc.shaderc_glsl_geometry_shader -> PackDevice.STAGE_GEOMETRY;
            case Shaderc.shaderc_glsl_compute_shader -> PackDevice.STAGE_COMPUTE;
            case Shaderc.shaderc_glsl_tess_control_shader -> PackDevice.STAGE_TESSELLATION_CONTROL;
            case Shaderc.shaderc_glsl_tess_evaluation_shader -> PackDevice.STAGE_TESSELLATION_EVALUATION;
            default -> PackDevice.STAGE_FRAGMENT;
        };
    }
}
