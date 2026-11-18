// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;

/** A pack's custom image is cleared with vkCmdClearColorImage, so its format must take transfers too. */
final class PackStorageImageTest {
    @Test
    void aFormatWithoutTransferIsRefused() {
        int sampledStorage = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
        assertEquals("advanced image format lacks sampled/storage/transfer support: voxels format=98",
                PackStorageImage.formatFailure(sampledStorage, "voxels", VK_FORMAT_R32_UINT));
        assertNull(PackStorageImage.formatFailure(sampledStorage | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT
                | VK_FORMAT_FEATURE_TRANSFER_DST_BIT, "voxels", VK_FORMAT_R32_UINT));
    }
}
