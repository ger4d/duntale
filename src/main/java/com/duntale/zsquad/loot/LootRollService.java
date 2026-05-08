package com.duntale.zsquad.loot;

import com.duntale.zsquad.progression.CombatScaling;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
        return roll(roleName, CombatScaling.NpcVariant.NORMAL, npcLevel, luckLevel);
    }

    /**
     * Rolls loot for a given role name, NPC variant, NPC level, and Luck value.
     *
     * <p>Variant-specific tables are resolved first using the naming convention
     * {@code <RoleName>_Elite} or {@code <RoleName>_Boss}. If no variant table exists,
     * the base role table is used as a fallback.
     *
     * @param roleName  the loot table key to roll
     * @param variant   the NPC variant used for variant-specific table resolution
     * @param npcLevel  the NPC level used for entry eligibility and gold scaling
     * @param luckLevel the attacker Luck level used for bonus rolls and drop chance
     * @return an immutable list of rolled drops, or an empty list when no table exists or nothing drops
     */
    @Nonnull
    public List<ItemStack> roll(@Nonnull String roleName,
                                @Nonnull CombatScaling.NpcVariant variant,
                                int npcLevel,
                                int luckLevel) {
        LootTable lootTable = resolveLootTable(roleName, variant);
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
        return hasTable(roleName, CombatScaling.NpcVariant.NORMAL);
    }

    /**
     * Returns whether a loot table exists for the given role and variant.
     *
     * @param roleName the role name to test
     * @param variant  the NPC variant used for variant-specific lookup
     * @return {@code true} when a table can be resolved
     */
    public boolean hasTable(@Nonnull String roleName, @Nonnull CombatScaling.NpcVariant variant) {
        return resolveTableId(roleName, variant) != null;
    }

    @Nonnull
    private ItemStack scaleGold(@Nonnull ItemStack drop, int npcLevel) {
        if (npcLevel > 1 && "Gold_Coin".equals(drop.getItemId())) {
            return new ItemStack(drop.getItemId(), drop.getQuantity() * npcLevel);
        }
        return drop;
    }

    @Nullable
    private LootTable resolveLootTable(@Nonnull String roleName, @Nonnull CombatScaling.NpcVariant variant) {
        String resolvedTableId = resolveTableId(roleName, variant);
        if (resolvedTableId == null) {
            return null;
        }
        return lootTableRegistry.get(resolvedTableId);
    }

    @Nullable
    private String resolveTableId(@Nonnull String roleName, @Nonnull CombatScaling.NpcVariant variant) {
        if (variant != CombatScaling.NpcVariant.NORMAL) {
            String variantSuffix = variantSuffix(variant);
            String titleCaseId = roleName + "_" + variantSuffix;
            if (lootTableRegistry.has(titleCaseId)) {
                return titleCaseId;
            }

            String enumCaseId = roleName + "_" + variant.name();
            if (lootTableRegistry.has(enumCaseId)) {
                return enumCaseId;
            }
        }

        if (lootTableRegistry.has(roleName)) {
            return roleName;
        }
        return null;
    }

    @Nonnull
    private static String variantSuffix(@Nonnull CombatScaling.NpcVariant variant) {
        return switch (variant) {
            case ELITE -> "Elite";
            case BOSS -> "Boss";
            case NORMAL -> "Normal";
        };
    }
}