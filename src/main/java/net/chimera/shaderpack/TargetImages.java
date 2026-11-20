// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's colorimgN: colour target N as a storage image, the texture the program samples as colortexN. Chimera
 * serves it in post passes and hand programs only; Iris also gives it to other gbuffers programs and to final.
 */
public final class TargetImages {
    private static final Pattern SYMBOL = Pattern.compile("colorimg(1[0-5]|[0-9])");

    private TargetImages() {}

    /** The target {@code symbol} names as colorimgN, or -1. */
    public static int target(String symbol) {
        if (symbol == null) return -1;
        Matcher matcher = SYMBOL.matcher(symbol);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** The resource a colorimgN binding names: the colour target, as its sampler names it. */
    static String resource(int target) {
        return "colortex" + target;
    }

    /** Whether Chimera binds colorimgN in {@code program}: a post pass other than final, or a hand program. */
    public static boolean servedIn(String program) {
        return PostTargetPlan.isPostProgramName(program) && !"final".equals(program)
                || FamilyAdapterRegistry.isHandFamily(program);
    }

    /** Whether {@code program} draws the world, where colorimgN binds the image world programs read. */
    public static boolean world(String program) {
        return !PostTargetPlan.isPostProgramName(program);
    }

    /** The targets a program's stages declare and use as colorimgN, ascending. */
    static Set<Integer> referenced(PackProgramPlan program) {
        Set<Integer> targets = new TreeSet<>();
        if (program == null || !servedIn(program.name())) return targets;
        for (PreparedShaderSource stage : program.stages().values()) {
            String source = stage == null ? null : stage.source();
            if (source == null || !source.contains("colorimg")) continue;
            Matcher declaration = GraphicsImageScan.GRAPHICS_IMAGE_DECLARATION.matcher(source);
            while (declaration.find()) {
                int target = target(declaration.group(3));
                if (target >= 0 && GlslTokenRewriter.identifierCount(source, declaration.group(3)) > 1) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }
}
