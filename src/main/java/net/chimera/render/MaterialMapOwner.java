// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the resource pack's normal and specular map images for one renderer lifetime. The pump builds each
 * companion in its albedo's layout, and a draw finds it by its exact albedo image; two permanent 1x1 flat
 * images stand in where no map exists.
 */
public final class MaterialMapOwner implements AutoCloseable {
    static final Logger LOGGER = LoggerFactory.getLogger(MaterialMapOwner.class);

    /** Sampler name, resource-pack file suffix and flat fallback texel (ABGR). */
    public enum MaterialMapKind {
        NORMALS("normals", "_n", 0xFFFF7F7F),
        SPECULAR("specular", "_s", 0x00000000);

        private final String sampler;
        private final String suffix;
        private final int fallbackAbgr;

        MaterialMapKind(String sampler, String suffix, int fallbackAbgr) {
            this.sampler = sampler;
            this.suffix = suffix;
            this.fallbackAbgr = fallbackAbgr;
        }

        public String sampler() { return sampler; }
        public String suffix() { return suffix; }
        public int fallbackAbgr() { return fallbackAbgr; }

        public static MaterialMapKind forSampler(String sampler) {
            for (MaterialMapKind kind : values()) {
                if (kind.sampler.equals(sampler)) return kind;
            }
            return null;
        }
    }

    private final DynamicTexture normalsTexture;
    private final DynamicTexture specularTexture;
    private final VulkanImage normalsImage;
    private final VulkanImage specularImage;
    private final CompanionFactory companionFactory;
    private final MaterialSamplerFactory materialSamplerFactory;
    private final Object companionsLock = new Object();
    /** Latest pending upload per atlas location; the pump drains it on the render thread. */
    private final Map<String, PendingUpload> pendingUploads = new LinkedHashMap<>();
    /**
     * Atlas ticks since an upload that is not built yet. The albedo starts ticking at upload, so the build
     * fast-forwards its animations by this count to keep step.
     */
    private final Map<String, Integer> ticksSinceUpload = new LinkedHashMap<>();
    /** Built companions per atlas location, each tied to the exact base image it was built for. */
    private final Map<String, BuiltAtlas> builtAtlases = new LinkedHashMap<>();
    /** Non-atlas albedo images seen at draw time, waiting for the pump to identify them. */
    private final Set<VulkanImage> wantedSimple =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Simple-texture companions by exact albedo image. */
    private final Map<VulkanImage, SimpleCompanion> simpleCompanions =
            new IdentityHashMap<>();
    /** Albedo images known to have no maps; not scanned again within a generation. */
    private final Set<VulkanImage> simpleMisses =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Companion sets retired by a generation bump, closed at the next pump. */
    private final List<AutoCloseable> retiredCompanions = new ArrayList<>();
    /** Resource-reload generation, zero before the first reload; a bump retires the simple-texture caches. */
    private int generation;
    /** Cap on queued unknown images, so one frame cannot grow the queue without bound. */
    private static final int MAX_SIMPLE_WANTED = 256;
    private boolean closed;

    /**
     * One stitched sprite. The origin is the padded slot's, while width and height are the content's; X and Y
     * padding must agree.
     */
    public record AtlasSprite(String id, String namespace, String path, int x, int y, int width,
            int height, int padX, int padY) {}

    /** Snapshot of one {@code TextureAtlas.upload} call. */
    public record AtlasUpload(String location, int width, int height, int mipLevels,
            List<AtlasSprite> sprites) {}

    /** Opens a resource-pack file, empty when absent; the owner closes every stream it gets. */
    public interface ResourceLookup {
        Optional<InputStream> open(String namespace, String path);
    }

    /** Decoded material file, as ABGR pixels in {@link MaterialMapPixels}'s packing. */
    public record DecodedImage(int[] pixels, int width, int height) {}

    /** Decodes a PNG stream to ABGR pixels: through NativeImage in game, ImageIO in tests. */
    public interface MapImageDecoder {
        DecodedImage decode(InputStream in) throws IOException;
    }

    /** A resource-backed simple texture's albedo image and the resource it came from. */
    public record SimpleEntry(VulkanImage image, String namespace, String path) {}

    /** The live resource-backed simple textures, enumerated from {@code TextureManager} in game. */
    public interface SimpleTextureSource {
        List<SimpleEntry> entries();
    }

    /** One mip level of a companion, as ABGR pixels. */
    public record LevelPixels(int[] pixels, int width, int height) {}

    /** GPU image behind one kind's companion. */
    public interface CompanionSet extends AutoCloseable {
        VulkanImage image();

        /** Rewrites one rectangle of one mip level; only animation calls this, at the pump. */
        void writeRegion(int level, int x, int y, int width, int height, int[] pixels);

        @Override void close();
    }

    /** Allocates one kind's companion image and uploads its levels. */
    public interface CompanionFactory {
        CompanionSet build(String location, MaterialMapKind kind, int width, int height,
                List<LevelPixels> levels);
    }

    private record PendingUpload(AtlasUpload upload, VulkanImage baseImage) {}

    /** An animated map in a built companion: where it sits and what it shows now. */
    private static final class AnimatedSlot {
        final MaterialMapKind kind;
        final AtlasSprite sprite;
        final MaterialAnimation animation;
        boolean dirty;

        AnimatedSlot(MaterialMapKind kind, AtlasSprite sprite, MaterialAnimation animation) {
            this.kind = kind;
            this.sprite = sprite;
            this.animation = animation;
        }
    }

    private record BuiltAtlas(VulkanImage baseImage, Map<MaterialMapKind, CompanionSet> sets,
            boolean labPbr, int width, int height, List<AnimatedSlot> animated) implements AutoCloseable {
        @Override
        public void close() {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: companion close failed", failure);
                }
            }
        }
    }

    private record SimpleCompanion(Map<MaterialMapKind, CompanionSet> sets, boolean labPbr)
            implements AutoCloseable {
        @Override
        public void close() {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: simple close failed", failure);
                }
            }
        }
    }

    /** A companion image, the labPBR mode it was built under, and whether it belongs to a simple texture. */
    private record CompanionMatch(VulkanImage image, boolean labPbr, boolean simple) {}

    /** The Vulkan sampler for a derived sampler key; in game, VulkanMod's cached sampler for the key. */
    public interface MaterialSamplerFactory {
        long sampler(SamplerManager.SamplerKey key);
    }

    private MaterialMapOwner(DynamicTexture normalsTexture, VulkanImage normalsImage,
            DynamicTexture specularTexture, VulkanImage specularImage,
            CompanionFactory companionFactory, MaterialSamplerFactory materialSamplerFactory) {
        this.normalsTexture = normalsTexture;
        this.normalsImage = normalsImage;
        this.specularTexture = specularTexture;
        this.specularImage = specularImage;
        this.companionFactory = companionFactory;
        this.materialSamplerFactory = materialSamplerFactory;
    }

    /** Test seam: an owner over prebuilt fallback images that allocates nothing on the GPU. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage) {
        return withImages(normalsImage, specularImage, (location, kind, width, height, levels) -> {
            throw new UnsupportedOperationException("MATERIAL_MAP_NO_GPU:test owner");
        });
    }

    /** Test seam: prebuilt fallbacks plus a caller-supplied companion factory. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage,
            CompanionFactory companionFactory) {
        return withImages(normalsImage, specularImage, companionFactory,
                key -> {
                    throw new UnsupportedOperationException("MATERIAL_MAP_NO_GPU:test owner");
                });
    }

    /** Test seam: prebuilt fallbacks plus caller-supplied companion and sampler factories. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage,
            CompanionFactory companionFactory, MaterialSamplerFactory materialSamplerFactory) {
        return new MaterialMapOwner(null, normalsImage, null, specularImage, companionFactory,
                materialSamplerFactory);
    }

    /** Allocates the two flat fallbacks; render thread only, where uploads are allowed, never during a draw. */
    public static MaterialMapOwner create() {
        FlatImage normals = flat(MaterialMapKind.NORMALS);
        FlatImage specular;
        try {
            specular = flat(MaterialMapKind.SPECULAR);
        } catch (RuntimeException | Error failure) {
            normals.texture().close();
            throw failure;
        }
        LOGGER.debug("[chimera] material maps: flat fallbacks ready normals={} specular={}",
                normals.image().getId(), specular.image().getId());
        return new MaterialMapOwner(normals.texture(), normals.image(),
                specular.texture(), specular.image(), MaterialCompanionPixels::buildGpuCompanions,
                SamplerManager::getTextureSampler);
    }

    private record FlatImage(DynamicTexture texture, VulkanImage image) {}

    private static FlatImage flat(MaterialMapKind kind) {
        NativeImage pixels = new NativeImage(1, 1, false);
        boolean owned = true;
        try {
            pixels.setPixelRGBA(0, 0, kind.fallbackAbgr());
            DynamicTexture texture = new DynamicTexture(pixels);
            owned = false;
            VulkanImage image = ChimeraTextures.image(texture);
            if (image == null) {
                texture.close();
                throw new IllegalStateException("material fallback has no Vulkan image: " + kind.sampler());
            }
            return new FlatImage(texture, image);
        } finally {
            if (owned) pixels.close();
        }
    }

    /**
     * The flat fallback for a material sampler. An unknown name throws, so a bad manifest entry cannot bind the
     * wrong map.
     */
    public VulkanImage image(String sampler) {
        return snapshot(sampler).image();
    }

    /** The flat fallback for a material sampler, with its own sampler. */
    public ChimeraTextureBindingState.Snapshot snapshot(String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        VulkanImage image = kind == MaterialMapKind.NORMALS ? normalsImage : specularImage;
        return new ChimeraTextureBindingState.Snapshot(image,
                image == null ? null : image.getSampler());
    }

    /**
     * Queues one stitched atlas for companion building; the latest upload per location wins. Keeps metadata
     * only, so the upload path never waits on file or GPU work.
     */
    public void noteAtlasUpload(AtlasUpload upload, VulkanImage baseImage) {
        if (upload == null || baseImage == null || upload.sprites().isEmpty()) return;
        synchronized (companionsLock) {
            if (closed) return;
            pendingUploads.put(upload.location(), new PendingUpload(upload, baseImage));
            ticksSinceUpload.put(upload.location(), 0);
        }
    }

    /**
     * Advances the atlas's animated maps one tick with the albedo, marking changed slots for the next pump;
     * before the atlas is built, counts the tick for the build to catch up.
     */
    public void tickAtlas(String location) {
        synchronized (companionsLock) {
            if (closed) return;
            ticksSinceUpload.computeIfPresent(location, (key, ticks) -> ticks + 1);
            BuiltAtlas built = builtAtlases.get(location);
            if (built == null || ticksSinceUpload.containsKey(location)) return;
            for (AnimatedSlot slot : built.animated()) {
                if (slot.animation.tick()) slot.dirty = true;
            }
        }
    }

    public void pumpPendingBuilds(ResourceLookup lookup, MapImageDecoder decoder) {
        pumpPendingBuilds(lookup, decoder, null);
    }

    /**
     * Builds and writes pending companions at the frame boundary, where no draw can see a half-built one. A
     * failed atlas build keeps the previous companions; a null source leaves simple textures for the next pump.
     */
    public void pumpPendingBuilds(ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source) {
        List<PendingUpload> due;
        synchronized (companionsLock) {
            if (closed) return;
            closeRetiredLocked();
            due = new ArrayList<>(pendingUploads.values());
            pendingUploads.clear();
        }
        boolean labPbr = MaterialCompanionPixels.detectLabPbr(lookup);
        for (PendingUpload pending : due) {
            try {
                buildAtlas(pending.upload(), pending.baseImage(), lookup, decoder, labPbr);
            } catch (RuntimeException failure) {
                synchronized (companionsLock) {
                    ticksSinceUpload.remove(pending.upload().location());
                }
                LOGGER.warn("[chimera] material maps: atlas build failed, keeping previous: {}",
                        pending.upload().location(), failure);
            }
        }
        pumpSimpleWanted(lookup, decoder, source, labPbr);
        flushAnimations();
        synchronized (companionsLock) {
            closeRetiredLocked();
        }
    }

    /**
     * Writes every animated slot that changed since the last pump; a failed write leaves that slot on its last
     * good frame.
     */
    private void flushAnimations() {
        synchronized (companionsLock) {
            if (closed) return;
            for (BuiltAtlas built : builtAtlases.values()) {
                for (AnimatedSlot slot : built.animated()) {
                    if (!slot.dirty) continue;
                    slot.dirty = false;
                    CompanionSet set = built.sets().get(slot.kind);
                    if (set == null) continue;
                    try {
                        writeSlot(set, slot, built.width(), built.height());
                    } catch (RuntimeException failure) {
                        LOGGER.warn("[chimera] material maps: animated {} {} write failed",
                                slot.sprite.id(), slot.kind.sampler(), failure);
                    }
                }
            }
        }
    }

    private static void writeSlot(CompanionSet set, AnimatedSlot slot, int atlasWidth, int atlasHeight) {
        MaterialAnimation animation = slot.animation;
        for (int level = 0; level < animation.levels(); level++) {
            int pad = slot.sprite.padX() >> level;
            int width = MaterialMapPixels.levelSize(animation.width(), level);
            int height = MaterialMapPixels.levelSize(animation.height(), level);
            int[] padded = MaterialMapPixels.padReplicate(animation.level(level), width, height, pad);
            MaterialMapPixels.Rect rect = MaterialMapPixels.clipRect(padded, width + pad * 2, height + pad * 2,
                    slot.sprite.x() >> level, slot.sprite.y() >> level,
                    MaterialMapPixels.levelSize(atlasWidth, level),
                    MaterialMapPixels.levelSize(atlasHeight, level));
            if (rect != null) {
                set.writeRegion(level, rect.x(), rect.y(), rect.width(), rect.height(), rect.pixels());
            }
        }
    }

    public int generation() {
        synchronized (companionsLock) {
            return generation;
        }
    }
    /**
     * Starts a new resource-reload generation and returns it, retiring the simple-texture caches. Atlas
     * companions stay, since the listener runs after the re-stitch and retiring them would close companions
     * already built for the new atlases.
     */
    public int bumpGeneration() {
        synchronized (companionsLock) {
            generation++;
            for (SimpleCompanion simple : simpleCompanions.values()) {
                retiredCompanions.add(simple);
            }
            simpleCompanions.clear();
            simpleMisses.clear();
            wantedSimple.clear();
            LOGGER.debug("[chimera] material maps: generation {} started, {} simple sets retired",
                    generation, retiredCompanions.size());
            return generation;
        }
    }

    private void closeRetiredLocked() {
        if (retiredCompanions.isEmpty()) return;
        List<AutoCloseable> due = new ArrayList<>(retiredCompanions);
        retiredCompanions.clear();
        for (AutoCloseable retired : due) {
            try {
                retired.close();
            } catch (Exception failure) {
                LOGGER.warn("[chimera] material maps: retired companion close failed", failure);
            }
        }
    }

    /**
     * Notes an albedo image a draw binds, for simple-texture identification. Atlas bases and known images
     * return at once; anything else queues once for the next pump. Safe during a draw.
     */
    public void noteDrawAlbedo(VulkanImage base) {
        synchronized (companionsLock) {
            if (closed || base == null) return;
            if (isKnownBaseLocked(base) || simpleMisses.contains(base)
                    || wantedSimple.contains(base)) return;
            if (wantedSimple.size() >= MAX_SIMPLE_WANTED) return;
            wantedSimple.add(base);
        }
    }

    /**
     * The material image and sampler for one draw: the albedo's companion with a sampler derived from the
     * draw's, else the flat fallback. Never reads live slot state.
     */
    public ChimeraTextureBindingState.Snapshot resolveDrawMaterial(
            DrawMaterialContext context, String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        VulkanImage base = context.albedo();
        if (base != null) noteDrawAlbedo(base);
        CompanionMatch match = base == null ? null : findCompanion(base, kind);
        if (match == null || match.image() == null) {
            // the flat fallback stands in for a map, so it samples with the draw's sampler as a companion would
            ChimeraTextureBindingState.Snapshot flat = snapshot(sampler);
            SamplerProfile profile = context.profile();
            long id = profile != null && profile.id() != 0
                    ? profile.id()
                    : flat.image().getSampler();
            return new ChimeraTextureBindingState.Snapshot(flat.image(), id);
        }
        long id = resolveSamplerId(context.profile(), base, match, kind);
        return new ChimeraTextureBindingState.Snapshot(match.image(), id);
    }

    private long resolveSamplerId(SamplerProfile profile, VulkanImage base,
            CompanionMatch match, MaterialMapKind kind) {
        boolean labSpecular = kind == MaterialMapKind.SPECULAR && match.labPbr();
        if (!match.simple() && !labSpecular) {
            // atlas normals and non-labPBR specular use the albedo's sampler as is
            return drawSampler(profile, base, match);
        }
        if (profile == null) {
            return fallbackSampler(base, match);
        }
        // labPBR specular reads the nearest texel of the nearest level, keeping the draw's mip ceiling;
        // a simple-texture map keeps the draw's filter and wrap but clamps to the base level
        SamplerManager.SamplerKey key = profile.derive(labSpecular, match.simple());
        if (key == null) {
            // the profile's fields are unknown, but its sampler id is still the draw's
            return drawSampler(profile, base, match);
        }
        synchronized (companionsLock) {
            return closed ? fallbackSampler(base, match) : materialSamplerFactory.sampler(key);
        }
    }

    /** The draw's own sampler when it has one, else the base image's or the companion's. */
    private static long drawSampler(SamplerProfile profile, VulkanImage base, CompanionMatch match) {
        if (profile != null && profile.id() != 0) return profile.id();
        return fallbackSampler(base, match);
    }

    private static long fallbackSampler(VulkanImage base, CompanionMatch match) {
        if (base.getSampler() != 0) return base.getSampler();
        return match.image().getSampler();
    }

    private CompanionMatch findCompanion(VulkanImage base, MaterialMapKind kind) {
        synchronized (companionsLock) {
            for (BuiltAtlas built : builtAtlases.values()) {
                if (built.baseImage() == base) {
                    CompanionSet set = built.sets().get(kind);
                    return new CompanionMatch(
                            set == null ? null : set.image(), built.labPbr(), false);
                }
            }
            SimpleCompanion simple = simpleCompanions.get(base);
            if (simple != null) {
                CompanionSet set = simple.sets().get(kind);
                return new CompanionMatch(
                        set == null ? null : set.image(), simple.labPbr(), true);
            }
            return new CompanionMatch(null, false, false);
        }
    }

    private boolean isKnownBaseLocked(VulkanImage base) {
        for (BuiltAtlas built : builtAtlases.values()) {
            if (built.baseImage() == base) return true;
        }
        return simpleCompanions.containsKey(base);
    }

    /** The companion for an exact albedo image, or null when it has none. */
    public ChimeraTextureBindingState.Snapshot companionFor(VulkanImage baseImage, String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        CompanionMatch match = baseImage == null
                ? new CompanionMatch(null, false, false)
                : findCompanion(baseImage, kind);
        if (match.image() == null) return null;
        return new ChimeraTextureBindingState.Snapshot(match.image(),
                match.image().getSampler());
    }

    private void pumpSimpleWanted(ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source, boolean labPbr) {
        List<VulkanImage> due;
        synchronized (companionsLock) {
            if (closed || wantedSimple.isEmpty() || source == null) return;
            due = new ArrayList<>(wantedSimple);
            wantedSimple.clear();
        }
        for (VulkanImage albedo : due) {
            try {
                buildSimple(albedo, lookup, decoder, source, labPbr);
            } catch (RuntimeException failure) {
                LOGGER.warn("[chimera] material maps: simple build failed, staying fallback", failure);
                synchronized (companionsLock) {
                    simpleMisses.add(albedo);
                }
            }
        }
    }

    private void buildSimple(VulkanImage albedo, ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source, boolean labPbr) {
        SimpleEntry match = findSimpleEntry(source, albedo);
        synchronized (companionsLock) {
            if (closed) return;
            if (isKnownBaseLocked(albedo)) return;
            if (match == null) {
                simpleMisses.add(albedo);
                return;
            }
        }
        String matchId = match.namespace() + ":" + match.path();
        Map<MaterialMapKind, CompanionSet> sets = new EnumMap<>(MaterialMapKind.class);
        Map<MaterialMapKind, String> dims = new EnumMap<>(MaterialMapKind.class);
        try {
            for (MaterialMapKind kind : MaterialMapKind.values()) {
                String resource = MaterialMapPixels.siblingResource(
                        match.namespace(), match.path(), kind.suffix());
                int separator = resource.indexOf(':');
                try (InputStream in = lookup.open(
                        resource.substring(0, separator), resource.substring(separator + 1))
                        .orElse(null)) {
                    if (in == null) continue;
                    DecodedImage decoded = decoder.decode(in);
                    MaterialMapPixels.checkSize(decoded.width(), decoded.height(), resource);
                    List<LevelPixels> levels = List.of(
                            new LevelPixels(decoded.pixels(), decoded.width(), decoded.height()));
                    sets.put(kind, companionFactory.build(
                            matchId, kind, decoded.width(), decoded.height(), levels));
                    dims.put(kind, decoded.width() + "x" + decoded.height());
                } catch (IOException | IllegalArgumentException failure) {
                    LOGGER.warn("[chimera] material maps: skipping {} for {}: {}",
                            resource, matchId, failure.toString());
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(sets, failure);
            throw failure;
        }
        synchronized (companionsLock) {
            if (closed || isKnownBaseLocked(albedo)) {
                closeUnused(sets, "[chimera] material maps: late simple close failed");
                return;
            }
            if (sets.isEmpty()) {
                simpleMisses.add(albedo);
                return;
            }
            simpleCompanions.put(albedo, new SimpleCompanion(sets, labPbr));
        }
        LOGGER.debug("[chimera] material maps: simple {} kinds={} dims={} labpbr={} generation={}",
                matchId, sets.keySet(), dims.values(), labPbr, generation);
    }

    private void buildAtlas(AtlasUpload upload, VulkanImage baseImage,
            ResourceLookup lookup, MapImageDecoder decoder, boolean labPbr) {
        long started = System.nanoTime();
        Map<MaterialMapKind, CompanionSet> sets = new EnumMap<>(MaterialMapKind.class);
        List<AnimatedSlot> animated = new ArrayList<>();
        int mappedSprites = 0;
        try {
            MaterialMapPixels.checkSize(upload.width(), upload.height(), upload.location());
            if (upload.mipLevels() < 1) {
                throw new IllegalArgumentException("MATERIAL_MAP_MIPS:" + upload.location());
            }
            List<AtlasSprite> sprites = upload.sprites().stream()
                    .sorted(Comparator.comparing(AtlasSprite::id))
                    .toList();
            for (MaterialMapKind kind : MaterialMapKind.values()) {
                List<MaterialCompanionPixels.PlacedSprite> placed = MaterialCompanionPixels.decodeKind(sprites, kind,
                        lookup, decoder,
                        upload.mipLevels(), labPbr);
                if (placed.isEmpty()) continue;
                mappedSprites = Math.max(mappedSprites, placed.size());
                List<LevelPixels> levels = MaterialCompanionPixels.buildLevels(upload, kind, placed);
                sets.put(kind, companionFactory.build(
                        upload.location(), kind, upload.width(), upload.height(), levels));
                for (MaterialCompanionPixels.PlacedSprite sprite : placed) {
                    if (sprite.animation() != null) {
                        animated.add(new AnimatedSlot(kind, sprite.sprite(), sprite.animation()));
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(sets, failure);
            throw failure;
        }
        synchronized (companionsLock) {
            if (closed) {
                closeUnused(sets, "[chimera] material maps: late companion close failed");
                return;
            }
            // the albedo has ticked since its upload; catch the maps up to the same point in their schedules
            Integer missed = ticksSinceUpload.remove(upload.location());
            for (int tick = 0; missed != null && tick < missed; tick++) {
                for (AnimatedSlot slot : animated) {
                    if (slot.animation.tick()) slot.dirty = true;
                }
            }
            BuiltAtlas previous = builtAtlases.put(upload.location(), new BuiltAtlas(baseImage, sets,
                    labPbr, upload.width(), upload.height(), List.copyOf(animated)));
            if (previous != null) previous.close();
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        if (sets.isEmpty()) {
            LOGGER.debug("[chimera] material maps: atlas {} has none; fallbacks bound ({}x{}, {} mips)",
                    upload.location(), upload.width(), upload.height(), upload.mipLevels());
        } else {
            LOGGER.debug("[chimera] material maps: atlas {} kinds={} sprites={}/{} animated={} {}x{} {} mips "
                    + "labpbr={} base={} generation={} in {}ms",
                    upload.location(), sets.keySet(), mappedSprites, upload.sprites().size(),
                    animated.size(), upload.width(), upload.height(), upload.mipLevels(), labPbr,
                    baseImage.getId(), generation, millis);
        }
    }

    /** The live simple texture whose albedo is exactly this image, or null. */
    private static SimpleEntry findSimpleEntry(SimpleTextureSource source, VulkanImage albedo) {
        for (SimpleEntry entry : source.entries()) {
            if (entry.image() == albedo) return entry;
        }
        return null;
    }

    /** Closes the sets of a failed build, attaching each close failure to the build failure. */
    private static void closeAfterFailure(Map<MaterialMapKind, CompanionSet> sets, Throwable failure) {
        for (CompanionSet set : sets.values()) {
            try {
                set.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /** Closes freshly built sets nothing will use, warning with {@code message} on a close failure. */
    private static void closeUnused(Map<MaterialMapKind, CompanionSet> sets, String message) {
        for (CompanionSet set : sets.values()) {
            try {
                set.close();
            } catch (RuntimeException failure) {
                LOGGER.warn(message, failure);
            }
        }
    }

    @Override
    public void close() {
        synchronized (companionsLock) {
            if (closed) return;
            closed = true;
            pendingUploads.clear();
            ticksSinceUpload.clear();
            wantedSimple.clear();
            for (BuiltAtlas built : builtAtlases.values()) {
                try {
                    built.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: atlas close failed", failure);
                }
            }
            builtAtlases.clear();
            for (SimpleCompanion simple : simpleCompanions.values()) {
                try {
                    simple.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: simple close failed", failure);
                }
            }
            simpleCompanions.clear();
            simpleMisses.clear();
            closeRetiredLocked();
        }
        if (normalsTexture != null) normalsTexture.close();
        if (specularTexture != null && specularTexture != normalsTexture) specularTexture.close();
    }
}
