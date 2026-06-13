package com.duntale.loot;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.ChestDefinition;
import com.duntale.dungeongen.model.ChestTier;
import com.duntale.items.CustomItems;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls and applies dungeon chest loot using the shared loot table registry.
 *
 * <p>Chests are no longer pre-filled at generation: their positions are registered per world via
 * {@link #registerChests}, and the first player to open one triggers {@link #rollAndFill}, which
 * rolls the loot with that opener's Luck (rarity promotion), stamps rarity/attributes, and may
 * inject a custom big-ticket item into premium chests. The container being non-empty afterward is
 * the filled-once guard, so it survives relog without a persistent flag.
 */
public class ChestLootService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Custom big-ticket items eligible for injection into Golden/Legendary chests. */
    private static final List<String> BIG_TICKET_POOL = List.of(
            CustomItems.SPEED_BOOTS_III,
            CustomItems.HEALING_NECKLACE_II,
            CustomItems.IMMUNITY_TRAP_RING,
            CustomItems.VAMPIRE_JUICE,
            CustomItems.STAT_POINT_TOKEN
    );

    /** Big-ticket injection chance at the floor floor (level 10) and ceiling (level 50). */
    private static final double BIG_TICKET_CHANCE_LOW = 0.005;
    private static final double BIG_TICKET_CHANCE_HIGH = 0.03;
    private static final int BIG_TICKET_FLOOR_LOW = 10;
    private static final int BIG_TICKET_FLOOR_HIGH = 50;

    private final LootTableRegistry lootTableRegistry;

    @Nullable
    private final RarityRollService rarityRollService;

    /** Per-world map of registered, not-yet-opened dungeon chest positions to their roll context. */
    private final Map<String, Map<Vec3i, ChestRegistration>> pendingChests = new ConcurrentHashMap<>();

    /**
     * Creates a chest loot service without rarity stamping (used by tests and the roll-only path).
     *
     * @param lootTableRegistry the shared loot table registry
     */
    public ChestLootService(@Nonnull LootTableRegistry lootTableRegistry) {
        this(lootTableRegistry, null);
    }

    /**
     * Creates a new chest loot service.
     *
     * @param lootTableRegistry the shared loot table registry
     * @param rarityRollService the rarity roll service, or {@code null} to skip rarity stamping
     */
    public ChestLootService(@Nonnull LootTableRegistry lootTableRegistry,
                            @Nullable RarityRollService rarityRollService) {
        this.lootTableRegistry = Objects.requireNonNull(lootTableRegistry, "lootTableRegistry");
        this.rarityRollService = rarityRollService;
    }

    /** Registered roll context for a dungeon chest awaiting its first open. */
    private record ChestRegistration(@Nonnull ChestTier tier, int floorLevel) {
    }

    /**
     * Rolls one chest reward list for the given tier and floor level.
     *
     * @param tier       the chest tier to resolve
     * @param floorLevel the current dungeon floor level
     * @return rolled chest contents, or an empty list when no table exists
     */
    @Nonnull
    public List<ItemStack> roll(@Nonnull ChestTier tier, int floorLevel) {
        Objects.requireNonNull(tier, "tier");

        String tableId = resolveTableId(tier);
        LootTable lootTable = lootTableRegistry.get(tableId);
        if (lootTable == null) {
            LOGGER.atWarning().log("Missing chest loot table for tier %s (%s)", tier, tableId);
            return List.of();
        }

        return lootTable.roll(
                LootContext.forFloorLevel(floorLevel),
                resolveRollCount(tier),
                false
        );
    }

    /**
     * Registers generated dungeon chest positions so they fill on first open instead of up front.
     *
     * @param world            the target dungeon world
     * @param origin           dungeon origin used to translate blueprint-relative positions
     * @param chestDefinitions generated chest definitions to register
     * @param floorLevel       the current dungeon floor level
     */
    public void registerChests(@Nonnull World world,
                               @Nonnull Vec3i origin,
                               @Nonnull List<ChestDefinition> chestDefinitions,
                               int floorLevel) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(chestDefinitions, "chestDefinitions");

        Map<Vec3i, ChestRegistration> worldChests =
                pendingChests.computeIfAbsent(world.getName(), key -> new ConcurrentHashMap<>());
        for (ChestDefinition chest : chestDefinitions) {
            worldChests.put(translatePosition(origin, chest), new ChestRegistration(chest.tier(), floorLevel));
        }
        LOGGER.atInfo().log("Registered %d dungeon chest(s) in world %s (floor %d) for on-open fill",
                chestDefinitions.size(), world.getName(), floorLevel);
    }

    /**
     * Forgets every registered chest in a world (call on dungeon teardown to release memory).
     *
     * @param worldName the dungeon world name
     */
    public void forgetWorld(@Nonnull String worldName) {
        pendingChests.remove(worldName);
    }

    /**
     * Rolls and fills a registered chest the first time it is opened, applying the opener's Luck to
     * rarity promotion. A no-op for unregistered blocks or chests already filled (non-empty
     * container), so re-opens and multiplayer races leave existing contents intact.
     *
     * <p>Must run on the world thread.
     *
     * @param world     the dungeon world
     * @param x         the target block X
     * @param y         the target block Y
     * @param z         the target block Z
     * @param openerLuck the opening player's effective Luck level
     * @return {@code true} when this call rolled and filled the chest
     */
    public boolean rollAndFill(@Nonnull World world, int x, int y, int z, int openerLuck) {
        Map<Vec3i, ChestRegistration> worldChests = pendingChests.get(world.getName());
        if (worldChests == null) {
            return false;
        }
        Vec3i position = new Vec3i(x, y, z);
        ChestRegistration registration = worldChests.get(position);
        if (registration == null) {
            return false;
        }

        Ref<ChunkStore> blockEntity = resolveBlockEntity(world, position);
        if (blockEntity == null) {
            return false;
        }
        ItemContainerBlock container = blockEntity.getStore().getComponent(
                blockEntity, ItemContainerBlock.getComponentType());
        if (container == null) {
            return false;
        }
        if (!container.getItemContainer().isEmpty()) {
            // Already filled (re-open) — drop the registration and leave it.
            worldChests.remove(position);
            return false;
        }

        // Commit this opener as the filler before rolling so concurrent openers
        // see a missing registration and return false instead of overwriting.
        worldChests.remove(position);

        List<ItemStack> rolledLoot = new ArrayList<>(roll(registration.tier(), registration.floorLevel()));
        if (rarityRollService != null) {
            rolledLoot = new ArrayList<>(rarityRollService.applyToGearDrops(
                    rolledLoot, RaritySource.forChestTier(registration.tier()),
                    openerLuck, registration.floorLevel()));
        }
        injectBigTicket(rolledLoot, registration.tier(), registration.floorLevel());

        container.setDroplist(null);
        container.getItemContainer().clear();
        if (!rolledLoot.isEmpty()) {
            container.getItemContainer().addItemStacks(rolledLoot);
        }
        LOGGER.atInfo().log("Filled %s chest at %d,%d,%d (floor %d, openerLuck %d) with %d item(s)",
                registration.tier(), x, y, z, registration.floorLevel(), openerLuck, rolledLoot.size());
        return true;
    }

    /**
     * Injects a single custom big-ticket item into a Golden/Legendary chest at a floor-scaled
     * chance. Regular and Epic chests are never eligible.
     */
    private static void injectBigTicket(@Nonnull List<ItemStack> loot, @Nonnull ChestTier tier, int floorLevel) {
        if (tier != ChestTier.GOLDEN && tier != ChestTier.LEGENDARY) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= bigTicketChance(floorLevel)) {
            return;
        }
        String itemId = BIG_TICKET_POOL.get(ThreadLocalRandom.current().nextInt(BIG_TICKET_POOL.size()));
        loot.add(new ItemStack(itemId, 1));
    }

    private static double bigTicketChance(int floorLevel) {
        if (floorLevel <= BIG_TICKET_FLOOR_LOW) {
            return BIG_TICKET_CHANCE_LOW;
        }
        if (floorLevel >= BIG_TICKET_FLOOR_HIGH) {
            return BIG_TICKET_CHANCE_HIGH;
        }
        double t = (double) (floorLevel - BIG_TICKET_FLOOR_LOW) / (BIG_TICKET_FLOOR_HIGH - BIG_TICKET_FLOOR_LOW);
        return BIG_TICKET_CHANCE_LOW + t * (BIG_TICKET_CHANCE_HIGH - BIG_TICKET_CHANCE_LOW);
    }

    @Nonnull
    private static Vec3i translatePosition(@Nonnull Vec3i origin, @Nonnull ChestDefinition chest) {
        return new Vec3i(
                origin.x() + chest.x(),
                origin.y() + chest.y(),
                origin.z() + chest.z()
        );
    }

    @Nullable
    private static Ref<ChunkStore> resolveBlockEntity(@Nonnull World world, @Nonnull Vec3i position) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(position.x(), position.z()));
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockComponentEntity(position.x(), position.y(), position.z());
    }

    @Nonnull
    private static String resolveTableId(@Nonnull ChestTier tier) {
        return switch (tier) {
            case REGULAR -> "Chest_Regular";
            case GOLDEN -> "Chest_Golden";
            case EPIC -> "Chest_Epic";
            case LEGENDARY -> "Chest_Legendary";
        };
    }

    private static int resolveRollCount(@Nonnull ChestTier tier) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return switch (tier) {
            case REGULAR -> random.nextInt(3, 6);
            case GOLDEN -> random.nextInt(2, 5);
            case EPIC -> random.nextInt(1, 4);
            case LEGENDARY -> 1;
        };
    }
}