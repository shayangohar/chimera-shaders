package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Texture selector slots, shared by planning and the runtime's backing array. */
public final class SelectorNamespace {
    /** Slots from here up live in Chimera's own array (VTextureSelectorMixin). */
    public static final int FIRST_EXTENDED = 8;
    // slot 14 is unused; the slots around it keep their numbers
    /** shadowtex1, which every stage reads on its own selector. */
    public static final int SHADOW_TEX1_SLOT = 15;
    // pack-owned sampled resources between the reserved slots
    public static final int PACK_SLOT_FIRST = 16;
    public static final int PACK_SLOT_LAST = 21;
    public static final int COLORTEX8_SLOT = 22;
    public static final int SHADOW_COLOR0_SLOT = 23;
    public static final int SHADOW_COLOR1_SLOT = 24;
    /** The resource pack's normal map, for material-capable stages. */
    public static final int NORMALS_SLOT = 25;
    /** The resource pack's specular map, for material-capable stages. */
    public static final int SPECULAR_SLOT = 26;
    /** colortex9..colortex15 from here, the rest of Iris's sixteen colour targets. */
    public static final int HIGH_COLORTEX_FIRST_SLOT = 27;
    /** shadowcolor2..shadowcolor7 from here, as Iris's HIGHER_SHADOWCOLOR adds them. */
    public static final int HIGH_SHADOW_COLOR_FIRST_SLOT = HIGH_COLORTEX_FIRST_SLOT + 7;
    /**
     * shadowtex0HW and shadowtex1HW: the shadow depths again, through comparison samplers, as Iris's
     * SEPARATE_HARDWARE_SAMPLERS adds them.
     */
    public static final int SHADOW_TEX0_HW_SLOT = HIGH_SHADOW_COLOR_FIRST_SLOT + 6;
    public static final int SHADOW_TEX1_HW_SLOT = SHADOW_TEX0_HW_SLOT + 1;
    /**
     * Further pack-owned textures and images. Iris gives every custom texture and image its own unit, and
     * packs such as Escheridia and Glimmer need more than the ten slots between the host's own.
     */
    public static final int PACK_EXTENDED_FIRST = SHADOW_TEX1_HW_SLOT + 1;
    public static final int PACK_EXTENDED_COUNT = 32;
    /**
     * The Distant Horizons depth textures, which Iris registers with the render targets
     * (IrisSamplers.addRenderTargetSamplers): dhDepthTex and its alias dhDepthTex0, then dhDepthTex1.
     * Without Distant Horizons Iris binds texture 0 to both; Chimera reports {@code DH_DEPTH_UNBOUND}.
     */
    public static final int DH_DEPTH_SLOT = PACK_EXTENDED_FIRST + PACK_EXTENDED_COUNT;
    public static final int DH_DEPTH1_SLOT = DH_DEPTH_SLOT + 1;
    /** iris_centerDepthSmooth, which fullscreen passes read (CenterDepthSmooth). */
    public static final int CENTER_DEPTH_SLOT = DH_DEPTH1_SLOT + 1;
    public static final int LAST_RESERVED = CENTER_DEPTH_SLOT;
    public static final int EXTENDED_CAPACITY = LAST_RESERVED - FIRST_EXTENDED + 1;
    /**
     * Slots pack-owned sampled resources may take, in assignment order. Slots 8 to 11 also carry colortex4..7,
     * so the planner skips those a live colour target takes.
     */
    public static final List<Integer> PACK_SELECTOR_SLOTS = packSelectorSlots();

    private static List<Integer> packSelectorSlots() {
        List<Integer> slots = new ArrayList<>(List.of(8, 9, 10, 11));
        for (int slot = PACK_SLOT_FIRST; slot <= PACK_SLOT_LAST; slot++) slots.add(slot);
        for (int slot = PACK_EXTENDED_FIRST; slot < DH_DEPTH_SLOT; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    private SelectorNamespace() {}

    /**
     * The one selector colortexN is sampled through, in every stage that reads it; colortex0..3 keep the host's
     * post slots. -1 outside colortex0..15.
     */
    public static int colorTargetSlot(int target) {
        if (target >= 0 && target <= 3) return target;
        if (target >= 4 && target <= 7) return target + 4;
        if (target == 8) return COLORTEX8_SLOT;
        if (target >= 9 && target <= 15) return HIGH_COLORTEX_FIRST_SLOT + target - 9;
        return -1;
    }

    /** The selector shadowcolorN is sampled through; -1 outside shadowcolor0..7. */
    public static int shadowColorSlot(int index) {
        if (index == 0) return SHADOW_COLOR0_SLOT;
        if (index == 1) return SHADOW_COLOR1_SLOT;
        return index >= 2 && index <= 7 ? HIGH_SHADOW_COLOR_FIRST_SLOT + index - 2 : -1;
    }

    /** The selector of a shadowcolorN or a hardware shadow sampler (shadowtex0HW, shadowtex1HW); -1 otherwise. */
    public static int shadowSamplerSlot(String name) {
        if ("shadowtex0HW".equals(name)) return SHADOW_TEX0_HW_SLOT;
        if ("shadowtex1HW".equals(name)) return SHADOW_TEX1_HW_SLOT;
        return shadowColorSlot(PackResourcePlan.shadowColorIndex(name));
    }

    /** The selector of a DH depth texture's registration; -1 for another name. */
    public static int dhDepthSlot(String name) {
        if ("dhDepthTex1".equals(name)) return DH_DEPTH1_SLOT;
        return "dhDepthTex".equals(name) || "dhDepthTex0".equals(name) ? DH_DEPTH_SLOT : -1;
    }

    public static boolean isAddressable(int slot) {
        return slot >= 0 && slot <= LAST_RESERVED;
    }

    /** Index into the extended backing array, or -1 for a slot outside it. */
    public static int extendedIndex(int slot) {
        return slot >= FIRST_EXTENDED && isAddressable(slot) ? slot - FIRST_EXTENDED : -1;
    }

    /** Clears every selector that can retain an image from a retired pack. */
    public static void clearOwned(IntConsumer clearSlot) {
        for (int slot = 0; slot <= LAST_RESERVED; slot++) clearSlot.accept(slot);
    }
}
