package net.chimera.config;

/**
 * The display colour spaces Iris 1.8.14 converts the finished world image to (Iris ColorSpace,
 * in its order, so the saved names match). {@link #SRGB} leaves the image alone; the others
 * re-encode it for a wide-gamut display (ChimeraMainPass.outputResolvePipeline).
 */
public enum ColorSpace {
    SRGB("sRGB"),
    DCI_P3("DCI-P3"),
    DISPLAY_P3("Display P3"),
    REC2020("Rec. 2020"),
    ADOBE_RGB("Adobe RGB");

    private final String displayName;

    ColorSpace(String displayName) {
        this.displayName = displayName;
    }

    /** The name the settings page shows. */
    public String displayName() {
        return displayName;
    }
}
