// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Packs' vanilla cloud styles shade vanilla's cloud faces with their gbuffers_clouds program, as under Iris. */
@Tag("packs")
final class VanillaCloudPackTest {
    @Test
    void bslVanillaClouds() {
        verify(TestPacks.bsl(), "CLOUDS", "3", List.of(0));
    }

    @Test
    void complementaryVanillaClouds() {
        verify(TestPacks.complementary(), "CLOUD_STYLE_DEFINE", "50", List.of(0, 6, 3));
    }

    @Test
    void makeUpVanillaClouds() {
        Path makeUp = TestPacks.extra().stream()
                .filter(pack -> pack.getFileName().toString().startsWith("MakeUp")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Set -Pchimera.pack.extra to include MakeUp"));
        verify(makeUp, "V_CLOUDS", "0", List.of(1));
    }

    private static void verify(Path pack, String option, String value, List<Integer> targets) {
        String property = "chimera.option." + option;
        System.setProperty(property, value);
        try {
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            PackProgramPlan clouds = analysis.plan().program("gbuffers_clouds");
            String name = pack.getFileName() + " " + option + "=" + value;
            assertTrue(clouds != null && clouds.executable() && !clouds.cloudDrawsNothing()
                            && analysis.report().shouldAttempt("gbuffers_clouds"),
                    name + " gbuffers_clouds is not planned to run: "
                            + (clouds == null ? "no plan" : clouds.deviations()));
            assertEquals(FamilyAdapterPlan.VertexContract.CLOUD_POSITION_UV_COLOR_NORMAL,
                    clouds.familyAdapter().vertexContract(), name + " cloud vertex contract");
            assertEquals(targets, clouds.geometryOutputPlan().targetSlots(), name + " cloud targets");
            assertTrue(!"off".equals(analysis.plan().cloudSetting()), name + " turns the vanilla clouds off");
            var compiled = PackCompileTest.run(pack, analysis);
            assertTrue(compiled.failures().isEmpty(), name + " compilation: " + compiled.failures());
        } finally {
            System.clearProperty(property);
        }
    }
}
