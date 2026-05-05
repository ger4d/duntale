package com.duntale.zsquad.loot;

import com.duntale.zsquad.progression.GearLevelService;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A modifier that decides how an eligible loot entry is turned into an item stack.
 */
public sealed interface LootModifier permits LootModifier.Quantity, LootModifier.GearLevel {

    /**
     * Modifier families are used to reject duplicate modifier kinds during config validation.
     */
    enum Family {
        QUANTITY,
        GEAR_LEVEL
    }

    /**
     * Returns the logical modifier family.
     *
     * @return the family key
     */
    @Nonnull
    Family family();

    /**
     * Applies this modifier to the in-flight drop build state.
     *
     * @param state  the mutable drop build state
     * @param random the current random source
     */
    void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random);

    /**
     * Quantity modifier that sets the resulting stack size.
     */
    record Quantity(int minQuantity, int maxQuantity) implements LootModifier {

        @Override
        @Nonnull
        public Family family() {
            return Family.QUANTITY;
        }

        @Override
        public void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random) {
            int quantity = minQuantity == maxQuantity
                    ? minQuantity
                    : random.nextInt(minQuantity, maxQuantity + 1);
            state.setQuantity(quantity);
        }
    }

    /**
     * Gear-level modifier that stamps weapon or armor metadata onto the resulting stack.
     */
    record GearLevel(@Nonnull LootEntry.GearType gearType, int minLevel, int maxLevel) implements LootModifier {

        @Override
        @Nonnull
        public Family family() {
            return Family.GEAR_LEVEL;
        }

        @Override
        public void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random) {
            int rolledLevel = minLevel == maxLevel
                    ? minLevel
                    : random.nextInt(minLevel, maxLevel + 1);
            state.setGear(gearType, rolledLevel, GearLevelService.rollVariance());
        }
    }

    /**
     * Mutable build state shared across all modifiers on one picked loot entry.
     */
    final class BuildState {
        private final String itemId;
        private int quantity = 1;
        @Nullable
        private LootEntry.GearType gearType;
        private int gearLevel = 1;
        private float gearVariance = 1.0f;

        public BuildState(@Nonnull String itemId) {
            this.itemId = itemId;
        }

        public void setQuantity(int quantity) {
            this.quantity = Math.max(quantity, 1);
        }

        public void setGear(@Nonnull LootEntry.GearType gearType, int gearLevel, float gearVariance) {
            this.gearType = gearType;
            this.gearLevel = Math.max(gearLevel, 1);
            this.gearVariance = gearVariance;
        }

        @Nonnull
        public ItemStack toItemStack() {
            ItemStack stack = new ItemStack(itemId, quantity);
            if (gearType == null) {
                return stack;
            }

            return switch (gearType) {
                case WEAPON -> {
                    ItemStack leveled = GearLevelService.setWeaponLevel(stack, gearLevel);
                    yield GearLevelService.setWeaponVariance(leveled, gearVariance);
                }
                case ARMOR -> {
                    ItemStack leveled = GearLevelService.setArmorLevel(stack, gearLevel);
                    yield GearLevelService.setArmorVariance(leveled, gearVariance);
                }
            };
        }
    }
}