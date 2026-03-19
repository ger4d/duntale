package com.duntale.zsquad.command;

import com.duntale.zsquad.progression.GearLevelService;
import com.duntale.zsquad.progression.ScalingDataCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
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

/**
 * Testing command for the dungeon gear scaling system.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /dgive weapon <name> <level>} — Give a leveled weapon</li>
 *   <li>{@code /dgive armor <name> <level>}  — Give a leveled armor piece</li>
 * </ul>
 */
public class DGiveCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String CYAN = "#55FFFF";

    private final ScalingDataCache scalingCache;

    /**
     * Creates a new /dgive command.
     *
     * @param scalingCache the scaling data cache
     */
    public DGiveCommand(@Nonnull ScalingDataCache scalingCache) {
        super("dgive", "Give leveled gear for dungeon testing");
        this.scalingCache = scalingCache;

        this.addSubCommand(new WeaponSubCommand());
        this.addSubCommand(new ArmorSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /dgive weapon <name> <level>").color(GRAY));
        context.sendMessage(Message.raw("  /dgive armor <name> <level>").color(GRAY));
    }

    // -- Weapon subcommand ---------------------------------------------

    private class WeaponSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> nameArg =
                this.withRequiredArg("name", "Weapon asset ID (e.g. Weapon_Sword_Cobalt)", ArgTypes.STRING);
        private final RequiredArg<Integer> levelArg =
                this.withRequiredArg("level", "Dungeon level (1-60)", ArgTypes.INTEGER);

        WeaponSubCommand() {
            super("weapon", "Give a leveled weapon");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String weaponId = nameArg.get(context);
            int level = levelArg.get(context);

            if (level < 1 || level > 60) {
                context.sendMessage(Message.raw("Level must be between 1 and 60.").color(RED));
                return;
            }

            Item item = Item.getAssetMap().getAsset(weaponId);
            if (item == null) {
                context.sendMessage(Message.raw("Unknown item: " + weaponId).color(RED));
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                context.sendMessage(Message.raw("Could not resolve player.").color(RED));
                return;
            }

            // Create the item stack with weapon level + random variance
            float variance = GearLevelService.rollVariance();
            ItemStack stack = new ItemStack(item.getId(), 1);
            stack = GearLevelService.setWeaponLevel(stack, level);
            stack = GearLevelService.setWeaponVariance(stack, variance);

            CombinedItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
            try {
                combined.addItemStack(stack);
            } catch (Exception e) {
                context.sendMessage(Message.raw("Failed to add item: " + e.getMessage()).color(RED));
                return;
            }

            // Fetch scaling data for feedback
            float damageMult = scalingCache.getWeaponMultiplier(weaponId, level);
            ScalingDataCache.WeaponBaseRow base = scalingCache.getWeaponBase(weaponId);

            context.sendMessage(
                    Message.raw("Gave ").color(GREEN)
                            .insert(Message.raw(weaponId).color(GOLD).bold(true))
                            .insert(Message.raw(" Lv." + level).color(CYAN))
            );

            if (base != null) {
                float scaledDmg = base.baseDamage() * damageMult * variance;
                context.sendMessage(
                        Message.raw("  Damage: ").color(GRAY)
                                .insert(Message.raw(String.format("%.1f", scaledDmg)).color(YELLOW))
                                .insert(Message.raw(String.format(" (var %.0f%%)", (variance - 1f) * 100)).color(GRAY))
                );
            }
        }
    }

    // -- Armor subcommand ----------------------------------------------

    private class ArmorSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> nameArg =
                this.withRequiredArg("name", "Armor asset ID (e.g. Armor_Chest_Cobalt)", ArgTypes.STRING);
        private final RequiredArg<Integer> levelArg =
                this.withRequiredArg("level", "Dungeon level (1-60)", ArgTypes.INTEGER);

        ArmorSubCommand() {
            super("armor", "Give a leveled armor piece");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String armorId = nameArg.get(context);
            int level = levelArg.get(context);

            if (level < 1 || level > 60) {
                context.sendMessage(Message.raw("Level must be between 1 and 60.").color(RED));
                return;
            }

            Item item = Item.getAssetMap().getAsset(armorId);
            if (item == null) {
                context.sendMessage(Message.raw("Unknown item: " + armorId).color(RED));
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                context.sendMessage(Message.raw("Could not resolve player.").color(RED));
                return;
            }

            // Create the item stack with armor level + random variance
            float variance = GearLevelService.rollVariance();
            ItemStack stack = new ItemStack(item.getId(), 1);
            stack = GearLevelService.setArmorLevel(stack, level);
            stack = GearLevelService.setArmorVariance(stack, variance);

            CombinedItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
            try {
                combined.addItemStack(stack);
            } catch (Exception e) {
                context.sendMessage(Message.raw("Failed to add item: " + e.getMessage()).color(RED));
                return;
            }

            // Fetch scaling data for feedback
            float effectiveDr = scalingCache.getArmorDR(armorId, level) * variance;
            ScalingDataCache.ArmorBaseRow base = scalingCache.getArmorBase(armorId);

            context.sendMessage(
                    Message.raw("Gave ").color(GREEN)
                            .insert(Message.raw(armorId).color(GOLD).bold(true))
                            .insert(Message.raw(" Lv." + level).color(CYAN))
            );

            context.sendMessage(
                    Message.raw("  DR: ").color(GRAY)
                            .insert(Message.raw(String.format("%.1f%%", effectiveDr * 100)).color(YELLOW))
                            .insert(Message.raw(String.format(" (var %.0f%%)", (variance - 1f) * 100)).color(GRAY))
            );
            if (base != null && base.healthBonus() > 0) {
                context.sendMessage(
                        Message.raw("  Health: ").color(GRAY)
                                .insert(Message.raw("+" + base.healthBonus()).color(GREEN))
                );
            }
        }
    }
}
