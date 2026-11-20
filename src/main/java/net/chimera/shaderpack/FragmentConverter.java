// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Converts a pack fragment stage to Vulkan GLSL for its family; null on failure. In a post stage the pack
 * uniform block, if any, is binding 0 and the samplers follow it; in a world stage the samplers follow the
 * pipeline's host and pack uniform blocks.
 */
public final class FragmentConverter {
    private FragmentConverter() {}

    /** The sampler types a descriptor binding is written onto. */
    private static final Set<String> BINDABLE_SAMPLERS = Set.of("sampler3D", "isampler3D", "usampler3D",
            "sampler2D", "sampler2DShadow", "isampler2D", "usampler2D", "sampler1D", "isampler1D", "usampler1D");

    /** Host blocks before a terrain-lane program's uniform block: view and fog, or for shadow the view alone. */
    static int terrainHostBlocks(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.SHADOW ? 1 : 2;
    }

    /** Inputs for one fragment conversion; a plan left out is empty or disabled. */
    public record FragmentConversionRequest(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            GeometryOutputPlan geometryOutputPlan,
            PostVertexBridge.PostVaryingLayout postVaryingLayout,
            Set<String> atlasSamplers
    ) {
        public FragmentConversionRequest {
            atlasSamplers = atlasSamplers == null ? Set.of() : atlasSamplers;
        }

        public static FragmentConversionRequest of(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    null, null, null, null, null, Set.of());
        }

        /** Like {@link #of}, with a terrain layout and the interface plan derived from the source. */
        public static FragmentConversionRequest withAutoPlan(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots,
                        LegacyGlslConverter.TerrainVaryingLayout terrainLayout) {
            return of(source, sourceFile, geometryStage, geometrySamplerSlots)
                    .withTerrainLayout(terrainLayout)
                    .withInterfacePlan(InterfacePlanner.plan(source, LegacyGlslConverter.stageOf(geometryStage)));
        }

        public FragmentConversionRequest withTerrainLayout(LegacyGlslConverter.TerrainVaryingLayout terrainLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        public FragmentConversionRequest withInterfacePlan(UniformRegistry.ProgramInterface interfacePlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        public FragmentConversionRequest withTargetPlan(PostTargetPlan targetPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        public FragmentConversionRequest withGeometryOutputPlan(GeometryOutputPlan geometryOutputPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        public FragmentConversionRequest withPostVaryingLayout(PostVertexBridge.PostVaryingLayout postVaryingLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        public FragmentConversionRequest withAtlasSamplers(Set<String> atlasSamplers) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    postVaryingLayout, atlasSamplers);
        }

        /** Runs the conversion; null if it fails. */
        public String convert() {
            return convertFragment(this);
        }
    }

    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan
    ) {
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .convert();
    }

    /** Converts a post fragment that reads its varyings at the paired fullscreen vertex stage's locations. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, Integer> varyingLocations,
            Map<String, String> varyingTypes
    ) {
        PostVertexBridge.PostVaryingLayout layout = varyingLocations == null
                ? null : new PostVertexBridge.PostVaryingLayout(varyingLocations, varyingTypes);
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .withPostVaryingLayout(layout)
                .convert();
    }

    static String convertFragment(FragmentConversionRequest request) {
        try {
            return new FragmentConversion(request).convert();
        } catch (Exception e) {
            // the planner reports POST_CONVERTER_UNSUPPORTED; only the log gets the cause
            LoggerFactory.getLogger("chimera").warn(
                    "[chimera] fragment conversion failed: {}", e.toString());
            return null;
        }
    }

    /** One fragment stage's conversion: the request, and its source as each step rewrites it. */
    private static final class FragmentConversion {
        private final FragmentConversionRequest request;
        private final UniformRegistry.ProgramInterface interfacePlan;
        private final UniformRegistry.Stage stage;
        private final boolean geometryStage;
        private String src;
        /** A world stage in modern GLSL: version 450 or later, or a shadow stage with in/out globals. */
        private boolean modern;

        FragmentConversion(FragmentConversionRequest request) {
            this.request = request;
            this.interfacePlan = request.interfacePlan();
            this.geometryStage = request.geometryStage();
            boolean geometryInterface = interfacePlan != null && interfacePlan.stage() != UniformRegistry.Stage.POST;
            if (interfacePlan == null || !interfacePlan.executable() || geometryInterface != geometryStage) {
                throw new IllegalArgumentException("pack interface is outside the executable contract");
            }
            this.stage = interfacePlan.stage();
            PostTargetPlan targetPlan = request.targetPlan();
            if (targetPlan != null && (geometryStage || stage != UniformRegistry.Stage.POST
                    || !targetPlan.executable())) {
                throw new IllegalArgumentException("post target plan is outside the executable contract");
            }
            GeometryOutputPlan geometryOutputPlan = request.geometryOutputPlan();
            if (geometryOutputPlan != null && (!geometryStage || !geometryOutputPlan.executable())) {
                throw new IllegalArgumentException("geometry output plan is outside the executable contract");
            }
            if (request.source() == null) {
                throw new IllegalArgumentException("pack fragment source is missing");
            }
        }

        String convert() {
            prepare();
            rewriteDeclarations();
            bindSamplers();
            String outputDeclarations = rewriteOutputs();
            return assemble(outputDeclarations, uniformBlock());
        }

        /** Inlines includes, drops unreachable functions and settles the version line. */
        private void prepare() {
            src = LegacyGlslConverter.prepareSource(request.source(), request.sourceFile(), false);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(src);
            if (!usage.successful()) {
                throw new IllegalArgumentException(usage.deviations().toString());
            }
            src = GlslTokenRewriter.removeUnreachableFunctions(src, usage);
            if (!geometryStage) {
                PostVertexBridge.validatePostVersion(src);
                src = LegacyGlslConverter.withoutVersionLines(src);
                return;
            }
            GlslVersion version = GlslVersion.first(src);
            modern = (version != null && version.number() >= 450)
                    || (stage.shadowPass() && !TerrainDeclarations.modern(
                            src, TerrainDeclarations.MODERN_STORAGES).isEmpty());
            // Vulkan gets one version line; an authored profile means nothing there
            src = LegacyGlslConverter.withoutVersionLines(src);
            if (modern) {
                src = "#version 460\n" + src;
            }
        }

        /** Drops what the host serves and maps the varyings. */
        private void rewriteDeclarations() {
            src = LegacyGlslConverter.removePackMetadataConstants(src);
            src = LegacyGlslConverter.withoutKnownLegacyExtensions(src);
            src = InterfacePlanner.removeUniformDeclarations(src, interfacePlan);
            if (stage.perVertexEntityIdentity()) {
                src = EntityDeclarations.removeEntityIdDeclarations(src, interfacePlan);
                src = EntityIdRewrites.replaceEntityIdReferences(src, request.terrainLayout());
            }
            if (geometryStage && modern && request.geometryOutputPlan() == null && !stage.shadowPass()) {
                src = convertModernGeometryOutputs(src);
            }
            src = LegacyGlslConverter.convertVaryings(src, geometryStage, request.terrainLayout(),
                    request.postVaryingLayout(), stage);
        }

        /** Rewrites texture calls and binds each declared sampler at its binding in the program's layout. */
        private void bindSamplers() {
            if (geometryStage) {
                // 'texture' is a function in GLSL 460; rename OptiFine's sampler before convertTextureCalls
                src = GlslTokenRewriter.renameSamplerIdentifier(src, "texture", "chimeraTexture");
            }
            src = LegacyGlslConverter.convertTextureCalls(src);
            if (terrainContract(stage)) {
                Set<String> emittedAtlasSamplers = new HashSet<>();
                for (String name : request.atlasSamplers()) {
                    emittedAtlasSamplers.add(name.equals("texture") ? "chimeraTexture" : name);
                }
                src = AtlasSampleRewriter.rewriteTerrainAtlasSamples(src, emittedAtlasSamplers);
            }
            if (GlslTokenRewriter.containsIdentifier(src, "shadow2DProj")
                    || GlslTokenRewriter.containsIdentifier(src, "shadow2DProjLod")) {
                throw new IllegalArgumentException("projected shadow lookup is outside the supported bridge");
            }
            Map<String, String> samplerTypes = new TreeMap<>();
            for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
                samplerTypes.put(sampler.name(), sampler.glslType());
            }
            src = GlslTokenRewriter.rewriteShadowCalls(src, samplerTypes);
            bindSamplerDeclarations();
        }

        private void bindSamplerDeclarations() {
            int bindingBase = geometryStage
                    ? geometrySamplerBindingBase(interfacePlan)
                    : (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
            for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
                String name = sampler.name();
                String srcName = geometryStage && name.equals("texture") ? "chimeraTexture" : name;
                int binding = geometryStage
                        ? geometrySamplerBinding(name, request.geometrySamplerSlots(), interfacePlan)
                        : bindingBase + interfacePlan.samplerIndex(name);
                if (binding < bindingBase) {
                    throw new IllegalArgumentException("sampler is missing from the generated config: " + name);
                }
                if (hasSamplerDeclaration(src, srcName)) {
                    src = rewriteSamplerDeclaration(src, srcName, binding);
                }
            }
        }

        /** Routes the authored outputs to their attachments; returns their declarations, or null. */
        private String rewriteOutputs() {
            if (request.targetPlan() != null) {
                src = convertPostOutputs(src, request.targetPlan());
                return postOutputDeclarations(request.targetPlan());
            }
            if (request.geometryOutputPlan() != null) {
                src = convertGeometryOutputs(src, request.geometryOutputPlan());
                return geometryOutputDeclarations(request.geometryOutputPlan());
            }
            if (stage.shadowPass()) {
                return rewriteShadowOutputs();
            }
            if (src.contains("gl_FragColor") || src.contains("gl_FragData")) {
                src = GlslTokenRewriter.rewriteSingleOutput(src);
                // a single-attachment pass has nothing past target 0; drop gl_FragData writes beyond it
                src = src.replaceAll("gl_FragData\\s*\\[\\s*[1-9][0-9]*\\s*\\]\\s*=\\s*[^;]+;\\s*", "");
                return "layout(location = 0) out vec4 fragColor;";
            }
            return null;
        }

        /**
         * Each written shadowcolor goes to its index's attachment in the pack's type. Every colour up to the
         * highest written, and at least two, is declared; an unwritten one is a vec4 with masked writes.
         */
        private String rewriteShadowOutputs() {
            PostTargetPlan shadowOutputs = PostTargetPlan.parseShadow("shadow", src, Map.of()).plan();
            if (!shadowOutputs.executable() || shadowOutputs.targetSlots().stream()
                    .anyMatch(target -> target < 0 || target >= ShadowTargetDirectives.MAX_COLOR_TARGETS)) {
                throw new IllegalArgumentException("SHADOW_OUTPUT_TARGET_UNSUPPORTED");
            }
            src = convertPostOutputs(src, shadowOutputs);
            Map<String, String> outputs = new TreeMap<>();
            int highest = shadowOutputs.targetSlots().stream().mapToInt(Integer::intValue).max().orElse(0);
            int count = Math.max(2, highest + 1);
            String[] types = new String[count];
            int[] components = new int[count];
            Arrays.fill(types, "vec4");
            for (int location : shadowOutputs.outputLocations()) {
                int target = shadowOutputs.targetForOutput(location);
                outputs.put("chimeraFragColor" + location, "chimeraShadowColor" + target);
                types[target] = shadowOutputs.outputType(location);
                components[target] = shadowOutputs.outputComponent(location);
            }
            src = GlslTokenRewriter.replaceIdentifiers(src, outputs);
            StringBuilder declarations = new StringBuilder();
            for (int target = 0; target < count; target++) {
                if (target > 0) declarations.append('\n');
                declarations.append(outputLayout(target, components[target])).append(" out ").append(types[target])
                        .append(" chimeraShadowColor").append(target).append(';');
            }
            return declarations + (shadowOutputs.discardedOutputs().isEmpty() ? ""
                    : "\n" + discardedOutputDeclarations(shadowOutputs).stripTrailing());
        }

        /** The generated uniform block of the stage's family, empty when it reads no uniforms. */
        private String uniformBlock() {
            List<UniformRegistry.UniformDeclaration> uniforms = interfacePlan.executableUniforms();
            if (stage.shadowPass() && !uniforms.isEmpty()) {
                src = qualifyShadowUniformReferences(src, uniforms);
                return generatedShadowUniformBlock(uniforms, shadowUniformBinding(stage));
            }
            if (geometryStage && terrainContract(stage)) {
                return uniforms.isEmpty() ? "" : generatedGeometryUniformBlock(uniforms);
            }
            if ((entityStage(stage) || stage == UniformRegistry.Stage.SKY || stage == UniformRegistry.Stage.CLOUD)
                    && !uniforms.isEmpty()) {
                return entityUniformBlock(uniforms);
            }
            return !geometryStage && !uniforms.isEmpty() ? generatedUniformBlock(uniforms, src) : "";
        }

        /** Puts the generated declarations right after the version line; glslang rejects a use before them. */
        private String assemble(String outputDeclarations, String uniformBlock) {
            String declarations = (stage.perVertexEntityIdentity()
                    ? chimeraFragmentVaryings(request.terrainLayout()) : "")
                    + (outputDeclarations != null ? outputDeclarations + "\n" : "") + uniformBlock;
            if (stage.shadowPass()) {
                declarations = SHADOW_RENDER_STAGE_DEFINES + declarations;
            }
            if (!geometryStage || !modern) {
                String assembled = "#version 460\n" + declarations + src;
                return geometryStage ? withoutNanOutputs(assembled) : assembled;
            }
            return withoutNanOutputs(declarations.isEmpty() ? src
                    : LegacyGlslConverter.insertAfterFirstLine(src, declarations));
        }
    }

    /** The float output types a geometry fragment writes NaN through. */
    private static final Pattern FLOAT_OUTPUT = Pattern.compile("float|vec[234]|float16_t|f16vec[234]");

    /**
     * Replaces NaNs in the float outputs, at the end of main, with what NVIDIA's GL shows: a NaN colour becomes
     * opaque black, or nothing at alpha 0; other outputs take 0 per NaN component. GL blends a zero-weighted
     * NaN term to 0, where Vulkan keeps NaN.
     */
    static String withoutNanOutputs(String source) {
        GlslTree tree = GlslTree.parse(source);
        String helper = GlslTokenRewriter.uniqueIdentifier(source, "chimeraNoNan");
        Set<String> types = new TreeSet<>();
        StringBuilder statements = new StringBuilder();
        for (GlslDeclarations.Variable output : GlslDeclarations.variables(tree)) {
            // built-ins and never-written outputs stay unwritten; a write would store an undefined value
            if (!output.qualifiers().contains("out") || !output.plain() || output.name().startsWith("gl_")
                    || !FLOAT_OUTPUT.matcher(output.type()).matches()
                    || GlslTokenRewriter.identifierCount(source, output.name()) < 2) {
                continue;
            }
            types.add(output.type());
            // a 16-bit output may compile as its 32-bit type, so the helper is overloaded for both
            String widened = GlslVendorExtensions.widenedType(output.type());
            if (widened != null) types.add(widened);
            statements.append(output.name()).append(" = ").append(helper).append('(').append(output.name())
                    .append(");\n");
        }
        if (statements.isEmpty()) return source;
        StringBuilder helpers = new StringBuilder();
        for (String type : types) {
            String guarded = type.endsWith("vec4")
                    ? "any(isnan(value)) ? (value.a == " + type + "(0).a ? " + type + "(0) : " + type
                            + "(0, 0, 0, 1)) : value"
                    : "mix(value, " + type + "(0), isnan(value))";
            helpers.append(type).append(' ').append(helper).append('(').append(type).append(" value) { return ")
                    .append(guarded).append("; }\n");
        }
        int main = GlslTokenRewriter.mainDefinition(tree).range().start();
        String withHelpers = tree.print(List.of(new GlslTree.Edit(new GlslTree.Range(main, main),
                helpers.toString())));
        // after the alpha test PackAlphaTestPlan appended, so a NaN alpha is discarded first
        return GlslTokenRewriter.appendMainBody(withHelpers, statements.toString().strip());
    }

    private static boolean entityStage(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.ENTITY || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND || stage == UniformRegistry.Stage.PARTICLE;
    }

    static String convertPostOutputs(String source, PostTargetPlan targetPlan) {
        String result = rewriteModernOutputs(source, targetPlan);
        return GlslTokenRewriter.rewritePostOutputs(result, targetPlan);
    }

    /** A one-target geometry output is named {@code fragColor}, as on the single-output path. */
    static String convertGeometryOutputs(String source, GeometryOutputPlan outputPlan) {
        String result = convertPostOutputs(source, outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    static String geometryOutputDeclarations(GeometryOutputPlan outputPlan) {
        String result = postOutputDeclarations(outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    static String rewriteModernOutputs(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            return source;
        }
        List<PostTargetPlan.ModernOutput> outputs = PostTargetPlan.modernOutputs(source);
        if (outputs.isEmpty()) {
            return source;
        }
        StringBuilder result = new StringBuilder();
        Map<String, String> replacements = new TreeMap<>();
        int last = 0;
        for (PostTargetPlan.ModernOutput output : outputs) {
            String declaration = source.substring(output.start(), output.end());
            if (targetPlan.discardedOutputs().contains(output.location())) {
                // GL discards it past the route: keep a private global, and the line breaks for line numbers
                result.append(source, last, output.start())
                        .append(declaration.replaceAll("[^\\r\\n]", ""))
                        .append(output.type()).append(' ').append(output.name()).append(';');
                last = output.end();
                continue;
            }
            if (!targetPlan.outputLocations().contains(output.location())) {
                throw new IllegalArgumentException("modern fragment output exceeds target route: " + output.name());
            }
            replacements.put(output.name(), "chimeraFragColor" + output.location());
            // the generated declaration replaces this one; keep its line breaks
            result.append(source, last, output.start())
                    .append(declaration.replaceAll("[^\\r\\n]", ""));
            last = output.end();
        }
        result.append(source.substring(last));
        return GlslTokenRewriter.replaceIdentifiers(result.toString(), replacements);
    }

    static String postOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.outputLocations()) {
            declarations.append(outputLayout(location, targetPlan.outputComponent(location))).append(" out ")
                    .append(targetPlan.outputType(location))
                    .append(" chimeraFragColor").append(location).append(";\n");
        }
        return declarations.append(discardedOutputDeclarations(targetPlan)).toString();
    }

    /** An output's layout as the pack declared it: a component past 0 stays, or the value lands in .x. */
    private static String outputLayout(int location, int component) {
        return "layout(location = " + location + (component == 0 ? "" : ", component = " + component) + ")";
    }

    /** Private globals for discarded gl_FragData writes; a discarded modern output keeps its own. */
    static String discardedOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.discardedOutputs()) {
            declarations.append("vec4 ").append(GlslTokenRewriter.DISCARDED_OUTPUT).append(location).append(";\n");
        }
        return declarations.toString();
    }

    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertParticleFragment(source, sourceFile, samplerSlots,
                particleLayout, interfacePlan, null);
    }

    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = LegacyGlslConverter.prepareSource(source, sourceFile, false);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(particleLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertEntityFragment(source, sourceFile, samplerSlots, entityLayout,
                interfacePlan, null);
    }

    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = LegacyGlslConverter.prepareSource(source, sourceFile, false);
            validateEntityFragmentVersion(prepared);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(entityLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String convertSkyFragment(
            String source, Path sourceFile, int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout layout, UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan outputPlan
    ) {
        return FragmentConversionRequest.of(source, sourceFile, true, samplerSlots)
                .withTerrainLayout(layout)
                .withInterfacePlan(interfacePlan)
                .withGeometryOutputPlan(outputPlan)
                .convert();
    }

    /** gbuffers_terrain and gbuffers_water share one contract; only the samplers their stage tables admit differ. */
    static boolean terrainContract(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT;
    }

    /** First sampler binding of a geometry-family program, after its host and pack UBOs. */
    static int geometrySamplerBindingBase(UniformRegistry.ProgramInterface interfacePlan) {
        UniformRegistry.Stage stage = interfacePlan.stage();
        boolean uniforms = !interfacePlan.executableUniforms().isEmpty();
        if (stage == UniformRegistry.Stage.ENTITY || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND || stage == UniformRegistry.Stage.PARTICLE
                || stage == UniformRegistry.Stage.SHADOW_ENTITY) {
            return uniforms ? 3 : 2;
        }
        if (terrainContract(stage) || stage == UniformRegistry.Stage.SHADOW) {
            return terrainHostBlocks(stage) + (uniforms ? 1 : 0);
        }
        // sky and clouds: binding 2 holds the pack's uniform block, or an empty one
        return 3;
    }

    /**
     * Descriptor binding of one geometry-family sampler, or -1. VulkanMod exposes every image binding to all
     * graphics stages, so the vertex and fragment stages declare a sampler at the same binding.
     */
    static int geometrySamplerBinding(
            String name, int[] slots, UniformRegistry.ProgramInterface interfacePlan
    ) {
        int index = LegacyGlslConverter.configIndexOf(name, slots, interfacePlan.stage(), interfacePlan);
        return index < 0 ? -1 : geometrySamplerBindingBase(interfacePlan) + index;
    }

    /**
     * One block layout shared by both stages. A member a stage also declares as a file-scope global keeps its
     * slot under a reserved name, so the global wins in that stage, as under Iris.
     */
    static String generatedUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms,
                                                String stageSource) {
        Set<String> globals = LegacyGlslConverter.fileScopeGlobals(stageSource);
        StringBuilder block = new StringBuilder("layout(binding = 0) uniform ChimeraPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            String name = globals.contains(uniform.name()) ? "chimera_shadowed_" + uniform.name() : uniform.name();
            block.append("    ").append(uniform.glslType()).append(' ').append(name).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /** The shadow pack uniform block's binding, after its lane's host blocks. */
    static int shadowUniformBinding(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.SHADOW_ENTITY ? 2 : terrainHostBlocks(stage);
    }

    static String generatedShadowUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms,
            int binding
    ) {
        StringBuilder block = new StringBuilder("layout(binding = " + binding + ") uniform ChimeraShadowUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("} chimeraShadowUniforms;\n").toString();
    }

    /**
     * Reads shadow uniforms through the block instance, whose members keep PipelineConfig's names. A parameter
     * or local of the same name stays unqualified.
     */
    static String qualifyShadowUniformReferences(
            String source,
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        Map<String, String> replacements = new TreeMap<>();
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            replacements.put(uniform.name(), "chimeraShadowUniforms." + uniform.name());
        }
        return GlslScopedReferences.replace(source, replacements);
    }

    /** Terrain and water pack uniforms follow the terrain host blocks. */
    static String generatedGeometryUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        StringBuilder block = new StringBuilder("layout(binding = "
                + terrainHostBlocks(UniformRegistry.Stage.GEOMETRY) + ") uniform ChimeraTerrainPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /** Pack uniform block of entity, sky and cloud fragments, at binding 2 after the host's blocks. */
    static String entityUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms) {
        StringBuilder block = new StringBuilder("layout(binding = 2) uniform ChimeraEntityUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    static void validateEntityFragmentVersion(String source) {
        GlslVersion.requireSupported(source, "entity fragment", true);
    }

    static String chimeraFragmentVaryings(LegacyGlslConverter.TerrainVaryingLayout layout) {
        if (layout == null) return "";
        String declarations = "";
        for (String name : EntityIdRewrites.ENTITY_ID_NAMES) {
            String varying = EntityIdRewrites.entityIdVarying(name);
            if (layout.locations().containsKey(varying)) {
                declarations += "layout(location = " + layout.location(varying)
                        + ") flat in uint " + varying + ";\n";
            }
        }
        for (EntityVertexBridge.ForwardedVarying varying : EntityVertexBridge.ForwardedVarying.ALL) {
            if (layout.locations().containsKey(varying.name())) declarations += varying.declaration(layout, "in");
        }
        return declarations;
    }

    /**
     * A modern terrain fragment's single {@code out vec4} becomes {@code fragColor} at location 0; more than one
     * is unsupported.
     */
    static String convertModernGeometryOutputs(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<String> names = new ArrayList<>();
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslDeclarations.Variable output : GlslDeclarations.variables(tree)) {
            int start = GlslDeclarations.lineStartBefore(source, tree.offset(output.declaration().range().start()));
            List<String> qualifiers = output.qualifiers();
            boolean plainOutput = qualifiers.equals(List.of("out")) || qualifiers.equals(List.of("layout", "out"));
            if (start < 0 || !plainOutput || !output.type().equals("vec4") || !output.plain()) {
                continue;
            }
            int end = tree.offset(output.declaration().range().end());
            while (end < source.length() && " \t\n\u000B\f\r".indexOf(source.charAt(end)) >= 0) end++;
            names.add(output.name());
            removals.add(new GlslDeclarations.TextEdit(start, end, ""));
        }
        if (names.isEmpty()) {
            return source;
        }
        if (names.size() != 1) {
            throw new IllegalArgumentException("modern terrain MRT output is unsupported");
        }
        String result = GlslDeclarations.apply(source, removals);
        String name = names.get(0);
        if (!name.equals("fragColor")) {
            result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(name, "fragColor"));
        }
        // after the version line, where assemble also puts the other generated declarations
        return LegacyGlslConverter.insertAfterFirstLine(result, "layout(location = 0) out vec4 fragColor;\n");
    }

    /** {@code [layout(...)] uniform sampler name;} for this one name, with the binding written onto it. */
    static String rewriteSamplerDeclaration(String source, String name, int binding) {
        GlslTree tree = GlslTree.parse(source);
        GlslTree.Declaration declaration = samplerDeclaration(tree, name);
        if (declaration == null) {
            throw new IllegalArgumentException("sampler declaration is missing: " + name);
        }
        String replacement = "layout(binding = " + binding + ") uniform "
                + tree.text(declaration.type().name()) + " " + name + ";";
        return GlslDeclarations.apply(source, List.of(new GlslDeclarations.TextEdit(
                tree.offset(declaration.range().start()), tree.offset(declaration.range().end()), replacement)));
    }

    static boolean hasSamplerDeclaration(String source, String name) {
        return samplerDeclaration(GlslTree.parse(source), name) != null;
    }

    /** The first declaration of {@code name} alone as a bindable sampler, qualified uniform and at most a layout. */
    private static GlslTree.Declaration samplerDeclaration(GlslTree tree, String name) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            List<String> qualifiers = variable.qualifiers();
            boolean uniform = qualifiers.equals(List.of("uniform")) || qualifiers.equals(List.of("layout", "uniform"));
            if (uniform && variable.name().equals(name) && BINDABLE_SAMPLERS.contains(variable.type())
                    && variable.plain()) {
                return variable.declaration();
            }
        }
        return null;
    }

    /**
     * Iris's render stage constants for shadow shaders, guarded for sources converted directly; pack sources
     * already get them from PackEngineDefines.
     */
    static final String SHADOW_RENDER_STAGE_DEFINES = """
            #ifndef MC_RENDER_STAGE_TERRAIN_SOLID
            #define MC_RENDER_STAGE_TERRAIN_SOLID 8
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_TRANSLUCENT
            #define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT 10
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9
            #endif
            """;
}
