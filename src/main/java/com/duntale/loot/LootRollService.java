package com.duntale.loot;

import com.duntale.merchant.MerchantPriceRegistry;
import com.duntale.progression.CombatScaling;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared loot roll service used by NPC death handling and developer tooling.
 *
 * <p>After rolling the raw drops, leveled gear is post-processed through {@link RarityRollService}
 * (the NPC variant selects the base-rarity ladder; the killer's Luck drives promotion), and BOSS
 * gold rewards are scaled to a fraction of a representative top-rarity on-level gear value. Both
 * extensions are inert when their collaborators are absent, so the roll-only path is unchanged.
 */
public class LootRollService {

    /** Boss gold reward as a fraction of a top-rarity on-level gear value. */
    private static final double BOSS_GOLD_FRACTION = 0.5;

    /** Gold value of a single {@code Gold_Coin} (the coin quantity equals its gold worth). */
    private static final long GOLD_UNIT_VALUE = 1L;

    private final LootTableRegistry lootTableRegistry;

    @Nullable
    private final RarityRollService rarityRollService;
    @Nullable
    private final RarityRegistry rarityRegistry;
    @Nullable
    private final MerchantPriceRegistry merchantPriceRegistry;

    /**
     * Creates a roll-only service without rarity stamping or the boss gold reward (tests, tooling).
     *
     * @param lootTableRegistry the loot table registry used for lookups
     */
    public LootRollService(@Nonnull LootTableRegistry lootTableRegistry) {
        this(lootTableRegistry, null, null, null);
    }

    /**
     * Creates a new shared loot roll service.
     *
     * @param lootTableRegistry     the loot table registry used for lookups
     * @param rarityRollService     the rarity roll service, or {@code null} to skip rarity stamping
     * @param rarityRegistry        the rarity registry, or {@code null} to skip the boss gold reward
     * @param merchantPriceRegistry the price registry, or {@code null} to skip the boss gold reward
     */
    public LootRollService(@Nonnull LootTableRegistry lootTableRegistry,
                           @Nullable RarityRollService rarityRollService,
                           @Nullable RarityRegistry rarityRegistry,
                           @Nullable MerchantPriceRegistry merchantPriceRegistry) {
        this.lootTableRegistry = lootTableRegistry;
        this.rarityRollService = rarityRollService;
        this.rarityRegistry = rarityRegistry;
        this.merchantPriceRegistry = merchantPriceRegistry;
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

        List<ItemStack> rolledDrops = rollDrops(lootTable, variant, npcLevel, luckLevel);
        if (rolledDrops.isEmpty()) {
            return List.of();
        }

        List<ItemStack> scaledDrops = new ArrayList<>(rolledDrops.size());
        for (ItemStack drop : rolledDrops) {
            scaledDrops.add(scaleGold(drop, variant, npcLevel));
        }

        if (rarityRollService != null) {
            return rarityRollService.applyToGearDrops(
                    scaledDrops, RaritySource.forNpcVariant(variant), luckLevel, npcLevel);
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

    /**
     * Composes the raw drops for a table according to the variant's faucet policy.
     *
     * <p>NORMAL and ELITE roll the gear and gold pools independently (so gold stays a steady faucet
     * alongside occasional gear). BOSS is exclusive: it yields gear, or — only when no gear drops —
     * a single gold reward instead, preserving the "gold instead of gear" boss payout. The gold
     * stack is later upgraded to the boss reward amount by {@link #scaleGold}.
     */
    @Nonnull
    private List<ItemStack> rollDrops(@Nonnull LootTable table, @Nonnull CombatScaling.NpcVariant variant,
                                      int npcLevel, int luckLevel) {
        if (variant == CombatScaling.NpcVariant.BOSS) {
            List<ItemStack> gear = table.rollGear(npcLevel, luckLevel);
            return gear.isEmpty() ? table.rollGold(npcLevel) : gear;
        }
        return table.roll(npcLevel, luckLevel);
    }

    @Nonnull
    private ItemStack scaleGold(@Nonnull ItemStack drop, @Nonnull CombatScaling.NpcVariant variant, int npcLevel) {
        if (!"Gold_Coin".equals(drop.getItemId())) {
            return drop;
        }
        if (variant == CombatScaling.NpcVariant.BOSS && rarityRegistry != null && merchantPriceRegistry != null) {
            return bossGold(npcLevel);
        }
        if (npcLevel > 1) {
            return new ItemStack(drop.getItemId(), drop.getQuantity() * npcLevel);
        }
        return drop;
    }

    /**
     * Rolls a boss gold reward sized at {@link #BOSS_GOLD_FRACTION} of a representative top-rarity
     * on-level gear value.
     */
    @Nonnull
    private ItemStack bossGold(int npcLevel) {
        Rarity topRarity = rarityRegistry.topRarity(RaritySource.BOSS);
        long referenceValue = merchantPriceRegistry.referenceGearValue(npcLevel, topRarity);
        long quantity = Math.round(BOSS_GOLD_FRACTION * referenceValue / GOLD_UNIT_VALUE);
        int clamped = (int) Math.clamp(quantity, 1L, Integer.MAX_VALUE);
        return new ItemStack("Gold_Coin", clamped);
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