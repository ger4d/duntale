package com.duntale.zsquad.economy;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Converts gold coin items moved from external containers into player currency.
 */
public class InventoryGoldConversionSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String GOLD_ITEM_ID = "Gold_Coin";
    private static final String GOLD_COLOR = "#FFD700";

    @Nonnull
    private final GoldService goldService;

    public InventoryGoldConversionSystem(@Nonnull GoldService goldService) {
        super(InventoryChangeEvent.class);
        this.goldService = goldService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryChangeEvent event) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        ItemContainer playerInventory = InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
        int convertedAmount = consumeConvertedGold(event.getItemContainer(), event.getTransaction(), playerInventory);
        if (convertedAmount <= 0) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (!goldService.addGold(playerId, convertedAmount)) {
            ItemStackTransaction restoreTransaction = event.getItemContainer().addItemStack(new ItemStack(GOLD_ITEM_ID, convertedAmount));
            ItemStack remainder = restoreTransaction.getRemainder();
            if (!ItemStack.isEmpty(remainder)) {
                LOGGER.at(Level.SEVERE).log(
                        "Failed to restore %d inventory gold for %s after currency credit failed; remainder=%s",
                        convertedAmount,
                        playerId,
                        remainder);
            }
            return;
        }

        long newBalance = goldService.getBalance(playerId);
        playerRef.sendMessage(
                Message.raw("+ " + convertedAmount + " Gold")
                        .color(GOLD_COLOR)
                        .insert(Message.raw(" (Total: " + newBalance + ")").color("#AAAAAA"))
        );
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    static int consumeConvertedGold(@Nonnull ItemContainer destinationContainer,
                                    @Nonnull Transaction transaction,
                                    @Nonnull ItemContainer playerInventory) {
        Map<Short, Integer> goldAdditions = new LinkedHashMap<>();
        collectGoldAdditions(transaction, playerInventory, goldAdditions);
        if (goldAdditions.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (Map.Entry<Short, Integer> entry : goldAdditions.entrySet()) {
            short slot = entry.getKey();
            int quantity = entry.getValue();

            ItemStack current = destinationContainer.getItemStack(slot);
            if (ItemStack.isEmpty(current) || !GOLD_ITEM_ID.equals(current.getItemId())) {
                continue;
            }

            int quantityToRemove = Math.min(quantity, current.getQuantity());
            if (quantityToRemove <= 0) {
                continue;
            }

            if (destinationContainer.removeItemStackFromSlot(slot, current, quantityToRemove, true, true).succeeded()) {
                removed += quantityToRemove;
            }
        }

        return removed;
    }

    private static void collectGoldAdditions(@Nullable Transaction transaction,
                                             @Nonnull ItemContainer playerInventory,
                                             @Nonnull Map<Short, Integer> goldAdditions) {
        if (transaction == null || !transaction.succeeded()) {
            return;
        }

        if (transaction instanceof ListTransaction<?> listTransaction) {
            for (Transaction childTransaction : listTransaction.getList()) {
                collectGoldAdditions(childTransaction, playerInventory, goldAdditions);
            }
            return;
        }

        if (transaction instanceof MoveTransaction<?> moveTransaction) {
            if (!isExternalSource(moveTransaction.getOtherContainer(), playerInventory)) {
                return;
            }

            collectGoldAdditions(moveTransaction.getAddTransaction(), playerInventory, goldAdditions);
            return;
        }

        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTransaction : itemStackTransaction.getSlotTransactions()) {
                recordGoldAddition(slotTransaction, goldAdditions);
            }
            return;
        }

        if (transaction instanceof SlotTransaction slotTransaction) {
            recordGoldAddition(slotTransaction, goldAdditions);
        }
    }

    private static boolean isExternalSource(@Nonnull ItemContainer sourceContainer, @Nonnull ItemContainer playerInventory) {
        return !playerInventory.containsContainer(sourceContainer);
    }

    private static void recordGoldAddition(@Nonnull SlotTransaction slotTransaction,
                                           @Nonnull Map<Short, Integer> goldAdditions) {
        if (!slotTransaction.succeeded()) {
            return;
        }

        int addedQuantity = goldDelta(slotTransaction.getSlotBefore(), slotTransaction.getSlotAfter());
        if (addedQuantity <= 0) {
            return;
        }

        goldAdditions.merge(slotTransaction.getSlot(), addedQuantity, Integer::sum);
    }

    static int goldDelta(@Nullable ItemStack slotBefore, @Nullable ItemStack slotAfter) {
        return goldQuantity(slotAfter) - goldQuantity(slotBefore);
    }

    private static int goldQuantity(@Nullable ItemStack itemStack) {
        return !ItemStack.isEmpty(itemStack) && GOLD_ITEM_ID.equals(itemStack.getItemId()) ? itemStack.getQuantity() : 0;
    }
}