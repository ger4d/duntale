package com.duntale.progression;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
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
import java.util.logging.Level;

/**
 * Spawns enemy NPCs with scaled stats for a given dungeon level.
 *
 * <p>Uses {@link NPCPlugin#spawnEntity} with pre/post callbacks to:
 * <ol>
 *   <li>Compute HP and damage multiplier via {@link CombatScaling}</li>
 *   <li>Apply an HP modifier via {@link EntityStatMap#putModifier}</li>
 *   <li>Attach a {@link CombatScalingComponent} for damage-time lookups</li>
 *   <li>Set a display name with level prefix and variant indicator</li>
 *   <li>Optionally scale elite NPCs visually</li>
 * </ol>
 */
public class LeveledNpcSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVEL_SCALE_MODIFIER_KEY = "Duntale_LevelScale";

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;

    /**
     * Creates a new spawner.
     *
     * @param combatScalingType the registered combat scaling component type
     */
    public LeveledNpcSpawner(@Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType) {
        this.combatScalingType = combatScalingType;
    }

    /**
     * Spawns a leveled enemy NPC at the given position.
     *
     * @param store    the entity store
     * @param roleName the NPC role name (e.g. "Zombie")
     * @param position the spawn position
     * @param level    the dungeon level (1-60)
     * @param variant  the NPC variant (NORMAL, ELITE, or BOSS)
     * @return the spawned entity pair, or {@code null} if spawning failed
     */
    @Nullable
    public Pair<Ref<EntityStore>, NPCEntity> spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            @Nonnull Vector3d position,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
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

        return npcPlugin.spawnEntity(store, roleIndex, position, null, null,
                (npcEntity, holder, s) -> {
                    applyPreAdd(holder, roleName, level, variant);
                },
                (npcEntity, ref, s) -> {
                    applyPostSpawn(npcEntity, ref, s, roleName, level, variant);
                });
    }

    private void applyPreAdd(
            @Nonnull Holder<EntityStore> holder,
            @Nonnull String roleName,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
    ) {
        String nameplate = switch (variant) {
            case ELITE -> "[Lv." + level + " *] " + roleName;
            case BOSS  -> "[Lv." + level + " BOSS] " + roleName;
            default    -> "[Lv." + level + "] " + roleName;
        };
        holder.putComponent(Nameplate.getComponentType(), new Nameplate(nameplate));

        if (variant == CombatScaling.NpcVariant.ELITE) {
            holder.putComponent(EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(CombatScaling.ELITE_VISUAL_SCALE));
        }
    }

    private void applyPostSpawn(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
    ) {
        Role role = npcEntity.getRole();
        int baseHp = role != null ? role.getInitialMaxHealth() : 20;

        // Single call handles base curve + variant multipliers
        int targetHp = CombatScaling.npcScaledHp(baseHp, level, variant);
        float damageMult = CombatScaling.npcDamageMult(level, variant);

        // Apply +-5% variance
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

        // Attach ECS component (replaces NpcLevelRegistry.register)
        store.putComponent(ref, combatScalingType,
            new CombatScalingComponent(level, damageMult, false, variant));

        LOGGER.atInfo().log("Spawned %s%s Lv.%d — HP: %d, DmgMult: %.2f",
                variant != CombatScaling.NpcVariant.NORMAL ? variant.name() + " " : "",
                roleName, level, targetHp, damageMult);
    }
}
