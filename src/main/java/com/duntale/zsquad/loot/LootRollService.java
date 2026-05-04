package com.duntale.zsquad.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared loot roll service used by NPC death handling and developer tooling.
 */
public class LootRollService {

    private final LootTableRegistry lootTableRegistry;

    /**
     * Creates a new shared loot roll service.
     *
     * @param lootTableRegistry the loot table registry used for lookups
     */
    public LootRollService(@Nonnull LootTableRegistry lootTableRegistry) {
        this.lootTableRegistry = lootTableRegistry;
    }

    /**
     * Rolls loot for a given role name, NPC level, and Luck value.
     *
     * @param roleName  the loot table key to roll
     * @param npcLevel  the NPC level used for entry eligibility and gold scaling
     * @param luckLevel the attacker Luck level used for bonus rolls and drop chance
     * @return an immutable list of rolled drops, or an empty list when no table exists or nothing drops
     */
    @Nonnull
    public List<ItemStack> roll(@Nonnull String roleName, int npcLevel, int luckLevel) {
        LootTable lootTable = lootTableRegistry.get(roleName);
        if (lootTable == null) {
            return List.of();
        }

        List<ItemStack> rolledDrops = lootTable.roll(npcLevel, luckLevel);
        if (rolledDrops.isEmpty()) {
            return List.of();
        }

        List<ItemStack> scaledDrops = new ArrayList<>(rolledDrops.size());
        for (ItemStack drop : rolledDrops) {
            scaledDrops.add(scaleGold(drop, npcLevel));
        }
        return List.copyOf(scaledDrops);
    }

    /**
     * Returns whether a loot table exists for the given role name.
     *
     * @param roleName the role name to test
     * @return {@code true} when a table can be resolved
     */
    public boolean hasTable(@Nonnull String roleName) {
        return lootTableRegistry.has(roleName);
    }

    @Nonnull
    private ItemStack scaleGold(@Nonnull ItemStack drop, int npcLevel) {
        if (npcLevel > 1 && "Gold_Coin".equals(drop.getItemId())) {
            return new ItemStack(drop.getItemId(), drop.getQuantity() * npcLevel);
        }
        return drop;
    }
}