package com.duntale.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A condition that decides whether a loot entry is eligible in a given runtime context.
 */
public sealed interface LootCondition permits LootCondition.NpcLevelRange, LootCondition.FloorLevelRange {

    /**
     * Condition families are used to reject duplicate condition kinds during config validation.
     */
    enum Family {
        NPC_LEVEL,
        FLOOR_LEVEL
    }

    /**
     * Returns the logical condition family.
     *
     * @return the family key
     */
    @Nonnull
    Family family();

    /**
     * Returns whether this condition matches the supplied context.
     *
     * @param context the runtime loot context
     * @return {@code true} when the condition passes
     */
    boolean matches(@Nonnull LootContext context);

    /**
     * NPC-level inclusive gate.
     */
    record NpcLevelRange(@Nullable Integer minLevel, @Nullable Integer maxLevel) implements LootCondition {

        @Override
        @Nonnull
        public Family family() {
            return Family.NPC_LEVEL;
        }

        @Override
        public boolean matches(@Nonnull LootContext context) {
            Integer level = context.npcLevel();
            if (level == null) {
                return false;
            }
            if (minLevel != null && level < minLevel) {
                return false;
            }
            return maxLevel == null || level <= maxLevel;
        }
    }

    /**
     * Floor-level inclusive gate.
     */
    record FloorLevelRange(@Nullable Integer minLevel, @Nullable Integer maxLevel) implements LootCondition {

        @Override
        @Nonnull
        public Family family() {
            return Family.FLOOR_LEVEL;
        }

        @Override
        public boolean matches(@Nonnull LootContext context) {
            Integer level = context.floorLevel();
            if (level == null) {
                return false;
            }
            if (minLevel != null && level < minLevel) {
                return false;
            }
            return maxLevel == null || level <= maxLevel;
        }
    }
}