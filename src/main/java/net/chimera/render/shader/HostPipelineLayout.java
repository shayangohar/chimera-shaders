// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import net.chimera.render.ChimeraDevice;
import net.chimera.render.PackImageDescriptor;
import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageViewDescriptor;
import net.chimera.shaderpack.BetweenStages;
import net.chimera.shaderpack.PackDescriptorLayout;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackWorkers;
import net.chimera.shaderpack.SpirvLocalInitializer;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.chimera.shaderpack.SpirvTrigPrecision;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/**
 * Builds host pipelines from a {@link PackDescriptorLayout}, one host descriptor per entry at the same binding.
 * A uniform field the host knows by name keeps the host's value; any other comes from the {@link UniformSource},
 * and a field neither supplies fails the build.
 */
public final class HostPipelineLayout {
    private HostPipelineLayout() {}

    @FunctionalInterface
    public interface UniformSource {
        /** Null when this source has no value for the field. */
        Supplier<MappedBuffer> supplier(String name, String type);

        UniformSource HOST_ONLY = (name, type) -> null;
    }

    /** Carries the translated GLSL its message's line numbers refer to. */
    public static final class ShaderCompilationFailure extends RuntimeException {
        private final transient String source;

        ShaderCompilationFailure(RuntimeException cause, String source) {
            super(cause.getMessage(), cause);
            this.source = source;
        }

        public String source() {
            return this.source;
        }
    }

    /** A layout the host cannot bind. */
    public static final class LayoutFailure extends RuntimeException {
        LayoutFailure(String message) {
            super(message);
        }
    }

    /**
     * Pack modules get zero-initialised locals and GL-order trigonometry (SpirvLocalInitializer,
     * SpirvTrigPrecision); Chimera's own shaders keep their bytes.
     */
    public static GraphicsPipeline create(String name, VertexFormat format, PackDescriptorLayout layout,
                                          String vertexSource, String fragmentSource, UniformSource uniforms,
                                          boolean packModules) {
        return create(name, format, layout, vertexSource, BetweenStages.NONE, fragmentSource, uniforms, packModules);
    }

    /**
     * Also adds tessellation stages, drawn as patches of BetweenStages.PATCH_VERTICES, and a geometry stage.
     *
     * @throws LayoutFailure also when the device lacks a stage's feature
     */
    public static GraphicsPipeline create(String name, VertexFormat format, PackDescriptorLayout layout,
                                          String vertexSource, BetweenStages between, String fragmentSource,
                                          UniformSource uniforms, boolean packModules) {
        requireDeviceStages(name, between);
        Pipeline.Builder builder = new Pipeline.Builder(format, name);
        builder.setUniforms(uniformBuffers(layout, uniforms), images(layout, packModules));
        ((ChimeraPipelineBuilderAccessor) builder).chimera$setPushConstants(pushConstants(layout, uniforms));
        List<Stage> stages = new ArrayList<>(5);
        stages.add(new Stage(name + ".vsh", vertexSource, Shaderc.shaderc_glsl_vertex_shader));
        between.present().forEach(stage -> stages.add(betweenStage(name, stage)));
        stages.add(new Stage(name + ".fsh", fragmentSource, Shaderc.shaderc_glsl_fragment_shader));
        // stages compile on the pack workers, the pipeline on the calling thread
        List<SPIRVUtils.SPIRV> compiled = PackWorkers.map(stages, stage -> compile(stage, packModules));
        SPIRVUtils.SPIRV fragment = compiled.get(compiled.size() - 1);
        builder.setSPIRVs(compiled.get(0), fragment);
        if (between.tessellated()) {
            builder.setTessellationSPIRVs(compiled.get(1), compiled.get(2), BetweenStages.PATCH_VERTICES);
        }
        GraphicsPipeline pipeline = between.geometry() == null ? builder.createGraphicsPipeline()
                : GeometryStageModules.construct(compiled.get(compiled.size() - 2).bytecode(),
                        builder::createGraphicsPipeline);
        if (packModules) {
            MrtPipelineContext.recordWrittenOutputs(pipeline, SpirvOutputWrites.writtenLocations(fragment.bytecode()));
        }
        return pipeline;
    }

    /** {@code name} is the file name shaderc's messages report. */
    private record Stage(String name, String source, int kind) {}

    private static Stage betweenStage(String name, BetweenStages.Stage stage) {
        return switch (stage.name()) {
            case "tess_control" -> new Stage(name + ".tcs", stage.source(), Shaderc.shaderc_glsl_tess_control_shader);
            case "tess_evaluation" -> new Stage(name + ".tes", stage.source(),
                    Shaderc.shaderc_glsl_tess_evaluation_shader);
            default -> new Stage(name + ".gsh", stage.source(), Shaderc.shaderc_glsl_geometry_shader);
        };
    }

    private static void requireDeviceStages(String name, BetweenStages between) {
        if (between.geometry() != null && !ChimeraDevice.geometryShader()) {
            throw new LayoutFailure("the device has no geometryShader for " + name + ".gsh");
        }
        if (between.tessellated() && !DeviceManager.isTessellationEnabled()) {
            throw new LayoutFailure("the device has no tessellationShader for " + name + ".tcs");
        }
    }

    /** Builds one of Chimera's own pipelines from its JSON config; every uniform field is the host's. */
    public static GraphicsPipeline create(String name, String configName, VertexFormat format,
                                          UnaryOperator<String> vertexSource) {
        return create(name, configName, format, vertexSource, UnaryOperator.identity());
    }

    public static GraphicsPipeline create(String name, String configName, VertexFormat format,
                                          UnaryOperator<String> vertexSource, UnaryOperator<String> fragmentSource) {
        JsonObject json = ChimeraShaderLoader.loadJson(configName + ".json");
        return create(name, format, PackDescriptorLayout.fromJson(json),
                vertexSource.apply(ChimeraShaderLoader.loadSource(json.get("vertex").getAsString() + ".vsh")),
                fragmentSource.apply(ChimeraShaderLoader.loadSource(json.get("fragment").getAsString() + ".fsh")),
                UniformSource.HOST_ONLY, false);
    }

    /**
     * Uniform blocks, then storage buffers. The host sizes a descriptor set by its count, so bindings must run
     * from 0 without a gap.
     */
    public static List<UBO> uniformBuffers(PackDescriptorLayout layout, UniformSource uniforms) {
        requireDenseBindings(layout);
        List<UBO> buffers = new ArrayList<>();
        for (PackDescriptorLayout.UniformBlock block : layout.uniformBlocks()) {
            if (block.fields().isEmpty()) {
                buffers.add(new UBO(block.binding(), block.stages(), block.size(), null));
                continue;
            }
            AlignedStruct.Builder struct = new AlignedStruct.Builder();
            for (PackDescriptorLayout.Field field : block.fields()) {
                struct.addUniformInfo(hostType(field.type()), field.name());
            }
            UBO buffer = struct.buildUBO(block.binding(), block.stages());
            supply(buffer.getUniforms(), block.fields(), uniforms);
            buffers.add(buffer);
        }
        for (PackDescriptorLayout.StorageBuffer storage : layout.storageBuffers()) {
            buffers.add(new PackStorageBufferDescriptor(storage.binding(), storage.stages(), storage.size(),
                    storage.logicalIndex()));
        }
        return buffers;
    }

    /** VulkanMod binds the albedo and lightmap from the game's units; Chimera binds a pack's other images. */
    static List<ImageDescriptor> images(PackDescriptorLayout layout, boolean packModules) {
        List<ImageDescriptor> images = new ArrayList<>();
        for (PackDescriptorLayout.ImageSlot slot : layout.images()) {
            boolean storage = slot.access() == PackDescriptorLayout.ImageAccess.STORAGE;
            ImageDescriptor image;
            if (slot.viewFormat() != 0) {
                image = new PackStorageViewDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(),
                        slot.viewFormat());
            } else if (packModules && !PackResourcePlan.HOST_BOUND_SLOTS.contains(slot.textureSlot())) {
                image = new PackImageDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(), storage);
            } else {
                image = new ImageDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(), storage);
            }
            // images a stage may also write stay in the general layout
            if (slot.access() == PackDescriptorLayout.ImageAccess.SAMPLED_GENERAL) {
                image.setLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            images.add(image);
        }
        return images;
    }

    /** Null when the layout has none or it holds no field. */
    private static PushConstants pushConstants(PackDescriptorLayout layout, UniformSource uniforms) {
        PackDescriptorLayout.UniformBlock block = layout.pushConstants();
        if (block == null || block.fields().isEmpty()) return null;
        AlignedStruct.Builder struct = new AlignedStruct.Builder();
        for (PackDescriptorLayout.Field field : block.fields()) {
            struct.addUniformInfo(hostType(field.type()), field.name());
        }
        PushConstants constants = struct.buildPushConstant();
        supply(constants.getUniforms(), block.fields(), uniforms);
        return constants;
    }

    private static void supply(List<Uniform> built, List<PackDescriptorLayout.Field> fields, UniformSource uniforms) {
        for (int index = 0; index < fields.size(); index++) {
            PackDescriptorLayout.Field field = fields.get(index);
            if (hostSupplies(field)) continue;
            Supplier<MappedBuffer> supplier = uniforms.supplier(field.name(), field.type());
            if (supplier == null) {
                throw new LayoutFailure("nothing supplies uniform %s (%s)".formatted(field.name(), field.type()));
            }
            built.get(index).setSupplier(supplier);
        }
    }

    private static boolean hostSupplies(PackDescriptorLayout.Field field) {
        return switch (field.type()) {
            case "mat4" -> Uniforms.mat4f_uniformMap.containsKey(field.name());
            case "vec4" -> Uniforms.vec4f_uniformMap.containsKey(field.name());
            case "vec3" -> Uniforms.vec3f_uniformMap.containsKey(field.name());
            case "vec2" -> Uniforms.vec2f_uniformMap.containsKey(field.name());
            case "float" -> Uniforms.vec1f_uniformMap.containsKey(field.name());
            case "int" -> Uniforms.vec1i_uniformMap.containsKey(field.name());
            default -> false;
        };
    }

    /** The host lays out only float vectors; an integer vector has the same std140 layout and copies as bytes. */
    static String hostType(String type) {
        return switch (type) {
            case "ivec2" -> "vec2";
            case "ivec3" -> "vec3";
            case "ivec4" -> "vec4";
            default -> type;
        };
    }

    private static void requireDenseBindings(PackDescriptorLayout layout) {
        BitSet bindings = new BitSet();
        int count = 0;
        for (var block : layout.uniformBlocks()) { bindings.set(block.binding()); count++; }
        for (var buffer : layout.storageBuffers()) { bindings.set(buffer.binding()); count++; }
        for (var image : layout.images()) { bindings.set(image.binding()); count++; }
        if (bindings.cardinality() != count || bindings.nextClearBit(0) != count) {
            throw new LayoutFailure("descriptor bindings must be distinct and run 0.." + (count - 1)
                    + " without a gap: " + bindings);
        }
    }

    /** In a garbage-collected buffer, so a pipeline whose other stage fails leaves nothing to release. */
    private static SPIRVUtils.SPIRV compile(Stage stage, boolean packModule) {
        byte[] module = CompiledStages.module(stage.kind(), packModule, stage.source(),
                () -> compileModule(stage, packModule));
        ByteBuffer bytecode = ByteBuffer.allocateDirect(module.length).put(module).flip();
        return SPIRVUtils.SPIRV.ownedBuffer(bytecode, () -> {});
    }

    private static byte[] compileModule(Stage stage, boolean packModule) {
        ByteBuffer compiled;
        try {
            compiled = GlslCompiler.compile(stage.name(), stage.source(), stage.kind(), GlslCompiler::optimize);
        } catch (IllegalStateException failure) {
            throw new ShaderCompilationFailure(failure, stage.source());
        }
        try {
            ByteBuffer module = packModule ? SpirvTrigPrecision.apply(SpirvLocalInitializer.apply(compiled)) : compiled;
            byte[] bytes = new byte[module.remaining()];
            module.duplicate().get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(compiled);
        }
    }
}
