package com.duntale.merchant;

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

    private final MerchantService merchantService;
    private final CatalogGenerator catalogGenerator;

    /** Lazily built catalog. */
    private List<CatalogEntry> catalog;

    /**
     * Creates a new /merchant debug command.
     *
     * @param merchantService  the merchant service for opening the window
     * @param catalogGenerator the catalog generator for building test catalogs
     */
    public MerchantCommand(@Nonnull MerchantService merchantService,
                           @Nonnull CatalogGenerator catalogGenerator) {
        super("merchant", "Open a test merchant window");
        this.merchantService = merchantService;
        this.catalogGenerator = catalogGenerator;
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
     * Lazily builds the catalog using the merchant catalog generator at floor level 15.
     */
    @Nonnull
    private List<CatalogEntry> getCatalog() {
        if (catalog != null) {
            return catalog;
        }

        this.catalog = catalogGenerator.generate(15, 42L);
        return this.catalog;
    }
}
