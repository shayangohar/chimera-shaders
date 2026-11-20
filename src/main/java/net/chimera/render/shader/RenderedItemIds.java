// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.shaderpack.PackItemIdResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;

/**
 * Iris CapturedRenderingState's current rendered item, written into EntityIds.z (Iris iris_Entity.z) while
 * ItemRenderer.render draws it.
 */
public final class RenderedItemIds {
    /** The blockEntityId Iris sets while a block item renders. */
    public static final int BLOCK_ITEM_ENTITY_ID = 1;

    private static PackItemIdResolver items = PackItemIdResolver.empty();
    private static final int[] previousItems = new int[8];
    private static final int[] previousBlockEntities = new int[8];
    private static int depth;
    private static int current;

    private RenderedItemIds() {}

    public static void install(PackItemIdResolver next) {
        items = next == null ? PackItemIdResolver.empty() : next;
        depth = 0;
        current = 0;
    }

    /**
     * As Iris: a block item other than a solid bucket takes its default state's block.properties id, 0 when
     * unmapped; any other item its item.properties id.
     */
    public static int idOf(ItemStack stack) {
        Item item = stack == null ? null : stack.getItem();
        if (item == null) return 0;
        if (isBlockItem(item)) {
            int id = ChimeraTerrainPipelines.blockStateId(((BlockItem) item).getBlock().defaultBlockState());
            return id == -1 ? 0 : id;
        }
        return items.renderedItemId(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    private static boolean isBlockItem(Item item) {
        return item instanceof BlockItem && !(item instanceof SolidBucketItem);
    }

    /** A block item also sets blockEntityId to 1. */
    public static void beginItem(ItemStack stack) {
        push(idOf(stack), stack != null && isBlockItem(stack.getItem())
                ? BLOCK_ITEM_ENTITY_ID : ChimeraEntityBridge.currentBlockEntityId());
    }

    static void push(int itemId, int blockEntityId) {
        if (depth < previousItems.length) {
            previousItems[depth] = current;
            previousBlockEntities[depth] = ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        } else {
            ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        }
        depth++;
        current = itemId;
    }

    public static void pop() {
        if (depth <= 0) return;
        depth--;
        boolean saved = depth < previousItems.length;
        current = saved ? previousItems[depth] : 0;
        ChimeraEntityBridge.replaceBlockEntityId(saved ? previousBlockEntities[depth] : 0);
    }

    /**
     * Pops every open item. ItemRenderer.render has no finally, so a renderer that throws would leave its ids on
     * every later entity draw.
     */
    static void unwind() {
        if (depth <= 0) return;
        current = previousItems[0];
        ChimeraEntityBridge.replaceBlockEntityId(previousBlockEntities[0]);
        depth = 0;
    }

    public static int current() {
        return current;
    }
}
