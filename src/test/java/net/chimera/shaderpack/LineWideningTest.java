// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program drawn on the host line layout that does not widen its own line, as Iris widens it: the pack's
 * code runs once from the far point and once from the vertex's own, reading the widened built-ins wherever
 * it reads them.
 */
final class LineWideningTest {
    private static final String VERTEX = """
            #version 120
            varying vec3 normal;
            vec4 place(vec4 position) { return gl_ModelViewProjectionMatrix * position; }
            void main() {
                // gl_Vertex stays as written in a comment
                normal = gl_NormalMatrix * gl_Normal;
                gl_Position = place(gl_Vertex) + gl_ProjectionMatrix * gl_ModelViewMatrix * vec4(0.0);
                gl_PointSize = float(gl_VertexID);
            }
            """;

    /** Every function reads the moved vertex and the shrunk model view; the projection and normal stay. */
    @Test
    void thePackReadsTheWidenedBuiltins() {
        String widened = IrisCoreProfile.prepareLineVertex(VERTEX, null, false);
        assertTrue(widened.contains("vec4 place(vec4 position) { return (gl_ProjectionMatrix * chimeraLineShrink"
                + " * gl_ModelViewMatrix) * position; }"), widened);
        assertTrue(widened.contains("gl_Position = place((gl_Vertex + vec4(chimeraLineOffset, 0.0)))"
                + " + gl_ProjectionMatrix * (chimeraLineShrink * gl_ModelViewMatrix) * vec4(0.0);"), widened);
        assertTrue(widened.contains("normal = gl_NormalMatrix * vec3(0.0, 0.0, 1.0);"), widened);
        assertTrue(widened.contains("// gl_Vertex stays as written in a comment")
                && widened.contains("gl_PointSize = float(chimeraLineVertexIndex);"), widened);
        assertTrue(widened.contains("const mat4 chimeraLineShrink = mat4(mat3(255.0 / 256.0));"), widened);
    }

    /**
     * The pack's main runs from the far point, then from the vertex's own point with the position cleared; the
     * new entry point follows the pack's code.
     */
    @Test
    void thePackRunsAtBothPointsInOrder() {
        String widened = IrisCoreProfile.prepareLineVertex(VERTEX, null, false);
        String packMain = IrisCoreProfile.PACK_LINE_MAIN + "();";
        int far = widened.indexOf("chimeraLineOffset = chimeraLineDirection;");
        int first = widened.indexOf(packMain, far);
        int kept = widened.indexOf("vec4 farClip = gl_Position;", first);
        int cleared = widened.indexOf("gl_Position = vec4(0.0);", kept);
        int own = widened.indexOf("chimeraLineOffset = vec3(0.0);", cleared);
        int second = widened.indexOf(packMain, own);
        int widen = widened.indexOf("gl_Position = chimeraWidenLine(gl_Position, farClip);", second);
        assertTrue(widened.indexOf("void " + IrisCoreProfile.PACK_LINE_MAIN + "()") < far && far >= 0
                && first > far && kept > first && cleared > kept && own > cleared && second > own && widen > second,
                widened);
        assertTrue(widened.indexOf("void main()") > widened.indexOf("void " + IrisCoreProfile.PACK_LINE_MAIN),
                widened);
    }

    /** The screen size is declared after the header, where the pack declares it in no type. */
    @Test
    void theScreenSizeIsDeclaredWhereMissing() {
        String declared = IrisCoreProfile.prepareLineVertex("""
                #version 130
                #extension GL_ARB_explicit_attrib_location : enable
                uniform int viewWidth;
                void main() { gl_Position = ftransform(); }
                """, null, false);
        assertTrue(declared.startsWith("#version 130\n#extension GL_ARB_explicit_attrib_location : enable\n"
                + "uniform float viewHeight;\n"), declared);
        assertFalse(declared.contains("uniform float viewWidth;"), declared);
    }

    /** ftransform() reads the widened built-ins through a helper that only a stage calling it declares. */
    @Test
    void ftransformIsServedWhereCalled() {
        String called = IrisCoreProfile.prepareLineVertex(
                "#version 120\nvoid main() { gl_Position = ftransform(); }\n", null, false);
        assertTrue(called.contains("return (gl_ProjectionMatrix * chimeraLineShrink * gl_ModelViewMatrix)"
                + " * (gl_Vertex + vec4(chimeraLineOffset, 0.0));")
                && called.contains("gl_Position = chimeraLineFtransform();"), called);
        String uncalled = IrisCoreProfile.prepareLineVertex(VERTEX, null, false);
        assertFalse(uncalled.contains("chimeraLineFtransform"), uncalled);
    }

    @Test
    void aStageWithoutMainFails() {
        assertThrows(IllegalArgumentException.class, () -> IrisCoreProfile.prepareLineVertex(
                "#version 120\nvoid other() { gl_Position = ftransform(); }\n", null, false));
    }
}
