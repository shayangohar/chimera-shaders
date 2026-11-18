// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.Optional;

/** Creates the pass's material-map owner and pumps its builds at the frame boundary. */
final class MaterialMapRuntime {
    /** Lives as long as the renderer; null until the first pump or pack install creates it. */
    MaterialMapOwner materialMapOwner;

    public MaterialMapOwner materialMaps() {
        return this.materialMapOwner;
    }

    /** Creates the owner on first call; render thread only. */
    void ensureMaterialMapOwner() {
        if (this.materialMapOwner == null) {
            this.materialMapOwner = MaterialMapOwner.create();
        }
    }

    /**
     * Builds queued companions at the frame boundary. Material maps do not depend on the shader pack, so this
     * runs with no pack installed too. Never throws.
     */
    public void pumpMaterialMapBuilds() {
        try {
            ensureMaterialMapOwner();
            var manager = Minecraft.getInstance().getResourceManager();
            MaterialMapOwner.ResourceLookup lookup = (namespace, path) -> {
                try {
                    return manager.getResource(ResourceLocation.fromNamespaceAndPath(namespace, path))
                            .map(resource -> {
                                try {
                                    return resource.open();
                                } catch (IOException openFailure) {
                                    return null;
                                }
                            });
                } catch (RuntimeException lookupFailure) {
                    return Optional.empty();
                }
            };
            // one recording for every companion write of the pump; animated maps write every mip level of each
            // changed slot, and each write alone would wait on the GPU
            MaterialCompanionPixels.UPLOADS.open();
            try {
                this.materialMapOwner.pumpPendingBuilds(lookup, MaterialCompanionPixels::decodeNative,
                        () -> SimpleTextureIndex.snapshot(Minecraft.getInstance().getTextureManager()));
            } finally {
                MaterialCompanionPixels.UPLOADS.close();
            }
        } catch (RuntimeException failure) {
            ChimeraMainPass.LOGGER.warn("[chimera] material maps: pump failed", failure);
        }
    }
}
