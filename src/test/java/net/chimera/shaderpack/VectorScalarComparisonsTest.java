package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Body Camera's old lighting compares a vec3 with a number, which NVIDIA's GL compiler
 * takes as a comparison with that number in every component and GLSL refuses.
 */
final class VectorScalarComparisonsTest {
    @Test
    void aNumberComparedWithAVectorBecomesThatVector() {
        String source = """
                #version 430
                layout(location = 0) in vec3 mc_Entity;
                layout(location = 1) in vec3 vaNormal;
                layout(location = 0) out vec3 normal;
                bool same(ivec2 cell) { return -1 == cell; }
                vec2 entityValue() { return mc_Entity.xy; }
                void main() {
                    normal = (mc_Entity==1.) ? vec3(0, 1, 0) : vaNormal;
                    bool other = mc_Entity != -2.0, tall = same(ivec2(mc_Entity.xy));
                    // A terrain program reads mc_Entity through Chimera's vertex bridge.
                    bool bridged = (vec3(entityValue(), 0.0)==1.), level = 3 != vec2(entityValue());
                    bool either = mc_Entity == 1. ^^ tall ^^ 2. != mc_Entity;
                    if (other && tall || bridged || level || either) normal = -normal;
                    gl_Position = vec4(normal, 1.0);
                }
                """;
        String widened = VectorScalarComparisons.widen(source);
        assertTrue(widened.contains("normal = (mc_Entity==vec3(1.)) ?")
                && widened.contains("bool other = mc_Entity != vec3(-2.0), tall")
                && widened.contains("return ivec2(-1) == cell;")
                && widened.contains("bool bridged = (vec3(entityValue(), 0.0)==vec3(1.)), level = vec2(3) != vec2(")
                && widened.contains("bool either = mc_Entity == vec3(1.) ^^ tall ^^ vec3(2.) != mc_Entity;"),
                widened);
        assertNull(PackCompileTest.compile(source, true), "the stage must compile as Iris's driver compiles it");
    }

    /**
     * A component (of a name or a constructor), an element, a scalar, a function's result, two
     * vectors, and a parameter or local that hides a vector of its name stay as written.
     */
    @Test
    void anythingElseStays() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                layout(location = 0) in vec3 v;
                layout(location = 1) in vec3 w;
                layout(location = 2) in float f;
                const vec3 corners[2] = vec3[2](vec3(0.0), vec3(1.0));
                float scale(vec3 value) { return value.x; }
                bool unit(float v) { return v == 1.0; }
                void main() {
                    bool a = v.x == 1.0 || corners[0] == vec3(1.0) || f == 1.0 || v == w;
                    bool b = f - 1.0 == v.y || v.z == 1.0 + f || 2.0 * f == v.x;
                    bool c = vec3(f, 0.0, 0.0).x == 1.0 || scale(v) == 1.0 || 1.0 == scale(w);
                    {
                        float w = f;
                        c = c || w == 1.0 || unit(f);
                    }
                    color = vec4(a || b || c ? 1.0 : 0.0);
                }
                """;
        assertEquals(source, VectorScalarComparisons.widen(source));
        assertNull(PackCompileTest.compile(source, false));
    }

    /**
     * An operand longer than the name or the number, and a number its vector cannot hold unchanged
     * (NVIDIA compares ivec2(0) == 0.5 as floats), are left to the compiler, which refuses them.
     */
    @Test
    void anOperandItCannotWidenStays() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                layout(location = 0) in vec3 v;
                layout(location = 1) in float f;
                layout(location = 2) flat in ivec2 cell;
                layout(location = 3) flat in uvec2 index;
                void main() {
                    bool a = f + v == 1.0;
                    bool b = v == 1.0 + f;
                    bool c = f - 1.0 == v;
                    bool d = -v == 1.0 || f < v == 1.0;
                    bool e = cell == 0.5 || 1u == cell || index == 2.0 || v == 0.5lf;
                    color = vec4(a || b || c || d || e ? 1.0 : 0.0);
                }
                """;
        assertEquals(source, VectorScalarComparisons.widen(source));
    }

    /** A source the lexer or parser cannot follow (an unclosed comment) goes to the compiler as written. */
    @Test
    void anUnreadableSourceStays() {
        String source = "layout(location = 0) in vec3 v;\nbool b = v == 1.0; /* unclosed";
        assertEquals(source, VectorScalarComparisons.widen(source));
    }

    /** An integer vector takes an integer, a uvec also an unsigned one. */
    @Test
    void anIntegerVectorTakesAnInteger() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                layout(location = 0) flat in ivec2 cell;
                layout(location = 1) flat in uvec2 index;
                void main() {
                    color = vec4(cell == 0x10 || index != 3u || 7 == index ? 1.0 : 0.0);
                }
                """;
        assertTrue(VectorScalarComparisons.widen(source).contains(
                "cell == ivec2(0x10) || index != uvec2(3u) || uvec2(7) == index ?"));
        assertNull(PackCompileTest.compile(source, false));
    }
}
