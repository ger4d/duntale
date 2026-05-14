package com.duntale.loot;

import com.duntale.rpg.RpgStatEffects;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A loot table that produces randomised {@link ItemStack} drops from a weighted pool of {@link LootEntry} items.
 *
 * <p>Each table stores a default roll count and drop chance used by the NPC loot flow,
 * while the entry pool itself remains generic enough for other callers such as chest rolls.
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
        return roll(LootContext.forNpcLevel(npcLevel), new RollRequest(rolls, dropChance, true));
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

        // Adjusted drop chance with Luck bonus
        float dropBonus = RpgStatEffects.computeLuckDropBonus(luckLevel);
        double adjustedDropChance = Math.min(1.0, dropChance + dropBonus);

        // Total rolls = base rolls + Luck bonus rolls
        int bonusRolls = RpgStatEffects.computeLuckBonusRolls(luckLevel);
        return roll(
                LootContext.forNpcLevel(npcLevel),
                new RollRequest(rolls + bonusRolls, adjustedDropChance, true)
        );
    }

    /**
     * Rolls this loot table using an explicit runtime context and selection mode.
     *
     * <p>This is used by non-NPC callers such as chest rolls, which may want fixed
     * stack counts or no-replacement selection while reusing the same weighted pool.
     *
     * @param context          the runtime loot context used for condition evaluation
     * @param requestedRolls   the number of picks to make
     * @param withReplacement  whether the same entry may be picked multiple times
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(@Nonnull LootContext context, int requestedRolls, boolean withReplacement) {
        return roll(context, new RollRequest(requestedRolls, 1.0, withReplacement));
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

    @Nonnull
    private List<ItemStack> roll(@Nonnull LootContext context, @Nonnull RollRequest request) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (request.rolls() <= 0) {
            return Collections.emptyList();
        }
        if (request.dropChance() < 1.0 && random.nextDouble() >= request.dropChance()) {
            return Collections.emptyList();
        }

        List<LootEntry> eligible = new ArrayList<>();
        double totalWeight = 0;
        for (LootEntry entry : entries) {
            if (entry.isEligible(context)) {
                eligible.add(entry);
                totalWeight += entry.weight();
            }
        }

        if (eligible.isEmpty() || totalWeight <= 0.0) {
            return Collections.emptyList();
        }

        int rollCount = request.withReplacement()
                ? request.rolls()
                : Math.min(request.rolls(), eligible.size());
        List<ItemStack> result = new ArrayList<>(rollCount);

        if (request.withReplacement()) {
            for (int index = 0; index < rollCount; index++) {
                LootEntry picked = pickWeighted(eligible, totalWeight, random);
                result.add(picked.createItemStack(random));
            }
            return Collections.unmodifiableList(result);
        }

        List<LootEntry> remaining = new ArrayList<>(eligible);
        double remainingWeight = totalWeight;
        for (int index = 0; index < rollCount && !remaining.isEmpty() && remainingWeight > 0.0; index++) {
            LootEntry picked = pickWeighted(remaining, remainingWeight, random);
            result.add(picked.createItemStack(random));
            remaining.remove(picked);
            remainingWeight -= picked.weight();
        }
        return Collections.unmodifiableList(result);
    }

    private record RollRequest(int rolls, double dropChance, boolean withReplacement) {
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
