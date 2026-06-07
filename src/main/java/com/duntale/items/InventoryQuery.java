package com.duntale.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Read/scan helpers over a player's full (combined) inventory for custom-item lookups.
 *
 * <p>All methods operate on {@link InventoryComponent#getCombined} over
 * {@link InventoryComponent#EVERYTHING}, so they see hotbar, storage, armor, and
 * utility slots. They must be called on the entity's {@code WorldThread}.
 */
public final class InventoryQuery {

    private InventoryQuery() {
    }

    /**
     * Returns whether the entity's combined inventory holds at least one item with the given ID.
     *
     * @param store  the entity store
     * @param ref    the entity reference
     * @param itemId the item asset ID to search for
     * @return {@code true} if a matching item is present
     */
    public static boolean containsItem(@Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull String itemId) {
        ItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
        if (combined == null) {
            return false;
        }
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && itemId.equals(stack.getItemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a single unit of the first matching item from the entity's combined inventory.
     *
     * @param store  the entity store
     * @param ref    the entity reference
     * @param itemId the item asset ID to remove one of
     * @return {@code true} if one unit was removed
     */
    public static boolean removeOne(@Nonnull Store<EntityStore> store,
                                    @Nonnull Ref<EntityStore> ref,
                                    @Nonnull String itemId) {
        ItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
        if (combined == null) {
            return false;
        }
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && itemId.equals(stack.getItemId())) {
                return combined.removeItemStackFromSlot(slot, stack, 1, true, true).succeeded();
            }
        }
        return false;
    }
}
