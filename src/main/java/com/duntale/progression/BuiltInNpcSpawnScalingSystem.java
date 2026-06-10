package com.duntale.progression;

import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceService;
import com.duntale.dungeon.DungeonInstanceState;
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

    /**
     * Creates a new built-in NPC spawn scaling system.
     *
     * @param combatScalingType the registered combat scaling component type
     * @param dungeonInstanceService the dungeon instance service used for world-to-floor resolution
     * @param scalingApplicator the shared NPC scaling applicator
     */
    public BuiltInNpcSpawnScalingSystem(
            @Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType,
            @Nonnull DungeonInstanceService dungeonInstanceService,
            @Nonnull NpcScalingApplicator scalingApplicator
    ) {
        this.combatScalingType = Objects.requireNonNull(combatScalingType, "combatScalingType");
        this.dungeonInstanceService = Objects.requireNonNull(dungeonInstanceService, "dungeonInstanceService");
        this.scalingApplicator = Objects.requireNonNull(scalingApplicator, "scalingApplicator");
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

        NpcScalingProfile profile = scalingApplicator.createProfile(
                roleName,
                instance.floorLevel(),
                CombatScaling.NpcVariant.NORMAL
        );
        scalingApplicator.applyToSpawnedNpc(npcEntity, ref, store, commandBuffer, profile);
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