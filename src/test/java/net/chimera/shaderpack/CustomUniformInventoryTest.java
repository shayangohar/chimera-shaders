package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom-uniform inventory of the two pinned packs.
 *
 * A pack authors derived uniforms in its {@code shaders.properties}, and Chimera either serves one or
 * refuses it. A refusal must be loud: the declaration is reported, and no program that reads the name
 * runs. Both packs load through the path the game uses; the test prints the inventory and fails when a
 * refused declaration has no diagnostic or an executable reader, or a served name is not the pack's own.
 */
@Tag("packs")
final class CustomUniformInventoryTest {
    @Test
    void complementary() {
        audit("Complementary", PackProbe.analyze(TestPacks.complementary()));
    }

    @Test
    void bsl() {
        PackProbe.Analysis analysis = PackProbe.analyze(TestPacks.bsl());
        audit("BSL", analysis);
        verifyBslTimeAngle(analysis.plan().runtimeSettings());
    }

    private static void audit(String label, PackProbe.Analysis analysis) {
        PackRuntimeSettings settings = analysis.plan().runtimeSettings();
        Map<String, String> consumers = consumers(analysis);

        for (String name : new TreeSet<>(settings.rejected())) {
            String consumer = consumers.get(name);
            assertTrue(settings.deviations().stream().anyMatch(deviation -> deviation.contains(name)),
                    label + ": refused declaration without a diagnostic: " + name);
            assertTrue(consumer == null,
                    label + ": a refused declaration still has an executable consumer: "
                            + name + " in " + consumer);
        }

        for (Map.Entry<String, UniformRegistry.UniformDescriptor> entry
                : new TreeMap<>(settings.customDescriptors()).entrySet()) {
            UniformRegistry.UniformDescriptor descriptor = entry.getValue();
            if (descriptor.availability() != UniformRegistry.Availability.LIVE) {
                continue;
            }
            String name = entry.getKey();
            assertTrue(!UniformRegistry.isEngineInput(name),
                    label + ": a pack-authored name is also an engine input: " + name);
            String consumer = consumers.get(name);
            if (consumer == null) {
                continue;
            }
            PackProgramPlan program = analysis.plan().program(consumer);
            assertTrue(program != null && program.interfacePlan().deviations()
                            .contains("CUSTOM_UNIFORM_BRIDGE:" + name),
                    label + ": " + consumer + " reads " + name
                            + " without the pack's own declaration serving it");
            assertTrue(descriptor.sourceKey().equals("custom:" + name),
                    label + ": served declaration lost its own name: " + name);
        }

        inventory(label, settings, consumers);

        // pack-authored names the catalog must never answer with a synthetic value
        for (String packName : new String[] {"eyeBrightnessM", "timeAngle", "timeBrightness",
                "shadowFade", "rainFactor", "blindFactor", "framemod2", "framemod4", "framemod8",
                "framemod600", "frameTimeSmooth", "starter", "isEyeInCave", "isCold", "isDesert",
                "isJungle", "isMesa", "isMushroom", "isSavanna", "isSwamp", "inDry", "inRainy",
                "inSnowy", "inNetherWastes", "inCrimsonForest", "inWarpedForest", "inBasaltDeltas",
                "inSoulValley", "inPaleGarden", "maxBlindnessDarkness", "velocity"}) {
            assertTrue(UniformRegistry.descriptor(packName) == null,
                    label + ": the catalog answers a pack-authored name: " + packName);
        }
    }

    /**
     * BSL derives its sun colour from {@code timeAngle}, a chain of {@code variable.} values over
     * {@code sunAngle}. At noon (sunAngle 0.25) the chain gives 0.25; a wrong result turns
     * a noon scene into dusk.
     */
    private static void verifyBslTimeAngle(PackRuntimeSettings settings) {
        int index = settings.indexOf("timeAngle");
        assertTrue(index >= 0, "BSL timeAngle is not served");
        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> name.equals("sunAngle") ? 0.25 : 0.0, session, 0.05f);
        double tAmin = 0.25 - 0.033333333;
        double tAlin = tAmin * 1.15384615385;
        double tAfrc = (tAlin * 2.0) % 1.0;
        double tAfrs = tAfrc * tAfrc * (3.0 - 2.0 * tAfrc);
        double expected = (tAfrc * 0.7 + tAfrs * 0.3) * 0.5;
        double actual = session.values()[index];
        assertTrue(Math.abs(actual - expected) < 1.0e-4,
                "BSL timeAngle at noon is " + actual + ", expected " + expected);
    }

    /** Per name, the first executable program whose stages read it. */
    private static Map<String, String> consumers(PackProbe.Analysis analysis) {
        Map<String, String> result = new TreeMap<>();
        for (PackProgramPlan program : analysis.plan().programs()) {
            if (program == null || !program.executable()) {
                continue;
            }
            for (UniformRegistry.ProgramInterface stage : program.interfacePlan().stages().values()) {
                for (UniformRegistry.UniformDeclaration uniform : stage.executableUniforms()) {
                    result.putIfAbsent(uniform.name(), program.name());
                }
            }
        }
        return result;
    }

    private static void inventory(String label, PackRuntimeSettings settings,
                                  Map<String, String> consumers) {
        int served = 0;
        for (Map.Entry<String, UniformRegistry.UniformDescriptor> entry
                : new TreeMap<>(settings.customDescriptors()).entrySet()) {
            if (entry.getValue().availability() != UniformRegistry.Availability.LIVE) {
                continue;
            }
            served++;
            System.out.println("[chimera] inventory " + label + " served=" + entry.getKey()
                    + " consumer=" + consumers.getOrDefault(entry.getKey(), "unused"));
        }
        System.out.println("[chimera] inventory " + label + " served=" + served
                + " refused=" + settings.rejected().size());
    }
}
