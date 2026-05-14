package com.duntale.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A single weighted loot entry describing what item may drop, when it is eligible,
 * and which modifiers shape the final item stack.
 */
public record LootEntry(
        @Nonnull String itemId,
        double weight,
        @Nonnull List<LootCondition> conditions,
        @Nonnull List<LootModifier> modifiers
) {

    /**
     * The type of leveled gear — determines which metadata key is applied.
     */
    public enum GearType {
        /** Applies {@code duntale_weapon_level} + {@code duntale_weapon_variance}. */
        WEAPON,
        /** Applies {@code duntale_armor_level} + {@code duntale_armor_variance}. */
        ARMOR
    }

    public LootEntry {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
    }

    /**
     * Creates a simple weighted loot entry with no conditions or modifiers.
     *
     * @param itemId the Hytale item asset ID
     * @param weight relative weight for weighted random selection
     */
    public LootEntry(@Nonnull String itemId, double weight) {
        this(itemId, weight, List.of(), List.of());
    }

    /**
     * Returns whether this entry is eligible in the supplied runtime context.
     *
     * @param context the runtime loot context
     * @return {@code true} if all conditions pass
     */
    public boolean isEligible(@Nonnull LootContext context) {
        for (LootCondition condition : conditions) {
            if (!condition.matches(context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds an item stack by applying this entry's modifiers in order.
     *
     * @param random the current random source
     * @return the generated item stack
     */
    @Nonnull
    public ItemStack createItemStack(@Nonnull ThreadLocalRandom random) {
        LootModifier.BuildState state = new LootModifier.BuildState(itemId);
        for (LootModifier modifier : modifiers) {
            modifier.apply(state, random);
        }
        return state.toItemStack();
    }
}
