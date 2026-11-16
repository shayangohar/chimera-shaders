package net.chimera.config;

/**
 * Display colour spaces for the finished world image, named and ordered as Iris's ColorSpace so saved
 * values match. {@link #SRGB} leaves the image alone; the others re-encode it for a wide-gamut display.
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

    public String displayName() {
        return displayName;
    }
}
