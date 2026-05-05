package com.duntale.zsquad.loot;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.ChestDefinition;
import com.duntale.dungeongen.model.ChestTier;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls and applies dungeon chest loot using the shared loot table registry.
 */
public class ChestLootService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final LootTableRegistry lootTableRegistry;

    /**
     * Creates a new chest loot service.
     *
     * @param lootTableRegistry the shared loot table registry
     */
    public ChestLootService(@Nonnull LootTableRegistry lootTableRegistry) {
        this.lootTableRegistry = Objects.requireNonNull(lootTableRegistry, "lootTableRegistry");
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
     * Fills generated dungeon chests in-place on the world thread.
     *
     * @param world            the target dungeon world
     * @param origin           dungeon origin used to translate blueprint-relative positions
     * @param chestDefinitions generated chest definitions to fill
     * @param floorLevel       the current dungeon floor level
     */
    public void fillChests(@Nonnull World world,
                           @Nonnull Vec3i origin,
                           @Nonnull List<ChestDefinition> chestDefinitions,
                           int floorLevel) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(chestDefinitions, "chestDefinitions");

        for (ChestDefinition chest : chestDefinitions) {
            Vec3i absolutePosition = translatePosition(origin, chest);
            Ref<ChunkStore> blockEntity = resolveBlockEntity(world, absolutePosition);
            if (blockEntity == null) {
                LOGGER.atWarning().log(
                        "Missing chest block entity for %s at %d,%d,%d in world %s",
                        chest.blockId(),
                        absolutePosition.x(),
                        absolutePosition.y(),
                        absolutePosition.z(),
                        world.getName()
                );
                continue;
            }

            ItemContainerBlock container = blockEntity.getStore().getComponent(
                    blockEntity,
                    ItemContainerBlock.getComponentType()
            );
            if (container == null) {
                LOGGER.atWarning().log(
                        "Block %s at %d,%d,%d has no ItemContainerBlock in world %s",
                        chest.blockId(),
                        absolutePosition.x(),
                        absolutePosition.y(),
                        absolutePosition.z(),
                        world.getName()
                );
                continue;
            }

            List<ItemStack> rolledLoot = roll(chest.tier(), floorLevel);
            container.setDroplist(null);
            container.getItemContainer().clear();
            if (!rolledLoot.isEmpty()) {
                container.getItemContainer().addItemStacks(rolledLoot);
            }
        }
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