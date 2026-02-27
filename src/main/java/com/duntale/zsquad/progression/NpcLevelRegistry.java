package com.duntale.zsquad.progression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks level and scaling metadata for spawned NPCs by their UUID.
 *
 * <p>Populated at spawn time by {@link LeveledNpcSpawner} and queried
 * at damage time by {@link CombatScalingSystem}. Entries should be removed
 * when NPCs die or despawn.
 */
public class NpcLevelRegistry {

    private final ConcurrentHashMap<UUID, NpcLevelData> registry = new ConcurrentHashMap<>();

    /**
     * Register a spawned NPC's level data.
     *
     * @param uuid the NPC's entity UUID
     * @param data the level data
     */
    public void register(@Nonnull UUID uuid, @Nonnull NpcLevelData data) {
        registry.put(uuid, data);
    }

    /**
     * Look up level data for a spawned NPC.
     *
     * @param uuid the NPC's entity UUID
     * @return the level data, or {@code null} if the NPC is not tracked
     */
    @Nullable
    public NpcLevelData get(@Nonnull UUID uuid) {
        return registry.get(uuid);
    }

    /**
     * Remove a tracked NPC (e.g. on death or despawn).
     *
     * @param uuid the NPC's entity UUID
     */
    public void remove(@Nonnull UUID uuid) {
        registry.remove(uuid);
    }

    /**
     * Returns the number of currently tracked NPCs.
     *
     * @return the count of tracked NPCs
     */
    public int size() {
        return registry.size();
    }

    /**
     * Clear all tracked NPCs.
     */
    public void clear() {
        registry.clear();
    }

    /**
     * Level metadata for a spawned NPC.
     *
     * @param level          the dungeon level (1–60)
     * @param elite          whether this NPC is an elite variant
     * @param npcId          the NPC role name (e.g. "Zombie")
     * @param damageMultiplier the pre-computed damage multiplier for this NPC
     */
    public record NpcLevelData(
            int level,
            boolean elite,
            @Nonnull String npcId,
            float damageMultiplier
    ) {
    }
}
