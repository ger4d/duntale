package com.duntale.progression;

import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceService;
import com.duntale.dungeon.DungeonInstanceState;
import com.duntale.dungeon.FloorConfigService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * Applies Duntale combat scaling to selected built-in NPC spawns in active dungeon worlds.
 */
public class BuiltInNpcSpawnScalingSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Built-in roles that spawn dynamically at runtime (mage/brood summons) rather than via the
    // Duntale LeveledNpcSpawner, so they would otherwise reach the world at unscaled base stats.
    // "Skeleton" and "Scarak_Louse" are summoned adds; the Wolf_* roles are the companions summoned
    // by Template_Trork_Mage casters (Outlander_Sorcerer, Trork_Shaman) via their Combat.Summon state.
    @Nonnull
    private static final Set<String> ALLOWLISTED_SPECIAL_ROLES =
            Set.of("Skeleton", "Scarak_Louse", "Wolf_Outlander_Sorcerer", "Wolf_Outlander_Priest", "Wolf_Trork_Shaman", "Wolf_Trork_Hunter", "Wolf_Wife", "Wolf_Black");

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;
    private final DungeonInstanceService dungeonInstanceService;
    private final NpcScalingApplicator scalingApplicator;
    private final FloorConfigService floorConfigService;

    /**
     * Creates a new built-in NPC spawn scaling system.
     *
     * @param combatScalingType the registered combat scaling component type
     * @param dungeonInstanceService the dungeon instance service used for world-to-floor resolution
     * @param scalingApplicator the shared NPC scaling applicator
     * @param floorConfigService the floor config service supplying per-floor combat difficulty knobs
     */
    public BuiltInNpcSpawnScalingSystem(
            @Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType,
            @Nonnull DungeonInstanceService dungeonInstanceService,
            @Nonnull NpcScalingApplicator scalingApplicator,
            @Nonnull FloorConfigService floorConfigService
    ) {
        this.combatScalingType = Objects.requireNonNull(combatScalingType, "combatScalingType");
        this.dungeonInstanceService = Objects.requireNonNull(dungeonInstanceService, "dungeonInstanceService");
        this.scalingApplicator = Objects.requireNonNull(scalingApplicator, "scalingApplicator");
        this.floorConfigService = Objects.requireNonNull(floorConfigService, "floorConfigService");
    }

    @Override
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (reason != AddReason.SPAWN) {
            return;
        }

        CombatScalingComponent combatScalingComponent = store.getComponent(ref, combatScalingType);

        NPCEntity npcEntity = store.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        String roleName = resolveRoleName(npcEntity);
        if (!shouldScaleSpawn(roleName, combatScalingComponent != null)) {
            LOGGER.atInfo()
                    .log("Skipping built-in NPC scaling for role %s because it should not be scaled", roleName);
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        DungeonInstance instance;
        try {
            instance = dungeonInstanceService.getInstanceByWorld(world.getName());
        } catch (SQLException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("Failed to resolve dungeon instance for world %s while scaling built-in NPC spawn", world.getName());
            return;
        }

        if (instance == null || instance.state() != DungeonInstanceState.ACTIVE) {
            return;
        }

        // Per-floor difficulty compensation: a sparse floor's fewer fights are made nastier (more
        // Elites and/or a flat HP/damage bump) so the total threat still tracks the rising challenge
        // budget. Inert by default (eliteRate 0.0, difficultyMult 1.0) until the pacing solver authors
        // the combat.* overrides.
        FloorConfigService.CombatConfig combatConfig =
                floorConfigService.getCombatConfigForFloor(instance.floorLevel());
        CombatScaling.NpcVariant variant =
                rollVariant(combatConfig.eliteRate(), instance.instanceId(), ref.getIndex());
        float difficultyMult = (float) combatConfig.difficultyMult();

        NpcScalingProfile profile = scalingApplicator.createProfile(
                roleName,
                instance.floorLevel(),
                variant,
                difficultyMult
        );
        scalingApplicator.applyToSpawnedNpc(npcEntity, ref, store, commandBuffer, profile);
    }

    /**
     * Rolls the combat variant for a built-in spawn from the floor's Elite rate. The allowlisted
     * roles handled here are summoned adds that otherwise spawn at the {@code NORMAL} variant, so a
     * positive Elite rate may promote them; no role reaches this path already designated BOSS/ELITE,
     * so no double-promotion can occur.
     *
     * <p>The roll is deterministic on the instance id and the entity's ref index so a spawn's variant
     * is stable across re-scaling or reloads (the same entity always rolls the same value). An Elite
     * rate of {@code 0.0} always yields {@code NORMAL} and {@code 1.0} always {@code ELITE}.
     *
     * @param eliteRate   the per-spawn Elite promotion chance in {@code [0.0, 1.0]}
     * @param instanceId  the active dungeon instance id (part of the deterministic seed)
     * @param entityIndex the spawned entity's ref index (part of the deterministic seed)
     * @return {@code ELITE} when the deterministic roll is below the Elite rate, else {@code NORMAL}
     */
    @Nonnull
    static CombatScaling.NpcVariant rollVariant(
            double eliteRate,
            @Nonnull String instanceId,
            int entityIndex
    ) {
        if (eliteRate <= 0.0) {
            return CombatScaling.NpcVariant.NORMAL;
        }
        if (eliteRate >= 1.0) {
            return CombatScaling.NpcVariant.ELITE;
        }
        // Hash-mix the (instance, entity) seed to a well-distributed unit roll. A plain
        // Random(sequentialSeed).nextDouble() is NOT uniform for consecutive entity indices (its first
        // draw barely changes), which would make the realized Elite rate wildly off-target; the
        // SplitMix64 finalizer below decorrelates adjacent seeds while staying deterministic.
        long seed = ((long) instanceId.hashCode() << 32) ^ (entityIndex & 0xFFFFFFFFL);
        double roll = unitRoll(seed);
        return roll < eliteRate ? CombatScaling.NpcVariant.ELITE : CombatScaling.NpcVariant.NORMAL;
    }

    /** Maps a 64-bit seed to a well-distributed value in {@code [0, 1)} via the SplitMix64 finalizer. */
    private static double unitRoll(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        // Take the top 53 bits for a double in [0, 1), matching Random.nextDouble's resolution.
        return (z >>> 11) * 0x1.0p-53;
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    static boolean shouldScaleSpawn(@Nullable String roleName, boolean alreadyManagedByDuntale) {
        return !alreadyManagedByDuntale && isAllowlistedRole(roleName);
    }

    static boolean isAllowlistedRole(@Nullable String roleName) {
        return roleName != null && ALLOWLISTED_SPECIAL_ROLES.contains(roleName);
    }

    @Nullable
    private static String resolveRoleName(@Nonnull NPCEntity npcEntity) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin != null) {
            String roleName = npcPlugin.getName(npcEntity.getRoleIndex());
            if (roleName != null && !roleName.isBlank()) {
                return roleName;
            }
        }

        String roleName = npcEntity.getRoleName();
        if (roleName == null || roleName.isBlank()) {
            LOGGER.atFine().log("Skipping built-in NPC scaling because the role name could not be resolved");
            return null;
        }
        return roleName;
    }
}