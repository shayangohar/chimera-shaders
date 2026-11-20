// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

/**
 * The format and GLSL type a storage image declaration binds an image in: its own internal format, as Iris binds
 * it, or a view in another format of the same texel size that the layout names.
 */
record StorageForm(String format, String type) {
    /**
     * {@code vkFormat} as a declaration naming {@code declared} (lower case, empty for none) binds it; null for a
     * format of another texel size or one with no GLSL name.
     */
    static StorageForm of(int vkFormat, String declared) {
        IrisColorFormats.Format own = IrisColorFormats.stored(vkFormat);
        String view = own == null ? "" : AdvancedImageDeclarations.viewFormat(own, declared);
        if (view == null) return null;
        if (!view.isEmpty()) return new StorageForm(view, imageType(IrisColorFormats.of(view).kind()));
        String format = PassComputePlan.imageFormat(vkFormat);
        return format == null ? null : new StorageForm(format, imageType(kind(vkFormat)));
    }

    /** The kind of value a target format stores; float for one no target uses. */
    static IrisColorFormats.Kind kind(int vkFormat) {
        IrisColorFormats.Format format = IrisColorFormats.stored(vkFormat);
        return format == null ? IrisColorFormats.Kind.FLOAT : format.kind();
    }

    /** The 2D storage image type that reads values of {@code kind}. */
    static String imageType(IrisColorFormats.Kind kind) {
        return typePrefix(kind) + "image2D";
    }

    /** The GLSL type prefix of an opaque type reading values of {@code kind}: u, i, or none. */
    static String typePrefix(IrisColorFormats.Kind kind) {
        return kind == IrisColorFormats.Kind.UINT ? "u" : kind == IrisColorFormats.Kind.SINT ? "i" : "";
    }
}
