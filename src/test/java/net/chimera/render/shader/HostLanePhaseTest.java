package net.chimera.render.shader;

import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Iris answers renderStage for a held host-lane batch from the wrapper around the render type it was
 * asked for in (OuterWrappedRenderType). Reporting OUTLINE for every rendertype_lines draw would send a
 * fishing line down the block-outline branch of packs such as Glimmer and Photon.
 */
final class HostLanePhaseTest {
    @Test
    void aHeldBatchDrawsInItsWritersWrapperPhase() {
        assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.wrapperPhase(Family.ENTITY));
        assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.wrapperPhase(Family.BLOCK));
        assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.wrapperPhase(null));
    }

    /**
     * The wrapper is the one open when vanilla asks for the batch: a hitbox inside an entity. The block
     * outline is asked for outside both and reports NONE, as the outline draw reads renderStage 0 under
     * Iris; OUTLINE would make Glimmer's line transparent and Mellow's opaque black.
     */
    @Test
    void theWriterIsWhatIsRenderingWhenTheBatchIsAskedFor() {
        try {
            ChimeraEntityBridge.beginWorldWindow();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase(), "the block outline");
            ChimeraEntityBridge.beginEntity("minecraft:fishing_bobber");
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.hostLaneWritePhase());
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.beginBlockEntity(0);
            ChimeraEntityBridge.beginEntity("minecraft:pig");
            assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.hostLaneWritePhase(),
                    "a spawner's mob is written in the block entity's wrapper");
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.endContext();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase());
        } finally {
            ChimeraEntityBridge.endWorldWindow();
        }
    }

    /** A line drawn as written, outside every Iris wrapper, draws in the phase Iris holds between its windows. */
    @Test
    void aLineOutsideEveryWrapperDrawsInNone() {
        assertEquals(PackRenderingPhase.NONE, HostLayoutLane.LINE.phase());
    }

    /**
     * While a held batch draws (HeldLaneRenderType.draw), its lane draws in the batch's phase, not
     * its own; checked the other way round, a hitbox would report the lane's NONE again.
     */
    @Test
    void aHeldBatchsPhaseWinsWhileItDraws() {
        assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.lanePhase(HostLayoutLane.LINE));
        try {
            ChimeraEntityBridge.beginHeldBatch(PackRenderingPhase.ENTITIES);
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.lanePhase(HostLayoutLane.LINE));
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.lanePhase(HostLayoutLane.LEASH));
        } finally {
            ChimeraEntityBridge.endHeldBatch();
        }
        assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.lanePhase(HostLayoutLane.LINE),
                "the batch's phase ends with its draw");
    }
}
