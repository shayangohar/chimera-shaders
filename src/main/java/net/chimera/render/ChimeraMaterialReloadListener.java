// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Starts a new material map generation on each resource reload.
 *
 * Registered through RegisterClientReloadListenersEvent, so it applies after vanilla's listeners, textures
 * included, and atlas uploads seen during the reload already belong to the incoming packs. {@code apply} only
 * bumps the owner's generation (MaterialMapOwner.bumpGeneration), which retires the simple-texture maps; they
 * close at the next pump. It does no file or GPU work, and nothing while Chimera has no pass.
 */
public final class ChimeraMaterialReloadListener extends SimplePreparableReloadListener<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChimeraMaterialReloadListener.class);

    private final Supplier<MaterialMapOwner> owners;

    public ChimeraMaterialReloadListener(Supplier<MaterialMapOwner> owners) {
        this.owners = owners;
    }

    /** The listener over the running renderer's material maps. */
    public static ChimeraMaterialReloadListener live() {
        return new ChimeraMaterialReloadListener(() -> {
            var pass = ChimeraRenderer.getMainPass();
            return pass == null ? null : pass.materialMaps();
        });
    }

    @Override
    protected Integer prepare(ResourceManager manager, ProfilerFiller profiler) {
        return 0;
    }

    @Override
    protected void apply(Integer prepared, ResourceManager manager, ProfilerFiller profiler) {
        try {
            MaterialMapOwner owner = owners.get();
            if (owner != null) owner.bumpGeneration();
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: generation bump failed", failure);
        }
    }
}
