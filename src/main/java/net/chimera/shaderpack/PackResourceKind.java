// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

/** What a pack program's sampler reads. */
public enum PackResourceKind {
    TARGET,
    DEPTH,
    SHADOW_DEPTH,
    SHADOW_COLOR,
    NOISE,
    PACK_TEXTURE,
    /**
     * A raw texture file declared with its type, format and size
     * ({@code file TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}). It replaces a sampler only where the program
     * declares that sampler with the same type.
     */
    RAW_TEXTURE,
    ADVANCED_IMAGE,
    /** A resource pack's normal or specular map, found at draw time from the bound albedo. */
    MATERIAL_MAP,
    /** The 1x1 smoothed centre depth fullscreen passes read as iris_centerDepthSmooth. */
    CENTER_DEPTH,
    UNSERVED
}
