// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

/** A resource declaration from shaders.properties; stage {@code *} applies to every stage. */
public record PackResourceDeclaration(
        String key,
        String stage,
        String sampler,
        String source,
        PackResourceKind kind
) {
    public PackResourceDeclaration {
        key = key == null ? "" : key.trim();
        stage = stage == null ? "*" : stage.trim();
        sampler = sampler == null ? "" : sampler.trim();
        source = source == null ? "" : source.trim();
        kind = kind == null ? PackResourceKind.PACK_TEXTURE : kind;
    }
}
