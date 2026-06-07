package com.duntale.items;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spawns one-shot confirmation particle effects for custom-item interactions.
 *
 * <p>Used from interaction {@code firstRun} (on the {@code WorldThread}) on the
 * success path, so the VFX only plays when an item's effect actually applied.
 */
public final class ItemVfx {

    /** Vertical offset above the entity origin so the burst plays around the torso. */
    private static final double BODY_OFFSET_Y = 1.0;

    private ItemVfx() {
    }

    /**
     * Spawns a confirmation particle effect at the entity's body, visible to nearby players.
     *
     * @param commandBuffer the interaction command buffer (also a component accessor)
     * @param ref           the acting entity reference
     * @param particleId    the {@code ParticleSystem} asset ID to spawn
     */
    public static void spawnConfirmation(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull String particleId) {
        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = transform.getPosition();
        ParticleUtil.spawnParticleEffect(
                particleId,
                new Vector3d(position.x, position.y + BODY_OFFSET_Y, position.z),
                commandBuffer);
    }

    /**
     * Applies a cosmetic, time-limited {@link EntityEffect} whose model-attached
     * particles follow the entity for the effect's lifetime.
     *
     * <p>Used for activation VFX that must track the player while a buff is active
     * (e.g. the Speed Boots trail). The effect is re-applied with
     * {@link OverlapBehavior#OVERWRITE}, so re-using the item refreshes the timer
     * and restarts the trail rather than stacking. The engine detaches the attached
     * {@code ParticleSystem} automatically when the effect expires.
     *
     * @param commandBuffer   the interaction command buffer (also a component accessor)
     * @param ref             the acting entity reference
     * @param effectId        the {@code EntityEffect} asset ID to apply
     * @param durationSeconds how long the effect (and its trail) should last, in seconds
     */
    public static void applyFollowEffect(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull String effectId,
                                         float durationSeconds) {
        applyFollowEffect(commandBuffer, ref, effectId, Float.valueOf(durationSeconds));
    }

    /**
     * Applies a follow-effect using the {@link EntityEffect} asset's own {@code Duration}
     * (and {@code OverlapBehavior}) instead of an explicit runtime duration. Use this for
     * fixed-length confirmation VFX whose timing lives entirely in the effect asset.
     *
     * @param commandBuffer the interaction command buffer (also a component accessor)
     * @param ref           the acting entity reference
     * @param effectId      the {@code EntityEffect} asset ID to apply
     */
    public static void applyFollowEffect(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull String effectId) {
        applyFollowEffect(commandBuffer, ref, effectId, (Float) null);
    }

    private static void applyFollowEffect(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull String effectId,
                                          @Nullable Float durationSeconds) {
        EffectControllerComponent effects = commandBuffer.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects == null) {
            return;
        }
        int effectIndex = EntityEffect.getAssetMap().getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) {
            return;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectIndex);
        if (effect == null) {
            return;
        }
        if (durationSeconds != null) {
            effects.addEffect(ref, effectIndex, effect, durationSeconds, OverlapBehavior.OVERWRITE, commandBuffer);
        } else {
            effects.addEffect(ref, effectIndex, effect, commandBuffer);
        }
    }
}
