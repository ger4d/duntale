package com.duntale.loot;

import com.duntale.progression.GearLevelService;
import com.duntale.rpg.RpgStat;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls a {@link Rarity} (base ladder + two-step Luck promotion), rolls the rarity-granted
 * {@link GearAttribute}s, and stamps both onto leveled gear at generation time.
 *
 * <p>All tuning is read live from {@link RarityRegistry}. When the registry is not loaded, every
 * roll degrades to a no-op so generation behaves exactly as it did before the rarity system
 * existed. The service is pure with respect to its {@link Random} argument: the same seed always
 * produces the same roll, which the unit tests rely on.
 */
public class RarityRollService {

    private final RarityRegistry rarityRegistry;

    /**
     * Creates a new rarity roll service.
     *
     * @param rarityRegistry the rarity tuning registry
     */
    public RarityRollService(@Nonnull RarityRegistry rarityRegistry) {
        this.rarityRegistry = Objects.requireNonNull(rarityRegistry, "rarityRegistry");
    }

    // ============================================
    // Public API
    // ============================================

    /**
     * Returns whether rarity tuning is loaded. When {@code false}, callers should skip rarity
     * stamping so generation behaves as it did before the rarity system existed.
     *
     * @return {@code true} when the backing registry has authored ladders
     */
    public boolean isLoaded() {
        return rarityRegistry.isLoaded();
    }

    /**
     * Stamps rarity and attributes onto every leveled-gear stack in the given list, leaving
     * non-gear stacks (and everything when the registry is unloaded) untouched.
     *
     * @param stacks    the rolled drops to post-process
     * @param source    the roll source whose ladder seeds the base rarity
     * @param luckLevel the opener/killer effective Luck used for promotion (non-player sources pass 0)
     * @param gearLevel the gear level used for attribute value scaling
     * @return a new list with leveled gear re-stamped, or the input list when nothing changes
     */
    @Nonnull
    public List<ItemStack> applyToGearDrops(@Nonnull List<ItemStack> stacks,
                                            @Nonnull RaritySource source,
                                            int luckLevel,
                                            int gearLevel) {
        if (!rarityRegistry.isLoaded() || stacks.isEmpty()) {
            return stacks;
        }
        Random rng = ThreadLocalRandom.current();
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            result.add(applyToGear(stack, source, luckLevel, gearLevel, rng));
        }
        return List.copyOf(result);
    }

    /**
     * Stamps rarity and attributes onto a single stack when it is leveled gear and the registry is
     * loaded; otherwise returns it unchanged.
     *
     * @param stack     the stack to post-process
     * @param source    the roll source whose ladder seeds the base rarity
     * @param luckLevel the opener/killer effective Luck used for promotion
     * @param gearLevel the gear level used for attribute value scaling
     * @return the (possibly re-stamped) stack
     */
    @Nonnull
    public ItemStack applyToGear(@Nonnull ItemStack stack,
                                 @Nonnull RaritySource source,
                                 int luckLevel,
                                 int gearLevel) {
        if (!rarityRegistry.isLoaded()) {
            return stack;
        }
        return applyToGear(stack, source, luckLevel, gearLevel, ThreadLocalRandom.current());
    }

    @Nonnull
    private ItemStack applyToGear(@Nonnull ItemStack stack,
                                  @Nonnull RaritySource source,
                                  int luckLevel,
                                  int gearLevel,
                                  @Nonnull Random rng) {
        if (!isLeveledGear(stack) || GearLevelService.getRarity(stack) != null) {
            // Skip non-gear and gear already stamped (avoid re-rolling an existing rarity).
            return stack;
        }
        Rarity rarity = rollRarity(source, luckLevel, rng);
        List<GearAttribute> attributes = rollAttributes(rarity, gearLevel, rng);
        ItemStack stamped = GearLevelService.setRarity(stack, rarity);
        return GearLevelService.setAttributes(stamped, attributes);
    }

    /**
     * Rolls a final rarity: a weighted base pick followed by the two-step Luck promotion.
     *
     * @param source    the roll source
     * @param luckLevel the effective Luck used for promotion
     * @param rng       the random source
     * @return the rolled rarity
     */
    @Nonnull
    public Rarity rollRarity(@Nonnull RaritySource source, int luckLevel, @Nonnull Random rng) {
        return promote(rollBase(source, rng), luckLevel, rng);
    }

    /**
     * Rolls the base rarity from a source's weighted ladder.
     *
     * @param source the roll source
     * @param rng    the random source
     * @return the weighted base rarity, or {@link Rarity#COMMON} when the ladder is empty
     */
    @Nonnull
    public Rarity rollBase(@Nonnull RaritySource source, @Nonnull Random rng) {
        List<RarityRegistry.WeightedRarity> ladder = rarityRegistry.ladder(source);
        if (ladder.isEmpty()) {
            return Rarity.COMMON;
        }
        int total = 0;
        for (RarityRegistry.WeightedRarity entry : ladder) {
            total += entry.weight();
        }
        if (total <= 0) {
            return Rarity.COMMON;
        }
        int pick = rng.nextInt(total);
        for (RarityRegistry.WeightedRarity entry : ladder) {
            pick -= entry.weight();
            if (pick < 0) {
                return entry.rarity();
            }
        }
        return ladder.get(ladder.size() - 1).rarity();
    }

    /**
     * Applies the two-step Luck promotion to a base rarity: a single promotion gate
     * ({@code p = baseChance + luckCoeff * (min(luck,luckRef)/luckRef)^luckExp}) followed by a
     * weighted tier-jump pick, capped at {@link Rarity#LEGENDARY}.
     *
     * @param base      the base rarity
     * @param luckLevel the effective Luck
     * @param rng       the random source
     * @return the (possibly promoted) rarity
     */
    @Nonnull
    public Rarity promote(@Nonnull Rarity base, int luckLevel, @Nonnull Random rng) {
        RarityRegistry.Promotion promo = rarityRegistry.promotion();
        if (base.next() == base) {
            // Already at the ceiling (next() caps) — nothing to promote into.
            return base;
        }
        float normalizedLuck = promo.luckRef() > 0f
                ? Math.min(Math.max(luckLevel, 0), promo.luckRef()) / promo.luckRef()
                : 0f;
        double chance = promo.baseChance()
                + promo.luckCoeff() * Math.pow(normalizedLuck, promo.luckExp());
        chance = Math.clamp(chance, 0.0, 1.0);
        if (rng.nextDouble() >= chance) {
            return base;
        }
        int tiers = pickTiers(promo, normalizedLuck, rng);
        return base.promote(tiers);
    }

    /**
     * Rolls the rarity-granted attributes for a piece of gear: a count in the rarity's
     * {@code [min, max]} range, each a <em>distinct</em> eligible stat whose value is rolled
     * <em>independently</em> within the rarity's level-scaled value range (so e.g. a Rare can roll
     * {@code +1 Luck, +3 Strength}).
     *
     * @param rarity    the rolled rarity
     * @param gearLevel the gear level used for value scaling
     * @param rng       the random source
     * @return the rolled attributes (possibly empty)
     */
    @Nonnull
    public List<GearAttribute> rollAttributes(@Nonnull Rarity rarity, int gearLevel, @Nonnull Random rng) {
        RarityRegistry.AttrCount spec = rarityRegistry.attrCount(rarity);
        List<RpgStat> pool = new ArrayList<>(rarityRegistry.eligibleStats());
        if (pool.isEmpty() || spec.max() <= 0) {
            return List.of();
        }
        int desired = spec.min() + (spec.max() > spec.min()
                ? rng.nextInt(spec.max() - spec.min() + 1)
                : 0);
        int n = Math.min(desired, pool.size());
        if (n <= 0) {
            return List.of();
        }
        Collections.shuffle(pool, rng);
        int[] range = attributeValueRange(rarity, gearLevel);
        List<GearAttribute> attributes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int value = range[0] + (range[1] > range[0] ? rng.nextInt(range[1] - range[0] + 1) : 0);
            attributes.add(new GearAttribute(pool.get(i), value));
        }
        return List.copyOf(attributes);
    }

    /**
     * Computes the per-attribute value range {@code [min, max]} for a rarity at a gear level: the
     * rarity's level-1 range shifted up by {@code floor(level / valueLevelStep)} (a flat step that
     * raises the range by {@code +1} every {@code valueLevelStep} levels) and floored at 1.
     *
     * @param rarity    the rarity
     * @param gearLevel the gear level
     * @return a two-element {@code [min, max]} range (inclusive)
     */
    @Nonnull
    public int[] attributeValueRange(@Nonnull Rarity rarity, int gearLevel) {
        RarityRegistry.AttrCount spec = rarityRegistry.attrCount(rarity);
        int step = rarityRegistry.attrValueLevelStep();
        int shift = step > 0 ? Math.max(0, gearLevel) / step : 0;
        int min = Math.max(1, spec.valueMin() + shift);
        int max = Math.max(min, spec.valueMax() + shift);
        return new int[]{min, max};
    }

    // ============================================
    // Internal logic
    // ============================================

    private static int pickTiers(@Nonnull RarityRegistry.Promotion promo,
                                 float normalizedLuck, @Nonnull Random rng) {
        List<RarityRegistry.TierWeight> tierWeights = promo.tierWeights();
        if (tierWeights.isEmpty()) {
            return 1;
        }
        // tierLuckShift biases the jump toward higher tiers as Luck approaches its reference
        // (no-op at the draft default of 0.0).
        double total = 0;
        double[] weights = new double[tierWeights.size()];
        for (int i = 0; i < tierWeights.size(); i++) {
            RarityRegistry.TierWeight tier = tierWeights.get(i);
            double weight = tier.weight()
                    * (1.0 + promo.tierLuckShift() * normalizedLuck * tier.tiers());
            weights[i] = Math.max(0.0, weight);
            total += weights[i];
        }
        if (total <= 0) {
            return tierWeights.get(0).tiers();
        }
        double pick = rng.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick < 0) {
                return tierWeights.get(i).tiers();
            }
        }
        return tierWeights.get(tierWeights.size() - 1).tiers();
    }

    private static boolean isLeveledGear(@Nonnull ItemStack stack) {
        return GearLevelService.getWeaponLevel(stack) != null
                || GearLevelService.getArmorLevel(stack) != null;
    }
}
