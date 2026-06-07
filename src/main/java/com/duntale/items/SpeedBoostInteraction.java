package com.duntale.items;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom Secondary (right-click) interaction for the Speed Boots items.
 *
 * <p>Grants the using player a temporary additive click-to-move speed bonus via
 * {@link SpeedBoostManager}. The bonus magnitude ({@code Bonus}) and lifetime
 * ({@code DurationSeconds}) are configured per item in the asset JSON; the
 * re-use cooldown is enforced by the item's JSON {@code Cooldown} block.
 *
 * <p>Registered under the codec type {@code "Duntale_SpeedBoost"}. {@code firstRun}
 * executes on the entity's {@code WorldThread}, so direct ECS reads are safe.
 */
public class SpeedBoostInteraction extends SimpleInstantInteraction {

    /** Codec exposing the configurable {@code Bonus} and {@code DurationSeconds} fields. */
    public static final BuilderCodec<SpeedBoostInteraction> CODEC = BuilderCodec.builder(
                    SpeedBoostInteraction.class, SpeedBoostInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Grants the user a temporary click-to-move speed bonus (Duntale Speed Boots).")
            .<Float>appendInherited(
                    new KeyedCodec<>("Bonus", Codec.FLOAT),
                    (interaction, value) -> interaction.bonus = value,
                    interaction -> interaction.bonus,
                    (interaction, parent) -> interaction.bonus = parent.bonus)
            .documentation("Flat move-speed bonus to add while active.")
            .add()
            .<Float>appendInherited(
                    new KeyedCodec<>("DurationSeconds", Codec.FLOAT),
                    (interaction, value) -> interaction.durationSeconds = value,
                    interaction -> interaction.durationSeconds,
                    (interaction, parent) -> interaction.durationSeconds = parent.durationSeconds)
            .documentation("How long the speed bonus lasts, in seconds.")
            .add()
            .build();

    private static final float DEFAULT_DURATION_SECONDS = 4.0f;

    private float bonus = 0.0f;
    private float durationSeconds = DEFAULT_DURATION_SECONDS;

    public SpeedBoostInteraction() {
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
                            @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }
        Ref<EntityStore> ref = context.getEntity();
        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        DuntalePlugin.get().getSpeedBoostManager().apply(uuidComponent.getUuid(), this.bonus, this.durationSeconds);
        ItemVfx.spawnConfirmation(commandBuffer, ref, CustomItems.SPEED_BOOST_VFX);
    }

    @Nonnull
    @Override
    public String toString() {
        return "SpeedBoostInteraction{bonus=" + this.bonus + ", durationSeconds=" + this.durationSeconds + "} "
                + super.toString();
    }
}
