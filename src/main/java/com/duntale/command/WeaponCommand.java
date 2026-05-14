package com.duntale.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

public class WeaponCommand extends CommandBase {

    public WeaponCommand() {
        super("weapon", "Weapon testing utils");
        this.addSubCommand(new TestSubCommand());
    }

    @Override
    protected void executeSync(CommandContext context) {
         context.sendMessage(Message.raw("Usage: /weapon test"));
    }

    private static class TestSubCommand extends AbstractPlayerCommand {
        
        // List from user request
        private static final List<String> WEAPON_IDS = Arrays.asList(
            "Weapon_Assault_Rifle",
            "Weapon_Grenade_Frag",
            "Weapon_Gun",
            "Weapon_Gun_Blunderbuss",
            "Weapon_Gun_Blunderbuss_Rusty",
            "Weapon_Handgun"
        );

        public TestSubCommand() {
            super("test", "Give all test weapons");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            CombinedItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
            int addedCount = 0;

            for (String id : WEAPON_IDS) {
                Item item = Item.getAssetMap().getAsset(id);
                if (item != null) {
                    try {
                        combined.addItemStack(new ItemStack(item.getId(), 1));
                        addedCount++;
                    } catch (Exception e) {
                        context.sendMessage(Message.raw("Failed to add " + id + ": " + e.getMessage()));
                    }
                } else {
                    context.sendMessage(Message.raw("Warning: Weapon ID not found: " + id));
                }
            }
            context.sendMessage(Message.raw("Added " + addedCount + " weapons to your inventory."));
            
            // Auto-give Ammo
            int ammoAdded = 0;
            for (Item item : Item.getAssetMap().getAssetMap().values()) {
                if (item.getId().contains("Ammo") || item.getId().equals("Weapon_Arrow_Crude") || item.getId().equals("Weapon_Arrow") || item.getId().equals("Weapon_Ball_Iron")) {
                     try {
                        combined.addItemStack(new ItemStack(item.getId(), 64));
                        ammoAdded++;
                    } catch (Exception e) {
                        // ignore failed ammo additions
                    }
                }
            }
            context.sendMessage(Message.raw("Added " + ammoAdded + " types of ammo."));
        }
    }
}
