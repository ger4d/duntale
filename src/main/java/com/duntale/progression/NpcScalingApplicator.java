package com.duntale.progression;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Applies shared Duntale combat scaling to NPCs at holder-stage and ref-stage.
 */
public class NpcScalingApplicator {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVEL_SCALE_MODIFIER_KEY = "Duntale_LevelScale";
    private static final int FALLBACK_BASE_HP = 20;
    private static final int MIN_SCALED_HP = 1;
    private static final int MAX_SCALED_HP = 10_000;

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;

    /**
     * Creates a new applicator.
     *
     * @param combatScalingType the registered combat scaling component type
     */
    public NpcScalingApplicator(@Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType) {
        this.combatScalingType = Objects.requireNonNull(combatScalingType, "combatScalingType");
    }

    /**
     * Creates a shared scaling profile for the requested NPC role and dungeon level.
     *
     * @param roleName the NPC role name
     * @param level the dungeon level
     * @param variant the combat scaling variant
     * @return the shared scaling profile
     */
    @Nonnull
    public NpcScalingProfile createProfile(
            @Nonnull String roleName,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
    ) {
        Objects.requireNonNull(roleName, "roleName");
        Objects.requireNonNull(variant, "variant");

        float damageMultiplier = CombatScaling.applyVariance(CombatScaling.npcDamageMult(level, variant));
        return new NpcScalingProfile(
                roleName,
                level,
                variant,
                formatDisplayName(roleName, level, variant),
                damageMultiplier
        );
    }

    /**
     * Applies pre-add scaling components to an NPC holder.
     *
     * @param holder the NPC holder before insertion into the store
     * @param profile the shared scaling profile
     */
    public void applyToHolder(@Nonnull Holder<EntityStore> holder, @Nonnull NpcScalingProfile profile) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(profile, "profile");

        holder.putComponent(Nameplate.getComponentType(), new Nameplate(profile.displayName()));
        if (profile.variant() == CombatScaling.NpcVariant.ELITE) {
            holder.putComponent(
                    EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(CombatScaling.ELITE_VISUAL_SCALE)
            );
        }
        holder.putComponent(
                combatScalingType,
                new CombatScalingComponent(profile.level(), profile.damageMultiplier(), false, profile.variant())
        );
    }

    /**
     * Applies post-add health scaling to a spawned NPC.
     *
     * @param npcEntity the spawned NPC entity
     * @param ref the entity reference
     * @param store the entity store
     * @param profile the shared scaling profile
     */
    public void applyHealthToSpawnedNpc(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull NpcScalingProfile profile
    ) {
        Objects.requireNonNull(npcEntity, "npcEntity");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(profile, "profile");

        int initialMaxHealth = resolveInitialMaxHealth(npcEntity);
        int targetHp = computeTargetHp(initialMaxHealth, profile.level(), profile.variant());

        if (targetHp > initialMaxHealth) {
            EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) {
                LOGGER.atWarning().log("Missing EntityStatMap while applying NPC scaling to %s", profile.roleName());
            } else {
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

        LOGGER.atInfo().log(
                "Applied Duntale NPC scaling to %s%s Lv.%d — HP: %d, DmgMult: %.2f",
                profile.variant() != CombatScaling.NpcVariant.NORMAL ? profile.variant().name() + " " : "",
                profile.roleName(),
                profile.level(),
                targetHp,
                profile.damageMultiplier()
        );
    }

    /**
     * Applies ref-stage scaling to an already spawned NPC.
     *
     * @param npcEntity the spawned NPC entity
     * @param ref the entity reference
     * @param store the entity store
     * @param commandBuffer the command buffer for component writes
     * @param profile the shared scaling profile
     */
    public void applyToSpawnedNpc(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull NpcScalingProfile profile
    ) {
        Objects.requireNonNull(npcEntity, "npcEntity");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(profile, "profile");

        commandBuffer.putComponent(ref, Nameplate.getComponentType(), new Nameplate(profile.displayName()));
        if (profile.variant() == CombatScaling.NpcVariant.ELITE) {
            commandBuffer.putComponent(
                    ref,
                    EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(CombatScaling.ELITE_VISUAL_SCALE)
            );
        }
        commandBuffer.putComponent(
                ref,
                combatScalingType,
                new CombatScalingComponent(profile.level(), profile.damageMultiplier(), false, profile.variant())
        );
        applyHealthToSpawnedNpc(npcEntity, ref, store, profile);
    }

    @Nonnull
    private static String formatDisplayName(
            @Nonnull String roleName,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
    ) {
        return switch (variant) {
            case ELITE -> "[Lv." + level + " *] " + roleName;
            case BOSS -> "[Lv." + level + " BOSS] " + roleName;
            default -> "[Lv." + level + "] " + roleName;
        };
    }

    private static int resolveInitialMaxHealth(@Nonnull NPCEntity npcEntity) {
        Role role = npcEntity.getRole();
        return role != null ? role.getInitialMaxHealth() : FALLBACK_BASE_HP;
    }

    private static int computeTargetHp(int baseHp, int level, @Nonnull CombatScaling.NpcVariant variant) {
        int targetHp = CombatScaling.npcScaledHp(baseHp, level, variant);
        targetHp = Math.round(CombatScaling.applyVariance(targetHp));
        targetHp = Math.max(targetHp, MIN_SCALED_HP);
        return Math.min(targetHp, MAX_SCALED_HP);
    }
}