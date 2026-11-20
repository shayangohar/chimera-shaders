// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GL vendor extensions and leniencies that shaderc lacks, rewritten as Vulkan GLSL provides them. shaderc does
 * not know GL_NV_gpu_shader5, so it becomes the extensions in {@code VULKAN} when the device enables their
 * types; otherwise the stage fails, as on a GL driver without it.
 */
public final class GlslVendorExtensions {
    private GlslVendorExtensions() {}

    /** The sized arithmetic types the device enables: shaderFloat16, shaderInt16, shaderInt64. */
    public record SizedTypes(boolean float16, boolean int16, boolean int64) {
        public static final SizedTypes NONE = new SizedTypes(false, false, false);

        boolean all() {
            return float16 && int16 && int64;
        }
    }

    private static final String NVIDIA = "GL_NV_gpu_shader5";
    /**
     * GL_NV_gpu_shader5's 16-bit and 64-bit types in Vulkan GLSL, each emitting only its SPIR-V capability.
     * These keep glslang's GLSL 4.00 overload rules, which resolve {@code float16_t} mixed with float literals
     * as NVIDIA does; any GL_EXT_shader_explicit_arithmetic_types extension makes such calls ambiguous, so the
     * 8-bit types stay out.
     */
    private static final List<String> VULKAN = List.of("GL_AMD_gpu_shader_half_float", "GL_AMD_gpu_shader_int16",
            "GL_ARB_gpu_shader_int64");
    /**
     * An {@code #extension name : behaviour} line: the text before the name, the name, the rest, and
     * a trailing line comment ({@code : require // AMD path}), which the rewrite keeps.
     */
    private static final Pattern EXTENSION = Pattern.compile(
            "(?m)^([ \\t]*#[ \\t]*extension[ \\t]+)(\\w+)([ \\t]*:[ \\t]*\\w+[ \\t]*?)([ \\t]*//.*)?$");

    private static final String TRINARY = "GL_AMD_shader_trinary_minmax";
    /**
     * GL_AMD_shader_trinary_minmax's functions as the extension defines them, for a device without
     * VK_AMD_shader_trinary_minmax, whose SPIR-V crashes NVIDIA's driver ({@link PackDevice#missing}).
     */
    private static final List<String> TRINARY_BUILT_INS = List.of(
            "#define min3(a, b, c) min(a, min(b, c))",
            "#define max3(a, b, c) max(a, max(b, c))",
            "#define mid3(a, b, c) max(min(a, b), min(max(a, b), c))");

    /** {@code source} with GL_NV_gpu_shader5 enabled as its Vulkan extensions, when {@code types} serve it. */
    public static String forVulkan(String source, SizedTypes types) {
        return forVulkan(source, types, true);
    }

    /**
     * {@link #forVulkan(String, SizedTypes)}, with GL_AMD_shader_trinary_minmax's functions written with min
     * and max in place of its directive when the device lacks it ({@code trinaryMinmax}).
     */
    public static String forVulkan(String source, SizedTypes types, boolean trinaryMinmax) {
        if (source == null) return null;
        boolean nvidia = types.all() && source.contains(NVIDIA);
        boolean trinary = !trinaryMinmax && source.contains(TRINARY);
        if (!nvidia && !trinary) return source;
        Matcher line = EXTENSION.matcher(source);
        StringBuilder result = new StringBuilder();
        while (line.find()) {
            String name = line.group(2);
            List<String> lines = new ArrayList<>();
            if (nvidia && name.equals(NVIDIA)) {
                VULKAN.forEach(vulkan -> lines.add(line.group(1) + vulkan + line.group(3)));
            } else if (trinary && name.equals(TRINARY)) {
                lines.addAll(TRINARY_BUILT_INS);
            }
            if (lines.isEmpty()) {
                lines.add(line.group());
            } else if (line.group(4) != null) {
                // the directive's comment stays on the first line written in its place
                lines.set(0, lines.get(0) + line.group(4));
            }
            line.appendReplacement(result, Matcher.quoteReplacement(String.join("\n", lines)));
        }
        line.appendTail(result);
        return result.toString();
    }

    /**
     * {@code source} as the {@code device} compiles it, with its extension lines hoisted and vendor extensions
     * rewritten. 16-bit stage interfaces widen without storageInputOutput16, and NVIDIA's GL leniencies become
     * standard GLSL.
     */
    public static String forVulkan(String source, PackDevice device) {
        String lenient = VectorScalarComparisons.widen(ArrayLengthCalls.complete(
                VectorLogicalOperators.distribute(OpaqueSelections.hoist(hoistExtensions(source)))));
        String result = forVulkan(lenient, device.sizedTypes(), device.trinaryMinmax());
        return device.features().contains("storageInputOutput16") ? result : widenInterfaces(result);
    }

    /** A {@code #extension} or {@code #version} directive, as a line's text outside comments begins. */
    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*(extension|version)\\b");

    /**
     * {@code source} with its {@code #extension} lines moved up to follow {@code #version} when code precedes
     * one, since Chimera's generated declarations go ahead of the pack's source and may use an extension's types.
     */
    static String hoistExtensions(String source) {
        if (source == null || !source.contains("extension")) return source;
        List<int[]> extensions = new ArrayList<>();
        int versionEnd = 0;
        boolean code = false;
        boolean misplaced = false;
        boolean[] inComment = {false};
        for (int start = 0; start < source.length(); ) {
            int newline = source.indexOf('\n', start);
            int end = newline < 0 ? source.length() : newline + 1;
            String visible = outsideComments(source.substring(start, end), inComment);
            Matcher directive = DIRECTIVE.matcher(visible);
            if (directive.find()) {
                if (directive.group(1).equals("version")) {
                    versionEnd = end;
                } else {
                    extensions.add(new int[] {start, end});
                    misplaced |= code;
                }
            } else if (!visible.isBlank() && !visible.strip().startsWith("#")) {
                code = true;
            }
            start = end;
        }
        if (!misplaced) return source;
        StringBuilder hoisted = new StringBuilder();
        StringBuilder rest = new StringBuilder(source.substring(0, versionEnd));
        int at = versionEnd;
        for (int[] line : extensions) {
            // an extension line above #version stays where it is, as the compiler would read it
            if (line[0] < versionEnd) continue;
            hoisted.append(source, line[0], line[1]).append(source.charAt(line[1] - 1) == '\n' ? "" : "\n");
            rest.append(source, at, line[0]);
            at = line[1];
        }
        rest.append(source.substring(at));
        boolean newline = versionEnd == 0 || source.charAt(versionEnd - 1) == '\n';
        return rest.insert(versionEnd, (newline ? "" : "\n") + hoisted).toString();
    }

    /** {@code line} with its comments blanked; {@code inComment[0]} carries a block comment on to the next line. */
    private static String outsideComments(String line, boolean[] inComment) {
        StringBuilder visible = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            if (inComment[0]) {
                if (line.startsWith("*/", index)) {
                    inComment[0] = false;
                    index++;
                }
                visible.append(' ');
            } else if (line.startsWith("/*", index)) {
                inComment[0] = true;
                index++;
                visible.append(' ');
            } else if (line.startsWith("//", index)) {
                break;
            } else {
                visible.append(line.charAt(index));
            }
        }
        return visible.toString();
    }

    /**
     * {@code source} with its 16-bit stage inputs and outputs declared as their 32-bit types, since a 16-bit
     * Vulkan interface needs storageInputOutput16, which NVIDIA's GPUs lack. Values widen implicitly.
     */
    static String widenInterfaces(String source) {
        if (source == null || WIDENED.keySet().stream().noneMatch(source::contains)) return source;
        GlslTree tree;
        try {
            tree = GlslTree.parse(source);
        } catch (GlslParser.ParseFailure failure) {
            return source;
        }
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Declaration declaration
                    && stageInterface(tree, declaration.qualifiers())) {
                widen(tree, declaration, edits);
            } else if (external instanceof GlslTree.InterfaceBlock block
                    && stageInterface(tree, block.qualifiers())) {
                block.members().forEach(member -> widen(tree, member, edits));
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** The 16-bit types a stage interface can declare, and the 32-bit type each widens to. */
    private static final Map<String, String> WIDENED = widened();

    private static Map<String, String> widened() {
        Map<String, String> result = new HashMap<>(Map.of("float16_t", "float", "int16_t", "int",
                "uint16_t", "uint"));
        for (int size = 2; size <= 4; size++) {
            result.put("f16vec" + size, "vec" + size);
            result.put("i16vec" + size, "ivec" + size);
            result.put("u16vec" + size, "uvec" + size);
            result.put("f16mat" + size, "mat" + size);
            for (int rows = 2; rows <= 4; rows++) result.put("f16mat" + size + "x" + rows, "mat" + size + "x" + rows);
        }
        return Map.copyOf(result);
    }

    /** The 32-bit type a 16-bit stage interface type widens to, or null for another type. */
    static String widenedType(String type) {
        return WIDENED.get(type);
    }

    private static boolean stageInterface(GlslTree tree, List<GlslTree.Qualifier> qualifiers) {
        return qualifiers.stream().map(qualifier -> tree.text(qualifier.keyword()))
                .anyMatch(keyword -> keyword.equals("in") || keyword.equals("out") || keyword.equals("varying")
                        || keyword.equals("attribute"));
    }

    private static void widen(GlslTree tree, GlslTree.Declaration declaration, List<GlslTree.Edit> edits) {
        if (declaration.type() == null || declaration.type().struct() != null) return;
        int name = declaration.type().name();
        String wide = WIDENED.get(tree.text(name));
        if (wide != null) edits.add(new GlslTree.Edit(new GlslTree.Range(name, name + 1), wide));
    }
}
