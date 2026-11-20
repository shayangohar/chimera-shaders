// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.device.DeviceManager;

/**
 * The largest image a pack may make, the device's maxImageDimension. Iris leaves the size to the driver, so
 * Chimera refuses only past these limits, where Vulkan has no defined result.
 */
final class PackImageLimits {
    private PackImageLimits() {
    }

    /** Why the device cannot make {@code what} at {@code extent}, or null when it can. */
    static String deviceFailure(String what, int dimensions, int... extent) {
        if (DeviceManager.deviceProperties == null) return null;
        var limits = DeviceManager.deviceProperties.limits();
        int limit = switch (dimensions) {
            case 1 -> limits.maxImageDimension1D();
            case 2 -> limits.maxImageDimension2D();
            default -> limits.maxImageDimension3D();
        };
        return failure(what, dimensions, extent, limit);
    }

    /** Why an image of {@code dimensions} at {@code extent} exceeds {@code limit} on an axis, or null. */
    static String failure(String what, int dimensions, int[] extent, int limit) {
        for (int axis = 0; axis < dimensions; axis++) {
            if (Integer.compareUnsigned(extent[axis], limit) > 0) {
                StringBuilder size = new StringBuilder();
                for (int shown = 0; shown < dimensions; shown++) {
                    if (shown > 0) size.append('x');
                    size.append(Integer.toUnsignedString(extent[shown]));
                }
                return what + " at " + size + " exceeds the device's " + dimensions + "D limit " + limit;
            }
        }
        return null;
    }
}
