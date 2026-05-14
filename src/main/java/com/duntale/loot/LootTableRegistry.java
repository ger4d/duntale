package com.duntale.loot;

import com.duntale.loot.config.asset.LootTableConfig;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of {@link LootTable}s keyed by NPC role name.
 *
 * <p>Production tables are resolved from {@link LootTableConfig} assets embedded in the Duntale
 * plugin asset pack. Programmatic registrations still exist for narrow tests and take precedence
 * over asset-backed configs with the same key.
 *
 * <p>If no table is registered for a role, the default engine drops are suppressed
 * and nothing drops — this is by design for an RPG where only configured mobs yield loot.
 */
public class LootTableRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicBoolean ASSET_WARNING_LOGGED = new AtomicBoolean(false);

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
        LootTable registered = tables.get(roleName);
        if (registered != null) {
            return registered;
        }

        LootTableConfig config = resolveAssetConfig(roleName);
        if (config == null) {
            return null;
        }

        try {
            return config.toLootTable();
        } catch (RuntimeException e) {
            LOGGER.atWarning().log("Invalid loot table config for role %s: %s", roleName, e.getMessage());
            return null;
        }
    }

    /**
     * Returns whether a loot table is registered for the given role.
     *
     * @param roleName the NPC role name
     * @return {@code true} if a table exists
     */
    public boolean has(@Nonnull String roleName) {
        return tables.containsKey(roleName) || resolveAssetConfig(roleName) != null;
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
        Set<String> keys = new HashSet<>(tables.keySet());
        for (LootTableConfig config : resolveAssetConfigs()) {
            keys.add(config.getId());
        }
        return keys.size();
    }

    @Nullable
    private LootTableConfig resolveAssetConfig(@Nonnull String roleName) {
        try {
            return LootTableConfig.get(roleName);
        } catch (RuntimeException e) {
            logAssetLookupWarning(e);
            return null;
        }
    }

    @Nonnull
    private Iterable<LootTableConfig> resolveAssetConfigs() {
        try {
            return LootTableConfig.getAll();
        } catch (RuntimeException e) {
            logAssetLookupWarning(e);
            return Set.of();
        }
    }

    private void logAssetLookupWarning(@Nonnull RuntimeException e) {
        if (ASSET_WARNING_LOGGED.compareAndSet(false, true)) {
            LOGGER.atWarning().log("Loot table asset store is not ready: %s", e.getMessage());
        }
    }
}
