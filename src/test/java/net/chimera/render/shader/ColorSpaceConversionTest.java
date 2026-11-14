package net.chimera.render.shader;

import net.chimera.config.ColorSpace;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Color Space setting's world resolve: each conversion compiles and carries Iris's matrices. */
final class ColorSpaceConversionTest {
    private static final double[][] SRGB_TO_XYZ = {
            {0.4124564, 0.3575761, 0.1804375}, {0.2126729, 0.7151522, 0.0721750},
            {0.0193339, 0.1191920, 0.9503041}};
    private static final double[][] XYZ_TO_P3_D65 = {
            {2.4933963, -0.9313459, -0.4026945}, {-0.8294868, 1.7626597, 0.0236246},
            {0.0358507, -0.0761827, 0.9570140}};
    private static final double[][] XYZ_TO_REC2020 = {
            {1.7166511880, -0.3556707838, -0.2533662814}, {-0.6666843518, 1.6164812366, 0.0157685458},
            {0.0176398574, -0.0427706133, 0.9421031212}};
    private static final double[][] XYZ_TO_ADOBE_RGB = {
            {2.04158790381075, -0.56500697427886, -0.34473135077833},
            {-0.96924363628088, 1.87596750150772, 0.0415550574071756},
            {0.0134442806320311, -0.118362392231018, 1.01517499439121}};
    /** Bradford adaptation from D65 to the DCI white, which Iris applies after the P3 primaries. */
    private static final double[][] D65_TO_DCI = {
            {1.02449672775258, 0.0151635410224164, 0.0196885223342068},
            {0.0256121933371582, 0.972586305624413, 0.00471635229242733},
            {0.00638423065008769, -0.0122680827367302, 1.14794244517368}};

    @Test
    void everyConversionCompiles() {
        String source = ChimeraShaderLoader.loadSource("chimera_color_space/chimera_color_space.fsh");
        for (ColorSpace space : ColorSpace.values()) {
            if (space == ColorSpace.SRGB) continue;
            String converted = ChimeraPostPipelines.colorSpaceSource(source, space);
            var lines = converted.lines().toList();
            assertTrue(lines.get(0).startsWith("#version 460")
                            && lines.get(1).equals("#define CHIMERA_COLOR_SPACE " + space.ordinal()),
                    "the define follows the version line: " + lines.subList(0, 2));
            compileFragment(converted, "chimera_color_space_" + space);
        }
    }

    /** The folded matrices equal Iris's chains: the sRGB-to-XYZ matrix, the target's, and DCI's white. */
    @Test
    void matricesFoldIrisChains() {
        String source = ChimeraShaderLoader.loadSource("chimera_color_space/chimera_color_space.fsh");
        Map<String, double[][]> expected = Map.of(
                "SRGB_TO_DISPLAY_P3", multiply(XYZ_TO_P3_D65, SRGB_TO_XYZ),
                "SRGB_TO_DCI_P3", multiply(D65_TO_DCI, multiply(XYZ_TO_P3_D65, SRGB_TO_XYZ)),
                "SRGB_TO_REC2020", multiply(XYZ_TO_REC2020, SRGB_TO_XYZ),
                "SRGB_TO_ADOBE_RGB", multiply(XYZ_TO_ADOBE_RGB, SRGB_TO_XYZ));
        expected.forEach((name, matrix) -> {
            double[] written = constant(source, name);
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    assertEquals(matrix[row][column], written[row * 3 + column], 1.0e-7,
                            name + "[" + row + "][" + column + "]");
                }
            }
        });
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] product = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int k = 0; k < 3; k++) product[row][column] += a[row][k] * b[k][column];
            }
        }
        return product;
    }

    /** A mat3 constant's nine numbers, as written (row by row). */
    private static double[] constant(String source, String name) {
        Matcher matcher = Pattern.compile("const mat3 " + name + " = mat3\\(([^)]*)\\);").matcher(source);
        assertTrue(matcher.find(), name + " is declared");
        String[] parts = matcher.group(1).split(",");
        assertEquals(9, parts.length, name + " has nine entries");
        double[] values = new double[9];
        for (int i = 0; i < 9; i++) values[i] = Double.parseDouble(parts[i].strip());
        return values;
    }

    private static void compileFragment(String source, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_fragment_shader, name, "main", 0L);
        try {
            assertTrue(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                            == Shaderc.shaderc_compilation_status_success,
                    name + " compiles: " + (result == 0 ? "no shader result"
                            : Shaderc.shaderc_result_get_error_message(result)));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
