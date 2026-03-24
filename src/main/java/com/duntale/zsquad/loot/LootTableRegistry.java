package com.duntale.zsquad.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link LootTable}s keyed by NPC role name.
 *
 * <p>Tables are registered programmatically at plugin startup.
 * When a leveled NPC dies, the {@link NpcLootSystem} looks up the table
 * by the dead NPC's role name (from the {@link com.duntale.zsquad.progression.CombatScalingComponent}).
 *
 * <p>If no table is registered for a role, the default engine drops are suppressed
 * and nothing drops — this is by design for an RPG where only configured mobs yield loot.
 */
public class LootTableRegistry {

    private final ConcurrentHashMap<String, LootTable> tables = new ConcurrentHashMap<>();

    /**
     * Registers a loot table for the given NPC role name.
     *
     * @param roleName  the NPC role name (e.g. "Zombie", "Skeleton_Warrior")
     * @param lootTable the loot table to use for that role
     */
    public void register(@Nonnull String roleName, @Nonnull LootTable lootTable) {
        tables.put(roleName, lootTable);
    }

    /**
     * Looks up the loot table for a given NPC role name.
     *
     * @param roleName the NPC role name
     * @return the loot table, or {@code null} if none is registered
     */
    @Nullable
    public LootTable get(@Nonnull String roleName) {
        return tables.get(roleName);
    }

    /**
     * Returns whether a loot table is registered for the given role.
     *
     * @param roleName the NPC role name
     * @return {@code true} if a table exists
     */
    public boolean has(@Nonnull String roleName) {
        return tables.containsKey(roleName);
    }

    /**
     * Removes all registered loot tables.
     */
    public void clear() {
        tables.clear();
    }

    /**
     * Returns the number of registered tables.
     *
     * @return the count
     */
    public int size() {
        return tables.size();
    }
}
