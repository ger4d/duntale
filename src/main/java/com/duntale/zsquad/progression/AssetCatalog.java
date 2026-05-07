package com.duntale.zsquad.progression;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entity.damage.ResistanceModifier;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Read-only asset catalog populated from the Hytale runtime asset registry.
 *
 * <p>Scans {@link Item#getAssetMap()} at startup to build in-memory indexes for
 * weapons, armor, and (lazily) monsters. Eliminates the need for an external
 * {@code scaling.db} SQLite database and the Python asset-parsing pipeline.
 *
 * <p>All runtime scaling computation lives in {@link CombatScaling}.
 * This class only serves the asset catalog role — no scaling math, no hot-path queries.
 *
 * @since 1.7.0
 */
public class AssetCatalog {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Cached reflection handle for DamageEntityInteraction.damageCalculator. */
    @Nullable
    private static final Field DAMAGE_CALCULATOR_FIELD;

    static {
        Field f = null;
        try {
            f = DamageEntityInteraction.class.getDeclaredField("damageCalculator");
            f.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
            // Weapon base damage extraction will return 0 — acceptable degradation
        }
        DAMAGE_CALCULATOR_FIELD = f;
    }

    // ── In-memory indexes ───────────────────────────────────────────

    private final ConcurrentHashMap<String, WeaponBaseRow> weapons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArmorBaseRow> armor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MonsterBaseRow> monsters = new ConcurrentHashMap<>();

    /**
     * Scans the Hytale asset registry and populates the in-memory indexes.
     *
     * <p>Must be called after the asset system has finished loading (typically
     * in {@code setup()} or {@code start()} of the plugin).
     */
    public void initialize() {
        int weaponCount = 0;
        int armorCount = 0;

        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            String id = item.getId();
            if (id == null) continue;

            int itemLevel = item.getItemLevel();
            String quality = resolveQuality(item);

            // Skip templates, debug items
            if ("Template".equals(quality) || "Developer".equals(quality)
                    || "Technical".equals(quality) || "Debug".equals(quality)
                    || "QA".equals(quality)) {
                continue;
            }
            String lower = id.toLowerCase();
            if (lower.contains("debug") || lower.contains("test") || lower.contains("_qa_")) {
                continue;
            }

            if (item.getWeapon() != null) {
                WeaponBaseRow row = buildWeaponRow(item, id, quality, itemLevel);
                if (row != null) {
                    weapons.put(id, row);
                    weaponCount++;
                }
            }

            if (item.getArmor() != null) {
                ArmorBaseRow row = buildArmorRow(item, id, quality, itemLevel);
                if (row != null) {
                    armor.put(id, row);
                    armorCount++;
                }
            }
        }

        LOGGER.at(Level.INFO).log("AssetCatalog initialized from runtime assets: %d weapons, %d armor", weaponCount, armorCount);
    }

    // ── Single-item lookups ─────────────────────────────────────────

    /**
     * Retrieves the base data for a single weapon by its asset ID.
     *
     * @param weaponId the weapon asset ID (e.g. "Weapon_Sword_Cobalt")
     * @return the base row, or {@code null} if not found
     */
    @Nullable
    public WeaponBaseRow getWeaponBase(@Nonnull String weaponId) {
        return weapons.get(weaponId);
    }

    /**
     * Retrieves the base data for a single armor piece by its asset ID.
     *
     * @param armorId the armor asset ID (e.g. "Armor_Cobalt_Chest")
     * @return the base row, or {@code null} if not found
     */
    @Nullable
    public ArmorBaseRow getArmorBase(@Nonnull String armorId) {
        return armor.get(armorId);
    }

    /**
     * Retrieves the base HP for a monster by its role name.
     *
     * <p>Monster HP is cached lazily via {@link #cacheMonsterHp}
     * when NPCs are first spawned by {@link LeveledNpcSpawner}.
     *
     * @param name the NPC role name (e.g. "Zombie")
     * @return the base HP, or {@code 20} if not yet cached
     */
    public int getMonsterBaseHp(@Nonnull String name) {
        MonsterBaseRow row = monsters.get(name);
        if (row != null) {
            return row.baseHp();
        }

        Integer resolved = resolveMonsterBaseHp(name);
        return resolved != null ? resolved : 20;
    }

    /**
     * Caches a monster's base HP after it has been resolved from
     * {@code Role.getInitialMaxHealth()} at spawn time.
     *
     * @param roleName the NPC role name
     * @param baseHp   the base max HP from the role
     */
    public void cacheMonsterHp(@Nonnull String roleName, int baseHp) {
        monsters.computeIfAbsent(roleName, name -> new MonsterBaseRow(
                name.replace("_", " "),
                null,
                classifyTier(baseHp),
                baseHp,
                0f, 0f, 0f
        ));
    }

    @Nullable
    private Integer resolveMonsterBaseHp(@Nonnull String roleName) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }

        int roleIndex = npcPlugin.getIndex(roleName);
        if (roleIndex < 0) {
            return null;
        }

        try {
            BuilderInfo builderInfo = npcPlugin.prepareRoleBuilderInfo(roleIndex);
            @SuppressWarnings("unchecked")
            Builder<Role> roleBuilder = (Builder<Role>) builderInfo.getBuilder();
            NPCEntity npcEntity = new NPCEntity();
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            BuilderSupport builderSupport = new BuilderSupport(
                    npcPlugin.getBuilderManager(),
                    npcEntity,
                    holder,
                    new ExecutionContext(),
                    roleBuilder,
                    new RoleStats()
            );
            Role role = NPCPlugin.buildRole(roleBuilder, builderInfo, builderSupport, roleIndex);
            int baseHp = role.getInitialMaxHealth();
            cacheMonsterHp(roleName, baseHp);
            return baseHp;
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).log("Failed to resolve runtime base HP for role %s: %s", roleName, t.getMessage());
            return null;
        }
    }

    // ── List query methods ───────────────────────────────────────────

    /**
     * Lists monsters with sorting, filtering, and pagination.
     *
     * <p>Populated lazily as NPCs are spawned. Returns only cached entries.
     *
     * @param sortBy   sort column key (hp, name, tier); defaults to "hp"
     * @param ascending true for ASC, false for DESC
     * @param limit    maximum rows to return (clamped 1-100)
     * @param tier     optional tier filter (exact match)
     * @param category ignored (no longer available from runtime data)
     * @return sorted and filtered list of monster rows
     */
    @Nonnull
    public List<MonsterBaseRow> listMonsters(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String tier, @Nullable String category
    ) {
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        Stream<MonsterBaseRow> stream = monsters.values().stream();

        if (tier != null && !tier.isEmpty()) {
            stream = stream.filter(r -> tier.equals(r.tier()));
        }

        Comparator<MonsterBaseRow> cmp = switch (sortBy != null ? sortBy : "hp") {
            case "name" -> Comparator.comparing(MonsterBaseRow::name, String.CASE_INSENSITIVE_ORDER);
            case "tier" -> Comparator.comparing(r -> r.tier() != null ? r.tier() : "", String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingInt(MonsterBaseRow::baseHp);
        };
        if (!ascending) cmp = cmp.reversed();

        return stream.sorted(cmp).limit(clampedLimit).toList();
    }

    /**
     * Lists weapons with sorting, filtering, and pagination.
     *
     * @param sortBy  sort column key (name, family, level, quality); defaults to "level"
     * @param ascending true for ASC, false for DESC
     * @param limit   maximum rows to return (clamped 1-500)
     * @param family  optional family filter (exact match)
     * @return sorted and filtered list of weapon rows
     */
    @Nonnull
    public List<WeaponBaseRow> listWeapons(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String family
    ) {
        int clampedLimit = Math.max(1, Math.min(limit, 500));
        Stream<WeaponBaseRow> stream = weapons.values().stream();

        if (family != null && !family.isEmpty()) {
            stream = stream.filter(r -> family.equalsIgnoreCase(r.family()));
        }

        Comparator<WeaponBaseRow> cmp = switch (sortBy != null ? sortBy : "level") {
            case "name" -> Comparator.comparing(WeaponBaseRow::name, String.CASE_INSENSITIVE_ORDER);
            case "family" -> Comparator.comparing(r -> r.family() != null ? r.family() : "", String.CASE_INSENSITIVE_ORDER);
            case "quality" -> Comparator.comparing(r -> r.quality() != null ? r.quality() : "", String.CASE_INSENSITIVE_ORDER);
            case "dmg" -> Comparator.comparingDouble(WeaponBaseRow::baseDamage);
            default -> Comparator.comparingInt(WeaponBaseRow::itemLevel);
        };
        if (!ascending) cmp = cmp.reversed();

        return stream.sorted(cmp).limit(clampedLimit).toList();
    }

    /**
     * Lists armor with sorting, filtering, and pagination.
     *
     * @param sortBy  sort column key (phys, proj, hp, name, slot, level, quality); defaults to "phys"
     * @param ascending true for ASC, false for DESC
     * @param limit   maximum rows to return (clamped 1-500)
     * @param slot    optional slot filter (exact match)
     * @return sorted and filtered list of armor rows
     */
    @Nonnull
    public List<ArmorBaseRow> listArmor(
            @Nullable String sortBy, boolean ascending, int limit,
            @Nullable String slot
    ) {
        int clampedLimit = Math.max(1, Math.min(limit, 500));
        Stream<ArmorBaseRow> stream = armor.values().stream();

        if (slot != null && !slot.isEmpty()) {
            stream = stream.filter(r -> slot.equalsIgnoreCase(r.slot()));
        }

        Comparator<ArmorBaseRow> cmp = switch (sortBy != null ? sortBy : "phys") {
            case "name" -> Comparator.comparing(ArmorBaseRow::name, String.CASE_INSENSITIVE_ORDER);
            case "slot" -> Comparator.comparing(r -> r.slot() != null ? r.slot() : "", String.CASE_INSENSITIVE_ORDER);
            case "quality" -> Comparator.comparing(r -> r.quality() != null ? r.quality() : "", String.CASE_INSENSITIVE_ORDER);
            case "level" -> Comparator.comparingInt(ArmorBaseRow::itemLevel);
            case "proj" -> Comparator.comparingDouble(ArmorBaseRow::projResist);
            case "hp" -> Comparator.comparingInt(ArmorBaseRow::healthBonus);
            default -> Comparator.comparingDouble(ArmorBaseRow::physResist);
        };
        if (!ascending) cmp = cmp.reversed();

        return stream.sorted(cmp).limit(clampedLimit).toList();
    }

    // ── Sort key accessors ──────────────────────────────────────────

    private static final Set<String> NPC_SORT_KEYS = Set.of("hp", "name", "tier");
    private static final Set<String> WEAPON_SORT_KEYS = Set.of("dmg", "name", "family", "level", "quality");
    private static final Set<String> ARMOR_SORT_KEYS = Set.of("phys", "proj", "hp", "name", "slot", "level", "quality");

    /** @return valid NPC sort keys */
    @Nonnull
    public static Set<String> npcSortKeys() { return NPC_SORT_KEYS; }

    /** @return valid weapon sort keys */
    @Nonnull
    public static Set<String> weaponSortKeys() { return WEAPON_SORT_KEYS; }

    /** @return valid armor sort keys */
    @Nonnull
    public static Set<String> armorSortKeys() { return ARMOR_SORT_KEYS; }

    // ── Asset scanning helpers ──────────────────────────────────────

    @Nullable
    private static WeaponBaseRow buildWeaponRow(@Nonnull Item item, @Nonnull String id,
                                                 @Nullable String quality, int itemLevel) {
        // Skip arrows, ammo, NPC-only items
        String lower = id.toLowerCase();
        if (lower.contains("arrow") || lower.contains("ammo")
                || lower.endsWith("_npc") || lower.contains("_npc_")) {
            return null;
        }

        String family = inferWeaponFamily(id);
        float baseDamage = extractWeaponDamage(item);
        String name = id.replace('_', ' ');

        return new WeaponBaseRow(name, family, quality, itemLevel, baseDamage);
    }

    @Nullable
    private static ArmorBaseRow buildArmorRow(@Nonnull Item item, @Nonnull String id,
                                              @Nullable String quality, int itemLevel) {
        ItemArmor armorConfig = item.getArmor();
        if (armorConfig == null) return null;

        // Skip NPC-only items
        String lower = id.toLowerCase();
        if (lower.endsWith("_npc") || lower.contains("_npc_")) {
            return null;
        }

        String slot = armorConfig.getArmorSlot() != null ? armorConfig.getArmorSlot().name() : null;

        // Physical and projectile resistance
        float physResist = 0f;
        float projResist = 0f;
        Map<DamageCause, ResistanceModifier[]> drMap = armorConfig.getDamageResistanceValues();
        if (drMap != null) {
            if (DamageCause.PHYSICAL != null) {
                ResistanceModifier[] physMods = drMap.get(DamageCause.PHYSICAL);
                if (physMods != null) {
                    for (ResistanceModifier mod : physMods) {
                        physResist += (float) mod.getAmount();
                    }
                }
            }
            if (DamageCause.PROJECTILE != null) {
                ResistanceModifier[] projMods = drMap.get(DamageCause.PROJECTILE);
                if (projMods != null) {
                    for (ResistanceModifier mod : projMods) {
                        projResist += (float) mod.getAmount();
                    }
                }
            }
        }

        // Health bonus from stat modifiers
        int healthBonus = 0;
        Int2ObjectMap<StaticModifier[]> statMods = armorConfig.getStatModifiers();
        if (statMods != null) {
            int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
            if (healthIndex >= 0) {
                StaticModifier[] healthMods = statMods.get(healthIndex);
                if (healthMods != null) {
                    for (StaticModifier mod : healthMods) {
                        healthBonus += (int) mod.getAmount();
                    }
                }
            }
        }

        String name = id.replace('_', ' ');
        return new ArmorBaseRow(name, slot, quality, itemLevel, physResist, projResist, healthBonus, null);
    }

    /**
     * Infers weapon family from the asset ID naming convention.
     * E.g. "Weapon_Sword_Cobalt" → "Sword", "Weapon_Spear_Iron" → "Spear".
     */
    @Nullable
    private static String inferWeaponFamily(@Nonnull String id) {
        String[] parts = id.split("_");
        if (parts.length >= 2 && "Weapon".equals(parts[0])) {
            return parts[1];
        }
        return parts.length >= 1 ? parts[0] : null;
    }

    /**
     * Extracts weapon base damage by traversing the item's interaction tree.
     *
     * <p>Walks InteractionVars → RootInteraction → DamageEntityInteraction
     * and reads the DamageCalculator's BaseDamage via reflection.
     * Returns 0 if damage cannot be extracted.
     */
    private static float extractWeaponDamage(@Nonnull Item item) {
        if (DAMAGE_CALCULATOR_FIELD == null) return 0f;

        Map<String, String> interactionVars = item.getInteractionVars();
        if (interactionVars == null || interactionVars.isEmpty()) return 0f;

        List<Float> damages = new ArrayList<>();

        for (String rootId : interactionVars.values()) {
            RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
            if (root == null) continue;

            String[] childIds = root.getInteractionIds();
            if (childIds == null) continue;

            for (String childId : childIds) {
                Interaction interaction = Interaction.getAssetMap().getAsset(childId);
                if (!(interaction instanceof DamageEntityInteraction dei)) continue;

                try {
                    DamageCalculator calc = (DamageCalculator) DAMAGE_CALCULATOR_FIELD.get(dei);
                    if (calc == null) continue;

                    Object2FloatMap<DamageCause> damageMap = calc.calculateDamage(1.0);
                    if (damageMap == null || damageMap.isEmpty()) continue;

                    float total = 0f;
                    for (Object2FloatMap.Entry<DamageCause> entry : damageMap.object2FloatEntrySet()) {
                        if (entry.getFloatValue() > 0f) {
                            total += entry.getFloatValue();
                        }
                    }
                    if (total > 0f) {
                        damages.add(total);
                    }
                } catch (IllegalAccessException e) {
                    // Graceful degradation — damage will be 0
                }
            }
        }

        if (damages.isEmpty()) return 0f;
        // Average of all attack move damages (same as parse_assets.py)
        float sum = 0f;
        for (float d : damages) sum += d;
        return sum / damages.size();
    }

    /**
     * Resolves the quality string from an item's quality index.
     */
    @Nullable
    private static String resolveQuality(@Nonnull Item item) {
        int qualityIdx = item.getQualityIndex();
        if (qualityIdx <= 0) return null;
        ItemQuality iq = ItemQuality.getAssetMap().getAsset(qualityIdx);
        return iq != null ? iq.getId() : null;
    }

    /**
     * Classifies NPC tier based on base HP thresholds.
     * Mirrors the logic from {@code parse_assets.py:classify_tier()}.
     */
    @Nonnull
    private static String classifyTier(int baseHp) {
        if (baseHp <= 36) return "Fodder";
        if (baseHp <= 74) return "Standard";
        if (baseHp <= 150) return "Tough";
        if (baseHp <= 350) return "Elite";
        return "Boss";
    }

    // ── Record types ────────────────────────────────────────────────

    /**
     * Monster catalog entry.
     *
     * @param name           display name
     * @param category       NPC category (null when populated from runtime)
     * @param tier           tier classification (Fodder, Standard, Tough, Elite, Boss)
     * @param baseHp         base max HP
     * @param baseDamage     base melee damage (0 when populated from runtime)
     * @param baseSpeed      movement speed (0 when populated from runtime)
     * @param attackDistance  attack range (0 when populated from runtime)
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
     * Weapon catalog entry.
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
     * Armor catalog entry.
     *
     * @param name        display name
     * @param slot        armor slot (Head, Chest, Hands, Legs)
     * @param quality     item quality
     * @param itemLevel   the item level
     * @param physResist  physical damage resistance (multiplicative fraction)
     * @param projResist  projectile damage resistance (multiplicative fraction)
     * @param healthBonus flat health bonus
     * @param special     special attributes (always null for runtime-scanned entries)
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
