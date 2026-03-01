package com.duntale.zsquad.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A loot table that produces randomised {@link ItemStack} drops from a weighted pool of {@link LootEntry} items.
 *
 * <p>Each table has a configurable number of rolls and can optionally include a guaranteed drop
 * (always added regardless of rolls).
 *
 * <p>Entries are filtered by the NPC's level before rolling, so level-gated items are excluded
 * automatically.
 */
public class LootTable {

    private final List<LootEntry> entries;
    private final int rolls;

    /**
     * Creates a new loot table.
     *
     * @param entries the pool of possible drops
     * @param rolls   number of weighted-random rolls to perform (each roll picks one entry)
     */
    public LootTable(@Nonnull List<LootEntry> entries, int rolls) {
        this.entries = List.copyOf(entries);
        this.rolls = Math.max(rolls, 0);
    }

    /**
     * Rolls this loot table and produces a list of item stacks to drop.
     *
     * <p>Entries whose level gate excludes the given {@code npcLevel} are skipped.
     * Duplicate item IDs from multiple rolls are <em>not</em> merged — the caller
     * may merge them if desired.
     *
     * @param npcLevel the dying NPC's dungeon level
     * @return an unmodifiable list of rolled drops (may be empty)
     */
    @Nonnull
    public List<ItemStack> roll(int npcLevel) {
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

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<ItemStack> result = new ArrayList<>(rolls);

        for (int i = 0; i < rolls; i++) {
            LootEntry picked = pickWeighted(eligible, totalWeight, random);
            if (picked != null) {
                int quantity = picked.quantityMin() == picked.quantityMax()
                        ? picked.quantityMin()
                        : random.nextInt(picked.quantityMin(), picked.quantityMax() + 1);
                if (quantity > 0) {
                    result.add(new ItemStack(picked.itemId(), quantity));
                }
            }
        }

        return Collections.unmodifiableList(result);
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
     * Returns an unmodifiable view of this table's entries.
     *
     * @return the loot entries
     */
    @Nonnull
    public List<LootEntry> getEntries() {
        return entries;
    }
}
