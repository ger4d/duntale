package com.duntale.zsquad.progression;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns NPCs with scaled stats for a given dungeon level.
 *
 * <p>Uses {@link NPCPlugin#spawnEntity} with a postSpawn callback to:
 * <ol>
 *   <li>Query {@link ScalingDataCache} for the scaled HP and damage multiplier</li>
 *   <li>Apply an HP modifier via {@link EntityStatMap#putModifier}</li>
 *   <li>Register the NPC in {@link NpcLevelRegistry} for damage scaling</li>
 *   <li>Set a display name with level prefix</li>
 *   <li>Optionally scale elite NPCs visually</li>
 * </ol>
 */
public class LeveledNpcSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVEL_SCALE_MODIFIER_KEY = "ZSquad_LevelScale";
    private static final float ELITE_VISUAL_SCALE = 1.2f;
    private static final float STAT_VARIANCE = 0.05f; // ±5%

    private final ScalingDataCache scalingCache;
    private final NpcLevelRegistry levelRegistry;

    /**
     * Creates a new spawner.
     *
     * @param scalingCache the scaling data cache
     * @param levelRegistry the NPC level registry
     */
    public LeveledNpcSpawner(@Nonnull ScalingDataCache scalingCache, @Nonnull NpcLevelRegistry levelRegistry) {
        this.scalingCache = scalingCache;
        this.levelRegistry = levelRegistry;
    }

    /**
     * Spawns a leveled NPC at the given position.
     *
     * @param store    the entity store
     * @param roleName the NPC role name (e.g. "Zombie")
     * @param position the spawn position
     * @param level    the dungeon level (1–60)
     * @param elite    whether this is an elite variant
     * @return the spawned entity pair, or {@code null} if spawning failed
     */
    @Nullable
    public Pair<Ref<EntityStore>, NPCEntity> spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            @Nonnull Vector3d position,
            int level,
            boolean elite
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            LOGGER.at(Level.WARNING).log("NPCPlugin not available — cannot spawn %s", roleName);
            return null;
        }

        int roleIndex = npcPlugin.getIndex(roleName);
        if (roleIndex < 0) {
            LOGGER.at(Level.WARNING).log("Unknown NPC role: %s", roleName);
            return null;
        }

        // Query scaling data
        ScalingDataCache.MonsterScaledData scaledData = scalingCache.getMonsterScaled(roleName, level);

        return npcPlugin.spawnEntity(store, roleIndex, position, null, null,
                (npcEntity, holder, s) -> {
                    applyPreAdd(npcEntity, holder, roleName, level, elite);
                },
                (npcEntity, ref, s) -> {
                    applyPostSpawn(npcEntity, ref, s, roleName, level, elite, scaledData);
                });
    }

    private void applyPreAdd(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Holder<EntityStore> holder,
            @Nonnull String roleName,
            int level,
            boolean elite
    ) {
        // Nameplate (via Holder before entity is added)
        // Use putComponent — the entity may already have a Nameplate from its role.
        String nameplate = elite
                ? "[Lv." + level + " *] " + roleName
                : "[Lv." + level + "] " + roleName;
        holder.putComponent(Nameplate.getComponentType(), new Nameplate(nameplate));

        // Elite Visual Scaling
        if (elite) {
            holder.putComponent(EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(ELITE_VISUAL_SCALE));
        }
    }

    private void applyPostSpawn(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            int level,
            boolean elite,
            @Nonnull ScalingDataCache.MonsterScaledData scaledData
    ) {
        // ── 1. HP Scaling ────────────────────────────────────────────
        int targetHp = elite ? scaledData.eliteHp() : scaledData.scaledHp();
        float damageMult = elite ? (scaledData.eliteDamage() / Math.max(scaledData.scaledDamage(), 0.01f)) * scaledData.damageMult() : scaledData.damageMult();

        // Apply ±5% variance
        float variance = 1.0f + (ThreadLocalRandom.current().nextFloat() * 2 * STAT_VARIANCE - STAT_VARIANCE);
        targetHp = Math.round(targetHp * variance);
        damageMult *= variance;

        // Clamp
        targetHp = Math.max(targetHp, 1);
        targetHp = Math.min(targetHp, 10_000);
        damageMult = Math.max(damageMult, 1.0f);

        // Get the role's initial max health to compute the delta
        Role role = npcEntity.getRole();
        int initialMaxHealth = role != null ? role.getInitialMaxHealth() : 20;

        if (targetHp > initialMaxHealth) {
            EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap != null) {
                int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
                float delta = targetHp - initialMaxHealth;
                StaticModifier modifier = new StaticModifier(
                        Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.ADDITIVE,
                        delta
                );
                statMap.putModifier(healthIndex, LEVEL_SCALE_MODIFIER_KEY, modifier);
                statMap.maximizeStatValue(healthIndex);
            }
        }

        // ── 2. Register in NpcLevelRegistry ──────────────────────────
        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComp != null) {
            UUID uuid = uuidComp.getUuid();
            levelRegistry.register(uuid, new NpcLevelRegistry.NpcLevelData(
                    level, elite, roleName, damageMult
            ));
        }

        LOGGER.at(Level.INFO).log("Spawned %s%s Lv.%d — HP: %d, DmgMult: %.2f",
                elite ? "ELITE " : "", roleName, level, targetHp, damageMult);
    }
}
