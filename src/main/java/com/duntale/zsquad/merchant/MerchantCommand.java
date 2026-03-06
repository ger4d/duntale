package com.duntale.zsquad.merchant;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Debug command that opens a test merchant window for the executing player.
 *
 * <p>Usage: {@code /merchant}
 *
 * <p>Opens a merchant with items sourced from the {@link MerchantPriceRegistry}.
 * Items are sorted by buy price (cheapest first) and capped at 24 slots to fit
 * the merchant container UI.
 */
public class MerchantCommand extends AbstractPlayerCommand {

    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";

    /** Maximum items shown in the merchant buy zone. */
    private static final int MAX_CATALOG_SIZE = 24;

    private final MerchantService merchantService;
    private final MerchantPriceRegistry priceRegistry;

    /** Lazily built catalog from the price registry. */
    private List<CatalogEntry> catalog;

    /**
     * Creates a new /merchant debug command.
     *
     * @param merchantService the merchant service for opening the window
     * @param priceRegistry   the price registry for building the catalog
     */
    public MerchantCommand(@Nonnull MerchantService merchantService,
                           @Nonnull MerchantPriceRegistry priceRegistry) {
        super("merchant", "Open a test merchant window");
        this.merchantService = merchantService;
        this.priceRegistry = priceRegistry;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve player.").color(COLOR_RED));
            return;
        }

        merchantService.openMerchant(player, playerRef, ref, store, getCatalog());
        context.sendMessage(Message.raw("Merchant opened!").color(COLOR_GREEN));
    }

    /**
     * Lazily builds the catalog from all priced items, sorted by price ascending.
     */
    @Nonnull
    private List<CatalogEntry> getCatalog() {
        if (catalog != null) {
            return catalog;
        }

        List<CatalogEntry> entries = new ArrayList<>();
        for (String itemId : priceRegistry.getItemIds()) {
            entries.add(CatalogEntry.of(itemId));
        }

        // Sort by buy price so cheapest items appear first
        entries.sort(Comparator.comparingLong(e -> priceRegistry.getBuyPrice(e.itemId())));

        // Cap to avoid oversized container
        if (entries.size() > MAX_CATALOG_SIZE) {
            entries = new ArrayList<>(entries.subList(0, MAX_CATALOG_SIZE));
        }

        this.catalog = List.copyOf(entries);
        return this.catalog;
    }
}
