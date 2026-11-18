// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eyes render type sets no overlay, so the overlay unit holds VulkanMod's 1x1 white image while
 * UV1 is vanilla's NO_OVERLAY (0, 10). Iris's GL reads unbound texture 0 as (0, 0, 0, 1), and an
 * out-of-range fetch reads that too. NVIDIA reads zero there, an opaque black entityColor that
 * Shrimple mixes over its spider and enderman eyes.
 */
final class EntityOverlayColorTest {
    private static final String FRAGMENT =
            "#version 120\nuniform vec4 entityColor;\nvoid main() { gl_FragColor = entityColor; }\n";

    @Test
    void aFetchOutsideTheBoundOverlayReadsGlsEmptyTexel() {
        String fragment = EntityOverlayColor.inject(FRAGMENT);
        int fetch = fragment.indexOf("vec4 chimeraOverlayTexel = all(lessThan(uvec2(chimeraOverlayUV), "
                + "uvec2(textureSize(chimeraOverlay, 0)))) ? texelFetch(chimeraOverlay, chimeraOverlayUV, 0)"
                + " : vec4(0.0, 0.0, 0.0, 1.0);");
        int served = fragment.indexOf("entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);");
        assertTrue(fetch >= 0 && served > fetch && served < fragment.indexOf("gl_FragColor"),
                "entityColor is served from the texel before the pack reads it: " + fragment);
        assertTrue(fragment.contains("if (entityColor.a == 0.0) entityColor = vec4(0.0);"),
                "the empty texel leaves entityColor's alpha 0, so nothing is mixed over the eyes");
    }

    /** The fetch and the colour served from it compile in a Vulkan fragment stage. */
    @Test
    void theServedColourCompiles() {
        String fragment = EntityOverlayColor.inject("""
                #version 460
                layout(location = 0) flat in ivec2 chimeraOverlayUV;
                layout(location = 0) out vec4 color;
                uniform vec4 entityColor;
                void main() { color = entityColor; }
                """);
        MemoryUtil.memFree(GlslCompiler.compile("overlay.fsh", fragment, Shaderc.shaderc_glsl_fragment_shader,
                options -> Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)));
    }

    /**
     * The vertex stage beside an injected fragment loses an unread declaration as Iris's EntityPatcher deletes
     * it, read outside comments: a comment before it does not hide it, and one inside a comment is not code.
     */
    @Test
    void anUnreadVertexDeclarationIsFoundOutsideComments() {
        String commented = "#version 120\n/* tint */ uniform vec4 entityColor;\n"
                + "void main() { gl_Position = ftransform(); }\n";
        assertEquals("#version 120\n\nvoid main() { gl_Position = ftransform(); }\n",
                EntityOverlayColor.withoutUnreadVertexDeclaration(commented));
        String disabled = "#version 120\n/*\nuniform vec4 entityColor;\n*/\n"
                + "void main() { gl_Position = ftransform(); }\n";
        assertEquals(disabled, EntityOverlayColor.withoutUnreadVertexDeclaration(disabled));
    }

    /** A shadow geometry stage reads the primitive's first vertex the same way. */
    @Test
    void aGeometryStageFetchIsGuardedToo() {
        String geometry = "#version 150\nlayout(triangles) in;\nlayout(triangle_strip, max_vertices = 3) out;\n"
                + "uniform vec4 entityColor;\nvoid main() { if (entityColor.a > 0.5) EmitVertex(); }\n";
        EntityOverlayColor.ShadowStages stages = EntityOverlayColor.injectShadow(
                "#version 150\nin ivec2 UV1;\nvoid main() { gl_Position = vec4(0.0); }\n", geometry,
                "#version 150\nvoid main() {}\n", true);
        assertTrue(stages.geometry().contains("all(lessThan(uvec2(chimeraOverlayUV[0]), "
                + "uvec2(textureSize(chimeraOverlay, 0)))) ? texelFetch(chimeraOverlay, chimeraOverlayUV[0], 0)"
                + " : vec4(0.0, 0.0, 0.0, 1.0);"), stages.geometry());
    }
}
