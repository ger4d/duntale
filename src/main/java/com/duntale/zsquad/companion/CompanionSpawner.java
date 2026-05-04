package com.duntale.zsquad.companion;

import com.duntale.zsquad.progression.CombatScaling;
import com.duntale.zsquad.progression.CombatScalingComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
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
import java.util.logging.Level;

/**
 * Spawns companion NPCs with level-scaled stats.
 *
 * <p>Companion scaling uses its own constants (independent of enemy NPC scaling).
 * The companion's base HP always comes from its own role definition, fixing the
 * low-level HP mismatch that occurred when companions shared the enemy scaling pipeline.
 *
 * @since 1.5.0
 */
public class CompanionSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVEL_SCALE_MODIFIER_KEY = "ZSquad_LevelScale";
    private static final String COMPANION_ROLE_PREFIX = "Companion_";
    public static final String WOLF_BLACK_ROLE = "Companion_Wolf_Black";

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;

    /**
     * Creates a new companion spawner.
     *
     * @param combatScalingType the registered combat scaling component type
     */
    public CompanionSpawner(@Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType) {
        this.combatScalingType = combatScalingType;
    }

    /**
     * Spawns a companion NPC at the given position with level-scaled stats.
     *
     * @param store    the entity store
    * @param roleName the NPC role name (e.g. "Companion_Wolf_Black")
     * @param position the spawn position
     * @param level    the companion's level (derived from player level)
     * @return the spawned entity pair, or {@code null} if spawning failed
     */
    @Nullable
    public Pair<Ref<EntityStore>, NPCEntity> spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            @Nonnull Vector3d position,
            int level
    ) {
        return spawn(store, roleName, null, position, level);
    }

    /**
     * Spawns a companion NPC at the given position with level-scaled stats.
     *
     * @param store the entity store
    * @param roleName the NPC role name (e.g. "Companion_Wolf_Black")
     * @param displayNameOverride optional player-chosen nameplate override
     * @param position the spawn position
     * @param level the companion's level (derived from player level)
     * @return the spawned entity pair, or {@code null} if spawning failed
     */
    @Nullable
    public Pair<Ref<EntityStore>, NPCEntity> spawn(
        @Nonnull Store<EntityStore> store,
        @Nonnull String roleName,
        @Nullable String displayNameOverride,
        @Nonnull Vector3d position,
        int level
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            LOGGER.at(Level.WARNING).log("NPCPlugin not available — cannot spawn companion %s", roleName);
            return null;
        }

        int roleIndex = npcPlugin.getIndex(roleName);
        if (roleIndex < 0) {
            LOGGER.at(Level.WARNING).log("Unknown companion role: %s", roleName);
            return null;
        }

        return npcPlugin.spawnEntity(store, roleIndex, position, null, null,
                (npcEntity, holder, s) -> {
                    applyPreAdd(holder, roleName, displayNameOverride, level);
                },
                (npcEntity, ref, s) -> {
                    applyPostSpawn(npcEntity, ref, s, level);
                });
    }

    private void applyPreAdd(
            @Nonnull Holder<EntityStore> holder,
            @Nonnull String roleName,
            @Nullable String displayNameOverride,
            int level
    ) {
        holder.putComponent(
                Nameplate.getComponentType(),
                new Nameplate(buildNameplateText(roleName, displayNameOverride, level))
        );
        holder.putComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
    }

    @Nonnull
    public static String buildNameplateText(
            @Nonnull String roleName,
            @Nullable String displayNameOverride,
            int level
    ) {
        return "[Lv." + level + "] " + resolveDisplayName(roleName, displayNameOverride);
    }

    @Nonnull
    public static String resolveDisplayName(
            @Nonnull String roleName,
            @Nullable String displayNameOverride
    ) {
        if (displayNameOverride != null) {
            String trimmed = displayNameOverride.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        if (WOLF_BLACK_ROLE.equals(roleName)) {
            return "Wolf";
        }

        String displayName = roleName;
        if (displayName.startsWith(COMPANION_ROLE_PREFIX)) {
            displayName = displayName.substring(COMPANION_ROLE_PREFIX.length());
        }
        return displayName.replace('_', ' ');
    }

    private void applyPostSpawn(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            int level
    ) {
        // Companion base HP from its own role definition
        Role role = npcEntity.getRole();
        int baseHp = role != null ? role.getInitialMaxHealth() : 20;

        int targetHp = CombatScaling.companionScaledHp(baseHp, level);
        float damageMult = CombatScaling.companionDamageMult(level);

        // Apply variance
        targetHp = Math.round(CombatScaling.applyVariance(targetHp));
        damageMult = CombatScaling.applyVariance(damageMult);

        // Clamp
        targetHp = Math.max(targetHp, 1);
        targetHp = Math.min(targetHp, 10_000);
        damageMult = Math.max(damageMult, 1.0f);

        // Apply HP delta via EntityStatMap
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

        // Attach ECS component
        store.putComponent(ref, combatScalingType,
                new CombatScalingComponent(level, damageMult, true));

        LOGGER.at(Level.INFO).log("Spawned companion Lv.%d — HP: %d, DmgMult: %.2f",
                level, targetHp, damageMult);
    }
}
