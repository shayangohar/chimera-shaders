package net.chimera.shaderpack;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Iris raw texture declaration, {@code <file> <type> <internalFormat> <x> [y] [z] <pixelFormat> <pixelType>}.
 * Six words are 1D, seven 2D or RECTANGLE, eight 3D, as in Iris's ShaderProperties. The file holds the pixels
 * as glTexImage reads them: tightly packed, except that each row starts on a 4-byte boundary, since Iris's
 * TextureUploadHelper sets GL_UNPACK_ALIGNMENT 4.
 *
 * Chimera uploads formats whose pixel type already is the internal storage, and 32-bit floats into a 16-bit
 * float format, halved as glTexImage does; Pixel Perfect's colour LUT is RGB16F from FLOAT. A three-channel
 * format widens to four with alpha 1, since Vulkan devices rarely sample three-channel images. Anything else
 * is {@code PACK_RAW_TEXTURE_UNSUPPORTED}.
 */
public record RawTextureSpec(
        String file,
        String type,
        String internalFormat,
        int width,
        int height,
        int depth,
        String pixelFormat,
        String pixelType
) {
    /** Internal formats GL names by channels, bits and kind: RGBA8, RG16F, R32UI, RGB16_SNORM. */
    private static final Pattern INTERNAL_FORMAT = Pattern.compile("(RGBA|RGB|RG|R)(8|16|32)(F|I|UI|_SNORM)?");

    /** Null when {@code value} is not a well-formed raw declaration. */
    public static RawTextureSpec parse(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 6 || parts.length > 8) return null;
        try {
            // sizes the word count leaves out are 0, as Iris records them
            int width = Integer.parseInt(parts[3]);
            int height = parts.length >= 7 ? Integer.parseInt(parts[4]) : 0;
            int depth = parts.length == 8 ? Integer.parseInt(parts[5]) : 0;
            if (width < 1 || (parts.length >= 7 && height < 1) || (parts.length == 8 && depth < 1)) return null;
            return new RawTextureSpec(parts[0], parts[1].toUpperCase(Locale.ROOT),
                    parts[2].toUpperCase(Locale.ROOT), width, height, depth,
                    parts[parts.length - 2].toUpperCase(Locale.ROOT), parts[parts.length - 1].toUpperCase(Locale.ROOT));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** 1, 2 or 3; 0 for a type Iris does not name or whose word count disagrees with its sizes. */
    public int dimensions() {
        return switch (type) {
            case "TEXTURE_1D" -> height == 0 && depth == 0 ? 1 : 0;
            case "TEXTURE_2D", "TEXTURE_RECTANGLE" -> height > 0 && depth == 0 ? 2 : 0;
            case "TEXTURE_3D" -> depth > 0 ? 3 : 0;
            default -> 0;
        };
    }

    /** The image's height and depth: a size the declaration leaves out is one texel. */
    public int extentHeight() {
        return Math.max(height, 1);
    }

    public int extentDepth() {
        return Math.max(depth, 1);
    }

    /** Why this declaration cannot be uploaded, or null when it can. */
    public String unsupportedReason() {
        if (dimensions() == 0) return "type " + type;
        Storage storage = storage();
        if (storage == null) return "internal format " + internalFormat;
        if (!pixelFormat.equals(storage.pixelFormat())) return "pixel format " + pixelFormat + " for " + internalFormat;
        if (!pixelType.equals(storage.pixelType()) && !halvesFloats()) {
            return "pixel type " + pixelType + " for " + internalFormat;
        }
        return null;
    }

    /** Whether the file holds 32-bit floats that the upload halves into this 16-bit float format. */
    public boolean halvesFloats() {
        Storage storage = storage();
        return storage != null && storage.kind() == Kind.FLOAT && storage.bits() == 16 && pixelType.equals("FLOAT");
    }

    /** Bytes of one channel in the file: the storage's own, or a 32-bit float the upload halves. */
    public int fileChannelBytes() {
        Storage storage = storage();
        if (storage == null) return 0;
        return halvesFloats() ? Float.BYTES : storage.bits() / 8;
    }

    /** The storage this internal format names, or null when Chimera does not serve it. */
    public Storage storage() {
        String name = internalFormat.equals("RGBA") ? "RGBA8" : internalFormat;
        Matcher matcher = INTERNAL_FORMAT.matcher(name);
        if (!matcher.matches()) return null;
        int channels = matcher.group(1).length();
        int bits = Integer.parseInt(matcher.group(2));
        Kind kind = switch (matcher.group(3) == null ? "" : matcher.group(3)) {
            case "" -> bits == 32 ? null : Kind.UNORM;
            case "_SNORM" -> bits == 32 ? null : Kind.SNORM;
            case "F" -> bits == 8 ? null : Kind.FLOAT;
            case "I" -> Kind.SINT;
            default -> Kind.UINT;
        };
        return kind == null ? null : new Storage(channels, bits, kind);
    }

    /** Bytes of one tightly packed row as the file stores it, before GL's 4-byte row alignment. */
    public int rowBytes() {
        Storage storage = storage();
        return storage == null ? 0 : width * storage.channels() * fileChannelBytes();
    }

    /** Bytes from one row's start to the next in the file (GL_UNPACK_ALIGNMENT 4). */
    public int rowStride() {
        return (rowBytes() + 3) & ~3;
    }

    /** The smallest file that holds every row: all but the last row padded. */
    public long requiredBytes() {
        return (long) rowStride() * ((long) extentHeight() * extentDepth() - 1) + rowBytes();
    }

    public enum Kind { UNORM, SNORM, FLOAT, SINT, UINT }

    /** Channels, bits per channel and numeric kind of an internal format. */
    public record Storage(int channels, int bits, Kind kind) {
        /** The GL pixel format whose data needs no channel reorder. */
        public String pixelFormat() {
            String base = switch (channels) {
                case 1 -> "RED";
                case 2 -> "RG";
                case 3 -> "RGB";
                default -> "RGBA";
            };
            return kind == Kind.SINT || kind == Kind.UINT ? base + "_INTEGER" : base;
        }

        /** The GL pixel type that already is this storage. */
        public String pixelType() {
            boolean signed = kind == Kind.SNORM || kind == Kind.SINT;
            return switch (bits) {
                case 8 -> signed ? "BYTE" : "UNSIGNED_BYTE";
                case 16 -> kind == Kind.FLOAT ? "HALF_FLOAT" : signed ? "SHORT" : "UNSIGNED_SHORT";
                default -> kind == Kind.FLOAT ? "FLOAT" : signed ? "INT" : "UNSIGNED_INT";
            };
        }

        /** Channels the uploaded image has: three widen to four. */
        public int uploadedChannels() {
            return channels == 3 ? 4 : channels;
        }

        /** Integer images take no linear filtering. */
        public boolean integer() {
            return kind == Kind.SINT || kind == Kind.UINT;
        }

        /** The little-endian bytes of 1 in this storage: a widened channel's alpha. */
        public byte[] one() {
            return switch (kind) {
                case UNORM -> bits == 8 ? new byte[] {(byte) 0xFF} : new byte[] {(byte) 0xFF, (byte) 0xFF};
                case SNORM -> bits == 8 ? new byte[] {0x7F} : new byte[] {(byte) 0xFF, 0x7F};
                case FLOAT -> bits == 16 ? new byte[] {0x00, 0x3C} : new byte[] {0x00, 0x00, (byte) 0x80, 0x3F};
                case SINT, UINT -> {
                    byte[] value = new byte[bits / 8];
                    value[0] = 1;
                    yield value;
                }
            };
        }
    }
}
