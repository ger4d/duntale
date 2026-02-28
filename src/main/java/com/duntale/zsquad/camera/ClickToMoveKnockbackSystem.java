package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Reduces knockback for players in click-to-move mode.
 *
 * <p>The base engine applies a {@code 25×} knockback scale to players
 * ({@code HackKnockbackValues.PLAYER_KNOCKBACK_SCALE}). In isometric/top-down
 * camera modes this sends the player flying across the arena. This system runs
 * in the {@code FilterDamage} group and adds a scaling modifier to the
 * {@link KnockbackComponent} to reduce the effective knockback to a reasonable
 * level.</p>
 *
 * @since 1.0.0
 */
public class ClickToMoveKnockbackSystem extends DamageEventSystem {

    /**
     * Knockback reduction modifier applied to click-to-move players.
     * Combined with the engine's 25× multiplier, this yields
     * {@code 25 × 0.08 = 2×} effective knockback — a gentle push instead
     * of a launch.
     */
    private static final double KNOCKBACK_MODIFIER = 0.08;

    @Nonnull
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())
    );

    private final ClickToMoveManager clickToMoveManager;

    /**
     * Creates a new knockback clamping system.
     *
     * @param clickToMoveManager the CTM manager (to check if the target player is in CTM mode)
     */
    public ClickToMoveKnockbackSystem(@Nonnull ClickToMoveManager clickToMoveManager) {
        this.clickToMoveManager = clickToMoveManager;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        // Only interested in damage that carries knockback
        KnockbackComponent kb = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);
        if (kb == null) return;

        // Check if the damage target is a player in CTM mode
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(targetRef, PlayerRef.getComponentType());
        if (playerRef == null) return;

        if (!clickToMoveManager.isEnabled(playerRef.getUuid())) return;

        // Scale knockback down — the 25× engine multiplier is applied later,
        // and our modifier stacks multiplicatively via applyModifiers().
        kb.addModifier(KNOCKBACK_MODIFIER);
    }
}
