package com.duntale.items;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom Secondary (right-click) interaction for the Palporter consumable.
 *
 * <p>Registered under the codec type {@code "Duntale_PalporterOpen"}.
 */
public class PalporterOpenInteraction extends SimpleInstantInteraction {

    /** Codec for the palporter open interaction (no extra fields). */
    public static final BuilderCodec<PalporterOpenInteraction> CODEC = BuilderCodec.builder(
                    PalporterOpenInteraction.class, PalporterOpenInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Right-click opens a custom UI listing the other players in the player's current dungeon-instance world.")
            .build();

    public PalporterOpenInteraction() {
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
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        ItemContainer container = context.getHeldItemContainer();
        ItemStack held = context.getHeldItem();
        if (container == null || ItemStack.isEmpty(held) || !CustomItems.PALPORTER.equals(held.getItemId())) {
            return;
        }

        // Bridge to the world thread in the plugin to validate and open the page
        DuntalePlugin.get().openPalporterMenu(playerRef);
    }

    @Nonnull
    @Override
    public String toString() {
        return "PalporterOpenInteraction{} " + super.toString();
    }
}
