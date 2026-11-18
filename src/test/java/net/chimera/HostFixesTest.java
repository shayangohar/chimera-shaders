// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host behaviour Chimera relies on instead of workarounds of its own. Each test pins the host code that
 * provides it, so a host without it fails here.
 */
final class HostFixesTest {
    /**
     * A render target blits to the screen by the row order its image recorded: the GL-framebuffer
     * blit for a target drawn bottom-up, such as the entity outline.
     */
    @Test
    void aRenderTargetBlitsByItsRecordedRowOrder() throws IOException {
        List<AbstractInsnNode> copy = hostMethod("net/vulkanmod/render/RenderTargetBlit$Call", "copy");
        assertTrue(calls(copy, "net/vulkanmod/vulkan/texture/VulkanImage", "isRowsBottomUp")
                && calls(copy, "net/vulkanmod/vulkan/util/DrawUtil", "blitToScreen", "(Z)V"));
    }

    /**
     * Each entry of a pipeline's descriptor pool takes its descriptor's type, so a set holding a pack
     * storage buffer or storage image fits its pool.
     */
    @Test
    void descriptorPoolEntriesTakeTheirDescriptorsType() throws IOException {
        List<AbstractInsnNode> pool = hostMethod("net/vulkanmod/vulkan/shader/Pipeline$DescriptorSets",
                "createDescriptorPool");
        assertTrue(calls(pool, "net/vulkanmod/vulkan/shader/descriptor/UBO", "getType")
                && calls(pool, "net/vulkanmod/vulkan/shader/descriptor/ImageDescriptor", "getType"));
    }

    private static List<AbstractInsnNode> hostMethod(String owner, String method) throws IOException {
        return MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(owner), method);
    }

    private static boolean calls(List<AbstractInsnNode> instructions, String owner, String name) {
        return instructions.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name));
    }

    private static boolean calls(List<AbstractInsnNode> instructions, String owner, String name, String desc) {
        return instructions.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc));
    }
}
