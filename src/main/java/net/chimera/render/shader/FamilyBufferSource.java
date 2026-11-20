// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Batches the pack family lanes apart from the host's and draws them in Iris's batch order, one pack window per
 * run of equal outputs; vanilla would flush, and open a window, on every render type switch. The level's entity
 * and crumbling sources stand in for Iris's batched entity source: they hold batches until Iris's translucent
 * boundary, or with separateEntityDraws the non-opaque kinds until after the deferred passes.
 */
public final class FamilyBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
    private static final List<FamilyBufferSource> LIVE = new ArrayList<>();
    private static final int MAX_FAMILY_TYPES = 256;
    /** About half a second: the world source flushes two or three times a frame. */
    private static final long IDLE_FLUSHES = 64;
    private static boolean pastBoundary;
    private static boolean pastDeferred;
    private static final IrisBatchOrder<RenderType> LEVEL_ORDER = new IrisBatchOrder<>();

    private final MultiBufferSource.BufferSource host;
    /** True for the level's entity and crumbling sources. */
    private final boolean level;
    private final SequencedMap<RenderType, ByteBufferBuilder> familyBuffers;
    private final Map<TypeKey, FamilyRenderType> familyTypes = new HashMap<>();
    /** World host-lane batches a level source holds, one type per host type and phase. */
    private final Map<LaneKey, HeldLaneRenderType> laneTypes = new HashMap<>();
    /** Earlier closed batches of connected primitives, waiting to draw. */
    private final Map<RenderType, List<MeshData>> closedBatches = new HashMap<>();
    private long flushes;

    private record TypeKey(Family family, PackRenderingPhase phase, RenderType host) {}

    private record LaneKey(RenderType host, PackRenderingPhase phase) {}

    /** A source for one renderer's own batches, such as the hand's or the shadow casters'. */
    public FamilyBufferSource(MultiBufferSource.BufferSource host) {
        this(host, false);
    }

    public FamilyBufferSource(MultiBufferSource.BufferSource host, boolean level) {
        this(host, level, new LinkedHashMap<>());
    }

    private FamilyBufferSource(MultiBufferSource.BufferSource host, boolean level,
                               SequencedMap<RenderType, ByteBufferBuilder> familyBuffers) {
        // every family type has a fixed buffer, so the shared one is never written
        super(new ByteBufferBuilder(64), familyBuffers);
        this.host = host;
        this.level = level;
        this.familyBuffers = familyBuffers;
        LIVE.add(this);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        RenderType held = heldType(type);
        if (held == null) return host.getBuffer(type);
        if (level) LEVEL_ORDER.use(held, kind(held));
        // a chunk type asks for 4 MB up front; a held batch starts small and grows as it fills
        familyBuffers.computeIfAbsent(held, started -> new ByteBufferBuilder(
                Math.min(started.bufferSize(), RenderType.TRANSIENT_BUFFER_SIZE)));
        closeConnectedBatch(held);
        return super.getBuffer(held);
    }

    /**
     * Closes and holds a started strip (a lead's) before the next batch of its type, since one strip cannot take
     * another's vertices; Iris's segmented buffers do the same.
     */
    private void closeConnectedBatch(RenderType type) {
        if (type.canConsolidateConsecutiveGeometry()) return;
        BufferBuilder started = startedBuilders.remove(type);
        MeshData mesh = started == null ? null : started.build();
        if (mesh != null) closedBatches.computeIfAbsent(type, unused -> new ArrayList<>()).add(mesh);
    }

    /** The family or held-lane type this source holds {@code type} under, or null for the host to draw. */
    private RenderType heldType(RenderType type) {
        Family family = ChimeraEntityBridge.familyFor(type);
        if (family == null) {
            if (!level || !ChimeraEntityBridge.worldLaneDraws(type)) return null;
            PackRenderingPhase phase = ChimeraEntityBridge.hostLaneWritePhase();
            return laneTypes.computeIfAbsent(new LaneKey(type, phase), key -> new HeldLaneRenderType(type, phase));
        }
        PackRenderingPhase phase = ChimeraEntityBridge.writePhase(family);
        return familyTypes.computeIfAbsent(new TypeKey(family, phase, type),
                key -> new FamilyRenderType(family, phase, type));
    }

    private static IrisTransparency kind(RenderType type) {
        if (type instanceof FamilyRenderType family) return family.transparency();
        return type instanceof HeldLaneRenderType held ? held.transparency() : IrisTransparency.of(type);
    }

    @Override
    public void endLastBatch() {
        host.endLastBatch();
    }

    @Override
    public void endBatch() {
        endFamilyBatches(due());
        host.endBatch();
    }

    @Override
    public void endBatch(RenderType type) {
        if (type instanceof FamilyRenderType) {
            drawStarted(type);
        } else {
            host.endBatch(type);
        }
    }

    public void endFamilyBatches() {
        endFamilyBatches(type -> true);
    }

    public static void beginLevel() {
        pastBoundary = false;
        pastDeferred = false;
        LEVEL_ORDER.reset();
    }

    /** Opens an Iris batch group unless one is open; true when this call opened it and must close it. */
    public static boolean startLevelGroup() {
        return LEVEL_ORDER.startGroup();
    }

    public static void endLevelGroup() {
        LEVEL_ORDER.endGroup();
    }

    /**
     * Iris's translucent boundary, before the hand and the deferred passes (batchedentityrendering's
     * MixinLevelRenderer). The shadow pass has its own.
     */
    public static void endLevelBatchesAtBoundary(ChimeraMainPass pass) {
        if (pass == null || ChimeraRenderer.shadowPassActive()) return;
        pastBoundary = true;
        endLevelBatches(pass, levelDue());
    }

    /** Runs after the deferred passes, and at the end of the level for any batch started later. */
    public static void endHeldLevelBatches(ChimeraMainPass pass) {
        if (ChimeraRenderer.shadowPassActive()) return;
        pastBoundary = true;
        pastDeferred = true;
        endLevelBatches(pass, type -> true);
    }

    private Predicate<RenderType> due() {
        return level ? levelDue() : type -> true;
    }

    private static Predicate<RenderType> levelDue() {
        boolean separates = ChimeraRenderer.packSeparatesEntityDraws();
        return type -> levelDraws(kind(type), pastBoundary, pastDeferred, separates);
    }

    /** Iris's FullyBufferedMultiBufferSource ignores vanilla's endBatch(type) until its translucent boundary. */
    static boolean levelDraws(IrisTransparency kind, boolean pastBoundary, boolean pastDeferred,
                              boolean separates) {
        if (!pastBoundary) return false;
        return pastDeferred || !separates || kind.drawsBeforeDeferred();
    }

    /** Found here because RenderBuffers hands them out only while the bridge routes the world's buffers. */
    static List<FamilyBufferSource> levelSources() {
        return LIVE.stream().filter(source -> source.level).toList();
    }

    /** Drops held level batches unseen when the family bridge switches off, since nothing else would flush them. */
    public static void discardLevelBatches() {
        for (FamilyBufferSource source : levelSources()) {
            List.copyOf(source.familyBuffers.keySet()).forEach(source::discard);
        }
    }

    /** Draws both level sources as one, since Iris buffers crumbling with the entities. */
    private static void endLevelBatches(ChimeraMainPass pass, Predicate<RenderType> drawn) {
        Map<RenderType, FamilyBufferSource> owners = new HashMap<>();
        List<RenderType> started = new ArrayList<>();
        for (FamilyBufferSource source : levelSources()) {
            List<RenderType> own = source.startedTypes(drawn);
            source.noteFlush(own);
            own.forEach(type -> owners.put(type, source));
            started.addAll(own);
        }
        if (started.isEmpty()) return;
        if (pass != null) pass.rebindMainTarget();
        draw(started, levelOrder(), type -> owners.get(type).drawStarted(type),
                type -> owners.get(type).discard(type));
        new LinkedHashSet<>(owners.values()).forEach(FamilyBufferSource::trim);
    }

    private void endFamilyBatches(Predicate<RenderType> drawn) {
        List<RenderType> started = startedTypes(drawn);
        if (started.isEmpty()) return;
        noteFlush(started);
        draw(started, level ? levelOrder() : Comparator.comparing(FamilyBufferSource::kind), this::drawStarted,
                this::discard);
        trim();
    }

    /**
     * Iris's order ({@link IrisBatchOrder}); a lead drawn ahead of the chest in front of it would show through the
     * chest's outputs.
     */
    private static Comparator<RenderType> levelOrder() {
        Map<RenderType, Integer> ranks = LEVEL_ORDER.ranks();
        return Comparator.comparing(FamilyBufferSource::kind)
                .thenComparingInt(type -> ranks.getOrDefault(type, Integer.MAX_VALUE));
    }

    /** In first-use order. */
    private List<RenderType> startedTypes(Predicate<RenderType> drawn) {
        List<RenderType> started = new ArrayList<>();
        if (startedBuilders.isEmpty() && closedBatches.isEmpty()) return started;
        for (RenderType type : familyBuffers.keySet()) {
            if (holds(type) && drawn.test(type)) started.add(type);
        }
        return started;
    }

    private boolean holds(RenderType type) {
        return startedBuilders.containsKey(type) || closedBatches.containsKey(type);
    }

    private void noteFlush(List<RenderType> drawn) {
        if (drawn.isEmpty()) return;
        flushes++;
        for (RenderType type : drawn) {
            if (type instanceof FamilyRenderType family) family.drawnAt(flushes);
            if (type instanceof HeldLaneRenderType held) held.drawnAt(flushes);
        }
    }

    /** One pack window per run of family batches with equal outputs. */
    private static void draw(List<RenderType> started, Comparator<RenderType> order, Consumer<RenderType> draw,
                             Consumer<RenderType> discard) {
        started.sort(order);
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            started.forEach(draw);
        } else {
            pass.drawFamilyBatches(started, draw, discard);
        }
    }

    /** Closed batches first, in write order. */
    private void drawStarted(RenderType type) {
        List<MeshData> closed = closedBatches.remove(type);
        if (closed != null) closed.forEach(type::draw);
        super.endBatch(type);
    }

    private void trim() {
        if (familyBuffers.size() > MAX_FAMILY_TYPES) freeIdleTypes();
    }

    /**
     * Vanilla builds energy swirl and breeze wind types anew each frame, as some mods do, so idle held types
     * are freed with their buffers.
     */
    private void freeIdleTypes() {
        familyBuffers.entrySet().removeIf(entry -> {
            RenderType type = entry.getKey();
            long lastDrawn;
            if (type instanceof FamilyRenderType family) {
                lastDrawn = family.lastDrawn();
            } else if (type instanceof HeldLaneRenderType held) {
                lastDrawn = held.lastDrawn();
            } else {
                return false;
            }
            if (!idle(holds(type), flushes, lastDrawn)) return false;
            familyTypes.values().remove(type);
            laneTypes.values().remove(type);
            entry.getValue().close();
            return true;
        });
    }

    /** A type holding a batch may be waiting for the deferred passes, so it is never idle. */
    static boolean idle(boolean held, long flushes, long lastDrawn) {
        return !held && flushes - lastDrawn > IDLE_FLUSHES;
    }

    /** Drops held batches unseen, when the lane's pack outputs could not open this frame. */
    private void discard(RenderType type) {
        List<MeshData> closed = closedBatches.remove(type);
        if (closed != null) closed.forEach(MeshData::close);
        BufferBuilder builder = startedBuilders.remove(type);
        MeshData mesh = builder == null ? null : builder.build();
        if (mesh != null) mesh.close();
    }

    private void release() {
        familyBuffers.keySet().forEach(this::discard);
        familyBuffers.values().forEach(ByteBufferBuilder::close);
        familyBuffers.clear();
        familyTypes.clear();
        laneTypes.clear();
    }

    /** Called before the installed lanes change. */
    static void releaseAll() {
        LIVE.forEach(FamilyBufferSource::release);
    }

    @Override
    public void close() {
        release();
        sharedBuffer.close();
        LIVE.remove(this);
    }
}
