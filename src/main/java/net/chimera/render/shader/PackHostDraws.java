// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.function.BooleanSupplier;

/**
 * Draws a host mesh with the pack program that serves it, picked by shader and vertex format, inside the
 * program's pack outputs. The host's state and draw call stay. Family batches arrive with their draw already
 * open and pass through.
 */
public final class PackHostDraws {
    private static boolean serving;

    private PackHostDraws() {}

    /**
     * {@code drop} discards the mesh instead. A nested draw runs as it is: Reforged 0.3.5 draws an immediate mesh
     * through its immediate VertexBuffer, so ChimeraBufferUploaderMixin reaches ChimeraVertexBufferMixin with
     * the same mesh.
     */
    public static void draw(ShaderInstance shader, VertexFormat format, Runnable draw, Runnable drop) {
        if (serving || ChimeraEntityBridge.isDrawActive()) {
            draw.run();
            return;
        }
        serving = true;
        try {
            serve(shader, format, draw, drop);
        } finally {
            serving = false;
        }
    }

    static boolean serving() {
        return serving;
    }

    private static void serve(ShaderInstance shader, VertexFormat format, Runnable draw, Runnable drop) {
        String name = shader == null ? null : shader.getName();
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        PackPipelines.PackSky sky = pass == null ? null : ChimeraSkyBridge.programFor(name, format);
        if (sky != null) {
            GeometryOutputPlan outputs = sky.outputPlan();
            drawInWindow(pass, sky.pipeline(), outputs != null && outputs.requiresDynamicAttachments() ? outputs : null,
                    () -> {
                        ChimeraSkyBridge.beginDraw(sky);
                        return true;
                    }, ChimeraSkyBridge::endDraw, draw);
            return;
        }
        if (ChimeraSkyBridge.drawsUnfoggedSky(name)) {
            drawUnfogged(draw);
            return;
        }
        ChimeraEntityBridge.Family lane = pass == null ? null : ChimeraEntityBridge.hostLaneFor(name, format);
        if (lane != null) {
            drawInWindow(pass, ChimeraEntityBridge.lanePipeline(lane, format), pass.laneWindowOutputs(lane),
                    () -> ChimeraEntityBridge.beginDraw(lane, format, name), ChimeraEntityBridge::endDraw, draw);
        } else if (ChimeraEntityBridge.dropsHostDraw(name)) {
            drop.run();
        } else {
            draw.run();
        }
    }

    /** No fog, as FogRenderer.setupNoFog leaves it. */
    private static void drawUnfogged(Runnable draw) {
        float start = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        try {
            draw.run();
        } finally {
            RenderSystem.setShaderFogStart(start);
        }
    }

    private static void drawInWindow(ChimeraMainPass pass, GraphicsPipeline pipeline, GeometryOutputPlan outputs,
                                     BooleanSupplier begin, Runnable end, Runnable draw) {
        pass.prepareProgramImages(pipeline);
        if (outputs != null) {
            if (!pass.beginPackFamilyWindow(outputs)) {
                // outputs cannot open, so the mesh keeps its host program
                draw.run();
                return;
            }
            // the host already set up its pass
            pass.rebindMainTarget();
        }
        boolean open = false;
        try {
            open = begin.getAsBoolean();
            draw.run();
        } finally {
            if (open) end.run();
            if (outputs != null) pass.endPackFamilyWindow(outputs);
        }
    }
}
