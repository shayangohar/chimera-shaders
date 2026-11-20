// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.render.AllocationScope;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/**
 * Pack geometry stages (.gsh), which VulkanMod's GraphicsShaderStages has no slot for. Every variant of the
 * pipeline, including lazy ones, gets the module inserted (GraphicsPipelineGeometryStageMixin).
 */
public final class GeometryStageModules {
    /** The geometry SPIR-V of the pipeline this thread is constructing. */
    private static final class Pending {
        private final ByteBuffer spirv;
        private GraphicsPipeline pipeline;
        private long module;

        private Pending(ByteBuffer spirv) {
            this.spirv = spirv;
        }
    }

    private static final AllocationScope<Pending> PENDING = new AllocationScope<>();

    private GeometryStageModules() {}

    public static GraphicsPipeline construct(ByteBuffer spirv, Supplier<GraphicsPipeline> create) {
        Pending pending = new Pending(spirv);
        boolean constructed = false;
        try {
            GraphicsPipeline pipeline = PENDING.with(pending, create);
            constructed = true;
            return pipeline;
        } finally {
            if (!constructed && pending.module != 0L) {
                PipelineSide.of(pending.pipeline).geometryModule = 0L;
                destroy(pending.module);
            }
        }
    }

    /** Called from pipeline construction. */
    public static void attach(GraphicsPipeline pipeline) {
        Pending pending = PENDING.current();
        if (pending == null || pending.module != 0L) return;
        pending.module = createModule(pending.spirv);
        pending.pipeline = pipeline;
        PipelineSide.of(pipeline).geometryModule = pending.module;
    }

    public static VkPipelineShaderStageCreateInfo.Buffer withGeometry(GraphicsPipeline pipeline,
                                                                     VkPipelineShaderStageCreateInfo.Buffer stages) {
        long module = PipelineSide.of(pipeline).geometryModule;
        return module == 0L ? stages : insert(stages, module, MemoryStack.stackGet());
    }

    /** Places the geometry stage before the fragment stage, with the same entry point. */
    static VkPipelineShaderStageCreateInfo.Buffer insert(VkPipelineShaderStageCreateInfo.Buffer stages, long module,
                                                       MemoryStack stack) {
        int count = stages.remaining();
        VkPipelineShaderStageCreateInfo.Buffer out = VkPipelineShaderStageCreateInfo.calloc(count + 1, stack);
        int at = 0;
        for (int index = 0; index < count; index++) {
            VkPipelineShaderStageCreateInfo stage = stages.get(stages.position() + index);
            if (stage.stage() == VK_SHADER_STAGE_FRAGMENT_BIT) {
                // a copy keeps the entry point's terminated name; the geometry stage takes no specialisation
                out.get(at++).set(stage).stage(VK_SHADER_STAGE_GEOMETRY_BIT).module(module).pSpecializationInfo(null);
            }
            out.get(at++).set(stage);
        }
        return out;
    }

    /** Called from the pipeline's cleanUp. */
    public static void release(GraphicsPipeline pipeline) {
        PipelineSide side = PipelineSide.of(pipeline);
        long module = side.geometryModule;
        side.geometryModule = 0L;
        if (module != 0L) destroy(module);
    }

    private static long createModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateShaderModule(DeviceManager.vkDevice, info, null, handle);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("geometry shader module creation failed: " + result);
            }
            return handle.get(0);
        }
    }

    private static void destroy(long module) {
        vkDestroyShaderModule(DeviceManager.vkDevice, module, null);
    }
}
