package com.duntale.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies an infinite percentage-based regen effect to players carrying a Healing Necklace.
 *
 * <p>Once per scan interval the system resolves the player's highest-tier necklace
 * (tier II beats tier I) and ensures exactly that tier's infinite regen
 * {@link EntityEffect} is active, removing any stale/lower-tier effect. The effect
 * is only added when absent, so the underlying {@code DamageCalculatorCooldown}
 * heal cadence is never reset. The effect is removed within one scan after the
 * necklace leaves the inventory.
 */
public class HealingNecklaceSystem extends DelayedEntitySystem<EntityStore> {

    /** Inventory rescan interval, in seconds. */
    private static final float INTERVAL = 1.0f;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            EffectControllerComponent.getComponentType());

    public HealingNecklaceSystem() {
        super(INTERVAL);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (effects == null) {
            return;
        }

        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        String wanted = resolveWantedEffect(store, ref);
        reconcileEffect(ref, store, effects, CustomItems.HEALING_NECKLACE_I_EFFECT,
                CustomItems.HEALING_NECKLACE_I_EFFECT.equals(wanted));
        reconcileEffect(ref, store, effects, CustomItems.HEALING_NECKLACE_II_EFFECT,
                CustomItems.HEALING_NECKLACE_II_EFFECT.equals(wanted));
    }

    /**
     * Resolves the regen effect ID for the player's highest-tier necklace, if any.
     *
     * @param store the entity store
     * @param ref   the player reference
     * @return the wanted effect ID, or {@code null} if no necklace is carried
     */
    @Nullable
    private static String resolveWantedEffect(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (InventoryQuery.containsItem(store, ref, CustomItems.HEALING_NECKLACE_II)) {
            return CustomItems.HEALING_NECKLACE_II_EFFECT;
        }
        if (InventoryQuery.containsItem(store, ref, CustomItems.HEALING_NECKLACE_I)) {
            return CustomItems.HEALING_NECKLACE_I_EFFECT;
        }
        return null;
    }

    /**
     * Adds the effect if it is wanted and absent, or removes it if it is present and unwanted.
     */
    private static void reconcileEffect(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull EffectControllerComponent effects,
                                        @Nonnull String effectId,
                                        boolean wanted) {
        int effectIndex = EntityEffect.getAssetMap().getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) {
            return;
        }
        boolean present = effects.hasEffect(effectIndex);
        if (wanted && !present) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectIndex);
            if (effect != null) {
                effects.addEffect(ref, effect, store);
            }
        } else if (!wanted && present) {
            effects.removeEffect(ref, effectIndex, RemovalBehavior.COMPLETE, store);
        }
    }
}
