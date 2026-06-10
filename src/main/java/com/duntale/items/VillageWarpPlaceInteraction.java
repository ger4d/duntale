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
 * Custom Secondary (right-click) interaction for the Village Warp consumable.
 *
 * <p>Registered under the codec type {@code "Duntale_VillageWarpPlace"}.
 */
public class VillageWarpPlaceInteraction extends SimpleInstantInteraction {

    /** Codec for the village warp place interaction (no extra fields). */
    public static final BuilderCodec<VillageWarpPlaceInteraction> CODEC = BuilderCodec.builder(
                    VillageWarpPlaceInteraction.class, VillageWarpPlaceInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Right-click consumes the item and opens a village warp portal at the user's feet.")
            .build();

    public VillageWarpPlaceInteraction() {
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
        if (container == null || ItemStack.isEmpty(held) || !CustomItems.VILLAGE_WARP.equals(held.getItemId())) {
            return;
        }

        // Bridge to the world thread in the plugin to validate and place portal
        DuntalePlugin.get().placeVillageWarpPortal(playerRef);
    }

    @Nonnull
    @Override
    public String toString() {
        return "VillageWarpPlaceInteraction{} " + super.toString();
    }
}
