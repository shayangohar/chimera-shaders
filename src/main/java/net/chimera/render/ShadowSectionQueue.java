// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.StaticQueue;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * The chunk sections the shadow pass draws, chosen from the light's view. VulkanMod's own queues are culled and
 * face-masked from the camera, so replayed from the light they would drop off-screen and back-facing casters.
 * While active, renderSectionLayer reads this queue and DrawBuffers draws every facing, as Iris's shadow pass does.
 */
public final class ShadowSectionQueue {
    private static final int SECTIONS_PER_AREA = 512;
    private static final ShadowSectionQueue INSTANCE = new ShadowSectionQueue();

    private final FrustumIntersection frustum = new FrustumIntersection();
    private AreaSetQueue areas;
    private StaticQueue<RenderSection>[] sections;
    /** Drawn by the caster pass. */
    private final List<RenderSection> blockEntitySections = new ArrayList<>();
    private boolean active;
    // the last fill's casting sections, and how many it kept
    private int consideredSections;
    private int keptSections;

    private ShadowSectionQueue() {}

    public static boolean active() {
        return INSTANCE.active;
    }

    /** F3's kept/considered count, as Iris's "Shadow info" shows it. */
    static String sectionCounts() {
        return INSTANCE.keptSections + "/" + INSTANCE.consideredSections;
    }

    /** As Iris takes block entities from its shadow-culled section list. Empty while inactive. */
    public static List<RenderSection> blockEntitySections() {
        return INSTANCE.active ? INSTANCE.blockEntitySections : List.of();
    }

    public static AreaSetQueue areas() {
        return INSTANCE.areas;
    }

    public static StaticQueue<RenderSection> sections(ChunkArea area) {
        return INSTANCE.sections[area.index];
    }

    /**
     * Returns false, leaving the camera queues in use, when no section grid exists yet. {@code lightVolume} is
     * camera-relative and bounds only the host's own shadow; packs pass null, since Iris tests no projection
     * volume and most packs scale shadow clip depth, so casters past the ortho's planes still land in the map.
     */
    static boolean begin(SectionGrid grid, int areaCount, Matrix4fc lightVolume,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        return INSTANCE.fill(grid, areaCount, lightVolume, culling, cameraX, cameraY, cameraZ);
    }

    static void end() {
        INSTANCE.active = false;
        INSTANCE.blockEntitySections.clear();
    }

    /** StaticQueue.clear keeps its slots, so without this the last world's sections and level would stay reachable. */
    static void release() {
        end();
        INSTANCE.areas = null;
        INSTANCE.sections = null;
    }

    private boolean fill(SectionGrid grid, int areaCount, Matrix4fc lightVolume,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        this.active = false;
        this.blockEntitySections.clear();
        this.consideredSections = 0;
        this.keptSections = 0;
        if (grid == null || grid.sections == null || areaCount <= 0) return false;
        resetQueues(areaCount);

        FrustumIntersection volume = lightVolume == null ? null : this.frustum.set(lightVolume);
        for (RenderSection section : grid.sections) {
            // a chest alone bakes no geometry, so its section is empty but still casts
            if (section == null || (section.isCompletelyEmpty() && !section.containsBlockEntities())) continue;
            ChunkArea area = section.getChunkArea();
            if (area == null || area.index < 0 || area.index >= areaCount) continue;
            this.consideredSections++;
            if (!kept(volume, culling, (float) (section.xOffset - cameraX), (float) (section.yOffset - cameraY),
                    (float) (section.zOffset - cameraZ))) continue;
            this.keptSections++;
            if (section.containsBlockEntities()) this.blockEntitySections.add(section);
            if (section.isCompletelyEmpty()) continue;
            enqueue(area, section);
        }
        this.active = true;
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resetQueues(int areaCount) {
        if (this.areas == null || this.areas.size() != areaCount) {
            this.areas = new AreaSetQueue(areaCount);
            this.sections = new StaticQueue[areaCount];
        } else {
            for (var iterator = this.areas.iterator(); iterator.hasNext(); ) {
                this.sections[iterator.next().index].clear();
            }
            this.areas.clear();
        }
    }

    /** The section origin is camera-relative. */
    static boolean kept(FrustumIntersection lightVolume, ShadowCullingFrustum culling, float x, float y, float z) {
        // block models reach out of their section
        float m = ShadowCullingFrustum.SECTION_MARGIN;
        float minX = x - m, minY = y - m, minZ = z - m;
        float maxX = x + 16.0f + m, maxY = y + 16.0f + m, maxZ = z + 16.0f + m;
        return culling.forced(minX, minY, minZ, maxX, maxY, maxZ)
                || ((lightVolume == null || lightVolume.testAab(x, y, z, x + 16.0f, y + 16.0f, z + 16.0f))
                && culling.visible(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private void enqueue(ChunkArea area, RenderSection section) {
        StaticQueue<RenderSection> queue = this.sections[area.index];
        if (queue == null) {
            queue = new StaticQueue<>(SECTIONS_PER_AREA);
            this.sections[area.index] = queue;
        }
        this.areas.add(area);
        queue.add(section);
    }
}
