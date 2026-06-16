package com.duntale.loot;

import com.duntale.items.UnbreakableItems;
import com.duntale.progression.CombatScaling;
import com.duntale.progression.GearLevelService;
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
     * @param state   the mutable drop build state
     * @param random  the current random source
     * @param context the runtime loot context (carries the NPC/floor level for level-defaulting)
     */
    void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random, @Nonnull LootContext context);

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
        public void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random, @Nonnull LootContext context) {
            int quantity = minQuantity == maxQuantity
                    ? minQuantity
                    : random.nextInt(minQuantity, maxQuantity + 1);
            state.setQuantity(quantity);
        }
    }

    /**
     * Gear-level modifier that stamps weapon or armor metadata onto the resulting stack.
     *
     * <p>The level band is optional. When {@code minLevel}/{@code maxLevel} are both supplied it is an
     * explicit author override (bespoke gear that always stamps within that band). When either is
     * {@code null} the stamped level defaults to the killed NPC's level (= the dungeon floor) carried
     * by the {@link LootContext}, so a low-floor kill drops on-floor gear instead of a fixed per-role
     * band.
     */
    record GearLevel(@Nonnull LootEntry.GearType gearType, @Nullable Integer minLevel, @Nullable Integer maxLevel)
            implements LootModifier {

        /**
         * Fallback level used only when neither the entry nor the context supplies one (e.g. a
         * programmatic roll with an empty context). Keeps the modifier from ever producing an invalid
         * level.
         */
        private static final int FALLBACK_LEVEL = CombatScaling.MIN_LEVEL;

        @Override
        @Nonnull
        public Family family() {
            return Family.GEAR_LEVEL;
        }

        @Override
        public void apply(@Nonnull BuildState state, @Nonnull ThreadLocalRandom random, @Nonnull LootContext context) {
            int rolledLevel = resolveLevel(random, context);
            state.setGear(gearType, rolledLevel, GearLevelService.rollVariance());
        }

        /**
         * Resolves the level to stamp: an explicit author band when present, otherwise the context's
         * level (NPC level, falling back to floor level, then a safe constant).
         */
        private int resolveLevel(@Nonnull ThreadLocalRandom random, @Nonnull LootContext context) {
            if (minLevel != null && maxLevel != null) {
                return minLevel.equals(maxLevel)
                        ? minLevel
                        : random.nextInt(minLevel, maxLevel + 1);
            }
            Integer contextLevel = context.npcLevel() != null ? context.npcLevel() : context.floorLevel();
            int base = contextLevel != null ? contextLevel : FALLBACK_LEVEL;
            return CombatScaling.clampLevel(base);
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

            // Single chokepoint for all dropped/looted gear (NPC drops + chest loot
            // both flow through here): stamp it unbreakable, since player gear never breaks.
            return switch (gearType) {
                case WEAPON -> {
                    ItemStack leveled = GearLevelService.setWeaponLevel(stack, gearLevel);
                    yield UnbreakableItems.makeUnbreakable(GearLevelService.setWeaponVariance(leveled, gearVariance));
                }
                case ARMOR -> {
                    ItemStack leveled = GearLevelService.setArmorLevel(stack, gearLevel);
                    yield UnbreakableItems.makeUnbreakable(GearLevelService.setArmorVariance(leveled, gearVariance));
                }
            };
        }
    }
}