package com.duntale.command;

import com.duntale.loot.LootRollService;
import com.duntale.progression.CombatScaling;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer command for dry-running configured loot tables.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /dloot roll <npc> <level> [luck] [count] [--give]}</li>
 * </ul>
 */
public class DLootCommand extends CommandBase {

    private static final String LEVEL_RANGE_LABEL = CombatScaling.MIN_LEVEL + "-" + CombatScaling.MAX_LEVEL;
    private static final String LEVEL_RANGE_ERROR = "Level must be between "
            + CombatScaling.MIN_LEVEL + " and " + CombatScaling.MAX_LEVEL + ".";

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String CYAN = "#55FFFF";

    private final LootRollService lootRollService;

    /**
     * Creates a new /dloot command.
     *
     * @param lootRollService the shared loot roll service
     */
    public DLootCommand(@Nonnull LootRollService lootRollService) {
        super("dloot", "Developer loot table testing commands");
        this.lootRollService = lootRollService;

        this.addSubCommand(new RollSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /dloot roll <npc> <level> [luck] [count] [--give]").color(GRAY));
    }

    private final class RollSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> tableArg =
                this.withRequiredArg("npc", "NPC role or loot table ID", ArgTypes.STRING);
        private final RequiredArg<Integer> levelArg =
                this.withRequiredArg("level", "NPC level (" + LEVEL_RANGE_LABEL + ")", ArgTypes.INTEGER);
        private final OptionalArg<Integer> luckArg =
                this.withOptionalArg("luck", "Luck level (default 0)", ArgTypes.INTEGER);
        private final OptionalArg<Integer> countArg =
                this.withOptionalArg("count", "Number of rolls to simulate (default 1)", ArgTypes.INTEGER);
        private final FlagArg giveFlag =
                this.withFlagArg("give", "Give the rolled drops to your inventory");

        private RollSubCommand() {
            super("roll", "Roll a configured loot table");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String tableId = tableArg.get(context);
            int npcLevel = levelArg.get(context);
            int luckLevel = luckArg.provided(context) ? luckArg.get(context) : 0;
            int count = countArg.provided(context) ? countArg.get(context) : 1;
            boolean give = giveFlag.get(context);

            if (!CombatScaling.isSupportedLevel(npcLevel)) {
                context.sendMessage(Message.raw(LEVEL_RANGE_ERROR).color(RED));
                return;
            }
            if (luckLevel < 0) {
                context.sendMessage(Message.raw("Luck must be 0 or greater.").color(RED));
                return;
            }
            if (count < 1 || count > 100) {
                context.sendMessage(Message.raw("Count must be between 1 and 100.").color(RED));
                return;
            }
            if (!lootRollService.hasTable(tableId)) {
                context.sendMessage(Message.raw("Unknown loot table: " + tableId).color(RED));
                return;
            }

            List<ItemStack> allDrops = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                allDrops.addAll(lootRollService.roll(tableId, npcLevel, luckLevel));
            }

            context.sendMessage(
                    Message.raw("Rolled ").color(GREEN)
                            .insert(Message.raw(tableId).color(GOLD).bold(true))
                            .insert(Message.raw(" at Lv." + npcLevel).color(CYAN))
                            .insert(Message.raw(" x" + count).color(GREEN))
                            .insert(Message.raw(" (Luck " + luckLevel + ")").color(GRAY))
            );

            if (allDrops.isEmpty()) {
                context.sendMessage(Message.raw("  No loot dropped.").color(GRAY));
                return;
            }

            if (count == 1) {
                for (ItemStack drop : allDrops) {
                    context.sendMessage(formatDropLine(drop));
                }
            } else {
                for (Map.Entry<String, AggregateDrop> entry : aggregateDrops(allDrops).entrySet()) {
                    AggregateDrop aggregate = entry.getValue();
                    String suffix = aggregate.stacks > 1
                            ? String.format(" across %d rolls", aggregate.stacks)
                            : "";
                    context.sendMessage(
                            Message.raw("  x" + aggregate.quantity + " ").color(GRAY)
                                    .insert(Message.raw(entry.getKey()).color(WHITE))
                                    .insert(Message.raw(suffix).color(GRAY))
                    );
                }
            }

            if (!give) {
                return;
            }

            CombinedItemContainer combined = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
            int addedStacks = 0;
            for (ItemStack drop : allDrops) {
                try {
                    combined.addItemStack(drop);
                    addedStacks++;
                } catch (Exception e) {
                    context.sendMessage(Message.raw("Inventory add failed: " + e.getMessage()).color(RED));
                    break;
                }
            }

            context.sendMessage(
                    Message.raw("Added ").color(GREEN)
                            .insert(Message.raw(String.valueOf(addedStacks)).color(GOLD).bold(true))
                            .insert(Message.raw(" stack(s) to your inventory.").color(GREEN))
            );
        }

        @Nonnull
        private Message formatDropLine(@Nonnull ItemStack drop) {
            return Message.raw("  x" + drop.getQuantity() + " ").color(GRAY)
                    .insert(Message.raw(drop.getItemId()).color(WHITE));
        }

        @Nonnull
        private Map<String, AggregateDrop> aggregateDrops(@Nonnull List<ItemStack> drops) {
            Map<String, AggregateDrop> aggregated = new LinkedHashMap<>();
            for (ItemStack drop : drops) {
                AggregateDrop aggregate = aggregated.computeIfAbsent(drop.getItemId(), ignored -> new AggregateDrop());
                aggregate.quantity += drop.getQuantity();
                aggregate.stacks++;
            }
            return aggregated;
        }
    }

    private static final class AggregateDrop {
        private int quantity;
        private int stacks;
    }
}