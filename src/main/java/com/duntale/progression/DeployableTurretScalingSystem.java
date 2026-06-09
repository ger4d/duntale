package com.duntale.progression;

import com.hypixel.hytale.builtin.deployables.component.DeployableComponent;
import com.hypixel.hytale.builtin.deployables.config.DeployableConfig;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

/**
 * Stamps player-owned turret deployables with a level-scaled {@link CombatScalingComponent}
 * at spawn time, so the existing {@link CombatScalingSystem} companion branch multiplies each
 * arrow's damage by the owner's progression-scaled multiplier.
 *
 * <p>The turret's damage source is the turret entity (not the owner), so without this stamp the
 * arrow deals only the flat {@code ProjectileDamage} from the deployable asset. By attaching a
 * companion-flagged {@link CombatScalingComponent}, turret arrows scale identically to a held
 * weapon while leaving loot/companion systems untouched (they ignore companion-flagged refs).
 *
 * <p>Only the {@code "Turret"} deployable is stamped — Healing and Slowness totems are skipped.
 */
public class DeployableTurretScalingSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** The {@code Id} of the turret deployable config (see {@code Projectile_Config_Turret_Deploy.json}). */
    private static final String TURRET_CONFIG_ID = "Turret";

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;
    private final ProgressionService progressionService;

    /**
     * Creates a new deployable turret scaling system.
     *
     * @param combatScalingType  the registered combat scaling component type
     * @param progressionService the progression service used to resolve the owner's level
     */
    public DeployableTurretScalingSystem(
            @Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType,
            @Nonnull ProgressionService progressionService
    ) {
        this.combatScalingType = Objects.requireNonNull(combatScalingType, "combatScalingType");
        this.progressionService = Objects.requireNonNull(progressionService, "progressionService");
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

        DeployableComponent deployableComponent = store.getComponent(ref, DeployableComponent.getComponentType());
        if (deployableComponent == null) {
            return;
        }

        DeployableConfig config = deployableComponent.getConfig();
        if (config == null || !TURRET_CONFIG_ID.equals(config.getId())) {
            return;
        }

        UUID owner = deployableComponent.getOwnerUUID();
        if (owner == null) {
            return;
        }

        int level = CombatScaling.clampLevel(Math.max(1, progressionService.getLevel(owner)));
        float mult = CombatScaling.applyVariance(CombatScaling.turretDamageMult(level));
        commandBuffer.putComponent(ref, combatScalingType, new CombatScalingComponent(level, mult, true));

        LOGGER.atInfo().log("Stamped turret deployable owned by %s at level %d (damage mult %.2f)", owner, level, mult);
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
        return DeployableComponent.getComponentType();
    }
}
