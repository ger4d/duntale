package com.duntale.loot;

import com.duntale.rpg.RpgStatEffects;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A loot table that produces randomised {@link ItemStack} drops from weighted pools of
 * {@link LootEntry} items.
 *
 * <p>A table has two independent pools that roll separately:
 * <ul>
 *   <li>the <em>gear</em> pool ({@code entries} / {@code dropChance}, scaled by Luck), and</li>
 *   <li>the <em>gold</em> pool ({@code goldEntries} / {@code goldChance}, Luck-independent).</li>
 * </ul>
 * Rolling both independently keeps gold a steady faucet even when gear does not drop. The gold pool
 * is optional — a table created without it behaves exactly like a single gear pool, so existing
 * single-pool tables (including ones that list {@code Gold_Coin} as a gear entry) are unaffected.
 * The gear pool also stays generic enough for non-NPC callers such as chest rolls.
 */
public class LootTable {

    private final List<LootEntry> entries;
    private final int rolls;
    private final double dropChance;
    private final List<LootEntry> goldEntries;
    private final double goldChance;

    /**
     * Creates a new gear-only loot table with a guaranteed drop.
     *
     * @param entries the gear pool of possible drops
     * @param rolls   number of weighted-random rolls to perform (each roll picks one entry)
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls) {
        this(entries, rolls, 1.0);
    }

    /**
     * Creates a new gear-only loot table (no gold pool).
     *
     * @param entries    the gear pool of possible drops
     * @param rolls      number of weighted-random rolls to perform (each roll picks one entry)
     * @param dropChance probability (0.0–1.0) that the gear pool produces any loot at all;
     *                   checked once before rolling. A value of {@code 1.0} means always drops.
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls, double dropChance) {
        this(entries, rolls, dropChance, List.of(), 0.0);
    }

    /**
     * Creates a new loot table with independent gear and gold pools.
     *
     * @param entries     the gear pool of possible drops
     * @param rolls       number of weighted-random rolls to perform on the gear pool
     * @param dropChance  probability (0.0–1.0) that the gear pool produces any loot at all
     * @param goldEntries the gold pool of possible drops (rolled once); may be empty
     * @param goldChance  probability (0.0–1.0) that the gold pool produces any loot at all
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls, double dropChance,
                     @Nonnull List<LootEntry> goldEntries, double goldChance) {
        this.entries = List.copyOf(entries);
        this.rolls = Math.max(rolls, 0);
        this.dropChance = Math.clamp(dropChance, 0.0, 1.0);
        this.goldEntries = List.copyOf(goldEntries);
        this.goldChance = Math.clamp(goldChance, 0.0, 1.0);
    }

    /**
     * Rolls both pools (gear with no Luck, then gold) and returns their combined drops.
     *
     * @param npcLevel the dying NPC's dungeon level
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(int npcLevel) {
        return roll(npcLevel, 0);
    }

    /**
     * Rolls both pools independently and returns their combined drops.
     *
     * <p>The gear pool's drop chance is boosted by Luck via
     * {@link RpgStatEffects#computeLuckDropChance(double, int)}; the gold pool is Luck-independent so
     * the gold faucet stays steady. Each pool is gated by its own chance, so a kill may yield gear,
     * gold, both, or nothing.
     *
     * @param npcLevel  the dying NPC's dungeon level
     * @param luckLevel the attacker's Luck stat level (0 = no bonus)
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(int npcLevel, int luckLevel) {
        List<ItemStack> gear = rollGear(npcLevel, luckLevel);
        List<ItemStack> gold = rollGold(npcLevel);
        if (gold.isEmpty()) {
            return gear;
        }
        if (gear.isEmpty()) {
            return gold;
        }
        List<ItemStack> combined = new ArrayList<>(gear.size() + gold.size());
        combined.addAll(gear);
        combined.addAll(gold);
        return Collections.unmodifiableList(combined);
    }

    /**
     * Rolls the gear pool only, applying the Luck drop-chance bonus.
     *
     * @param npcLevel  the dying NPC's dungeon level
     * @param luckLevel the attacker's Luck stat level (0 = base chance, no bonus)
     * @return an unmodifiable list of rolled gear drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> rollGear(int npcLevel, int luckLevel) {
        double effectiveChance = luckLevel > 0
                ? RpgStatEffects.computeLuckDropChance(dropChance, luckLevel)
                : dropChance;
        return roll(entries, LootContext.forNpcLevel(npcLevel), new RollRequest(rolls, effectiveChance, true));
    }

    /**
     * Rolls the gold pool only (a single roll at {@link #goldChance}, Luck-independent).
     *
     * @param npcLevel the dying NPC's dungeon level
     * @return an unmodifiable list of rolled gold drops (may be empty, including when no gold pool exists)
     */
    @Nonnull
    public List<ItemStack> rollGold(int npcLevel) {
        if (goldEntries.isEmpty()) {
            return Collections.emptyList();
        }
        return roll(goldEntries, LootContext.forNpcLevel(npcLevel), new RollRequest(1, goldChance, true));
    }

    /**
     * Rolls the gear pool using an explicit runtime context and selection mode.
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
        return roll(entries, context, new RollRequest(requestedRolls, 1.0, withReplacement));
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
    private List<ItemStack> roll(@Nonnull List<LootEntry> pool, @Nonnull LootContext context, @Nonnull RollRequest request) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (request.rolls() <= 0) {
            return Collections.emptyList();
        }
        if (request.dropChance() < 1.0 && random.nextDouble() >= request.dropChance()) {
            return Collections.emptyList();
        }

        List<LootEntry> eligible = new ArrayList<>();
        double totalWeight = 0;
        for (LootEntry entry : pool) {
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
     * Returns the configured number of gear-pool rolls.
     *
     * @return the roll count
     */
    public int getRolls() {
        return rolls;
    }

    /**
     * Returns the gear-pool drop chance probability (0.0–1.0).
     *
     * @return the gear drop chance
     */
    public double getDropChance() {
        return dropChance;
    }

    /**
     * Returns the gold-pool drop chance probability (0.0–1.0).
     *
     * @return the gold drop chance
     */
    public double getGoldChance() {
        return goldChance;
    }

    /**
     * Returns an unmodifiable view of this table's gear entries.
     *
     * @return the gear loot entries
     */
    @Nonnull
    public List<LootEntry> getEntries() {
        return entries;
    }

    /**
     * Returns an unmodifiable view of this table's gold entries.
     *
     * @return the gold loot entries (may be empty)
     */
    @Nonnull
    public List<LootEntry> getGoldEntries() {
        return goldEntries;
    }
}
