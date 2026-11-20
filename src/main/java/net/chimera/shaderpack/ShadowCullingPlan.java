// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The shadow culling Iris's ShadowRenderer.createShadowFrustum chooses for a pack, and its distances: half-sizes
 * in blocks of a camera-centred BoxCuller. A negative {@code shadowDistanceRenderMul}, the default, means the
 * player's Max Shadow Distance.
 */
public record ShadowCullingPlan(
        State state,
        float halfPlaneLength,
        float renderMultiplier,
        float entityMultiplier,
        float voxelDistance,
        OptionalInt forcedDistanceChunks,
        List<String> deviations
) {
    /** Iris's ShadowCullState. */
    public enum State { DEFAULT, ADVANCED, SAFE_ZONE, DISTANCE }

    /** The frustum Iris builds: NonCullingFrustum, BoxCullingFrustum, Advanced or SafeZone. */
    public enum Mode { NONE, DISTANCE, ADVANCED, SAFE_ZONE }

    /**
     * One frustum. {@code box} is the BoxCuller half-size, infinite when Iris has none, or SAFE_ZONE's outer
     * distance box; {@code safeZone} is the half-size of the box SAFE_ZONE always draws.
     */
    public record Culler(Mode mode, double box, double safeZone) {
        public static final Culler NONE = new Culler(Mode.NONE, Double.POSITIVE_INFINITY, 0.0);

        public String describe() {
            return switch (mode) {
                case NONE -> "none";
                case DISTANCE -> "distance box=" + box;
                case ADVANCED -> "advanced box=" + (Double.isInfinite(box) ? "none" : Double.toString(box));
                case SAFE_ZONE -> "safe zone=" + safeZone + " box=" + box;
            };
        }
    }

    public ShadowCullingPlan {
        state = state == null ? State.DEFAULT : state;
        forcedDistanceChunks = forcedDistanceChunks == null ? OptionalInt.empty() : forcedDistanceChunks;
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    public static ShadowCullingPlan of(Map<String, String> properties, PackConfig.ShadowSettings settings) {
        return of(properties, settings, false);
    }

    /**
     * {@code voxelization} is set when the shadow program has a geometry stage; Iris then culls an unset
     * {@code shadow.culling} by distance only (ShadowRenderer's packHasVoxelization).
     */
    public static ShadowCullingPlan of(Map<String, String> properties, PackConfig.ShadowSettings settings,
                                       boolean voxelization) {
        List<String> deviations = new ArrayList<>();
        State state = voxelization ? State.DISTANCE : State.DEFAULT;
        String value = properties == null ? null : properties.get("shadow.culling");
        if (value != null) {
            switch (value.trim()) {
                case "false" -> state = State.DISTANCE;
                case "true" -> state = State.ADVANCED;
                case "reversed", "safe_zone" -> state = State.SAFE_ZONE;
                // Iris logs an unrecognised value and keeps the default
                default -> deviations.add("SHADOW_CULLING_DIRECTIVE_MALFORMED:" + value.trim());
            }
        }
        return new ShadowCullingPlan(state, settings.distance(), settings.cullingRenderMultiplier(),
                settings.entityDistanceMultiplier(), settings.voxelDistance(),
                settings.forcedShadowRenderDistanceChunks(), deviations);
    }

    /** The shadow distance in force, in chunks: the pack's forced one, else the player's; 0 means no shadow pass. */
    public int distanceInForce(int playerShadowChunks) {
        return forcedDistanceChunks.orElse(playerShadowChunks);
    }

    /** The terrain frustum, which also culls the block entities inside its sections. */
    public Culler terrain(int renderDistanceBlocks, int playerShadowChunks) {
        return create(renderMultiplier, renderDistanceBlocks, playerShadowChunks);
    }

    /** The entity frustum; Iris reuses the terrain one unless entityShadowDistanceMul is non-negative and not 1. */
    public Culler entities(int renderDistanceBlocks, int playerShadowChunks) {
        if (entityMultiplier == 1.0F || entityMultiplier < 0.0F) {
            return terrain(renderDistanceBlocks, playerShadowChunks);
        }
        return create(renderMultiplier * entityMultiplier, renderDistanceBlocks, playerShadowChunks);
    }

    private Culler create(float multiplier, int renderDistanceBlocks, int playerShadowChunks) {
        if (state == State.DISTANCE) {
            double distance = halfPlaneLength * multiplier;
            if (distance <= 0.0 || distance > renderDistanceBlocks) return Culler.NONE;
            return new Culler(Mode.DISTANCE, distance, 0.0);
        }
        boolean safeZone = state == State.SAFE_ZONE;
        // Iris takes a negative multiplier as 1 under safe-zone culling
        if (safeZone && multiplier < 0.0F) multiplier = 1.0F;
        double distance = (safeZone ? voxelDistance : halfPlaneLength) * multiplier;
        // a negative multiplier reads the player's setting, as in ShadowRenderer.createShadowFrustum
        if (multiplier < 0.0F) distance = playerShadowChunks * 16.0;
        if (safeZone) {
            return new Culler(Mode.SAFE_ZONE, halfPlaneLength * (double) multiplier, distance);
        }
        // at or past the render distance Iris builds no BoxCuller
        // a zero distance keeps BoxCuller(0): Iris overwrites its CullEverythingFrustum with the advanced one
        double box = distance >= renderDistanceBlocks ? Double.POSITIVE_INFINITY : distance;
        return new Culler(Mode.ADVANCED, box, 0.0);
    }
}
