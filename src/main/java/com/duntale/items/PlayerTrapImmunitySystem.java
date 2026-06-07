package com.duntale.items;

import com.duntale.rpg.RpgDamageScalingSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Cancels spike/snapjaw trap damage for players carrying the Immunity Trap Ring.
 *
 * <p>Trap blocks deal damage through an inline {@code ApplyEffect} whose
 * {@code ActiveEntityEffect} source carries a {@code Locale} of {@code "spikes"}
 * or {@code "snapjaw"} (see {@link CustomItems#TRAP_DAMAGE_LOCALES}). When such
 * damage targets a player who holds the ring anywhere in their inventory, it is
 * cancelled outright. The freeze component of snapjaw traps
 * ({@code HorizontalSpeedMultiplier == 0}) is intentionally left untouched.
 *
 * <p>Runs after {@link RpgDamageScalingSystem} so scaling is resolved before the
 * cancel check.
 */
public class PlayerTrapImmunitySystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private static final Query<EntityStore> QUERY = Player.getComponentType();

    @Nonnull
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, RpgDamageScalingSystem.class));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled() || !isTrapDamage(damage)) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (InventoryQuery.containsItem(store, targetRef, CustomItems.IMMUNITY_TRAP_RING)) {
            damage.setCancelled(true);
            LOGGER.atFine().log("[TrapImmunity] Cancelled trap damage for ring-holder ref=%s", targetRef);
        }
    }

    /**
     * Returns whether the given damage originates from a spike/snapjaw trap effect.
     *
     * @param damage the damage event
     * @return {@code true} if the damaging {@link ActiveEntityEffect}'s locale is a known trap locale
     */
    private static boolean isTrapDamage(@Nonnull Damage damage) {
        if (!(damage.getSource() instanceof ActiveEntityEffect effectSource)) {
            return false;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectSource.getEntityEffectIndex());
        if (effect == null) {
            return false;
        }
        String locale = effect.getLocale();
        return locale != null && CustomItems.TRAP_DAMAGE_LOCALES.contains(locale);
    }
}
