package com.duntale.items;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

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
}
