package com.duntale.zsquad.economy;

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
import java.util.UUID;

/**
 * Admin command for managing player gold balances.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /gold check <player>}          — Show a player's balance</li>
 *   <li>{@code /gold give <player> <amount>}   — Add gold to a player</li>
 *   <li>{@code /gold take <player> <amount>}   — Remove gold from a player</li>
 *   <li>{@code /gold set <player> <amount>}    — Set a player's balance</li>
 * </ul>
 */
public class GoldCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String CYAN = "#55FFFF";

    private final GoldService goldService;

    /**
     * Creates a new /gold admin command.
     *
     * @param goldService the gold service for balance operations
     */
    public GoldCommand(@Nonnull GoldService goldService) {
        super("gold", "Manage player gold balances");
        this.goldService = goldService;

        this.addSubCommand(new CheckSubCommand());
        this.addSubCommand(new GiveSubCommand());
        this.addSubCommand(new TakeSubCommand());
        this.addSubCommand(new SetSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /gold check <player>").color(GRAY));
        context.sendMessage(Message.raw("  /gold give <player> <amount>").color(GRAY));
        context.sendMessage(Message.raw("  /gold take <player> <amount>").color(GRAY));
        context.sendMessage(Message.raw("  /gold set <player> <amount>").color(GRAY));
    }

    // -- Check subcommand ----------------------------------------------

    private class CheckSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

        CheckSubCommand() {
            super("check", "Show a player's gold balance");
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
            long balance = goldService.getBalance(targetId);

            context.sendMessage(
                    Message.raw(playerName + "'s Gold: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(balance)).color(GOLD).bold(true))
            );
        }
    }

    // -- Give subcommand -----------------------------------------------

    private class GiveSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<Integer> amountArg =
                this.withRequiredArg("amount", "Amount of gold to give", ArgTypes.INTEGER);

        GiveSubCommand() {
            super("give", "Add gold to a player");
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
            long amount = amountArg.get(context);

            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            if (amount <= 0) {
                context.sendMessage(Message.raw("Amount must be positive.").color(RED));
                return;
            }

            UUID targetId = target.getUuid();
            long oldBalance = goldService.getBalance(targetId);
            boolean success = goldService.addGold(targetId, amount);

            if (!success) {
                context.sendMessage(Message.raw("Failed to add gold.").color(RED));
                return;
            }

            long newBalance = goldService.getBalance(targetId);
            context.sendMessage(
                    Message.raw("Gave ").color(GREEN)
                            .insert(Message.raw(String.valueOf(amount)).color(GOLD).bold(true))
                            .insert(Message.raw(" gold to ").color(GREEN))
                            .insert(Message.raw(playerName).color(CYAN))
            );
            context.sendMessage(
                    Message.raw("  Balance: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(oldBalance)).color(YELLOW))
                            .insert(Message.raw(" -> ").color(GRAY))
                            .insert(Message.raw(String.valueOf(newBalance)).color(GREEN))
            );
        }
    }

    // -- Take subcommand -----------------------------------------------

    private class TakeSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<Integer> amountArg =
                this.withRequiredArg("amount", "Amount of gold to take", ArgTypes.INTEGER);

        TakeSubCommand() {
            super("take", "Remove gold from a player");
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
            long amount = amountArg.get(context);

            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            if (amount <= 0) {
                context.sendMessage(Message.raw("Amount must be positive.").color(RED));
                return;
            }

            UUID targetId = target.getUuid();
            long oldBalance = goldService.getBalance(targetId);
            boolean success = goldService.removeGold(targetId, amount);

            if (!success) {
                context.sendMessage(
                        Message.raw("Failed to remove gold (insufficient balance?).").color(RED)
                );
                return;
            }

            long newBalance = goldService.getBalance(targetId);
            context.sendMessage(
                    Message.raw("Took ").color(GREEN)
                            .insert(Message.raw(String.valueOf(amount)).color(GOLD).bold(true))
                            .insert(Message.raw(" gold from ").color(GREEN))
                            .insert(Message.raw(playerName).color(CYAN))
            );
            context.sendMessage(
                    Message.raw("  Balance: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(oldBalance)).color(YELLOW))
                            .insert(Message.raw(" -> ").color(GRAY))
                            .insert(Message.raw(String.valueOf(newBalance)).color(GREEN))
            );
        }
    }

    // -- Set subcommand ------------------------------------------------

    private class SetSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<Integer> amountArg =
                this.withRequiredArg("amount", "Exact gold amount to set", ArgTypes.INTEGER);

        SetSubCommand() {
            super("set", "Set a player's gold balance");
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
            long newAmount = amountArg.get(context);

            PlayerRef target = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
            if (target == null) {
                context.sendMessage(Message.raw("Player not online: " + playerName).color(RED));
                return;
            }

            if (newAmount < 0) {
                context.sendMessage(Message.raw("Amount cannot be negative.").color(RED));
                return;
            }

            UUID targetId = target.getUuid();
            long oldBalance = goldService.getBalance(targetId);

            if (newAmount == oldBalance) {
                context.sendMessage(
                        Message.raw(playerName + "'s balance is already " + oldBalance + ".").color(YELLOW)
                );
                return;
            }

            boolean success;
            if (newAmount > oldBalance) {
                success = goldService.addGold(targetId, newAmount - oldBalance);
            } else {
                success = goldService.removeGold(targetId, oldBalance - newAmount);
            }

            if (!success) {
                context.sendMessage(Message.raw("Failed to set balance.").color(RED));
                return;
            }

            long actualBalance = goldService.getBalance(targetId);
            context.sendMessage(
                    Message.raw("Set ").color(GREEN)
                            .insert(Message.raw(playerName).color(CYAN))
                            .insert(Message.raw("'s gold balance").color(GREEN))
            );
            context.sendMessage(
                    Message.raw("  Balance: ").color(GRAY)
                            .insert(Message.raw(String.valueOf(oldBalance)).color(YELLOW))
                            .insert(Message.raw(" -> ").color(GRAY))
                            .insert(Message.raw(String.valueOf(actualBalance)).color(GREEN))
            );
        }
    }
}
