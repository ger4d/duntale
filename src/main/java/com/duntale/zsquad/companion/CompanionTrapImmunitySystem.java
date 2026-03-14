package com.duntale.zsquad.companion;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import javax.annotation.Nonnull;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Clears movement-freezing effects ({@code HorizontalSpeedMultiplier=0}) from companion NPCs
 * every 0.25 seconds to prevent them from getting permanently stuck in bear traps.
 *
 * <p>After removing the freezing effect, nudges the companion ~1 block in a random horizontal
 * direction so it exits the trap's collision zone and avoids immediate reapplication.
 */
public class CompanionTrapImmunitySystem extends DelayedEntitySystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float INTERVAL = 0.25f;
    private static final double ESCAPE_NUDGE = 1.0;

    private static final Query<EntityStore> QUERY = Query.and(
            CompanionComponent.getComponentType(),
            TransformComponent.getComponentType()
    );

    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;

    /**
     * Creates a new companion trap immunity system.
     *
     * @param companionComponentType the registered companion component type
     */
    public CompanionTrapImmunitySystem(
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType) {
        super(INTERVAL);
        this.companionComponentType = companionComponentType;
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

        if (chunk.getComponent(index, companionComponentType) == null) return;

        EffectControllerComponent effects = chunk.getComponent(
                index, EffectControllerComponent.getComponentType());
        if (effects == null || effects.getActiveEffects().isEmpty()) return;

        // Collect indexes of effects that zero out horizontal movement speed
        IntArrayList toRemove = null;
        for (Int2ObjectMap.Entry<ActiveEntityEffect> entry :
                effects.getActiveEffects().int2ObjectEntrySet()) {
            EntityEffect entityEffect = EntityEffect.getAssetMap().getAsset(entry.getIntKey());
            if (entityEffect == null) continue;
            ApplicationEffects appEffects = entityEffect.getApplicationEffects();
            if (appEffects != null && appEffects.getHorizontalSpeedMultiplier() == 0f) {
                if (toRemove == null) toRemove = new IntArrayList(2);
                toRemove.add(entry.getIntKey());
            }
        }

        if (toRemove == null) return;

        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        for (int i = 0; i < toRemove.size(); i++) {
            int effectIndex = toRemove.getInt(i);
            EntityEffect entityEffect = EntityEffect.getAssetMap().getAsset(effectIndex);
            LOGGER.atInfo().log("[CompanionTrapImmunity] Removing movement-freeze effect '%s' from companion ref=%s",
                    entityEffect != null ? entityEffect.getId() : effectIndex, ref);
            effects.removeEffect(ref, effectIndex, store);
        }

        // Nudge position to escape the trap's collision zone
        TransformComponent transform = chunk.getComponent(
                index, TransformComponent.getComponentType());
        if (transform != null) {
            Vector3d pos = transform.getPosition();
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            transform.setPosition(new Vector3d(
                    pos.x + Math.cos(angle) * ESCAPE_NUDGE,
                    pos.y,
                    pos.z + Math.sin(angle) * ESCAPE_NUDGE
            ));
        }
    }
}
