package com.duntale.merchant;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Admin/player command to spawn a merchant with a custom catalog at the player's position.
 *
 * <p>Usage: {@code /spawnmerchant <type>}
 * Currently supported types: village
 */
public class SpawnMerchantCommand extends AbstractPlayerCommand {

    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String MERCHANT_ROLE = "Dungeon_Merchant";

    private final RequiredArg<String> typeArg;

    public SpawnMerchantCommand() {
        super("spawnmerchant", "Spawn a Merchant NPC with a custom catalog at your position");
        this.typeArg = this.withRequiredArg("type", "Merchant catalog type (e.g. village)", ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve player.").color(COLOR_RED));
            return;
        }

        String type = typeArg.get(context);
        if (type == null || !type.equalsIgnoreCase("village")) {
            context.sendMessage(Message.raw("Invalid merchant type. Available types: village").color(COLOR_RED));
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            context.sendMessage(Message.raw("Could not resolve player position.").color(COLOR_RED));
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            context.sendMessage(Message.raw("NPCPlugin not available.").color(COLOR_RED));
            return;
        }

        int roleIndex = npcPlugin.getIndex(MERCHANT_ROLE);
        if (roleIndex < 0) {
            context.sendMessage(Message.raw("Unknown NPC role: " + MERCHANT_ROLE).color(COLOR_RED));
            return;
        }

        Vector3d position = transform.getPosition();

        var result = npcPlugin.spawnEntity(store, roleIndex, position, transform.getRotation(), null,
                (npcEntity, holder, s) -> {
                    holder.addComponent(MerchantComponent.getComponentType(),
                            new MerchantComponent(1, "VILLAGE"));
                },
                null);

        if (result != null) {
            context.sendMessage(Message.raw("Village Merchant spawned successfully!").color(COLOR_GREEN));
        } else {
            context.sendMessage(Message.raw("Failed to spawn Village Merchant.").color(COLOR_RED));
        }
    }
}
