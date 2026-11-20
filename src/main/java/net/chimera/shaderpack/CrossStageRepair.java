// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iris's cross-stage repair (CompatibilityTransformer.transformGrouped): the earlier of two adjacent stages is
 * edited to fit the later stage's non-array numeric inputs. An output array or one of another kind stays, and
 * the stage match refuses it, as Iris's link does. Unlike Iris, a tessellation control stage is left alone (its
 * outputs are arrays) and a geometry stage is not retyped (it would convert after its last EmitVertex).
 */
final class CrossStageRepair {
    private CrossStageRepair() {}

    static final String UNWRITTEN_ZEROED = "VARYING_UNWRITTEN_ZEROED:";
    static final String UNASSIGNED_ZEROED = "VARYING_UNASSIGNED_ZEROED:";
    static final String TYPE_CONVERTED = "VARYING_TYPE_CONVERTED:";

    private static final Pattern SCALAR = Pattern.compile("float|int|uint|bool");
    private static final Pattern VECTOR = Pattern.compile("[iub]?vec[234]");
    private static final Pattern MATRIX = Pattern.compile("mat[234](?:x[234])?");

    record Result(String earlier, List<String> deviations) {}

    private record Variable(GlslTree.Declaration declaration, GlslTree.Declarator declarator, String name,
                            String type) {
        /** Beyond the one dimension a per-vertex input takes. */
        boolean array(boolean perVertex) {
            int dimensions = declarator.arrays().size() + declaration.type().arrays().size();
            return dimensions > (perVertex ? 1 : 0);
        }
    }

    static Result repair(String vertex, String fragment) {
        return repair(vertex, GlslInterfaceScanner.Stage.VERTEX, fragment, GlslInterfaceScanner.Stage.FRAGMENT);
    }

    static Result repair(String earlier, GlslInterfaceScanner.Stage earlierStage, String later,
                         GlslInterfaceScanner.Stage laterStage) {
        Result unchanged = new Result(earlier, List.of());
        if (earlier == null || later == null || earlierStage == GlslInterfaceScanner.Stage.TESS_CONTROL) {
            return unchanged;
        }
        GlslTree earlierTree = GlslDeclarations.parseOrNull(earlier);
        GlslTree laterTree = GlslDeclarations.parseOrNull(later);
        GlslTree.Function main = earlierTree == null || laterTree == null
                ? null : GlslDeclarations.mainDefinition(earlierTree);
        if (main == null) return unchanged;
        Map<String, Variable> outputs = variables(earlierTree, Set.of("out", "varying"));
        Edits edits = new Edits(earlierTree, outputStorage(earlierTree, outputs.values(), earlier));
        boolean converts = earlierStage != GlslInterfaceScanner.Stage.GEOMETRY;
        for (Variable input : variables(laterTree, Set.of("in", "varying")).values()) {
            if (!input.name().startsWith("gl_") && kind(input.type()) >= 0
                    && !input.array(laterStage.perVertexInputs())) {
                repairInput(edits, earlierTree, laterTree, input, outputs.get(input.name()), converts);
            }
        }
        return edits.result(main);
    }

    /** {@code output} may be null. */
    private static void repairInput(Edits edits, GlslTree earlierTree, GlslTree laterTree, Variable input,
                                    Variable output, boolean converts) {
        if (output == null) {
            if (referenced(laterTree, input)) edits.declare(laterTree, input);
        } else if (output.array(false)) {
            return;
        } else if (output.type().equals(input.type())) {
            if (!referenced(earlierTree, output)) edits.zeroUnassigned(output);
        } else if (converts && kind(output.type()) == kind(input.type())) {
            edits.convert(output, input.type());
        }
    }

    private static Map<String, Variable> variables(GlslTree tree, Set<String> storages) {
        Map<String, Variable> result = new LinkedHashMap<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null
                    || declaration.type().name() < 0 || storage(tree, declaration, storages) == null) {
                continue;
            }
            String type = tree.text(declaration.type().name());
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                result.putIfAbsent(tree.text(declarator.name()),
                        new Variable(declaration, declarator, tree.text(declarator.name()), type));
            }
        }
        return result;
    }

    private static GlslTree.Qualifier storage(GlslTree tree, GlslTree.Declaration declaration, Set<String> storages) {
        for (GlslTree.Qualifier qualifier : declaration.qualifiers()) {
            if (storages.contains(tree.text(qualifier.keyword()))) return qualifier;
        }
        return null;
    }

    /** Outside its declaration, and not as a field. */
    private static boolean referenced(GlslTree tree, Variable variable) {
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            if (variable.declaration().range().contains(index) || !tokens.get(index).identifier(variable.name())) {
                continue;
            }
            int previous = GlslLexer.previousSignificant(tokens, index);
            if (previous < 0 || !tokens.get(previous).symbol(".")) return true;
        }
        return false;
    }

    /** 0 for a scalar, 1 for a vector, 2 for a matrix, -1 for any other type (Iris's dimension). */
    private static int kind(String type) {
        if (SCALAR.matcher(type).matches()) return 0;
        if (VECTOR.matcher(type).matches()) return 1;
        return MATRIX.matcher(type).matches() ? 2 : -1;
    }

    /**
     * The stage's own outputs' storage first, so a post vertex stage whose version line the normalizer dropped
     * keeps {@code out}.
     */
    private static String outputStorage(GlslTree tree, Collection<Variable> outputs, String source) {
        for (Variable output : outputs) {
            GlslTree.Qualifier storage = storage(tree, output.declaration(), Set.of("out", "varying"));
            if (storage != null) return tree.text(storage.keyword());
        }
        GlslVersion version = GlslVersion.first(source);
        return version == null || version.number() < 130 ? "varying" : "out";
    }

    /** LegacyShaderNormalizer zeroes outputs with the same statement, so it adds no second one. */
    static String zero(String type) {
        return type + "(0)";
    }

    /** {@code internal} is the global of the old type the stage now writes. */
    private record Retype(String type, String internal) {}

    private static final class Edits {
        private final GlslTree tree;
        private final String outputStorage;
        private final StringBuilder declarations = new StringBuilder();
        private final StringBuilder prologue = new StringBuilder();
        private final StringBuilder epilogue = new StringBuilder();
        private final Map<GlslTree.Declaration, Map<String, Retype>> retyped = new LinkedHashMap<>();
        private final List<GlslTree.Edit> renames = new ArrayList<>();
        private final List<String> deviations = new ArrayList<>();

        Edits(GlslTree tree, String outputStorage) {
            this.tree = tree;
            this.outputStorage = outputStorage;
        }

        void declare(GlslTree laterTree, Variable input) {
            GlslTree.Qualifier storage = storage(laterTree, input.declaration(), Set.of("in", "varying"));
            for (GlslTree.Qualifier qualifier : input.declaration().qualifiers()) {
                declarations.append(qualifier == storage ? outputStorage : laterTree.text(qualifier.range()))
                        .append(' ');
            }
            declarations.append(input.type()).append(' ').append(input.name()).append(";\n");
            initialize(input);
            deviations.add(UNWRITTEN_ZEROED + input.name());
        }

        void zeroUnassigned(Variable output) {
            initialize(output);
            deviations.add(UNASSIGNED_ZEROED + output.name());
        }

        private void initialize(Variable variable) {
            prologue.append(variable.name()).append(" = ").append(zero(variable.type())).append(";\n");
        }

        /** The end of main converts the old-type global into the retyped output. */
        void convert(Variable output, String inputType) {
            String internal = GlslTokenRewriter.uniqueIdentifier(tree.source(), "chimeraVarying_" + output.name());
            retyped.computeIfAbsent(output.declaration(), key -> new LinkedHashMap<>())
                    .put(output.name(), new Retype(inputType, internal));
            List<GlslLexer.Token> tokens = tree.tokens();
            for (int index = 0; index < tokens.size(); index++) {
                if (output.declaration().range().contains(index) || !tokens.get(index).identifier(output.name())) {
                    continue;
                }
                GlslTree.Binding binding = tree.resolve(index);
                if (binding != null && binding.node() == output.declarator()) {
                    renames.add(new GlslTree.Edit(new GlslTree.Range(index, index + 1), internal));
                }
            }
            boolean widened = kind(inputType) == 1 && size(output.type()) < size(inputType);
            epilogue.append(output.name()).append(" = ").append(inputType).append('(').append(internal)
                    .append(widened ? ", vec4(0)" : "").append(");\n");
            deviations.add(TYPE_CONVERTED + output.name());
        }

        Result result(GlslTree.Function main) {
            if (deviations.isEmpty()) return new Result(tree.source(), List.of());
            List<GlslTree.Edit> edits = new ArrayList<>(renames);
            retyped.forEach((declaration, types) -> edits.add(
                    new GlslTree.Edit(declaration.range(), redeclared(declaration, types))));
            if (!declarations.isEmpty()) {
                int first = firstDeclaration();
                edits.add(new GlslTree.Edit(new GlslTree.Range(first, first), declarations.toString()));
            }
            GlslTree.Range body = main.body().range();
            if (!prologue.isEmpty()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(body.start() + 1, body.start() + 1),
                        "\n" + prologue));
            }
            if (!epilogue.isEmpty()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(body.end() - 1, body.end() - 1), epilogue.toString()));
            }
            return new Result(tree.print(edits), List.copyOf(deviations));
        }

        /** One statement per name, a retyped one after the global of its old type. */
        private String redeclared(GlslTree.Declaration declaration, Map<String, Retype> types) {
            String qualifiers = tree.text(new GlslTree.Range(declaration.qualifiers().get(0).range().start(),
                    declaration.type().range().start()));
            String oldType = tree.text(declaration.type().range());
            StringBuilder result = new StringBuilder();
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                String name = tree.text(declarator.name());
                Retype retype = types.get(name);
                if (retype == null) {
                    result.append(qualifiers).append(oldType).append(' ').append(tree.text(declarator.range()));
                } else {
                    result.append(oldType).append(' ').append(retype.internal()).append(";\n")
                            .append(qualifiers).append(retype.type()).append(' ').append(name);
                }
                result.append(";\n");
            }
            return result.toString();
        }

        /** Iris's BEFORE_DECLARATIONS. */
        private int firstDeclaration() {
            for (GlslTree.External external : tree.externals()) {
                if (!(external instanceof GlslTree.Directive)) return external.range().start();
            }
            return tree.tokens().size();
        }

        private static int size(String vector) {
            return vector.charAt(vector.length() - 1) - '0';
        }
    }
}
