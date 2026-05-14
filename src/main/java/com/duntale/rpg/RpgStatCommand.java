package com.duntale.rpg;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

/**
 * Admin command for viewing and modifying player RPG stats.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /stat check <player>}              — Show all stats</li>
 *   <li>{@code /stat set <player> <stat> <value>}  — Set a specific stat</li>
 *   <li>{@code /stat add <player> <stat> <value>}  — Add a delta to a stat</li>
 * </ul>
 */
public class RpgStatCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String CYAN = "#55FFFF";
    private static final String WHITE = "#FFFFFF";

    private final RpgService rpgService;

    /**
     * Creates a new /stat admin command.
     *
     * @param rpgService the RPG service for stat operations
     */
    public RpgStatCommand(@Nonnull RpgService rpgService) {
        super("stat", "View and modify player RPG stats");
        this.rpgService = rpgService;

        this.addSubCommand(new CheckSubCommand());
        this.addSubCommand(new SetSubCommand());
        this.addSubCommand(new AddSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /stat check <player>").color(GRAY));
        context.sendMessage(Message.raw("  /stat set <player> <stat> <value>").color(GRAY));
        context.sendMessage(Message.raw("  /stat add <player> <stat> <value>").color(GRAY));
    }

    // -- Check subcommand ----------------------------------------------

    private class CheckSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

        CheckSubCommand() {
            super("check", "Show all stats for a player");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String playerName = playerArg.get(context);
            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            UUID targetId = target.getUuid();
            RpgProfile profile = rpgService.getProfile(targetId);
            Map<RpgStat, Integer> allStats = profile.getAll();

            context.sendMessage(
                    Message.raw("--- " + playerName + "'s Stats ---").color(GOLD).bold(true)
            );

            for (RpgStat stat : RpgStat.values()) {
                int value = allStats.getOrDefault(stat, 0);
                String label = String.format("  %-12s", stat.name());
                context.sendMessage(
                        Message.raw(label).color(GRAY)
                                .insert(Message.raw(String.valueOf(value)).color(
                                        value > 0 ? GREEN : WHITE
                                ))
                );
            }
        }
    }

    // -- Set subcommand ------------------------------------------------

    private class SetSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<String> statArg =
                this.withRequiredArg("stat", "Stat name (e.g. SPEED, STRENGTH)", ArgTypes.STRING);
        private final RequiredArg<Integer> valueArg =
                this.withRequiredArg("value", "New stat value", ArgTypes.INTEGER);

        SetSubCommand() {
            super("set", "Set a specific stat for a player");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String playerName = playerArg.get(context);
            String statName = statArg.get(context);
            int value = valueArg.get(context);

            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            RpgStat stat;
            try {
                stat = RpgStat.valueOf(statName.toUpperCase());
            } catch (IllegalArgumentException e) {
                context.sendMessage(Message.raw("Unknown stat: " + statName).color(RED));
                context.sendMessage(Message.raw("  Valid stats: SPEED, STRENGTH, LUCK, STAMINA, AGILITY, RESISTANCE, VITALITY").color(GRAY));
                return;
            }

            UUID targetId = target.getUuid();
            int oldValue = rpgService.getStat(targetId, stat);
            rpgService.setStat(targetId, stat, value);
            int newValue = rpgService.getStat(targetId, stat);

            context.sendMessage(
                    Message.raw("Set ").color(GREEN)
                            .insert(Message.raw(playerName).color(CYAN))
                            .insert(Message.raw("'s ").color(GREEN))
                            .insert(Message.raw(stat.name()).color(GOLD).bold(true))
            );
            context.sendMessage(
                    Message.raw("  Value: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(oldValue)).color(YELLOW))
                            .insert(Message.raw(" -> ").color(GRAY))
                            .insert(Message.raw(String.valueOf(newValue)).color(GREEN))
            );
        }
    }

    // -- Add subcommand ------------------------------------------------

    private class AddSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<String> statArg =
                this.withRequiredArg("stat", "Stat name (e.g. SPEED, STRENGTH)", ArgTypes.STRING);
        private final RequiredArg<Integer> valueArg =
                this.withRequiredArg("value", "Delta to add (can be negative)", ArgTypes.INTEGER);

        AddSubCommand() {
            super("add", "Add a delta to a stat for a player");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String playerName = playerArg.get(context);
            String statName = statArg.get(context);
            int delta = valueArg.get(context);

            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            RpgStat stat;
            try {
                stat = RpgStat.valueOf(statName.toUpperCase());
            } catch (IllegalArgumentException e) {
                context.sendMessage(Message.raw("Unknown stat: " + statName).color(RED));
                context.sendMessage(Message.raw("  Valid stats: SPEED, STRENGTH, LUCK, STAMINA, AGILITY, RESISTANCE, VITALITY").color(GRAY));
                return;
            }

            UUID targetId = target.getUuid();
            int oldValue = rpgService.getStat(targetId, stat);
            rpgService.addStat(targetId, stat, delta);
            int newValue = rpgService.getStat(targetId, stat);

            String sign = delta >= 0 ? "+" : "";
            context.sendMessage(
                    Message.raw("Added ").color(GREEN)
                            .insert(Message.raw(sign + delta).color(GOLD).bold(true))
                            .insert(Message.raw(" to ").color(GREEN))
                            .insert(Message.raw(playerName).color(CYAN))
                            .insert(Message.raw("'s ").color(GREEN))
                            .insert(Message.raw(stat.name()).color(GOLD).bold(true))
            );
            context.sendMessage(
                    Message.raw("  Value: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(oldValue)).color(YELLOW))
                            .insert(Message.raw(" -> ").color(GRAY))
                            .insert(Message.raw(String.valueOf(newValue)).color(GREEN))
            );
        }
    }
}
