package com.duntale.zsquad.merchant;

import com.duntale.zsquad.economy.GoldService;
import com.duntale.zsquad.progression.GearLevelService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages merchant sessions — opening, closing, and handling buy/sell transactions.
 *
 * <p>Each player can have at most one active merchant session. Opening a merchant
 * creates a {@link MerchantContainer} populated with catalog items, wraps it in
 * a {@link ContainerWindow}, and opens it alongside the player's inventory via
 * {@code pageManager.setPageWithWindows(Page.Bench, ...)}.
 *
 * <p>Transactions are handled via
 * {@link ItemContainer#registerChangeEvent(java.util.function.Consumer) registerChangeEvent},
 * which fires when items move between the player's inventory and the merchant container.
 *
 * @see MerchantContainer
 * @see MerchantPriceRegistry
 */
public class MerchantService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String COLOR_GOLD = "#FFD700";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_GRAY = "#AAAAAA";

    /** Default number of sell-zone slots in the merchant container. */
    private static final short DEFAULT_SELL_SLOTS = 2;



    /** Key for buy-price metadata on merchant display items. */
    static final String META_BUY_PRICE = "merchant_buy_price";

    /** Key for player gold-balance metadata on merchant display items. */
    static final String META_GOLD = "merchant_gold";

    /** Key for sell-price metadata on items bought from the merchant. */
    static final String META_SELL_PRICE = "merchant_sell_price";

    private final MerchantPriceRegistry priceRegistry;
    private final GoldService goldService;
    private final Map<UUID, MerchantSession> openSessions = new ConcurrentHashMap<>();

    /** Guard against re-entrant change events caused by slot mutations inside handlers. */
    private final Map<UUID, Boolean> processingChange = new ConcurrentHashMap<>();

    /**
     * Creates a new merchant service.
     *
     * @param priceRegistry the price registry for item pricing
     * @param goldService   the gold service for balance operations
     */
    public MerchantService(@Nonnull MerchantPriceRegistry priceRegistry,
                           @Nonnull GoldService goldService) {
        this.priceRegistry = priceRegistry;
        this.goldService = goldService;
    }

    /**
     * Opens a merchant window for the player with the given catalog.
     *
     * @param player    the player component
     * @param playerRef the player reference (must not be stale)
     * @param ref       the player entity reference
     * @param store     the entity store
     * @param catalog   the list of items the merchant sells
     */
    public void openMerchant(@Nonnull Player player,
                             @Nonnull PlayerRef playerRef,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull List<CatalogEntry> catalog) {
        UUID playerId = playerRef.getUuid();

        // Close existing session if any
        closeMerchant(playerId);

        // Cap catalog to max buy slots
        List<CatalogEntry> effectiveCatalog = catalog.size() > CatalogGenerator.MAX_BUY_SLOTS
                ? catalog.subList(0, CatalogGenerator.MAX_BUY_SLOTS)
                : catalog;

        // Create container
        short buySlots = (short) effectiveCatalog.size();
        MerchantContainer container = new MerchantContainer(buySlots, DEFAULT_SELL_SLOTS,
                priceRegistry, goldService);
        container.setPlayerId(playerId);

        // Populate buy zone with display items
        long currentGold = goldService.getBalance(playerId);
        for (short i = 0; i < effectiveCatalog.size(); i++) {
            CatalogEntry entry = effectiveCatalog.get(i);
            ItemStack displayItem = createDisplayItem(entry, currentGold);
            if (displayItem != null) {
                container.addItemStackToSlot(i, displayItem, false, false);
            }
        }

        // Register change event for buy/sell handling
        MerchantWindow window = new MerchantWindow(container, playerId, this);
        container.registerChangeEvent(event ->
                handleContainerChange(playerId, event, container, effectiveCatalog, playerRef));

        // Open the window
        player.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, window);

        // Track session
        openSessions.put(playerId, new MerchantSession(playerId, container, window, effectiveCatalog));

        LOGGER.at(Level.INFO).log("Opened merchant for %s with %d items",
                playerRef.getUsername(), catalog.size());
    }

    /**
     * Closes the merchant session for the given player.
     *
     * @param playerId the player's UUID
     */
    public void closeMerchant(@Nonnull UUID playerId) {
        MerchantSession session = openSessions.remove(playerId);
        if (session != null) {
            LOGGER.at(Level.FINE).log("Closed merchant session for %s", playerId);
        }
    }

    /**
     * Returns whether the given player has an active merchant session.
     *
     * @param playerId the player's UUID
     * @return {@code true} if a session is active
     */
    public boolean hasOpenSession(@Nonnull UUID playerId) {
        return openSessions.containsKey(playerId);
    }

    // ── Transaction Handling ─────────────────────────────────────────

    private void handleContainerChange(@Nonnull UUID playerId,
                                       @Nonnull ItemContainer.ItemContainerChangeEvent event,
                                       @Nonnull MerchantContainer container,
                                       @Nonnull List<CatalogEntry> catalog,
                                       @Nonnull PlayerRef playerRef) {
        Transaction transaction = event.transaction();
        if (!transaction.succeeded()) {
            return;
        }

        // Guard against reentrancy from slot mutations inside this handler
        if (Boolean.TRUE.equals(processingChange.get(playerId))) {
            return;
        }
        processingChange.put(playerId, true);
        try {
            handleContainerChangeInner(playerId, container, catalog, playerRef, transaction);
        } finally {
            processingChange.remove(playerId);
        }
    }

    private void handleContainerChangeInner(@Nonnull UUID playerId,
                                            @Nonnull MerchantContainer container,
                                            @Nonnull List<CatalogEntry> catalog,
                                            @Nonnull PlayerRef playerRef,
                                            @Nonnull Transaction transaction) {
        // Check each slot for changes
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            if (!transaction.wasSlotModified(slot)) {
                continue;
            }

            if (container.isBuySlot(slot)) {
                handleBuySlotChange(playerId, slot, container, catalog, playerRef);
            } else if (container.isSellSlot(slot)) {
                handleSellSlotChange(playerId, slot, container, playerRef);
            }
        }
    }

    /**
     * Handles a buy-zone slot change. If the slot was emptied (item taken),
     * deducts gold and refills the display item.
     */
    private void handleBuySlotChange(@Nonnull UUID playerId, short slot,
                                     @Nonnull MerchantContainer container,
                                     @Nonnull List<CatalogEntry> catalog,
                                     @Nonnull PlayerRef playerRef) {
        ItemStack current = container.getSlotDirect(slot);
        if (current != null) {
            // Item still in slot — no purchase happened
            return;
        }

        // Item was taken — player bought it
        if (slot >= catalog.size()) {
            return;
        }

        CatalogEntry entry = catalog.get(slot);
        long price = entry.buyPrice();
        if (price <= 0) {
            return;
        }

        // Deduct gold
        boolean deducted = goldService.removeGold(playerId, price);
        if (!deducted) {
            LOGGER.at(Level.WARNING).log("Failed to deduct %d gold from %s for purchase of %s",
                    price, playerId, entry.itemId());
            return;
        }
        long newBalance = goldService.getBalance(playerId);

        // Notify player
        playerRef.sendMessage(
                Message.raw("Bought ").color(COLOR_GRAY)
                        .insert(Message.raw(entry.itemId()).color(COLOR_GOLD))
                        .insert(Message.raw(" for " + formatGold(price)).color(COLOR_GREEN))
        );

        // Refill the buy slot with updated gold metadata
        ItemStack refillItem = createDisplayItem(entry, newBalance);
        if (refillItem != null) {
            container.addItemStackToSlot(slot, refillItem, false, false);
        }

        // Update all buy item metadata with new balance
        updateBuyItemBalances(container, catalog, newBalance);

        // Tag the bought item in the player's inventory with sell-price metadata
        long sellPrice = priceRegistry.getSellPrice(entry.itemId(), entry.level());
        tagBoughtItemInInventory(playerRef, entry.itemId(), sellPrice);
    }

    /**
     * Handles a sell-zone slot change. If an item was placed, credits gold
     * and immediately consumes the item.
     */
    private void handleSellSlotChange(@Nonnull UUID playerId, short slot,
                                      @Nonnull MerchantContainer container,
                                      @Nonnull PlayerRef playerRef) {
        ItemStack item = container.getSlotDirect(slot);
        if (item == null) {
            // Slot is empty — item was removed (player took back), nothing to do
            return;
        }

        // Item placed — player is selling
        String itemId = item.getItemId();
        int dungeonLevel = getItemDungeonLevel(item);
        long sellPrice = priceRegistry.getSellPrice(itemId, dungeonLevel);
        if (sellPrice <= 0) {
            return;
        }

        // Credit gold
        goldService.addGold(playerId, sellPrice);
        long newBalance = goldService.getBalance(playerId);

        // Consume the item from the sell slot
        container.removeItemStackFromSlot(slot);

        // Notify player
        playerRef.sendMessage(
                Message.raw("Sold ").color(COLOR_GRAY)
                        .insert(Message.raw(itemId).color(COLOR_GOLD))
                        .insert(Message.raw(" for " + formatGold(sellPrice)).color(COLOR_GREEN))
        );

        // Update buy item metadata with new balance
        MerchantSession session = openSessions.get(playerId);
        if (session != null) {
            updateBuyItemBalances(container, session.catalog(), newBalance);
        }
    }

    /**
     * Updates the gold balance metadata on all buy-zone display items so that
     * the tooltip provider shows the current balance.
     */
    private void updateBuyItemBalances(@Nonnull MerchantContainer container,
                                       @Nonnull List<CatalogEntry> catalog,
                                       long newBalance) {
        for (short i = 0; i < Math.min(catalog.size(), container.getBuyCapacity()); i++) {
            ItemStack existing = container.getSlotDirect(i);
            if (existing == null) {
                continue;
            }
            // Replace with updated gold metadata
            ItemStack updated = existing.withMetadata(META_GOLD, Codec.LONG, newBalance);
            container.setSlotDirect(i, updated);
        }
    }

    // ── Item Creation ────────────────────────────────────────────────

    /**
     * Creates a display {@link ItemStack} for a merchant buy slot, including
     * buy-price and gold-balance metadata for tooltip rendering.
     *
     * @param entry       the catalog entry
     * @param currentGold the player's current gold balance
     * @return the display item stack, or {@code null} if the item cannot be resolved
     */
    @Nullable
    private ItemStack createDisplayItem(@Nonnull CatalogEntry entry, long currentGold) {
        Item item = Item.getAssetMap().getAsset(entry.itemId());
        if (item == null) {
            LOGGER.at(Level.WARNING).log("Unknown merchant item: %s", entry.itemId());
            return null;
        }

        long buyPrice = entry.buyPrice();
        ItemStack stack = new ItemStack(item.getId(), 1);

        // Add level metadata if this is a leveled item
        // (Merchant items don't have variance — they're display templates)
        if (entry.level() > 0) {
            String levelKey = entry.itemId().startsWith("Armor_")
                    ? "zsquad_armor_level"
                    : "zsquad_weapon_level";
            stack = stack.withMetadata(levelKey, Codec.INTEGER, entry.level());
        }

        // Merchant-specific metadata for tooltip provider
        stack = stack.withMetadata(META_BUY_PRICE, Codec.LONG, buyPrice);
        stack = stack.withMetadata(META_GOLD, Codec.LONG, currentGold);

        return stack;
    }

    // ── Inventory Tagging ────────────────────────────────────────────

    /**
        * Finds the first item in the player's carried or equipped containers matching
        * the given item ID that still has buy-price metadata, strips the merchant
        * display tags, and replaces them with a sell-price tag.
     *
     * @param playerRef the player reference
     * @param itemId    the item ID to look for
     * @param sellPrice the sell price to tag
     */
    private void tagBoughtItemInInventory(@Nonnull PlayerRef playerRef,
                                          @Nonnull String itemId,
                                          long sellPrice) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;

        InventoryComponent.Hotbar hotbar = ref.getStore().getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = ref.getStore().getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armor = ref.getStore().getComponent(ref, InventoryComponent.Armor.getComponentType());
        tagFirstMatchInContainers(itemId, sellPrice,
                hotbar != null ? hotbar.getInventory() : null,
                storage != null ? storage.getInventory() : null,
                armor != null ? armor.getInventory() : null);
    }

    static void tagFirstMatchInContainers(@Nonnull String itemId,
                                          long sellPrice,
                                          @Nullable ItemContainer... containers) {
        for (ItemContainer container : containers) {
            if (container != null && tagFirstMatchInContainer(container, itemId, sellPrice)) {
                return;
            }
        }
    }

    /**
     * Scans a container for the first item matching the given item ID with buy-price
     * metadata, and replaces buy/gold metadata with sell-price metadata.
     */
    static boolean tagFirstMatchInContainer(@Nonnull ItemContainer container,
                                            @Nonnull String itemId,
                                            long sellPrice) {
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || ItemStack.isEmpty(stack)) continue;
            if (!stack.getItemId().equals(itemId)) continue;

            // Check if this item has merchant buy-price metadata (was just purchased)
            if (stack.getFromMetadataOrNull(META_BUY_PRICE, Codec.LONG) == null) continue;

            // Replace buy/gold metadata with sell-price
            ItemStack updated = stack
                    .withMetadata(META_BUY_PRICE, Codec.LONG, null)
                    .withMetadata(META_GOLD, Codec.LONG, null)
                    .withMetadata(META_SELL_PRICE, Codec.LONG, sellPrice);
            container.setItemStackForSlot(i, updated);
            return true; // Only tag one item per purchase
        }

        return false;
    }

    /**
     * Strips all merchant-specific metadata from items in the player's inventory.
     * Called when the merchant window is closed.
     *
     * @param playerId the player's UUID
     * @param ref      the player entity reference
     * @param accessor the component accessor
     */
    void cleanupInventoryMetadata(@Nonnull UUID playerId,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull ComponentAccessor<EntityStore> accessor) {
        closeMerchant(playerId);

        Player player = accessor.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        InventoryComponent.Hotbar hotbar = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = accessor.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armor = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        stripMerchantMetaFromContainers(
                hotbar != null ? hotbar.getInventory() : null,
                storage != null ? storage.getInventory() : null,
                armor != null ? armor.getInventory() : null);
    }

    static void stripMerchantMetaFromContainers(@Nullable ItemContainer... containers) {
        for (ItemContainer container : containers) {
            if (container != null) {
                stripMerchantMeta(container);
            }
        }
    }

    /**
     * Strips merchant display metadata ({@link #META_BUY_PRICE}, {@link #META_GOLD})
     * from all items in the given container. Leaves {@link #META_SELL_PRICE} intact
     * since it's useful for tooltip display outside the merchant.
     */
    static void stripMerchantMeta(@Nonnull ItemContainer container) {
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || ItemStack.isEmpty(stack)) continue;

            boolean hasBuy = stack.getFromMetadataOrNull(META_BUY_PRICE, Codec.LONG) != null;
            boolean hasGold = stack.getFromMetadataOrNull(META_GOLD, Codec.LONG) != null;
            if (!hasBuy && !hasGold) continue;

            ItemStack cleaned = stack;
            if (hasBuy) cleaned = cleaned.withMetadata(META_BUY_PRICE, Codec.LONG, null);
            if (hasGold) cleaned = cleaned.withMetadata(META_GOLD, Codec.LONG, null);
            container.setItemStackForSlot(i, cleaned);
        }
    }

    /**
     * Formats a gold amount with comma separators.
     *
     * @param gold the gold amount
     * @return the formatted string
     */
    @Nonnull
    static String formatGold(long gold) {
        return String.format("%,d Gold", gold);
    }

    /**
     * Extracts the dungeon level from an item's weapon or armor level metadata.
     *
     * @param item the item stack to inspect
     * @return the dungeon level, or {@code 0} if unleveled
     */
    private static int getItemDungeonLevel(@Nonnull ItemStack item) {
        Integer weaponLevel = GearLevelService.getWeaponLevel(item);
        if (weaponLevel != null) {
            return weaponLevel;
        }
        Integer armorLevel = GearLevelService.getArmorLevel(item);
        return armorLevel != null ? armorLevel : 0;
    }

    // ── Session Record ───────────────────────────────────────────────

    /**
     * Immutable record tracking a single merchant session.
     *
     * @param playerId  the player's UUID
     * @param container the merchant container
     * @param window    the container window
     * @param catalog   the catalog used to populate buy slots
     */
    record MerchantSession(
            @Nonnull UUID playerId,
            @Nonnull MerchantContainer container,
            @Nonnull ContainerWindow window,
            @Nonnull List<CatalogEntry> catalog
    ) {}
}
