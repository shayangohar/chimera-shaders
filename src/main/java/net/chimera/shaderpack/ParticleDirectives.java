// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a pack draws particles against its deferred passes, as Iris's ShaderProperties and IrisRenderingPipeline
 * resolve it. {@code separateEntityDraws}, true or false, sets mixed, and {@code particles.before.deferred = true}
 * sets before while nothing else has. Unset means after. Iris's check for deferred programs always passes, so none
 * is made here.
 *
 * Keys apply in first-appearance order, each with its last value (Iris: OrderBackedProperties), so a later
 * separateEntityDraws undoes an earlier {@code particles.ordering}.
 */
public record ParticleDirectives(Ordering ordering, boolean separateEntityDraws, List<String> deviations) {
    public static final ParticleDirectives DEFAULTS = new ParticleDirectives(Ordering.AFTER, false, List.of());
    private static final String ORDERING = "particles.ordering";
    private static final String BEFORE_DEFERRED = "particles.before.deferred";
    private static final String SEPARATE_ENTITY_DRAWS = "separateEntityDraws";

    /** Iris's ParticleRenderingSettings without UNSET, which resolves before a frame draws. */
    public enum Ordering {
        BEFORE, MIXED, AFTER;

        /** Whether a particle draws in NeoForge's early call, before translucent terrain and the deferred passes. */
        public boolean early(boolean translucent) {
            return this == BEFORE || this == MIXED && !translucent;
        }

        /** Whether NeoForge's early or late particle call draws a sheet of this translucency. */
        public boolean drawsSheet(boolean earlyCall, boolean translucentSheet) {
            return early(translucentSheet) == earlyCall;
        }

        /** Whether NeoForge's early or late particle call draws anything; Iris skips the call that doesn't. */
        public boolean drawsIn(boolean earlyCall) {
            return this == MIXED || (this == BEFORE) == earlyCall;
        }
    }

    public ParticleDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    /** Reads {@code properties} in Iris's key order, as a LinkedHashMap keeps them. */
    static ParticleDirectives fromOrderedProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        Ordering ordering = null;
        boolean separate = false;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().trim();
            if (key.startsWith(ORDERING)) {
                ordering = named(value, deviations);
                continue;
            }
            if (!key.equals(BEFORE_DEFERRED) && !key.equals(SEPARATE_ENTITY_DRAWS)) continue;
            Boolean on = flag(value);
            if (on == null) {
                deviations.add("PARTICLE_DIRECTIVE_MALFORMED:" + key);
            } else if (key.equals(SEPARATE_ENTITY_DRAWS)) {
                separate = on;
                ordering = Ordering.MIXED;
            } else if (on && ordering == null) {
                ordering = Ordering.BEFORE;
            }
        }
        if (ordering == null) ordering = separate ? Ordering.MIXED : Ordering.AFTER;
        return new ParticleDirectives(ordering, separate, deviations);
    }

    /** The named ordering, or null for Iris's UNSET or an unknown name, which leave it to the default. */
    private static Ordering named(String value, List<String> deviations) {
        String name = value.toUpperCase(Locale.ROOT);
        if (name.equals("UNSET")) return null;
        try {
            return Ordering.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            deviations.add("PARTICLE_ORDERING_UNKNOWN:" + value);
            return null;
        }
    }

    private static Boolean flag(String value) {
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> null;
        };
    }
}
