package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pixel Perfect reads an array's length without (), which NVIDIA's GL compiler takes and GLSL does not. */
final class ArrayLengthCallsTest {
    @Test
    void anArraysLengthGetsItsParentheses() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                layout(location = 0) flat in int frameCounter;
                const vec3 superSampleOffsetsCross[5] = vec3[5](vec3(0), vec3(1), vec3(2), vec3(3), vec3(4));
                const vec2[2] pair = vec2[2](vec2(0), vec2(1));
                float total(float weights[3]) {
                    float sum = 0.0;
                    for (int i = 0; i < weights.length; i++) sum += weights[i];
                    return sum;
                }
                void main() {
                    vec3 sum = vec3(0.0);
                    for (int i = 1; i < superSampleOffsetsCross.length; i++) sum += superSampleOffsetsCross[i];
                    int index = int(mod(frameCounter, pair . length));
                    color = vec4(sum + pair[index].xyx, total(float[3](1.0, 2.0, 3.0)));
                }
                """;
        String completed = ArrayLengthCalls.complete(source);
        assertTrue(completed.contains("i < weights.length(); i++")
                && completed.contains("i < superSampleOffsetsCross.length(); i++")
                && completed.contains("mod(frameCounter, pair . length())"), completed);
        assertNull(PackCompileTest.compile(source, false), "the stage must compile as Iris's driver compiles it");
    }

    /** Spaced out and the source's only bare length, it is still found. */
    @Test
    void aSpacedLengthAloneIsFound() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                const vec2 pair[2] = vec2[2](vec2(0), vec2(1));
                void main() {
                    color = vec4(float(pair .  length));
                }
                """;
        assertTrue(ArrayLengthCalls.complete(source).contains("float(pair .  length())"));
        assertNull(PackCompileTest.compile(source, false));
    }

    /**
     * A struct's length field (which glslang refuses on its own), a length already called, a
     * vector's length(), and a local that hides an array of its name stay as written.
     */
    @Test
    void anythingElseStays() {
        String source = """
                #version 430
                layout(location = 0) out vec4 color;
                struct Ray { vec3 origin; float length; };
                const float weights[2] = float[2](0.25, 0.75);
                float hidden() {
                    Ray weights = Ray(vec3(0.0), 1.0);
                    return weights.length;
                }
                void main() {
                    Ray ray = Ray(vec3(0.0), 2.0);
                    vec3 v = vec3(1.0);
                    color = vec4(ray.length, float(weights.length()), float(v.length()), hidden());
                }
                """;
        assertEquals(source, ArrayLengthCalls.complete(source));
    }
}
