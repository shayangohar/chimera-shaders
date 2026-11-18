// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Pixel and layout code for resource-pack material companions, on plain ABGR {@code int[]} buffers so tests
 * need no Minecraft natives or GPU.
 *
 * ABGR packing matches {@code NativeImage}: red in bits 0-7, green 8-15, blue 16-23, alpha 24-31.
 */
public final class MaterialMapPixels {
    private MaterialMapPixels() {}

    /** labPBR specular green: below 230 is a dielectric's reflectance, 230 and above a metal index. */
    public static final int LAB_GREEN_SPLIT = 230;
    /** labPBR specular blue: below 65 is porosity, 65 and above subsurface scattering. */
    public static final int LAB_BLUE_SPLIT = 65;
    /** labPBR specular alpha: 255 means no emission, anything below is an emission strength. */
    public static final int LAB_ALPHA_OPAQUE = 255;
    /** Largest image the companion builder accepts: 16M texels, 64 MiB as RGBA. */
    public static final int MAX_IMAGE_PIXELS = 1 << 24;

    public static int red(int abgr) { return abgr & 0xFF; }
    public static int green(int abgr) { return (abgr >> 8) & 0xFF; }
    public static int blue(int abgr) { return (abgr >> 16) & 0xFF; }
    public static int alpha(int abgr) { return (abgr >>> 24) & 0xFF; }

    public static int pack(int alpha, int blue, int green, int red) {
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    /** Rejects empty or oversized dimensions before anything is allocated. */
    public static void checkSize(int width, int height, String what) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("MATERIAL_MAP_SIZE:" + what + "=" + width + "x" + height);
        }
        if ((long) width * (long) height > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("MATERIAL_MAP_TOO_LARGE:" + what + "=" + width + "x" + height);
        }
    }

    /**
     * Scales a decoded material frame to its atlas slot size. Equal sizes copy and whole-number enlargement
     * point-samples. Any other ratio, including every reduction, takes a per-channel box average, since point
     * sampling would keep only the top-left texel of each footprint.
     */
    public static int[] scale(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "scale-src");
        checkSize(dstWidth, dstHeight, "scale-dst");
        if (srcWidth == dstWidth && srcHeight == dstHeight) {
            return src.clone();
        }
        if (dstWidth % srcWidth == 0 && dstHeight % srcHeight == 0) {
            return scalePoint(src, srcWidth, srcHeight, dstWidth, dstHeight);
        }
        return downscale(src, srcWidth, srcHeight, dstWidth, dstHeight, MaterialMapPixels::averageFootprint);
    }

    private static int[] scalePoint(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        int xStep = dstWidth / srcWidth;
        int yStep = dstHeight / srcHeight;
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int srcRow = (y / yStep) * srcWidth;
            int dstRow = y * dstWidth;
            for (int x = 0; x < dstWidth; x++) {
                dst[dstRow + x] = src[srcRow + x / xStep];
            }
        }
        return dst;
    }

    /** Plain arithmetic mean per channel: normal maps and non-labPBR specular maps. */
    public static int[] reduceLinear(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "reduce-src");
        checkSize(dstWidth, dstHeight, "reduce-dst");
        return downscale(src, srcWidth, srcHeight, dstWidth, dstHeight, MaterialMapPixels::averageFootprint);
    }

    /**
     * labPBR-aware reduction. Red is a plain mean. Green treats every dielectric (below 230) as one class and
     * each metal index as its own, since averaging two metal indices would make up a third material. Blue and
     * alpha split at their thresholds. Green, blue and alpha each average only the texels of the footprint's
     * majority class; a tie goes to the class seen first in row-major order.
     */
    public static int[] reduceLabPbr(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "labpbr-src");
        checkSize(dstWidth, dstHeight, "labpbr-dst");
        return downscale(src, srcWidth, srcHeight, dstWidth, dstHeight, MaterialMapPixels::reduceLabFootprint);
    }

    private static int reduceLabFootprint(int[] src, int srcWidth, int x0, int y0, int x1, int y1) {
        int redSum = 0;
        int count = 0;
        LinkedHashMap<Integer, int[]> greenClasses = new LinkedHashMap<>();
        int blueLow = 0;
        int alphaLow = 0;
        int first = src[y0 * srcWidth + x0];
        for (int y = y0; y < y1; y++) {
            int row = y * srcWidth;
            for (int x = x0; x < x1; x++) {
                int texel = src[row + x];
                redSum += red(texel);
                count++;
                // {count, sum} per green class, keyed -1 for any dielectric, else by the metal index
                int[] tally = greenClasses.computeIfAbsent(green(texel) < LAB_GREEN_SPLIT ? -1 : green(texel),
                        ignored -> new int[2]);
                tally[0]++;
                tally[1] += green(texel);
                if (blue(texel) < LAB_BLUE_SPLIT) blueLow++;
                if (alpha(texel) < LAB_ALPHA_OPAQUE) alphaLow++;
            }
        }
        int[] greenTally = majorityTally(greenClasses);
        int greenSum = greenTally[1];
        int greenCount = greenTally[0];
        boolean blueIsLow = lowClassWins(blueLow, count, blue(first) < LAB_BLUE_SPLIT);
        boolean alphaIsLow = lowClassWins(alphaLow, count, alpha(first) < LAB_ALPHA_OPAQUE);
        int blueSum = 0;
        int blueCount = 0;
        int alphaSum = 0;
        int alphaCount = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * srcWidth;
            for (int x = x0; x < x1; x++) {
                int texel = src[row + x];
                if ((blue(texel) < LAB_BLUE_SPLIT) == blueIsLow) {
                    blueSum += blue(texel);
                    blueCount++;
                }
                if ((alpha(texel) < LAB_ALPHA_OPAQUE) == alphaIsLow) {
                    alphaSum += alpha(texel);
                    alphaCount++;
                }
            }
        }
        return pack(alphaSum / alphaCount, blueSum / blueCount, greenSum / greenCount, redSum / count);
    }

    /** The {count, sum} tally of the most populated class; a tie keeps the earliest class seen. */
    private static int[] majorityTally(Map<Integer, int[]> classes) {
        int[] chosen = null;
        for (int[] tally : classes.values()) {
            if (chosen == null || tally[0] > chosen[0]) chosen = tally;
        }
        return chosen;
    }

    /** True when the low threshold class holds the majority; a tie keeps the first texel's class. */
    private static boolean lowClassWins(int lowCount, int count, boolean firstIsLow) {
        return lowCount * 2 > count || (lowCount * 2 == count && firstIsLow);
    }

    /** Reduces the source footprint [x0, x1) x [y0, y1) to one destination texel. */
    private interface FootprintReducer {
        int reduce(int[] src, int srcWidth, int x0, int y0, int x1, int y1);
    }

    /** Downscales by reducing, for each destination texel, the source footprint it covers. */
    private static int[] downscale(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight,
                                   FootprintReducer reducer) {
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int y0 = y * srcHeight / dstHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * srcHeight / dstHeight);
            for (int x = 0; x < dstWidth; x++) {
                int x0 = x * srcWidth / dstWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * srcWidth / dstWidth);
                dst[y * dstWidth + x] = reducer.reduce(src, srcWidth, x0, y0, x1, y1);
            }
        }
        return dst;
    }

    private static int averageFootprint(int[] src, int srcWidth, int x0, int y0, int x1, int y1) {
        long a = 0;
        long b = 0;
        long g = 0;
        long r = 0;
        int count = 0;
        for (int sy = y0; sy < y1; sy++) {
            int row = sy * srcWidth;
            for (int sx = x0; sx < x1; sx++) {
                int texel = src[row + sx];
                a += alpha(texel);
                b += blue(texel);
                g += green(texel);
                r += red(texel);
                count++;
            }
        }
        return pack((int) (a / count), (int) (b / count), (int) (g / count), (int) (r / count));
    }

    /** Copies a sprite rectangle into a level canvas, clipped to the canvas. */
    public static void placeRect(int[] canvas, int canvasWidth, int canvasHeight,
            int x, int y, int[] rect, int rectWidth, int rectHeight) {
        for (int ry = 0; ry < rectHeight; ry++) {
            int cy = y + ry;
            if (cy < 0 || cy >= canvasHeight) continue;
            int canvasRow = cy * canvasWidth;
            int rectRow = ry * rectWidth;
            for (int rx = 0; rx < rectWidth; rx++) {
                int cx = x + rx;
                if (cx < 0 || cx >= canvasWidth) continue;
                canvas[canvasRow + cx] = rect[rectRow + rx];
            }
        }
    }

    /** A rectangle of ABGR pixels at a canvas position. */
    public record Rect(int x, int y, int width, int height, int[] pixels) {}

    /**
     * The part of a rectangle placed at {@code (x, y)} that falls inside a {@code canvasWidth x canvasHeight}
     * canvas, clipped as {@link #placeRect} clips; null when nothing is inside.
     */
    public static Rect clipRect(int[] rect, int rectWidth, int rectHeight, int x, int y,
            int canvasWidth, int canvasHeight) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(canvasWidth, x + rectWidth);
        int y1 = Math.min(canvasHeight, y + rectHeight);
        if (x0 >= x1 || y0 >= y1) return null;
        int width = x1 - x0;
        int height = y1 - y0;
        int[] clipped = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(rect, (y0 - y + row) * rectWidth + (x0 - x), clipped, row * width, width);
        }
        return new Rect(x0, y0, width, height, clipped);
    }

    /**
     * Pads one logical mip level with its own edge texels on all four sides, as the stitcher pads a sprite.
     * Returns a {@code (width + 2*pad) x (height + 2*pad)} image with the content in the centre; a zero pad
     * copies.
     */
    public static int[] padReplicate(int[] logical, int width, int height, int pad) {
        checkSize(width, height, "pad-src");
        if (pad < 0) {
            throw new IllegalArgumentException("MATERIAL_MAP_PAD:" + pad);
        }
        if (pad == 0) {
            return logical.clone();
        }
        int paddedWidth = width + pad * 2;
        int[] padded = new int[paddedWidth * (height + pad * 2)];
        for (int y = 0; y < height + pad * 2; y++) {
            int srcY = Math.max(0, Math.min(height - 1, y - pad));
            int paddedRow = y * paddedWidth;
            int srcRow = srcY * width;
            for (int x = 0; x < paddedWidth; x++) {
                int srcX = Math.max(0, Math.min(width - 1, x - pad));
                padded[paddedRow + x] = logical[srcRow + srcX];
            }
        }
        return padded;
    }

    /**
     * One axis of a stitched sprite's padding: its UV edge in atlas texels minus its placed origin. X and Y
     * must agree; the builder skips a sprite where they differ.
     */
    public static int recoverPad(float uvEdge, int atlasDim, int origin) {
        return Math.max(0, Math.round(uvEdge * atlasDim) - origin);
    }

    /** Halves per level, never below one texel. */
    public static int levelSize(int base, int level) {
        return Math.max(1, base >> level);
    }

    /**
     * The {@code namespace:path} of a sprite's material file. Sprites resolve under {@code textures/}, except
     * OptiFine CIT paths under {@code optifine/cit/}, which keep their directory.
     */
    public static String siblingResource(String namespace, String spritePath, String suffix) {
        String base = spritePath.startsWith("optifine/cit/") ? spritePath : "textures/" + spritePath;
        return namespace + ":" + base + suffix + ".png";
    }

    /**
     * Whether an {@code optifine/texture.properties} stream declares {@code format=lab-pbr}, with or without a
     * {@code /version} suffix.
     */
    public static boolean detectLabPbr(InputStream properties) throws IOException {
        Properties parsed = new Properties();
        parsed.load(properties);
        String format = parsed.getProperty("format", "").trim();
        return format.equals("lab-pbr") || format.startsWith("lab-pbr/");
    }
}
