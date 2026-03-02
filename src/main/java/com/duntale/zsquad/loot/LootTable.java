package com.duntale.zsquad.loot;

import com.duntale.zsquad.progression.GearLevelService;
import com.duntale.zsquad.rpg.RpgStatEffects;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A loot table that produces randomised {@link ItemStack} drops from a weighted pool of {@link LootEntry} items.
 *
 * <p>Each table has a configurable number of rolls. Entries are filtered by the NPC's level
 * before rolling, so level-gated items are excluded automatically.
 *
 * <p>Supports two entry types:
 * <ul>
 *   <li>{@link LootEntry.Simple} — plain items (quantity only).</li>
 *   <li>{@link LootEntry.Leveled} — weapons/armor stamped with gear level + variance
 *       via {@link GearLevelService}.</li>
 * </ul>
 */
public class LootTable {

    private final List<LootEntry> entries;
    private final int rolls;
    private final double dropChance;

    /**
     * Creates a new loot table with a guaranteed drop.
     *
     * @param entries the pool of possible drops
     * @param rolls   number of weighted-random rolls to perform (each roll picks one entry)
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls) {
        this(entries, rolls, 1.0);
    }

    /**
     * Creates a new loot table.
     *
     * @param entries    the pool of possible drops
     * @param rolls      number of weighted-random rolls to perform (each roll picks one entry)
     * @param dropChance probability (0.0–1.0) that this table produces any loot at all;
     *                   checked once before rolling. A value of {@code 1.0} means always drops.
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls, double dropChance) {
        this.entries = List.copyOf(entries);
        this.rolls = Math.max(rolls, 0);
        this.dropChance = Math.clamp(dropChance, 0.0, 1.0);
    }

    /**
     * Rolls this loot table and produces a list of item stacks to drop.
     *
     * <p>First checks {@link #dropChance} — if the random check fails, returns an empty list.
     * Then entries whose level gate excludes the given {@code npcLevel} are skipped.
     * Duplicate item IDs from multiple rolls are <em>not</em> merged — the caller
     * may merge them if desired.
     *
     * @param npcLevel the dying NPC's dungeon level
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(int npcLevel) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Drop chance gate — checked once per kill
        if (dropChance < 1.0 && random.nextDouble() >= dropChance) {
            return Collections.emptyList();
        }

        // Filter eligible entries
        List<LootEntry> eligible = new ArrayList<>();
        double totalWeight = 0;
        for (LootEntry entry : entries) {
            if (entry.isEligible(npcLevel)) {
                eligible.add(entry);
                totalWeight += entry.weight();
            }
        }

        if (eligible.isEmpty() || totalWeight <= 0) {
            return Collections.emptyList();
        }

        List<ItemStack> result = new ArrayList<>(rolls);

        for (int i = 0; i < rolls; i++) {
            LootEntry picked = pickWeighted(eligible, totalWeight, random);
            ItemStack stack = createItemStack(picked, random);
            if (stack != null) {
                result.add(stack);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Rolls this loot table with Luck bonus applied.
     *
     * <p>Luck provides two bonuses:
     * <ul>
     *   <li>Drop chance bonus: increases the base drop chance</li>
     *   <li>Bonus rolls: additional rolls on the loot table</li>
     * </ul>
     *
     * @param npcLevel  the dying NPC's dungeon level
     * @param luckLevel the attacker's Luck stat level (0 = no bonus)
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(int npcLevel, int luckLevel) {
        if (luckLevel <= 0) {
            return roll(npcLevel);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Adjusted drop chance with Luck bonus
        float dropBonus = RpgStatEffects.computeLuckDropBonus(luckLevel);
        double adjustedDropChance = Math.min(1.0, dropChance + dropBonus);

        if (adjustedDropChance < 1.0 && random.nextDouble() >= adjustedDropChance) {
            return Collections.emptyList();
        }

        // Filter eligible entries
        List<LootEntry> eligible = new ArrayList<>();
        double totalWeight = 0;
        for (LootEntry entry : entries) {
            if (entry.isEligible(npcLevel)) {
                eligible.add(entry);
                totalWeight += entry.weight();
            }
        }

        if (eligible.isEmpty() || totalWeight <= 0) {
            return Collections.emptyList();
        }

        // Total rolls = base rolls + Luck bonus rolls
        int bonusRolls = RpgStatEffects.computeLuckBonusRolls(luckLevel);
        int totalRolls = rolls + bonusRolls;

        List<ItemStack> result = new ArrayList<>(totalRolls);

        for (int i = 0; i < totalRolls; i++) {
            LootEntry picked = pickWeighted(eligible, totalWeight, random);
            ItemStack stack = createItemStack(picked, random);
            if (stack != null) {
                result.add(stack);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Creates an {@link ItemStack} from a picked {@link LootEntry}, applying gear metadata
     * for leveled entries.
     */
    @Nonnull
    private static ItemStack createItemStack(@Nonnull LootEntry entry, @Nonnull ThreadLocalRandom random) {
        return switch (entry) {
            case LootEntry.Simple simple -> {
                int quantity = simple.quantityMin() == simple.quantityMax()
                        ? simple.quantityMin()
                        : random.nextInt(simple.quantityMin(), simple.quantityMax() + 1);
                yield new ItemStack(simple.itemId(), Math.max(quantity, 1));
            }
            case LootEntry.Leveled leveled -> {
                int gearLevel = leveled.gearLevelMin() == leveled.gearLevelMax()
                        ? leveled.gearLevelMin()
                        : random.nextInt(leveled.gearLevelMin(), leveled.gearLevelMax() + 1);
                float variance = GearLevelService.rollVariance();

                ItemStack stack = new ItemStack(leveled.itemId(), 1);
                stack = switch (leveled.gearType()) {
                    case WEAPON -> {
                        ItemStack s = GearLevelService.setWeaponLevel(stack, gearLevel);
                        yield GearLevelService.setWeaponVariance(s, variance);
                    }
                    case ARMOR -> {
                        ItemStack s = GearLevelService.setArmorLevel(stack, gearLevel);
                        yield GearLevelService.setArmorVariance(s, variance);
                    }
                };
                yield stack;
            }
        };
    }

    /**
     * Picks a single entry using weighted random selection.
     */
    @Nonnull
    private static LootEntry pickWeighted(
            @Nonnull List<LootEntry> eligible,
            double totalWeight,
            @Nonnull ThreadLocalRandom random
    ) {
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (LootEntry entry : eligible) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        // Floating-point edge case — return last entry
        return eligible.getLast();
    }

    /**
     * Returns the configured number of rolls.
     *
     * @return the roll count
     */
    public int getRolls() {
        return rolls;
    }

    /**
     * Returns the drop chance probability (0.0–1.0).
     *
     * @return the drop chance
     */
    public double getDropChance() {
        return dropChance;
    }

    /**
     * Returns an unmodifiable view of this table's entries.
     *
     * @return the loot entries
     */
    @Nonnull
    public List<LootEntry> getEntries() {
        return entries;
    }
}
