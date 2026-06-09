package com.duntale.companion;

import com.hypixel.hytale.builtin.deployables.component.DeployableComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Cancels friendly-fire damage dealt to companions.
 *
 * <p>Runs inside the {@code FilterDamage} group. When the victim carries a
 * {@link CompanionComponent}, the damage is cancelled if its source is <em>any</em> player or
 * <em>any</em> player-deployed turret/deployable — covering both the companion's own owner and,
 * in multiplayer, other players and their turrets. Damage from enemy NPCs is left untouched so
 * companions still take normal combat damage.
 *
 * <p>This complements the engine's {@code FilterPlayerFlockDamageSystem}, which protects only
 * <em>player</em> victims in a flock — companions (NPC victims) have no such protection by default.
 */
public class CompanionFriendlyFireSystem extends DamageEventSystem {

    @Nonnull
    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;

    /**
     * Creates a new companion friendly-fire system.
     *
     * @param companionComponentType the registered companion component type (also the victim query)
     */
    public CompanionFriendlyFireSystem(
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType
    ) {
        this.companionComponentType = Objects.requireNonNull(companionComponentType, "companionComponentType");
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return companionComponentType;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!sourceRef.isValid()) {
            return;
        }

        boolean fromPlayer = store.getComponent(sourceRef, Player.getComponentType()) != null;
        boolean fromDeployable = store.getComponent(sourceRef, DeployableComponent.getComponentType()) != null;

        if (shouldCancelDamage(fromPlayer, fromDeployable)) {
            damage.setCancelled(true);
        }
    }

    /**
     * Decides whether damage to a companion should be cancelled based on the source's nature.
     *
     * <p>Pure, framework-free decision for direct unit testing: damage is cancelled when the
     * source is a player or a player-deployed turret/deployable, and allowed otherwise (e.g.
     * enemy NPCs, traps, environment).
     *
     * @param fromPlayer     {@code true} if the damage source carries a {@code Player} component
     * @param fromDeployable {@code true} if the damage source carries a {@code DeployableComponent}
     * @return {@code true} when the damage should be cancelled
     */
    static boolean shouldCancelDamage(boolean fromPlayer, boolean fromDeployable) {
        return fromPlayer || fromDeployable;
    }
}
