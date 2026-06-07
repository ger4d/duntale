package com.duntale.items;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Custom Secondary (right-click) interaction for the +1 Stat Point token.
 *
 * <p>Consumes one token from the held stack and grants the player one unassigned
 * RPG stat point. The token is consumed <em>before</em> the point is granted, and
 * because {@code firstRun} runs once per click on the entity's {@code WorldThread},
 * spamming a stack stays exactly 1:1 (no free or duplicated points).
 *
 * <p>Registered under the codec type {@code "Duntale_GrantStatPoint"}.
 */
public class GrantStatPointInteraction extends SimpleInstantInteraction {

    private static final String COLOR_GREEN = "#55FF55";

    /** Codec for the grant-stat-point interaction (no extra fields). */
    public static final BuilderCodec<GrantStatPointInteraction> CODEC = BuilderCodec.builder(
                    GrantStatPointInteraction.class, GrantStatPointInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Consumes one token and grants the user a single RPG stat point (Duntale Stat Point Token).")
            .build();

    public GrantStatPointInteraction() {
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

        ItemContainer container = context.getHeldItemContainer();
        byte slot = context.getHeldItemSlot();
        ItemStack held = context.getHeldItem();
        if (container == null || ItemStack.isEmpty(held) || !CustomItems.STAT_POINT_TOKEN.equals(held.getItemId())) {
            return;
        }

        // Consume one token first; abort the grant if consumption fails.
        if (!container.removeItemStackFromSlot(slot, held, 1, true, true).succeeded()) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        UUID playerId = uuidComponent.getUuid();
        DuntalePlugin.get().getRpgService().grantStatPoints(playerId, 1);
        ItemVfx.spawnConfirmation(commandBuffer, ref, CustomItems.STAT_POINT_VFX);

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            playerRef.sendMessage(Message.raw("+1 Stat Point").color(COLOR_GREEN));
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "GrantStatPointInteraction{} " + super.toString();
    }
}
