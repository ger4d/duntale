package com.duntale.zsquad.progression;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Caches precomputed scaling data from the SQLite database.
 *
 * <p>Opens a read-only SQLite connection on construction and lazy-loads
 * scaled data into an in-memory {@link ConcurrentHashMap}. If the database
 * is missing or a query fails, returns safe defaults (multiplier 1.0).
 */
public class ScalingDataCache {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ConcurrentHashMap<String, MonsterScaledData> monsterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Float> weaponMultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Float> armorDrCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WeaponBaseRow> weaponBaseCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArmorBaseRow> armorBaseCache = new ConcurrentHashMap<>();

    @Nullable
    private Connection connection;

    /** The classloader that loaded the SQLite JDBC driver — needed for WorldThread JDBC calls. */
    private final ClassLoader jdbcClassLoader;

    private static final String DB_FILENAME = "scaling.db";

    /**
     * Creates a new cache backed by a {@code scaling.db} file inside the given data directory.
     *
     * @param dataDirectory the plugin data directory (from {@code getDataDirectory()}).
     *                      The database is resolved as {@code dataDirectory/scaling.db}.
     */
    public ScalingDataCache(@Nonnull Path dataDirectory) {
        this.jdbcClassLoader = getClass().getClassLoader();
        Path dbPath = dataDirectory.resolve(DB_FILENAME);
        if (!Files.exists(dbPath)) {
            LOGGER.at(Level.WARNING).log("Scaling database not found at %s -- all multipliers will default to 1.0", dbPath.toAbsolutePath());
            this.connection = null;
            return;
        }

        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();
            this.connection = DriverManager.getConnection(url);
            LOGGER.at(Level.INFO).log("Scaling database loaded from %s", dbPath.toAbsolutePath());
        } catch (ClassNotFoundException e) {
            LOGGER.at(Level.WARNING).log("SQLite JDBC driver not found: %s", e.getMessage());
            this.connection = null;
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to open scaling database: %s", e.getMessage());
            this.connection = null;
        }
    }

    /**
     * Runs a JDBC operation with the plugin classloader set as the thread context classloader.
     * Required because JDBC calls from the WorldThread use a different context classloader
     * that cannot resolve shaded SQLite classes.
     */
    private <T> T withJdbcClassLoader(@Nonnull java.util.function.Supplier<T> action) {
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(jdbcClassLoader);
        try {
            return action.get();
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    /**
     * Retrieves the scaled data for a monster at a given level.
     *
     * @param npcId the NPC role name (e.g. "Zombie")
     * @param level the dungeon level (1–60)
     * @return the scaled data, or a default if not found
     */
    @Nonnull
    public MonsterScaledData getMonsterScaled(@Nonnull String npcId, int level) {
        String key = npcId + ":" + level;
        MonsterScaledData cached = monsterCache.get(key);
        if (cached != null) {
            return cached;
        }

        MonsterScaledData data = withJdbcClassLoader(() -> queryMonster(npcId, level));
        monsterCache.put(key, data);
        return data;
    }

    /**
     * Retrieves the weapon damage multiplier at a given level.
     *
     * @param weaponId the weapon asset ID
     * @param level    the dungeon level (1–60)
     * @return the damage multiplier (defaults to 1.0)
     */
    public float getWeaponMultiplier(@Nonnull String weaponId, int level) {
        String key = weaponId + ":" + level;
        Float cached = weaponMultCache.get(key);
        if (cached != null) {
            return cached;
        }

        float mult = withJdbcClassLoader(() -> queryWeaponMultiplier(weaponId, level));
        weaponMultCache.put(key, mult);
        return mult;
    }

    /**
     * Retrieves the effective armor damage reduction at a given level.
     *
     * @param armorId the armor asset ID
     * @param level   the dungeon level (1–60)
     * @return the effective DR (0.0–0.65, defaults to 0.0)
     */
    public float getArmorDR(@Nonnull String armorId, int level) {
        String key = armorId + ":" + level;
        Float cached = armorDrCache.get(key);
        if (cached != null) {
            return cached;
        }

        float dr = withJdbcClassLoader(() -> queryArmorDR(armorId, level));
        armorDrCache.put(key, dr);
        return dr;
    }

    /**
     * Retrieves the base data for a single weapon by its asset ID.
     *
     * @param weaponId the weapon asset ID (e.g. "Weapon_Sword_Cobalt")
     * @return the base row, or {@code null} if not found
     */
    @Nullable
    public WeaponBaseRow getWeaponBase(@Nonnull String weaponId) {
        WeaponBaseRow cached = weaponBaseCache.get(weaponId);
        if (cached != null) {
            return cached;
        }

        WeaponBaseRow row = withJdbcClassLoader(() -> queryWeaponBase(weaponId));
        if (row != null) {
            weaponBaseCache.put(weaponId, row);
        }
        return row;
    }

    /**
     * Retrieves the base data for a single armor piece by its asset ID.
     *
     * @param armorId the armor asset ID (e.g. "Armor_Chest_Cobalt")
     * @return the base row, or {@code null} if not found
     */
    @Nullable
    public ArmorBaseRow getArmorBase(@Nonnull String armorId) {
        ArmorBaseRow cached = armorBaseCache.get(armorId);
        if (cached != null) {
            return cached;
        }

        ArmorBaseRow row = withJdbcClassLoader(() -> queryArmorBase(armorId));
        if (row != null) {
            armorBaseCache.put(armorId, row);
        }
        return row;
    }

    // ── Sort column whitelists (user-friendly name → SQL column) ────

    private static final Map<String, String> NPC_SORT_COLUMNS = Map.of(
            "hp", "base_hp", "dmg", "base_damage", "name", "name",
            "tier", "tier", "speed", "base_speed", "category", "category"
    );

    private static final Map<String, String> WEAPON_SORT_COLUMNS = Map.of(
            "dmg", "base_damage", "name", "name", "family", "family",
            "level", "item_level", "quality", "quality"
    );

    private static final Map<String, String> ARMOR_SORT_COLUMNS = Map.of(
            "phys", "phys_resist", "proj", "proj_resist", "hp", "health_bonus",
            "name", "name", "slot", "slot", "level", "item_level", "quality", "quality"
    );

    // ── List query methods ───────────────────────────────────────────

    /**
     * Lists monsters from the base table with sorting, filtering, and pagination.
     *
     * @param sortBy   sort column key (hp, dmg, name, tier, speed, category); defaults to "hp"
     * @param ascending true for ASC, false for DESC
     * @param limit    maximum rows to return (clamped 1–100)
     * @param tier     optional tier filter (exact match)
     * @param category optional category filter (exact match)
     * @return list of monster rows, or empty list if DB is unavailable
     */
    @Nonnull
    public List<MonsterBaseRow> listMonsters(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String tier, @Nullable String category
    ) {
        return withJdbcClassLoader(() -> listMonstersInternal(sortBy, ascending, limit, tier, category));
    }

    @Nonnull
    private List<MonsterBaseRow> listMonstersInternal(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String tier, @Nullable String category
    ) {
        if (connection == null) {
            return Collections.emptyList();
        }

        String column = NPC_SORT_COLUMNS.getOrDefault(sortBy != null ? sortBy : "hp", "base_hp");
        String order = ascending ? "ASC" : "DESC";
        int clampedLimit = Math.max(1, Math.min(limit, 100));

        StringBuilder sql = new StringBuilder(
                "SELECT name, category, tier, base_hp, base_damage, base_speed, attack_distance FROM monsters_base"
        );
        List<Object> params = new ArrayList<>();
        List<String> clauses = new ArrayList<>();

        if (tier != null && !tier.isEmpty()) {
            clauses.add("tier = ?");
            params.add(tier);
        }
        if (category != null && !category.isEmpty()) {
            clauses.add("category = ?");
            params.add(category);
        }
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        sql.append(" ORDER BY ").append(column).append(" ").append(order);
        sql.append(" LIMIT ?");
        params.add(clampedLimit);

        List<MonsterBaseRow> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    stmt.setString(i + 1, s);
                } else if (p instanceof Integer n) {
                    stmt.setInt(i + 1, n);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new MonsterBaseRow(
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getString("tier"),
                            rs.getInt("base_hp"),
                            rs.getFloat("base_damage"),
                            rs.getFloat("base_speed"),
                            rs.getFloat("attack_distance")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to list monsters: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Lists weapons from the base table with sorting, filtering, and pagination.
     *
     * @param sortBy  sort column key (dmg, name, family, level, quality); defaults to "dmg"
     * @param ascending true for ASC, false for DESC
     * @param limit   maximum rows to return (clamped 1–100)
     * @param family  optional family filter (exact match)
     * @return list of weapon rows, or empty list if DB is unavailable
     */
    @Nonnull
    public List<WeaponBaseRow> listWeapons(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String family
    ) {
        return withJdbcClassLoader(() -> listWeaponsInternal(sortBy, ascending, limit, family));
    }

    @Nonnull
    private List<WeaponBaseRow> listWeaponsInternal(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String family
    ) {
        if (connection == null) {
            return Collections.emptyList();
        }

        String column = WEAPON_SORT_COLUMNS.getOrDefault(sortBy != null ? sortBy : "dmg", "base_damage");
        String order = ascending ? "ASC" : "DESC";
        int clampedLimit = Math.max(1, Math.min(limit, 100));

        StringBuilder sql = new StringBuilder(
                "SELECT name, family, quality, item_level, base_damage FROM weapons_base"
        );
        List<Object> params = new ArrayList<>();

        if (family != null && !family.isEmpty()) {
            sql.append(" WHERE family = ?");
            params.add(family);
        }
        sql.append(" ORDER BY ").append(column).append(" ").append(order);
        sql.append(" LIMIT ?");
        params.add(clampedLimit);

        List<WeaponBaseRow> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    stmt.setString(i + 1, s);
                } else if (p instanceof Integer n) {
                    stmt.setInt(i + 1, n);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new WeaponBaseRow(
                            rs.getString("name"),
                            rs.getString("family"),
                            rs.getString("quality"),
                            rs.getInt("item_level"),
                            rs.getFloat("base_damage")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to list weapons: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Lists armor from the base table with sorting, filtering, and pagination.
     *
     * @param sortBy  sort column key (phys, proj, hp, name, slot, level, quality); defaults to "phys"
     * @param ascending true for ASC, false for DESC
     * @param limit   maximum rows to return (clamped 1–100)
     * @param slot    optional slot filter (exact match)
     * @return list of armor rows, or empty list if DB is unavailable
     */
    @Nonnull
    public List<ArmorBaseRow> listArmor(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String slot
    ) {
        return withJdbcClassLoader(() -> listArmorInternal(sortBy, ascending, limit, slot));
    }

    @Nonnull
    private List<ArmorBaseRow> listArmorInternal(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String slot
    ) {
        if (connection == null) {
            return Collections.emptyList();
        }

        String column = ARMOR_SORT_COLUMNS.getOrDefault(sortBy != null ? sortBy : "phys", "phys_resist");
        String order = ascending ? "ASC" : "DESC";
        int clampedLimit = Math.max(1, Math.min(limit, 100));

        StringBuilder sql = new StringBuilder(
                "SELECT name, slot, quality, item_level, phys_resist, proj_resist, health_bonus, special FROM armor_base"
        );
        List<Object> params = new ArrayList<>();

        if (slot != null && !slot.isEmpty()) {
            sql.append(" WHERE slot = ?");
            params.add(slot);
        }
        sql.append(" ORDER BY ").append(column).append(" ").append(order);
        sql.append(" LIMIT ?");
        params.add(clampedLimit);

        List<ArmorBaseRow> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    stmt.setString(i + 1, s);
                } else if (p instanceof Integer n) {
                    stmt.setInt(i + 1, n);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ArmorBaseRow(
                            rs.getString("name"),
                            rs.getString("slot"),
                            rs.getString("quality"),
                            rs.getInt("item_level"),
                            rs.getFloat("phys_resist"),
                            rs.getFloat("proj_resist"),
                            rs.getInt("health_bonus"),
                            rs.getString("special")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to list armor: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns the set of valid NPC sort keys.
     *
     * @return unmodifiable set of sort keys (hp, dmg, name, tier, speed, category)
     */
    @Nonnull
    public static java.util.Set<String> npcSortKeys() {
        return NPC_SORT_COLUMNS.keySet();
    }

    /**
     * Returns the set of valid weapon sort keys.
     *
     * @return unmodifiable set of sort keys (dmg, name, family, level, quality)
     */
    @Nonnull
    public static java.util.Set<String> weaponSortKeys() {
        return WEAPON_SORT_COLUMNS.keySet();
    }

    /**
     * Returns the set of valid armor sort keys.
     *
     * @return unmodifiable set of sort keys (phys, proj, hp, name, slot, level, quality)
     */
    @Nonnull
    public static java.util.Set<String> armorSortKeys() {
        return ARMOR_SORT_COLUMNS.keySet();
    }

    /**
     * Closes the underlying database connection and clears all caches.
     */
    public void shutdown() {
        monsterCache.clear();
        weaponMultCache.clear();
        armorDrCache.clear();
        weaponBaseCache.clear();
        armorBaseCache.clear();

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Failed to close scaling database: %s", e.getMessage());
            }
            connection = null;
        }
    }

    // ── Private query methods ────────────────────────────────────────

    @Nonnull
    private MonsterScaledData queryMonster(@Nonnull String npcId, int level) {
        if (connection == null) {
            return MonsterScaledData.DEFAULT;
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT scaled_hp, scaled_damage, damage_mult, elite_hp, elite_damage " +
                        "FROM monsters_scaled WHERE npc_id = ? AND level = ?")) {
            stmt.setString(1, npcId);
            stmt.setInt(2, level);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MonsterScaledData(
                            rs.getInt("scaled_hp"),
                            rs.getFloat("scaled_damage"),
                            rs.getFloat("damage_mult"),
                            rs.getInt("elite_hp"),
                            rs.getFloat("elite_damage")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query monster scaling for %s L%d: %s", npcId, level, e.getMessage());
        }

        return MonsterScaledData.DEFAULT;
    }

    private float queryWeaponMultiplier(@Nonnull String weaponId, int level) {
        if (connection == null) {
            return 1.0f;
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT damage_mult FROM weapons_scaled WHERE weapon_id = ? AND level = ?")) {
            stmt.setString(1, weaponId);
            stmt.setInt(2, level);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getFloat("damage_mult");
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query weapon scaling for %s L%d: %s", weaponId, level, e.getMessage());
        }

        return 1.0f;
    }

    private float queryArmorDR(@Nonnull String armorId, int level) {
        if (connection == null) {
            return 0.0f;
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT effective_dr FROM armor_scaled WHERE armor_id = ? AND level = ?")) {
            stmt.setString(1, armorId);
            stmt.setInt(2, level);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getFloat("effective_dr");
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query armor scaling for %s L%d: %s", armorId, level, e.getMessage());
        }

        return 0.0f;
    }

    @Nullable
    private WeaponBaseRow queryWeaponBase(@Nonnull String weaponId) {
        if (connection == null) {
            return null;
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT name, family, quality, item_level, base_damage FROM weapons_base WHERE weapon_id = ?")) {
            stmt.setString(1, weaponId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new WeaponBaseRow(
                            rs.getString("name"),
                            rs.getString("family"),
                            rs.getString("quality"),
                            rs.getInt("item_level"),
                            rs.getFloat("base_damage")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query weapon base for %s: %s", weaponId, e.getMessage());
        }

        return null;
    }

    @Nullable
    private ArmorBaseRow queryArmorBase(@Nonnull String armorId) {
        if (connection == null) {
            return null;
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT name, slot, quality, item_level, phys_resist, proj_resist, health_bonus, special " +
                        "FROM armor_base WHERE armor_id = ?")) {
            stmt.setString(1, armorId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new ArmorBaseRow(
                            rs.getString("name"),
                            rs.getString("slot"),
                            rs.getString("quality"),
                            rs.getInt("item_level"),
                            rs.getFloat("phys_resist"),
                            rs.getFloat("proj_resist"),
                            rs.getInt("health_bonus"),
                            rs.getString("special")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query armor base for %s: %s", armorId, e.getMessage());
        }

        return null;
    }

    /**
     * Pre-computed data for a monster at a specific level.
     *
     * @param scaledHp     the scaled max HP
     * @param scaledDamage the scaled base damage
     * @param damageMult   the damage multiplier relative to base
     * @param eliteHp      the elite variant scaled HP
     * @param eliteDamage  the elite variant scaled damage
     */
    public record MonsterScaledData(
            int scaledHp,
            float scaledDamage,
            float damageMult,
            int eliteHp,
            float eliteDamage
    ) {
        /** Default fallback data — no scaling applied. */
        public static final MonsterScaledData DEFAULT = new MonsterScaledData(0, 0f, 1.0f, 0, 0f);
    }

    /**
     * A row from the monsters_base table.
     *
     * @param name           display name
     * @param category       NPC category (Undead, Creature, etc.)
     * @param tier           tier classification (Fodder, Standard, Tough, Elite, Boss)
     * @param baseHp         base max HP
     * @param baseDamage     base melee damage
     * @param baseSpeed      movement speed
     * @param attackDistance  attack range
     */
    public record MonsterBaseRow(
            @Nonnull String name,
            @Nullable String category,
            @Nullable String tier,
            int baseHp,
            float baseDamage,
            float baseSpeed,
            float attackDistance
    ) {}

    /**
     * A row from the weapons_base table.
     *
     * @param name       display name
     * @param family     weapon family (Sword, Axe, Spear, etc.)
     * @param quality    item quality (Common, Uncommon, Rare, Epic)
     * @param itemLevel  the item level
     * @param baseDamage average base physical damage
     */
    public record WeaponBaseRow(
            @Nonnull String name,
            @Nullable String family,
            @Nullable String quality,
            int itemLevel,
            float baseDamage
    ) {}

    /**
     * A row from the armor_base table.
     *
     * @param name        display name
     * @param slot        armor slot (Head, Chest, Hands, Legs)
     * @param quality     item quality
     * @param itemLevel   the item level
     * @param physResist  physical damage resistance (multiplicative fraction)
     * @param projResist  projectile damage resistance (multiplicative fraction)
     * @param healthBonus flat health bonus
     * @param special     special attributes (e.g. "Light +6%")
     */
    public record ArmorBaseRow(
            @Nonnull String name,
            @Nullable String slot,
            @Nullable String quality,
            int itemLevel,
            float physResist,
            float projResist,
            int healthBonus,
            @Nullable String special
    ) {}
}
