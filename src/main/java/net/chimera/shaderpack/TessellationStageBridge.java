// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.WorldBetweenStages.PassedOn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.WorldBetweenStages.arraySuffix;
import static net.chimera.shaderpack.WorldBetweenStages.qualified;

/**
 * A program's tessellation stages, which Iris runs between the vertex and fragment stages when the pack lists
 * TESSELLATION_SHADERS. Each stage pair matches by name at one shared location. Positions stay in GL's clip
 * range until the evaluation stage, which converts the depth it emits; its winding is swapped, since Vulkan's
 * upper-left domain origin orders a generated triangle's vertices opposite to GL.
 */
final class TessellationStageBridge {
    private TessellationStageBridge() {}

    /** The evaluation stage's input layout, {@code layout(triangles, ccw) in;}. */
    private static final Pattern INPUT_LAYOUT = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*in\\s*;");
    /** A vertex order among a layout's qualifiers. */
    private static final Pattern VERTEX_ORDER = Pattern.compile("\\bc?cw\\b");

    /** The converted stages, and the locations of the evaluation stage's outputs, which the fragment stage reads. */
    record Conversion(String control, String evaluation, LegacyGlslConverter.TerrainVaryingLayout outputLayout) {}

    /** Both converted stages, or null when either failed; a half pair would fail the whole pack's reports. */
    static Conversion paired(String control, String evaluation,
                             LegacyGlslConverter.TerrainVaryingLayout outputLayout) {
        return control == null || evaluation == null ? null : new Conversion(control, evaluation, outputLayout);
    }

    /**
     * The control stage as the vertex stage's consumer: each per-vertex input declared as the one
     * vertex's value it reads, and no output, so the vertex bridge pairs with it as with a fragment.
     */
    static String consumerView(String control) {
        return GlslInterfaceScanner.rewrite(control, GlslInterfaceScanner.Stage.TESS_CONTROL, declaration ->
                !declaration.input() ? "" : qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.name() + arraySuffix(declaration) + ";\n");
    }

    /**
     * Converts both stages of a terrain or water program; {@code inputLayout} holds the vertex stage's output
     * locations.
     *
     * @throws IllegalArgumentException when a stage reads an input nothing writes, or a resource the plan left out
     */
    static Conversion convertTerrain(String control, String evaluation,
                                     LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                     UniformRegistry.ProgramInterface controlPlan,
                                     UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        return convertStages(new Stage(normalized(control), GlslInterfaceScanner.Stage.TESS_CONTROL, controlPlan),
                new Stage(normalized(evaluation), GlslInterfaceScanner.Stage.TESS_EVALUATION, evaluationPlan),
                inputLayout, fragment, allowStorageBuffers, PassedOn.NONE, false);
    }

    /** {@link #convertHand} for a hand program ({@code hand}), else {@link #convertTerrain}. */
    static Conversion convert(boolean hand, String control, String evaluation,
                              LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                              UniformRegistry.ProgramInterface controlPlan,
                              UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        return hand ? convertHand(control, evaluation, inputLayout, fragment, controlPlan, evaluationPlan,
                allowStorageBuffers) : convertTerrain(control, evaluation, inputLayout, fragment, controlPlan,
                evaluationPlan, allowStorageBuffers);
    }

    /**
     * Converts both stages of a hand program, drawn by the entity vertex bridge. The stages read the legacy
     * matrices as the hand's vertex stage does, with the projection in GL's clip range, and pass the bridge's
     * entity identities and host values on to the fragment stage ({@link PassedOn}).
     *
     * @throws IllegalArgumentException as {@link #convertTerrain}, and when the domain is not triangles
     */
    static Conversion convertHand(String control, String evaluation,
                                  LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                  UniformRegistry.ProgramInterface controlPlan,
                                  UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        Stage controlStage = new Stage(normalized(control), GlslInterfaceScanner.Stage.TESS_CONTROL, controlPlan);
        Stage evaluationStage = new Stage(normalized(evaluation), GlslInterfaceScanner.Stage.TESS_EVALUATION,
                evaluationPlan);
        Matcher domain = INPUT_LAYOUT.matcher(GlslLexer.stripComments(evaluationStage.source()));
        if (!domain.find() || !domain.group(1).matches("(?s).*\\btriangles\\b.*")) {
            throw new IllegalArgumentException("hand tessellation domain is not triangles");
        }
        return convertStages(controlStage, evaluationStage, inputLayout, fragment, allowStorageBuffers,
                PassedOn.of(inputLayout), true);
    }

    private static Conversion convertStages(Stage controlStage, Stage evaluationStage,
                                            LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                            boolean allowStorageBuffers, PassedOn passedOn, boolean hand) {
        LegacyGlslConverter.TerrainVaryingLayout patchLayout = patchLayout(controlStage.source(), passedOn);
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = passedOn.outputLayout(
                producerView(evaluationStage.source()), fragment);
        String convertedControl = WorldClipRange.GL.withHelpers(passedOn.control(
                converted(controlStage, inputLayout, patchLayout, allowStorageBuffers, hand), inputLayout,
                patchLayout));
        String convertedEvaluation = WorldClipRange.GL.convert(swappedWinding(passedOn.evaluation(
                converted(evaluationStage, patchLayout, outputLayout, allowStorageBuffers, hand), patchLayout,
                outputLayout)));
        return new Conversion(WorldBetweenStages.preamble(convertedControl, controlStage.plan(), hand)
                + convertedControl, WorldBetweenStages.preamble(convertedEvaluation, evaluationStage.plan(), hand)
                + convertedEvaluation, outputLayout);
    }

    /** One stage's normalised source, its kind and its projection of the program's interface. */
    private record Stage(String source, GlslInterfaceScanner.Stage kind, UniformRegistry.ProgramInterface plan) {}

    private static String normalized(String source) {
        return LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeModernTerrain(source));
    }

    /** The evaluation stage as the fragment stage's producer: its outputs alone. */
    private static String producerView(String evaluation) {
        return GlslInterfaceScanner.rewrite(evaluation, GlslInterfaceScanner.Stage.TESS_EVALUATION,
                declaration -> declaration.input() ? "" : null);
    }

    /**
     * The control stage's per-vertex outputs, packed in name order, which the evaluation stage reads,
     * then the values it passes on.
     */
    private static LegacyGlslConverter.TerrainVaryingLayout patchLayout(String control, PassedOn passedOn) {
        GlslInterfaceScanner.StageInterface scanned = GlslInterfaceScanner.scan(control,
                GlslInterfaceScanner.Stage.TESS_CONTROL);
        if (!scanned.deviations().isEmpty()) {
            throw new IllegalArgumentException("tessellation control interface unsupported: " + scanned.deviations());
        }
        Map<String, String> types = new TreeMap<>();
        Map<String, Integer> locations = new TreeMap<>();
        Map<String, String> qualifiers = new TreeMap<>();
        int location = 0;
        for (GlslInterfaceScanner.Declaration output : scanned.outputs()) {
            types.put(output.name(), output.typeWithArray());
            locations.put(output.name(), location);
            if (output.qualifier() != null) qualifiers.put(output.name(), output.qualifier());
            location += output.slots();
        }
        return passedOn.appendedTo(new LegacyGlslConverter.TerrainVaryingLayout(types, locations, qualifiers),
                location, PassedOn.PATCH_SUFFIX);
    }

    /**
     * The stage converted below its preamble: interface located, uniforms served, then the world
     * stages' built-ins and samplers (WorldBetweenStages.builtins).
     */
    private static String converted(Stage stage, LegacyGlslConverter.TerrainVaryingLayout inputs,
                                     LegacyGlslConverter.TerrainVaryingLayout outputs, boolean allowStorageBuffers,
                                     boolean hand) {
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(stage.source()));
        converted = stage.plan() == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : InterfacePlanner.removeUniformDeclarations(converted, stage.plan());
        requireSupported(converted, allowStorageBuffers);
        converted = located(converted, stage.kind(), inputs, outputs);
        return WorldBetweenStages.builtins(converted, stage.plan(), hand);
    }

    /** A main, no storage buffer the plan did not admit, and no value uniform the plan left out. */
    private static void requireSupported(String source, boolean allowStorageBuffers) {
        String stripped = GlslLexer.stripComments(source);
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null) {
            throw new IllegalArgumentException("tessellation stage main is missing");
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        String uniform = TerrainVertexBridge.valueUniform(tree);
        if (uniform != null) {
            throw new IllegalArgumentException("tessellation stage value uniform is unplanned: " + uniform);
        }
    }

    /**
     * Each input at the location of the output it reads and each output at its own location. An
     * input nothing writes goes when nothing reads it, as GL leaves it inactive. An integer output
     * of the evaluation stage is flat, as the fragment stage takes it.
     */
    private static String located(String source, GlslInterfaceScanner.Stage stage,
                                  LegacyGlslConverter.TerrainVaryingLayout inputs,
                                  LegacyGlslConverter.TerrainVaryingLayout outputs) {
        return GlslInterfaceScanner.rewrite(source, stage, declaration -> {
            LegacyGlslConverter.TerrainVaryingLayout layout = declaration.input() ? inputs : outputs;
            if (!layout.locations().containsKey(declaration.name())) {
                if (declaration.referenced() || declaration.output()) {
                    throw new IllegalArgumentException(stage + " varying has no location: " + declaration.name());
                }
                return "";
            }
            String interpolation = declaration.qualifier() == null ? "" : declaration.qualifier();
            if (declaration.output() && stage == GlslInterfaceScanner.Stage.TESS_EVALUATION) {
                interpolation = outputs.qualifier(declaration.name());
                if (interpolation.isEmpty() && declaration.integral()) interpolation = "flat";
            }
            return "layout(location = " + layout.location(declaration.name()) + ") " + qualified(interpolation)
                    + (declaration.input() ? "in " : "out ") + declaration.type() + " " + declaration.declarator()
                    + ";\n";
        });
    }

    /**
     * The evaluation stage with its triangle winding reversed: every vertex order in an input layout is swapped,
     * and with none the first layout takes cw, as GL's default is ccw.
     */
    static String swappedWinding(String evaluation) {
        // layouts and orders come from the comment-masked source, whose offsets match the source as written
        String masked = GlslLexer.maskComments(evaluation);
        Matcher layout = INPUT_LAYOUT.matcher(masked);
        List<int[]> layouts = new ArrayList<>();
        List<int[]> orders = new ArrayList<>();
        while (layout.find()) {
            layouts.add(new int[] {layout.start(1), layout.end(1)});
            Matcher order = VERTEX_ORDER.matcher(masked).region(layout.start(1), layout.end(1));
            while (order.find()) orders.add(new int[] {order.start(), order.end()});
        }
        if (layouts.isEmpty()) return evaluation;
        StringBuilder result = new StringBuilder(evaluation);
        if (orders.isEmpty()) return result.insert(layouts.get(0)[1], ", cw").toString();
        for (int index = orders.size() - 1; index >= 0; index--) {
            int[] range = orders.get(index);
            result.replace(range[0], range[1], masked.substring(range[0], range[1]).equals("ccw") ? "cw" : "ccw");
        }
        return result.toString();
    }
}
