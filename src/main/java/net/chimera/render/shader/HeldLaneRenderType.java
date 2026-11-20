// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;

import java.util.Locale;
import java.util.Optional;

/**
 * A host-layout lane's render type held by a level source with the Iris phase it was written in. Iris takes
 * renderStage from the wrapper the type was asked for in, and vanilla uses RenderType.lines() for both hitboxes
 * and the block outline, so one batch per type would draw both in one phase.
 */
final class HeldLaneRenderType extends RenderType {
    private final RenderType host;
    private final PackRenderingPhase phase;
    private final IrisTransparency transparency;
    /** The FamilyBufferSource flush that last drew this type. */
    private long lastDrawn;

    HeldLaneRenderType(RenderType host, PackRenderingPhase phase) {
        super("chimera_" + phase.name().toLowerCase(Locale.ROOT) + ":" + host.name, host.format(), host.mode(),
                host.bufferSize(), host.affectsCrumbling(), host.sortOnUpload(), host::setupRenderState,
                host::clearRenderState);
        this.host = host;
        this.phase = phase;
        this.transparency = IrisTransparency.of(host);
    }

    IrisTransparency transparency() {
        return transparency;
    }

    long lastDrawn() {
        return lastDrawn;
    }

    void drawnAt(long flush) {
        lastDrawn = flush;
    }

    @Override
    public void draw(MeshData mesh) {
        ChimeraEntityBridge.beginHeldBatch(phase);
        try {
            super.draw(mesh);
        } finally {
            ChimeraEntityBridge.endHeldBatch();
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
