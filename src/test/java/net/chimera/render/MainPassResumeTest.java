// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged splits the bound pass for a readback or blit and resumes with MainPass.resumeRenderPass's
 * answer, by default the same pass; a clearing pass would wipe the frame, so each resumes with its
 * loading twin.
 */
final class MainPassResumeTest {
    private static final String TARGETS = "net/chimera/render/MainPassTargets";

    @Test
    void aSplitPassResumesWithItsLoadTwin() throws IOException {
        var main = MixinBytecode.nativeClass("net/chimera/render/ChimeraMainPass");
        assertTrue(MixinBytecode.instructionsThrough(main, "resumeRenderPass").stream().anyMatch(instruction ->
                        instruction instanceof MethodInsnNode call && call.owner.equals(TARGETS)
                                && call.name.equals("resumePass")),
                "ChimeraMainPass overrides MainPass.resumeRenderPass with MainPassTargets.resumePass");

        List<FieldInsnNode> reads = MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(TARGETS), "resumePass")
                .stream().filter(instruction -> instruction.getOpcode() == Opcodes.GETFIELD)
                .map(FieldInsnNode.class::cast).toList();
        // resumePass compares the bound pass with each clearing pass and answers with its twin
        Set<String> clearing = Set.of("hdrRenderPass", "hdrAuxClearDepthRenderPass", "compositeRenderPass",
                "compositeAuxClearDepthRenderPass");
        Set<String> twins = Set.of("hdrAuxRenderPass", "compositeAuxRenderPass");
        Set<String> read = reads.stream().map(field -> field.name).collect(Collectors.toSet());
        assertTrue(read.containsAll(clearing) && read.containsAll(twins), read.toString());
        assertEquals(Set.of(), read.stream().filter(name -> !clearing.contains(name) && !twins.contains(name))
                .collect(Collectors.toSet()), "resumePass resumes no other pass in place of the bound one");
    }
}
