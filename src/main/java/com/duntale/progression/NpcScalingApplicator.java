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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies shared Duntale combat scaling to NPCs at holder-stage and ref-stage.
 *
 * <p>Mapped roles are normalized to an archetype anchor via {@link NpcArchetypeRegistry}: HP derives
 * from the anchor base instead of the role's (wildly non-uniform) asset base, and the damage
 * multiplier is a corrective ratio that retargets the role's average attack damage to the anchor.
 * This keeps enemies of the same archetype comparable regardless of their authored asset stats.
 * Unmapped roles keep the legacy asset-base scaling (with a one-time warning per role).
 */
public class NpcScalingApplicator {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVEL_SCALE_MODIFIER_KEY = "Duntale_LevelScale";
    private static final int FALLBACK_BASE_HP = 20;
    private static final int MIN_SCALED_HP = 1;
    private static final float MIN_LEGACY_DAMAGE_MULTIPLIER = 1.0f;

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;
    private final NpcArchetypeRegistry archetypeRegistry;

    /** Role names already warned about as unmapped, to keep the legacy-path log to one line per role. */
    private final Set<String> warnedUnmappedRoles = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new applicator.
     *
     * <p>Scaled HP is no longer clamped to an upper ceiling: it is exactly what the scaling formula
     * yields (the curve saturates, so the top boss resolves to a designed maximum on its own), with
     * only a lower floor of {@link #MIN_SCALED_HP}.
     *
     * @param combatScalingType the registered combat scaling component type
     * @param archetypeRegistry  the archetype-anchor registry resolving role normalization
     */
    public NpcScalingApplicator(
            @Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType,
            @Nonnull NpcArchetypeRegistry archetypeRegistry
    ) {
        this.combatScalingType = Objects.requireNonNull(combatScalingType, "combatScalingType");
        this.archetypeRegistry = Objects.requireNonNull(archetypeRegistry, "archetypeRegistry");
    }

    /**
     * Creates a shared scaling profile for the requested NPC role and dungeon level with no per-floor
     * difficulty compensation (multiplier {@code 1.0}).
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
        return createProfile(roleName, level, variant, 1.0f);
    }

    /**
     * Creates a shared scaling profile for the requested NPC role and dungeon level, applying a
     * per-floor difficulty compensation factor to both damage and (at the health stage) HP.
     *
     * <p>The factor closes the gap between a floor's hand-authored enemy count and its target challenge:
     * a sparse floor's fewer fights are made nastier so the total threat still tracks the rising
     * challenge budget. A factor of {@code 1.0} is inert and reproduces the no-compensation behavior.
     *
     * @param roleName the NPC role name
     * @param level the dungeon level
     * @param variant the combat scaling variant
     * @param difficultyMult the per-floor difficulty multiplier applied to damage and HP ({@code 1.0} = none)
     * @return the shared scaling profile
     */
    @Nonnull
    public NpcScalingProfile createProfile(
            @Nonnull String roleName,
            int level,
            @Nonnull CombatScaling.NpcVariant variant,
            float difficultyMult
    ) {
        Objects.requireNonNull(roleName, "roleName");
        Objects.requireNonNull(variant, "variant");

        NpcArchetypeRegistry.ResolvedArchetype resolved = archetypeRegistry.resolve(roleName);
        float baseDamageMult = CombatScaling.npcDamageMult(level, variant);

        float damageMultiplier;
        String archetype;
        int anchorBaseHp;
        if (resolved != null && resolved.assetBaseDamage() > 0f) {
            // Corrective ratio: retarget the role's average attack damage to the archetype anchor
            // while preserving the relative spread between its individual attack moves.
            float ratio = resolved.effectiveBaseDamage() / resolved.assetBaseDamage();
            damageMultiplier = CombatScaling.applyVariance(baseDamageMult * ratio);
            archetype = resolved.name();
            anchorBaseHp = resolved.effectiveBaseHp();
        } else {
            // Legacy path: unmapped role (or zero/negative asset base damage). Keep the historic
            // behavior, including the >= 1.0 floor, and warn once per role.
            damageMultiplier = Math.max(
                    CombatScaling.applyVariance(baseDamageMult), MIN_LEGACY_DAMAGE_MULTIPLIER);
            // A resolved mapping with a bad asset base damage still normalizes HP to its anchor.
            archetype = resolved != null ? resolved.name() : null;
            anchorBaseHp = resolved != null ? resolved.effectiveBaseHp() : 0;
            if (resolved == null && warnedUnmappedRoles.add(roleName)) {
                LOGGER.atWarning().log(
                        "NPC role %s has no archetype mapping - using legacy asset-base scaling", roleName);
            }
        }

        // Difficulty compensation multiplies damage now; HP is applied at the health stage, so the
        // factor is carried on the profile and re-applied in computeTargetHp.
        damageMultiplier *= difficultyMult;

        return new NpcScalingProfile(
                roleName,
                level,
                variant,
                formatDisplayName(roleName, level, variant),
                damageMultiplier,
                archetype,
                anchorBaseHp,
                difficultyMult
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
        // Mapped roles scale from the archetype anchor; unmapped roles scale from the asset base.
        int baseHp = profile.anchorBaseHp() > 0 ? profile.anchorBaseHp() : initialMaxHealth;
        int targetHp = computeTargetHp(baseHp, profile.level(), profile.variant(), profile.difficultyMult());

        // Normalization needs negative deltas too (e.g. Werewolf asset base 283 vs Heavy anchor),
        // so apply the additive MAX modifier whenever the target differs from the engine's base.
        if (targetHp != initialMaxHealth) {
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
                "Applied Duntale NPC scaling to %s%s Lv.%d - HP: %d, DmgMult: %.2f",
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

    private static int computeTargetHp(
            int baseHp, int level, @Nonnull CombatScaling.NpcVariant variant, float difficultyMult) {
        // Per-floor difficulty compensation scales the on-level HP so a sparse floor's fewer enemies
        // still total the target challenge. Inert at the default multiplier of 1.0.
        float scaled = CombatScaling.npcScaledHp(baseHp, level, variant) * difficultyMult;
        int targetHp = Math.round(CombatScaling.applyVariance(scaled));
        // No upper clamp: the scaling curve saturates, so HP tops out at a designed maximum on its own.
        return Math.max(targetHp, MIN_SCALED_HP);
    }
}