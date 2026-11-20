// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Set;

/** A group of frame values one class captures, served by uniform name beside PackFrameState's table. */
interface FrameValueSource {
    Set<String> names();

    /** {@code component} is -1 for a scalar; NaN past the value's width. */
    double value(String name, int component);

    /** Writes in the type the pack declared. */
    void write(String name, String type, MappedBuffer target);
}
