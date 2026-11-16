package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Optional;

/**
 * A vanilla render type drawn by a pack family lane. It keeps the vanilla type's state and ordering flags,
 * writes the lane's widened format, and draws inside the lane's draw window: its setupRenderState opens the
 * draw once vanilla's state, and with it the shader, is set, in the Iris rendering phase of the context that
 * wrote it.
 *
 * {@link FamilyBufferSource} keeps one instance per lane, phase and type.
 */
public final class FamilyRenderType extends RenderType {
    private final Family family;
    private final PackRenderingPhase phase;
    private final RenderType host;
    /** The host type's transparency, which orders the batch's draw as Iris orders its batches. */
    private final IrisTransparency transparency;
    /** The FamilyBufferSource flush that last drew this type. */
    private long lastDrawn;

    FamilyRenderType(Family family, PackRenderingPhase phase, RenderType host) {
        super("chimera_" + family.name().toLowerCase() + ":" + host.name,
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
     * RenderType.draw has no finally, so a draw that throws between setup and clear would leave the family
     * draw open and every later ShaderInstance draw would get this family's pipeline. The shadow pass catches
     * its casters' failures and carries on, so the draw closes here whatever happens.
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
