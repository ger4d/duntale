package com.duntale.zsquad.merchant;

import com.duntale.zsquad.economy.GoldService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Custom item container for the merchant window, divided into buy and sell zones.
 *
 * <p><strong>Buy zone</strong> (slots {@code [0, buyCapacity)}): pre-populated with
 * display items. Players drag items from this zone to buy them. Gold is checked
 * via {@link #cantRemoveFromSlot(short)} before the engine moves the item.
 *
 * <p><strong>Sell zone</strong> (slots {@code [buyCapacity, capacity)}): initially empty.
 * Players drag items here to sell them. Only items recognised by the
 * {@link MerchantPriceRegistry} are accepted via {@link #cantAddToSlot(short, ItemStack, ItemStack)}.
 *
 * <p>Extends {@link SimpleItemContainer} to inherit concurrency, storage, and
 * packet serialisation. This container is created per-session and not persisted.
 *
 * @see MerchantService
 */
public class MerchantContainer extends SimpleItemContainer {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final short buyCapacity;
    private final MerchantPriceRegistry priceRegistry;
    private final GoldService goldService;
    private UUID playerId;

    /**
     * Creates a merchant container with the given zone sizes.
     *
     * @param buySlots      number of buy-zone slots
     * @param sellSlots     number of sell-zone slots
     * @param priceRegistry the price registry for buy/sell validation
     * @param goldService   the gold service for balance checks
     */
    public MerchantContainer(short buySlots, short sellSlots,
                             @Nonnull MerchantPriceRegistry priceRegistry,
                             @Nonnull GoldService goldService) {
        super((short) (buySlots + sellSlots));
        this.buyCapacity = buySlots;
        this.priceRegistry = priceRegistry;
        this.goldService = goldService;
    }

    /**
     * Sets the player UUID for this session. Must be called before the container is opened.
     *
     * @param playerId the player's UUID
     */
    public void setPlayerId(@Nonnull UUID playerId) {
        this.playerId = playerId;
    }

    /**
     * Returns the player UUID bound to this session.
     *
     * @return the player UUID, or {@code null} if not set
     */
    @Nullable
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Returns whether the given slot is in the buy zone.
     *
     * @param slot the slot index
     * @return {@code true} if the slot is a buy slot
     */
    public boolean isBuySlot(short slot) {
        return slot >= 0 && slot < buyCapacity;
    }

    /**
     * Returns whether the given slot is in the sell zone.
     *
     * @param slot the slot index
     * @return {@code true} if the slot is a sell slot
     */
    public boolean isSellSlot(short slot) {
        return slot >= buyCapacity && slot < getCapacity();
    }

    /**
     * Returns the number of buy slots in this container.
     *
     * @return the buy zone capacity
     */
    public short getBuyCapacity() {
        return buyCapacity;
    }

    /**
     * Blocks removal from buy slots if the player cannot afford the item.
     * Sell zone removals are always allowed (player taking back their item).
     */
    @Override
    protected boolean cantRemoveFromSlot(short slot) {
        // Apply parent filter logic first
        if (super.cantRemoveFromSlot(slot)) {
            return true;
        }

        if (isBuySlot(slot)) {
            if (playerId == null) {
                return true;
            }
            ItemStack item = internal_getSlot(slot);
            if (item == null) {
                return true;
            }
            long price = priceRegistry.getBuyPrice(item.getItemId());
            return !goldService.hasEnough(playerId, price);
        }

        // Sell zone: allow removal
        return false;
    }

    /**
     * Blocks adding items to buy slots. In the sell zone, only sellable items
     * (those with prices in the registry) are accepted.
     */
    @Override
    protected boolean cantAddToSlot(short slot, ItemStack itemStack, ItemStack existing) {
        if (isBuySlot(slot)) {
            // Never allow placing items in the buy zone
            return true;
        }

        if (isSellSlot(slot)) {
            if (itemStack == null) {
                return true;
            }
            // Reject buy-zone display items dragged directly into sell slots
            if (itemStack.getFromMetadataOrNull(MerchantService.META_BUY_PRICE, Codec.LONG) != null) {
                return true;
            }
            // Only accept items the registry knows how to price
            boolean sellable = priceRegistry.isSellable(itemStack.getItemId());
            if (!sellable) {
                LOGGER.atInfo().log("[Merchant] Rejected unsellable item: '%s'", itemStack.getItemId());
            }
            return !sellable;
        }

        return super.cantAddToSlot(slot, itemStack, existing);
    }

    /**
     * Prevents dropping items from buy slots.
     */
    @Override
    protected boolean cantDropFromSlot(short slot) {
        return isBuySlot(slot) || super.cantDropFromSlot(slot);
    }

    // ── Delegate Methods ─────────────────────────────────────────────

    /**
     * Returns the item in the given slot. Delegates to the protected
     * {@link #internal_getSlot(short)} for use by {@link MerchantService}.
     *
     * @param slot the slot index
     * @return the item stack, or {@code null} if empty
     */
    @Nullable
    public ItemStack getSlotDirect(short slot) {
        return internal_getSlot(slot);
    }

    /**
     * Sets the item in the given slot. Delegates to the protected
     * {@link #internal_setSlot(short, ItemStack)} for use by {@link MerchantService}.
     *
     * @param slot the slot index
     * @param item the item stack to place
     */
    public void setSlotDirect(short slot, @Nonnull ItemStack item) {
        internal_setSlot(slot, item);
    }
}
