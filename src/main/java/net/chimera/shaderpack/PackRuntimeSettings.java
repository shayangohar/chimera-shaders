package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A pack's custom uniforms and variables from {@code shaders.properties}, compiled into {@link PackExpression}
 * programs.
 *
 * Values are ordered so one that reads another reads a resolved value, and are evaluated once per frame into the
 * storage the uniform writer serves. Smoothing state lives in {@link Session} and is dropped on reload.
 *
 * A declaration may take a name Chimera only keeps as a placeholder. It may not take an engine input's name: the
 * host answers those, and a declaration that redefines one is refused.
 */
public final class PackRuntimeSettings {
    private final List<Value> values;
    /** Each value's first storage slot; a vector holds one slot per component. */
    private final Map<String, Integer> indices;
    private final Map<String, Integer> widths;
    private final int slotCount;
    private final Map<String, UniformRegistry.UniformDescriptor> customDescriptors;
    private final Set<String> rejected;
    private final List<String> deviations;

    private PackRuntimeSettings(
            List<Value> values,
            Map<String, UniformRegistry.UniformDescriptor> rejectedDescriptors,
            Set<String> rejected,
            List<String> deviations
    ) {
        this.values = List.copyOf(values);
        Map<String, Integer> indexMap = new TreeMap<>();
        Map<String, Integer> widthMap = new TreeMap<>();
        Map<String, UniformRegistry.UniformDescriptor> descriptors = new TreeMap<>();
        int slot = 0;
        for (Value value : this.values) {
            indexMap.put(value.name(), slot);
            widthMap.put(value.name(), value.width());
            slot += value.width();
            if (value.exposed()) {
                descriptors.put(value.name(), new UniformRegistry.UniformDescriptor(
                        value.name(), glslTypes(value.type()), UniformRegistry.Availability.LIVE,
                        "custom:" + value.name()));
            }
        }
        if (rejectedDescriptors != null) {
            descriptors.putAll(rejectedDescriptors);
        }
        this.indices = Collections.unmodifiableMap(indexMap);
        this.widths = Collections.unmodifiableMap(widthMap);
        this.slotCount = slot;
        this.customDescriptors = Collections.unmodifiableMap(descriptors);
        this.rejected = rejected == null ? Set.of() : Set.copyOf(rejected);
        this.deviations = deviations == null
                ? List.of() : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
    }

    public static PackRuntimeSettings empty() {
        return new PackRuntimeSettings(
                List.of(),
                Map.of(),
                Set.of(),
                List.of());
    }

    static PackRuntimeSettings build(List<Declaration> declarations, List<String> deviations) {
        return build(declarations, deviations, BiomeIds.constants());
    }

    static PackRuntimeSettings build(
            List<Declaration> declarations,
            List<String> deviations,
            PackExpression.Constants constants
    ) {
        List<String> result = new ArrayList<>(deviations == null ? List.of() : deviations);

        Map<String, Declaration> byName = declarationsByName(declarations);
        Map<String, ParsedValue> parsed = new TreeMap<>();
        Set<String> rejected = new TreeSet<>();
        // Iris logs a value it cannot parse and declares nothing, so shaders read that name as zero;
        // Bliss's `uniform.float.sandStorm = 0.0;` is one; its dependents are still refused
        Set<String> dropped = new TreeSet<>();
        // a declared value sets the width of every reference to it; any other name is a host input
        PackExpression.Widths widths = name -> {
            Declaration declared = byName.get(name);
            return declared != null ? width(declared.type()) : UniformRegistry.expressionWidth(name);
        };
        for (Declaration declaration : byName.values()) {
            Compiled compiled = compile(declaration, constants, widths, byName.keySet());
            if (compiled.refusal() != null) {
                result.add(compiled.refusal());
                rejected.add(declaration.name());
                if (compiled.refusal().startsWith(PackExpression.SYNTAX + ":")) dropped.add(declaration.name());
            } else {
                parsed.put(declaration.name(), new ParsedValue(declaration, compiled.program()));
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String name : parsed.keySet()) {
            resolve(name, parsed, rejected, visiting, resolved, order, result);
        }
        Set<String> refusedToShaders = new TreeSet<>(rejected);
        refusedToShaders.removeAll(dropped);
        return new PackRuntimeSettings(orderedValues(order, parsed, rejected),
                rejectedDescriptors(refusedToShaders, byName), rejected, result);
    }

    /** The non-null declarations by name; a later declaration of a name replaces the earlier one. */
    private static Map<String, Declaration> declarationsByName(List<Declaration> declarations) {
        Map<String, Declaration> byName = new TreeMap<>();
        if (declarations != null) {
            for (Declaration declaration : declarations) {
                if (declaration != null) {
                    byName.put(declaration.name(), declaration);
                }
            }
        }
        return byName;
    }

    /** One declaration's compiled program, or the deviation that refuses it. */
    private record Compiled(PackExpression.Program program, String refusal) {
        static Compiled refused(String refusal) {
            return new Compiled(null, refusal);
        }
    }

    /**
     * Compiles one declaration, or refuses it for an unserved type, an engine input's name, an expression that does
     * not parse or has the wrong width, or a dependency nothing serves.
     */
    private static Compiled compile(Declaration declaration, PackExpression.Constants constants,
                                    PackExpression.Widths widths, Set<String> declared) {
        if (width(declaration.type()) == 0) {
            return Compiled.refused("CUSTOM_VALUE_TYPE_UNSUPPORTED:" + declaration.name());
        }
        if (UniformRegistry.isEngineInput(declaration.name())) {
            return Compiled.refused("CUSTOM_VALUE_SHADOWS_BUILTIN:" + declaration.name());
        }
        PackExpression.Program program;
        try {
            program = PackExpression.parse(declaration.expression(), constants, widths);
        } catch (PackExpression.Unsupported failure) {
            return Compiled.refused(failure.code() + ":" + declaration.name()
                    + (failure.detail().isBlank() ? "" : ":" + failure.detail()));
        }
        if (program.width() != width(declaration.type())) {
            return Compiled.refused("CUSTOM_EXPRESSION_TYPE:" + declaration.name() + ":" + declaration.type()
                    + " given width " + program.width());
        }
        String unknown = unresolved(program, declared);
        if (unknown != null) {
            return Compiled.refused("CUSTOM_EXPRESSION_UNKNOWN:" + declaration.name() + ":" + unknown);
        }
        String unreadable = unreadable(program, declared);
        if (unreadable != null) {
            return Compiled.refused("CUSTOM_EXPRESSION_COMPONENT:" + declaration.name() + ":" + unreadable);
        }
        return new Compiled(program, null);
    }

    /** The values to evaluate, in dependency order, skipping every refused name. */
    private static List<Value> orderedValues(List<String> order, Map<String, ParsedValue> parsed,
                                             Set<String> rejected) {
        List<Value> values = new ArrayList<>();
        for (String name : order) {
            if (!rejected.contains(name)) {
                ParsedValue value = parsed.get(name);
                values.add(new Value(value.declaration().name(), value.declaration().type(),
                        value.declaration().exposed(), value.program(),
                        width(value.declaration().type())));
            }
        }
        return values;
    }

    /** A REJECTED descriptor for each refused value that shaders can see. */
    private static Map<String, UniformRegistry.UniformDescriptor> rejectedDescriptors(
            Set<String> rejected, Map<String, Declaration> byName) {
        Map<String, UniformRegistry.UniformDescriptor> descriptors = new TreeMap<>();
        for (String name : rejected) {
            Declaration declaration = byName.get(name);
            if (declaration != null && declaration.exposed()) {
                descriptors.put(name, new UniformRegistry.UniformDescriptor(
                        name, glslTypes(declaration.type()),
                        UniformRegistry.Availability.REJECTED, "rejected:" + name));
            }
        }
        return descriptors;
    }

    /** The first dependency that is neither a declared name nor a served uniform. */
    private static String unresolved(PackExpression.Program program, Set<String> declared) {
        for (String dependency : program.dependencies()) {
            if (!declared.contains(dependency) && UniformRegistry.descriptor(dependency) == null) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * A host vector or matrix the expression reads whose components the host does not serve to expressions.
     *
     * A declaration that reads one is refused, since evaluating it against zeros would not mean what it says.
     */
    private static String unreadable(PackExpression.Program program, Set<String> declared) {
        for (String dependency : program.dependencies()) {
            if (!declared.contains(dependency) && UniformRegistry.expressionWidth(dependency) > 1
                    && !UniformRegistry.expressionComponentsServed(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * GLSL declarations a value may be read through. Iris serves {@code uniform.bool} to a
     * {@code bool} or an {@code int} declaration alike; both are one 32-bit integer in the block.
     */
    private static List<String> glslTypes(String type) {
        return type.equals("bool") ? List.of("int", "bool") : List.of(type);
    }

    /** Components a declared type holds, or 0 for a type the language does not serve. */
    private static int width(String type) {
        return switch (type) {
            case "float", "int", "bool" -> 1;
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> 0;
        };
    }

    public List<String> deviations() {
        return deviations;
    }

    public Map<String, UniformRegistry.UniformDescriptor> customDescriptors() {
        return customDescriptors;
    }

    /** Refused declaration names; none of them is served as a value. */
    public Set<String> rejected() {
        return rejected;
    }

    /** A value's first storage slot, or -1; a vector's components follow it in order. */
    public int indexOf(String name) {
        return indices.getOrDefault(name, -1);
    }

    /** Per-session storage: one smoothing state per value, one slot per component. */
    public static final class Session {
        private final PackExpression.State[] states;
        private final double[] scratch;
        /** Served values: an int or bool exact, as Iris's int uniforms are; a float rounded to float. */
        private final double[] output;
        /** One value's components for the frame, before they are stored. */
        private final double[] lanes = new double[4];
        private final Map<String, String> failures = new TreeMap<>();
        private int frames;

        private Session(List<Value> values, int slots) {
            this.states = new PackExpression.State[values.size()];
            for (int index = 0; index < values.size(); index++) {
                this.states[index] = values.get(index).program().newState();
            }
            this.scratch = new double[slots];
            this.output = new double[slots];
        }

        /** The frame's evaluated values, one slot per component, in dependency order. */
        public double[] values() {
            return this.output;
        }

        /**
         * Values whose expression gave a non-finite result, with the context of the first frame it happened on.
         *
         * Each is kept once; the value is still served as computed, as Iris serves it.
         */
        public Map<String, String> failures() {
            return Map.copyOf(this.failures);
        }

        /** Drops every accumulator and value, for a pack reload. */
        public void reset() {
            for (PackExpression.State state : this.states) {
                state.reset();
            }
            Arrays.fill(this.scratch, 0.0);
            Arrays.fill(this.output, 0.0);
            this.failures.clear();
            this.frames = 0;
        }
    }

    public Session newSession() {
        return new Session(values, slotCount);
    }

    /**
     * Evaluates every valid custom value once, in dependency order, before any program's uniform block is written.
     *
     * A value that reads another reads this frame's result; a smoothing call advances its own state by one frame.
     */
    public void evaluate(PackExpression.Inputs engine, Session session, float frameDelta) {
        if (session == null) {
            return;
        }
        session.frames++;
        PackExpression.Inputs inputs = this.lookup(engine, session.scratch);
        for (int index = 0; index < values.size(); index++) {
            Value value = values.get(index);
            int base = indices.get(value.name());
            value.program().evaluate(inputs, session.states[index], frameDelta, session.lanes, 0);
            for (int lane = 0; lane < value.width(); lane++) {
                double result = session.lanes[lane];
                int slot = base + lane;
                if (!Double.isFinite(result)) {
                    // served as Iris serves it, to the uniform and to the values that read it;
                    // RenderPearl's fog density divides by its Fog setting, and Iris's infinity at 0 means no fog
                    // recorded once per value, with its expression and inputs
                    session.failures.putIfAbsent(value.name(),
                            value.name() + (value.width() > 1 ? "." + "xyzw".charAt(lane) : "")
                                    + " is " + result + " at frame " + session.frames + ": "
                                    + value.program().source() + ", inputs " + context(value, inputs));
                }
                session.scratch[slot] = result;
                // an int past float's 24 bits stays exact: RenderPearl's packedView at 1366x768 is 50333014,
                // which a float rounds to 50333016, a width of 1368
                // both arms are double; an int arm beside a float arm makes the conditional a float (JLS 15.25)
                boolean integral = value.type().equals("int") || value.type().equals("bool");
                session.output[slot] = integral ? (double) (int) result : (double) (float) result;
            }
        }
    }

    /** The inputs one expression read, as the failing evaluation saw them. */
    private static String context(Value value, PackExpression.Inputs inputs) {
        TreeMap<String, Double> named = new TreeMap<>();
        for (String name : value.program().dependencies()) {
            named.put(name, inputs.value(name, -1));
        }
        return named.toString();
    }

    /** Custom values win over the host for their own names; everything else is the host's. */
    private PackExpression.Inputs lookup(PackExpression.Inputs engine, double[] scratch) {
        return (name, component) -> {
            Integer index = indices.get(name);
            if (index == null) {
                return engine.value(name, component);
            }
            int width = widths.get(name);
            return component < 0 ? scratch[index] : component < width ? scratch[index + component] : Double.NaN;
        };
    }

    /** Components a served value holds: 1 for a scalar, 2 to 4 for a vector, 0 when absent. */
    public int widthOf(String name) {
        return widths.getOrDefault(name, 0);
    }

    static Declaration declaration(boolean exposed, String type, String name, String expression) {
        return new Declaration(exposed, type, name, expression);
    }

    /**
     * One authored declaration. {@code uniform.} lines are {@code exposed} to shaders; {@code variable.} lines are
     * the pack's own working values.
     */
    record Declaration(boolean exposed, String type, String name, String expression) {
        Declaration {
            type = type == null ? "" : type.trim();
            name = name == null ? "" : name.trim();
            expression = expression == null ? "" : expression.trim();
        }
    }

    private record ParsedValue(Declaration declaration, PackExpression.Program program) {}

    private record Value(String name, String type, boolean exposed, PackExpression.Program program,
                         int width) {}

    private static boolean resolve(
            String name,
            Map<String, ParsedValue> parsed,
            Set<String> rejected,
            Set<String> visiting,
            Set<String> resolved,
            List<String> order,
            List<String> deviations
    ) {
        if (resolved.contains(name)) {
            return !rejected.contains(name);
        }
        if (!visiting.add(name)) {
            rejected.add(name);
            deviations.add("CUSTOM_VALUE_CYCLE:" + name);
            return false;
        }
        ParsedValue value = parsed.get(name);
        boolean valid = value != null && !rejected.contains(name);
        if (valid) {
            for (String dependency : value.program().dependencies()) {
                // a dependency refused at parse time is still declared, so the host does not answer for it;
                // its dependents are refused too
                ParsedValue dependencyValue = parsed.get(dependency);
                if (rejected.contains(dependency) || (dependencyValue != null
                        && !resolve(dependency, parsed, rejected, visiting, resolved, order,
                        deviations))) {
                    rejected.add(name);
                    deviations.add("CUSTOM_VALUE_DEPENDENCY_INVALID:" + name);
                    valid = false;
                }
            }
        }
        visiting.remove(name);
        resolved.add(name);
        if (valid) {
            order.add(name);
        }
        return valid;
    }
}
