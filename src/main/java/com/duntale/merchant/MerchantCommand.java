package com.duntale.merchant;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Random;

/**
 * Debug command that opens a test merchant window for the executing player.
 *
 * <p>Usage: {@code /merchant [--roll] [--level=15] [--randseed]}
 *
 * <p>Opens a merchant with items sourced from the merchant catalog generator,
 * capped at {@link CatalogGenerator#MAX_BUY_SLOTS} buy slots.
 */
public class MerchantCommand extends AbstractPlayerCommand {

    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";

    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_LEVEL = 15;

    private final MerchantService merchantService;
    private final CatalogGenerator catalogGenerator;

    private final FlagArg rollFlag = this.withFlagArg("roll", "Re-roll (regenerate) the merchant catalog");
    private final DefaultArg<Integer> levelArg = this.withDefaultArg("level", "Floor level for catalog generation",
            ArgTypes.INTEGER, DEFAULT_LEVEL, String.valueOf(DEFAULT_LEVEL));
    private final FlagArg randseedFlag = this.withFlagArg("randseed", "Use a random seed instead of the default");

    /** Lazily built catalog. */
    private List<CatalogEntry> catalog;
    /** Last-used level for the cached catalog. */
    private int cachedLevel = DEFAULT_LEVEL;
    /** Last-used seed for the cached catalog. */
    private long cachedSeed = DEFAULT_SEED;

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

        int level = levelArg.get(context);
        long seed = randseedFlag.get(context) ? new Random().nextLong() : DEFAULT_SEED;

        if (rollFlag.get(context)) {
            this.catalog = null;
        }

        merchantService.openMerchant(player, playerRef, ref, store, getCatalog(level, seed));
        context.sendMessage(Message.raw("Merchant opened!").color(COLOR_GREEN));
    }

    /**
     * Lazily builds the catalog using the merchant catalog generator.
     *
     * @param level the floor level for catalog generation
     * @param seed  the seed for deterministic randomization
     */
    @Nonnull
    private List<CatalogEntry> getCatalog(int level, long seed) {
        if (catalog != null && cachedLevel == level && cachedSeed == seed) {
            return catalog;
        }

        this.catalog = catalogGenerator.generate(level, seed);
        this.cachedLevel = level;
        this.cachedSeed = seed;
        return this.catalog;
    }
}
