// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads a pack's {@code image.<name>} declarations as Iris's ShaderProperties does. */
final class AdvancedImageDeclarations {
    /** The sampler name ShaderProperties reads as no sampler. */
    private static final String NO_SAMPLER = "none";
    /** Iris's image limit (ShaderProperties). */
    static final int MAX_IMAGES = 16;
    private static final Pattern SAMPLER_3D =
            Pattern.compile("\\b(?:sampler3D|isampler3D|usampler3D)\\s+([A-Za-z_]\\w*)");

    private AdvancedImageDeclarations() {}

    static Map<String, PackAdvancedResourcePlan.ImageSpec> parseImages(
            PackSettingsPlan settings, List<String> deviations) {
        Map<String, PackAdvancedResourcePlan.ImageSpec> images = new TreeMap<>();
        // sizes resolve against the macros in effect at the end of shaders.properties
        Map<String, String> macros = new TreeMap<>(settings.propertyMacros());
        macros.putAll(settings.preprocessorDefines());
        for (Map.Entry<String, String> entry : settings.propertyValues().entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            PackAdvancedResourcePlan.ImageSpec spec = parseImage(entry.getKey(), entry.getValue(), macros);
            images.put(spec.name(), spec);
            deviations.addAll(spec.deviations());
        }
        images.values().stream().filter(PackAdvancedResourcePlan.ImageSpec::supported)
                .forEach(spec -> deviations.add("ADVANCED_IMAGE_APPLIED:" + spec.name()));
        return images;
    }

    /** Split on single spaces, as ShaderProperties does. */
    static String[] values(String raw) {
        return raw == null ? new String[0] : raw.stripLeading().split(" ");
    }

    private static boolean relative(String[] values) {
        return values.length > 5 && Boolean.parseBoolean(values[5]);
    }

    /**
     * On any other entry ShaderProperties throws and Iris loads no pack at all; Chimera refuses that image
     * alone.
     */
    private static boolean readable(String[] values) {
        return relative(values) ? values.length >= 8 : values.length >= 7 && values.length <= 9;
    }

    /** Iris keeps the first 16 in file order; an unreadable entry does not count. */
    static void dropImagesPastIrisLimit(Map<String, String> orderedProperties, List<String> deviations) {
        int added = 0;
        for (var entries = orderedProperties.entrySet().iterator(); entries.hasNext(); ) {
            var entry = entries.next();
            if (!entry.getKey().startsWith("image.")) continue;
            if (added >= MAX_IMAGES) {
                entries.remove();
                deviations.add("ADVANCED_IMAGE_LIMIT_EXCEEDED:" + entry.getKey().substring("image.".length()));
                continue;
            }
            if (readable(values(entry.getValue()))) added++;
        }
    }

    /**
     * Values: sampler or none, pixel format, internal format, pixel type, clear, relative, then a relative
     * image's two screen shares or a fixed image's one to three dimensions.
     */
    static PackAdvancedResourcePlan.ImageSpec parseImage(
            String key,
            String raw,
            Map<String, String> defines
    ) {
        String name = key.substring("image.".length());
        List<String> deviations = new ArrayList<>();
        String[] values = values(raw);
        boolean relative = relative(values);
        if (!readable(values)) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, values, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        try {
            return relative ? relativeImage(name, values, defines, deviations)
                    : fixedImage(name, values, defines, deviations);
        } catch (RuntimeException failure) {
            if (deviations.stream().noneMatch(value -> value.startsWith(
                    "ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + name + ":"))) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            }
            return image(name, values, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
    }

    /** No size cap here, as in Iris; device limits apply at creation (PackAdvancedImageOwner). */
    private static PackAdvancedResourcePlan.ImageSpec fixedImage(
            String name, String[] values, Map<String, String> defines, List<String> deviations) {
        int[] size = new int[3];
        for (int axis = 0; axis < values.length - 6; axis++) {
            size[axis] = resolveImageDimension(name, values[6 + axis], defines, deviations);
        }
        boolean unsized = size[0] < 1 || values.length > 7 && size[1] < 1 || values.length > 8 && size[2] < 1;
        long bytes = unsized ? 0 : Math.multiplyExact(Math.multiplyExact((long) size[0], Math.max(1, size[1])),
                Math.max(1, size[2])) * bytesPerVoxel(values[2].toLowerCase(Locale.ROOT));
        if (unsized) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, values, size, bytes, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
        return image(name, values, size, bytes, PackAdvancedResourcePlan.Status.SUPPORTED, deviations);
    }

    /** Sized with the screen (GlImage.Relative); its tokens keep the shares. */
    private static PackAdvancedResourcePlan.ImageSpec relativeImage(
            String name, String[] values, Map<String, String> defines, List<String> deviations) {
        float width = relativeShare(name, values[6], defines, deviations);
        float height = relativeShare(name, values[7], defines, deviations);
        bytesPerVoxel(values[2].toLowerCase(Locale.ROOT));
        String[] shares = values.clone();
        shares[6] = Float.toString(width);
        shares[7] = Float.toString(height);
        if (!(width > 0.0F) || !(height > 0.0F)) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, shares, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
        return image(name, shares, new int[3], 0, PackAdvancedResourcePlan.Status.SUPPORTED, deviations);
    }

    /** A number, or a macro that names one. */
    private static float relativeShare(String image, String token, Map<String, String> defines,
                                       List<String> deviations) {
        String value = token.trim();
        for (int depth = 0; depth < 8 && defines != null && defines.containsKey(value); depth++) {
            value = defines.get(value).trim();
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException failure) {
            deviations.add("ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + image + ":" + token);
            throw failure;
        }
    }

    /** A sampler and an image uniform always have different GLSL names, so the two keys never meet. */
    static String slotKey(String image, String samplerToken) {
        return samplerToken.equals(NO_SAMPLER) ? image : samplerToken;
    }

    private static PackAdvancedResourcePlan.ImageSpec image(String name, String[] values, int[] size, long bytes,
            PackAdvancedResourcePlan.Status status, List<String> deviations) {
        IrisColorFormats.Format format = IrisColorFormats.of(token(values, 2));
        if (status == PackAdvancedResourcePlan.Status.SUPPORTED && format != null && !format.exact()) {
            deviations.add("ADVANCED_IMAGE_FORMAT_APPROXIMATED:" + name + "=" + format.token() + "->"
                    + IrisColorFormats.stored(format.vkFormat()).token());
        }
        String sampler = token(values, 0).equals(NO_SAMPLER) ? "" : token(values, 0);
        return new PackAdvancedResourcePlan.ImageSpec(name, sampler, token(values, 1),
                token(values, 2).toLowerCase(Locale.ROOT),
                token(values, 3), Boolean.parseBoolean(token(values, 4)), Boolean.parseBoolean(token(values, 5)),
                size[0], size[1], size[2], sizeToken(values, 6), sizeToken(values, 7), sizeToken(values, 8),
                bytes, status, deviations);
    }

    private static String sizeToken(String[] values, int index) {
        return values.length > index ? values[index] : null;
    }

    static int resolveImageDimension(
            String image,
            String token,
            Map<String, String> defines,
            List<String> deviations
    ) {
        String value = token == null ? "" : token.trim();
        Set<String> visiting = new TreeSet<>();
        for (int depth = 0; depth < 8; depth++) {
            if (value.matches("[-+]?\\d+")) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException failure) {
                    break;
                }
            }
            if (!value.matches("[A-Za-z_]\\w*") || defines == null || !defines.containsKey(value)
                    || !visiting.add(value)) {
                break;
            }
            value = defines.get(value);
        }
        deviations.add("ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + image + ":" + token);
        return 0;
    }

    /**
     * A 3D sampler a pack raw texture serves ({@code packVolume}) is an ordinary texture. A source the analysis
     * cannot parse counts as using one.
     */
    static boolean usesAdvancedResource(String source, Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                                        Predicate<String> packVolume) {
        if (source == null) return false;
        Set<String> injectedSamplers = images == null ? Set.of() : images.values().stream()
                .map(PackAdvancedResourcePlan.ImageSpec::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source, injectedSamplers);
        if (!usage.successful()) return true;
        if (!usage.liveImages().isEmpty()) return true;
        Matcher sampler3D = SAMPLER_3D.matcher(source);
        while (sampler3D.find()) {
            String name = sampler3D.group(1);
            if (usage.liveSamplers().contains(name) && !packVolume.test(name)) return true;
        }
        return usage.liveSamplers().stream().anyMatch(injectedSamplers::contains);
    }

    /** As stored; Iris skips an image whose format it does not know. */
    static int bytesPerVoxel(String format) {
        IrisColorFormats.Format known = IrisColorFormats.of(format);
        if (known == null) throw new IllegalArgumentException("unsupported advanced image format: " + format);
        return known.bytes();
    }

    static String storageImageType(PackAdvancedResourcePlan.ImageSpec spec) {
        return spec == null || spec.format() == null ? "image3D" : spec.typePrefix() + "image" + spec.dimensionSuffix();
    }

    /** Its own type for an empty {@code viewFormat}. */
    static String storageImageType(PackAdvancedResourcePlan.ImageSpec spec, String viewFormat) {
        IrisColorFormats.Format view = viewFormat.isEmpty() ? null : IrisColorFormats.of(viewFormat);
        if (view == null) return storageImageType(spec);
        IrisColorFormats.Kind kind = view.kind();
        String prefix = kind == IrisColorFormats.Kind.UINT ? "u" : kind == IrisColorFormats.Kind.SINT ? "i" : "";
        return prefix + "image" + spec.dimensionSuffix();
    }

    /** Lower case; empty when the {@code layout(...)} list names none. */
    static String declaredFormat(String layout) {
        if (layout == null) return "";
        for (String part : layout.split(",")) {
            String token = part.trim();
            if (!token.contains("=") && IrisColorFormats.of(token) != null) return token.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * A qualifier of another format with the same texel size reinterprets the bits, as a GL image unit does.
     * Empty for the image's own format or no qualifier, null for a format of another size.
     */
    static String viewFormat(PackAdvancedResourcePlan.ImageSpec spec, String declared) {
        return viewFormat(spec.format(), declared);
    }

    static String viewFormat(IrisColorFormats.Format own, String declared) {
        IrisColorFormats.Format format = IrisColorFormats.of(declared);
        if (format == null || format.vkFormat() == own.vkFormat()) return "";
        return format.bytes() == own.bytes()
                ? IrisColorFormats.stored(format.vkFormat()).token().toLowerCase(Locale.ROOT) : null;
    }

    static String token(String[] values, int index) {
        return values.length > index ? values[index] : "";
    }
}
