// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Locale;
import java.util.Optional;

/**
 * A vanilla render type drawn by a pack family lane in the lane's widened format. Its setupRenderState opens the
 * lane's draw once vanilla has set the shader, in the Iris phase that wrote the batch.
 */
public final class FamilyRenderType extends RenderType {
    private final Family family;
    private final PackRenderingPhase phase;
    private final RenderType host;
    private final IrisTransparency transparency;
    /** The FamilyBufferSource flush that last drew this type. */
    private long lastDrawn;

    FamilyRenderType(Family family, PackRenderingPhase phase, RenderType host) {
        super("chimera_" + family.name().toLowerCase(Locale.ROOT) + ":" + host.name,
                ChimeraEntityBridge.widenedFormat(family, host.format()), host.mode(), host.bufferSize(),
                host.affectsCrumbling(), host.sortOnUpload(), host::setupRenderState, host::clearRenderState);
        this.family = family;
        this.phase = phase;
        this.host = host;
        this.transparency = IrisTransparency.of(host);
    }

    IrisTransparency transparency() {
        return transparency;
    }

    public Family family() {
        return family;
    }

    long lastDrawn() {
        return lastDrawn;
    }

    void drawnAt(long flush) {
        lastDrawn = flush;
    }

    @Override
    public void setupRenderState() {
        super.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (!ChimeraEntityBridge.beginDraw(family, format(), shader == null ? null : shader.getName(), phase)) {
            ChimeraEntityBridge.noteDrawRefused(family, host);
        }
    }

    @Override
    public void clearRenderState() {
        if (ChimeraEntityBridge.isDrawActive()) ChimeraEntityBridge.endDraw();
        super.clearRenderState();
    }

    /**
     * RenderType.draw has no finally, and the shadow pass carries on after a caster throws, so an open family
     * draw would leak its pipeline to every later draw.
     */
    @Override
    public void draw(MeshData mesh) {
        try {
            super.draw(mesh);
        } finally {
            if (ChimeraEntityBridge.isDrawActive()) ChimeraEntityBridge.endDraw();
        }
    }

    @Override
    public Optional<RenderType> outline() {
        return host.outline();
    }

    @Override
    public boolean isOutline() {
        return host.isOutline();
    }

    @Override
    public boolean canConsolidateConsecutiveGeometry() {
        return host.canConsolidateConsecutiveGeometry();
    }
}
