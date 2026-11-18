// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every path to the rasterizer converts GL's clip depth to Vulkan's exactly once when its stages
 * work in GL's range, never when they keep the host's, and only in its last stage (ClipDepth).
 * Converting twice halves every depth a later pass rebuilds; missing it puts the near half of the
 * view in front of the near plane.
 */
final class ClipDepthTest {
    @Test
    void theWrappersRunTheAuthoredMainThenConvertOnce() {
        String wrapped = ClipDepth.afterEntry("void main() { gl_Position = vec4(1.0); }\n", "chimeraEntry");
        assertTrue(wrapped.startsWith("void chimeraEntry() {"), wrapped);
        assertTrue(wrapped.endsWith("void main() {\n    chimeraEntry();\n    " + ClipDepth.TO_VULKAN + "\n}\n"),
                wrapped);
        assertEquals(1, ClipDepth.count(wrapped));
        String compact = ClipDepth.afterRenamedEntry("void chimeraHandMain() {}\n", "chimeraHandMain");
        assertTrue(compact.endsWith("\nvoid main() { chimeraHandMain(); " + ClipDepth.TO_VULKAN + " }\n"), compact);
        assertEquals(1, ClipDepth.count(compact));
        assertEquals(0, ClipDepth.count(null));
    }

    /**
     * A post pass's authored vertex works in GL's range: DrDestens' {@code gl_Vertex * 2 - 1} puts
     * its quad at z = -1, GL's near plane, which Vulkan clips unless converted. Its main mirrors y,
     * then converts the depth once.
     */
    @Test
    void anAuthoredPostVertexConvertsAfterTheMirror() {
        String vertex = "#version 120\nvoid main() { gl_Position = gl_Vertex * 2 - 1; }\n";
        String fragment = "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n";
        UniformRegistry.ProgramInterface plan = InterfacePlanner.planProgram(fragment, vertex,
                UniformRegistry.Stage.POST, null, true).effective(UniformRegistry.Stage.POST);
        GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
        GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
        PostVertexBridge.PostVertexConversion conversion = PostVertexBridge.convertPostVertex(
                vertex, vs, fs, GlslInterfaceScanner.match(vs, fs), plan);
        String source = conversion.source();
        assertTrue(source != null, "post vertex refused: " + conversion.deviations());
        assertEquals(1, ClipDepth.count(source));
        assertTrue(source.endsWith("chimeraPostMain(); gl_Position.y = -gl_Position.y; "
                + ClipDepth.TO_VULKAN + " }\n"), source);
    }

    /**
     * Every program of a corpus pack (CorpusChecks): its stages before the rasterizer, as converted:
     * at most one conversion, in the last of them, and exactly one where they work in GL's range: a
     * shadow program, the hand and sky draws (their preamble's chimeraHandProjection or
     * chimeraSkyProjection), any stage that takes the host's clip position into GL's range
     * (chimeraGlClip), and a post pass's authored vertex. A glint drawn in the hand converts once
     * too. Returns the paths it checked.
     */
    static int checkPack(Path pack, PackProbe.Analysis analysis, List<String> failures) {
        int paths = 0;
        int converting = 0;
        for (PackProgramPlan plan : analysis.plan().programs()) {
            UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(plan.name());
            if (!plan.executable() || plan.convertedVertex() == null) continue;
            if (stage == UniformRegistry.Stage.POST) {
                // Chimera's own fullscreen vertex writes z = 0, inside both ranges
                if (!plan.deviations().contains("POST_VERTEX_AUTHORED_TRANSLATED")) continue;
                paths++;
                int total = ClipDepth.count(plan.convertedVertex());
                if (total != 1) failures.add(pack.getFileName() + " " + plan.name()
                        + ": authored post vertex converts " + total + " times");
                else converting++;
                continue;
            }
            paths++;
            String failure = pathFailure(plan, stage);
            if (failure != null) failures.add(pack.getFileName() + " " + plan.name() + ": " + failure);
            if (conversions(chain(plan)) == 1) converting++;
        }
        System.out.printf("[chimera] clip depth %s: %d paths, %d convert in GL's range%n", pack.getFileName(),
                paths, converting);
        return paths;
    }

    /** What is wrong with a program's path to the rasterizer, or null. */
    private static String pathFailure(PackProgramPlan plan, UniformRegistry.Stage stage) {
        List<String> chain = chain(plan);
        int total = conversions(chain);
        if (total > 1) return total + " conversions";
        if (total == 1 && ClipDepth.count(chain.get(chain.size() - 1)) != 1) return "converted before the last stage";
        // recognised by the projection the stages carry, never by the wrapper that converts
        boolean glRange = stage.shadowPass() || chain.stream().anyMatch(source -> source.contains("chimeraGlClip(")
                || source.contains("chimeraHandProjection(") || source.contains("chimeraSkyProjection("));
        if (glRange && total != 1) return "works in GL's range and converts " + total + " times";
        if (FamilyAdapterRegistry.isGlintFamily(plan.name())
                && plan.convertedVertex().contains(GlslVertexPreambles.GLINT_VERTEX_PREAMBLE)) {
            int inHand = ClipDepth.count(EntityVertexBridge.handGlintVertex(plan.convertedVertex()));
            if (inHand != 1) return "the glint in the hand converts " + inHand + " times";
        }
        return null;
    }

    /** The converted stages before the rasterizer, in the order GL links them. */
    private static List<String> chain(PackProgramPlan plan) {
        List<String> chain = new ArrayList<>();
        for (String source : new String[] {plan.convertedVertex(), plan.convertedTessControl(),
                plan.convertedTessEvaluation(), plan.convertedGeometry()}) {
            if (source != null) chain.add(source);
        }
        return chain;
    }

    private static int conversions(List<String> chain) {
        return chain.stream().mapToInt(ClipDepth::count).sum();
    }
}
