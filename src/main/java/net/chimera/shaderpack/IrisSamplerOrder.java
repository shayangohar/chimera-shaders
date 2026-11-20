// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The order Iris registers a program's samplers in; a name reads its last registration
 * (ProgramSamplers.Builder.addDynamicSampler). A stage texture replaces what the sampler interceptor binds, but
 * not a registration a renderer makes on the program's own builder.
 */
final class IrisSamplerOrder {
    /** What Iris binds a sampler name to. */
    enum Source { TARGET, CUSTOM_TEXTURE, IMAGE, LEVEL, DEPTH, NOISE, SHADOW, CENTER_DEPTH }

    /**
     * One renderer's registrations in order. {@code direct} are those made on the builder; {@code depth} matches
     * its depth sampler names; {@code firstTarget} is its first colour target, 4 for world programs
     * (IrisSamplers.addRenderTargetSamplers).
     */
    record Order(List<Source> sources, Set<Source> direct, Pattern depth, int firstTarget) {
        /** Whether a stage texture replaces what {@code source} binds. */
        boolean intercepted(Source source) {
            return !direct.contains(source);
        }

        /**
         * Which of the built-in sampler under {@code name}, the pack's image sampler ({@code image}) and its
         * customTexture entry ({@code customTexture}) Iris registers last; null when there is none.
         */
        Source last(String name, boolean image, boolean customTexture) {
            Source last = builtIn(name);
            if (image && (last == null || sources.indexOf(Source.IMAGE) > sources.indexOf(last))) {
                last = Source.IMAGE;
            }
            if (customTexture && (last == null || sources.indexOf(Source.CUSTOM_TEXTURE) > sources.indexOf(last))) {
                last = Source.CUSTOM_TEXTURE;
            }
            return last;
        }

        private Source builtIn(String name) {
            if (name == null) return null;
            if (SHADOW.matcher(name).matches()) return Source.SHADOW;
            if (name.equals("noisetex")) return Source.NOISE;
            if (name.equals("iris_centerDepthSmooth")) {
                return sources.contains(Source.CENTER_DEPTH) ? Source.CENTER_DEPTH : null;
            }
            if (depth.matcher(name).matches()) return Source.DEPTH;
            if (sources.contains(Source.LEVEL) && LEVEL.matcher(name).matches()) return Source.LEVEL;
            if (!sources.contains(Source.TARGET)) return null;
            // the DH depth textures come with the render targets, whatever the first target
            Integer target = PackResourcePlan.targetIndex(name);
            return PackResourcePlan.isDhDepth(name) || target != null && target >= firstTarget ? Source.TARGET : null;
        }

        /**
         * The names Iris registers {@code name}'s built-in texture under, in order. A stage texture for the first
         * name that has one replaces it, as in CustomTextureSamplerInterceptor.getOverride. Where gdepthtex is
         * registered it comes before depthtex0, as in IrisSamplers.addCompositeSamplers.
         */
        List<String> names(String name) {
            return "depthtex0".equals(name) && depth.matcher("gdepthtex").matches()
                    ? List.of("gdepthtex", "depthtex0") : List.of(name);
        }

        boolean registers(Source source) {
            return sources.contains(source);
        }
    }

    /** The names IrisSamplers.addShadowSamplers registers. */
    private static final Pattern SHADOW = Pattern.compile("shadow|watershadow|shadowtex[01](?:HW)?|shadowcolor[0-7]?");
    /** The world's texture, lightmap, overlay and PBR maps, as IrisSamplers.addLevelSamplers names them. */
    private static final Pattern LEVEL = Pattern.compile("tex|texture|gtexture|lightmap|iris_overlay|normals|specular");

    /**
     * A begin, prepare, deferred or composite program, and every pass's computes, as CompositeRenderer and
     * FinalPassRenderer.createComputes register them: customTexture entries and the smoothed centre depth on
     * the builder.
     */
    static final Order POST = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.IMAGE, Source.NOISE,
            Source.DEPTH, Source.SHADOW, Source.CENTER_DEPTH), Set.of(Source.CUSTOM_TEXTURE, Source.CENTER_DEPTH),
            Pattern.compile("gdepthtex|depthtex[0-2]"), 0);
    /** The final program; FinalPassRenderer registers its customTexture entries after images. */
    static final Order FINAL = new Order(List.of(Source.TARGET, Source.IMAGE, Source.CUSTOM_TEXTURE, Source.NOISE,
            Source.DEPTH, Source.SHADOW, Source.CENTER_DEPTH), POST.direct(), POST.depth(), 0);
    /**
     * A setup compute, as IrisRenderingPipeline.createSetupComputes registers it: customTexture entries and the
     * composite depth samplers on the builder, before images.
     */
    static final Order SETUP = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.DEPTH, Source.IMAGE,
            Source.NOISE, Source.SHADOW), Set.of(Source.CUSTOM_TEXTURE, Source.DEPTH), POST.depth(), 0);
    /**
     * A gbuffers or shadow program, as IrisRenderingPipeline.addGbufferOrShadowSamplers registers it:
     * customTexture entries through the interceptor, the level samplers on the builder, images after noise and
     * depth.
     */
    static final Order WORLD = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.LEVEL, Source.DEPTH,
            Source.NOISE, Source.IMAGE, Source.SHADOW), Set.of(Source.LEVEL), Pattern.compile("depthtex[0-2]"), 4);

    /**
     * A shadowcomp compute or fragment, as ShadowCompositeRenderer's createComputes and createProgram register
     * it: noise, customTexture entries and the shadow samplers through the interceptor, then custom images on
     * the builder; no colour target and no depth texture.
     */
    static final Order SHADOWCOMP = new Order(List.of(Source.NOISE, Source.CUSTOM_TEXTURE, Source.SHADOW,
            Source.IMAGE), Set.of(Source.IMAGE), Pattern.compile("(?!)"), 0);

    private IrisSamplerOrder() {}

    /** The order {@code program} registers in, or that a compute of its pass does when {@code compute}. */
    static Order of(String program, boolean compute) {
        if (PassComputePlan.isSetup(program)) return SETUP;
        if (PassComputePlan.isShadowcomp(program)) return SHADOWCOMP;
        if (program == null || PackResourcePlan.postChain(program) == null) return WORLD;
        return !compute && program.equals("final") ? FINAL : POST;
    }
}
