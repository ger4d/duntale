package com.duntale.zsquad.command;

import com.duntale.zsquad.dungeon.PartyService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;

/**
 * Player-facing command for party management.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * /party create
 * /party invite <playerName>
 * /party kick <playerName>
 * /party leave
 * /party disband
 * /party list
 * }</pre>
 *
 * @since 1.6.0
 */
public class PartyCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String AQUA = "#55FFFF";

    private final PartyService partyService;

    /**
     * Creates the /party command.
     *
     * @param partyService the party service
     */
    public PartyCommand(@Nonnull PartyService partyService) {
        super("party", "Manage your party");
        this.partyService = partyService;

        this.addSubCommand(new CreateSubCommand());
        this.addSubCommand(new InviteSubCommand());
        this.addSubCommand(new KickSubCommand());
        this.addSubCommand(new LeaveSubCommand());
        this.addSubCommand(new DisbandSubCommand());
        this.addSubCommand(new ListSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(
                Message.raw("Usage: /party create|invite|kick|leave|disband|list").color(YELLOW)
        );
        context.sendMessage(
                Message.raw("  create").color(GOLD)
                        .insert(Message.raw(" — create a new party").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  invite <player>").color(GOLD)
                        .insert(Message.raw(" — invite an online player").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  kick <player>").color(GOLD)
                        .insert(Message.raw(" — kick a member (owner only)").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  leave").color(GOLD)
                        .insert(Message.raw(" — leave your party").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  disband").color(GOLD)
                        .insert(Message.raw(" — disband your party (owner only)").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  list").color(GOLD)
                        .insert(Message.raw(" — show party members").color(GRAY))
        );
    }

    // ============================================
    // create
    // ============================================

    private class CreateSubCommand extends AbstractPlayerCommand {

        CreateSubCommand() {
            super("create", "Create a new party");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();
            if (partyService.createParty(playerId)) {
                context.sendMessage(Message.raw("Party created!").color(GREEN));
            } else {
                context.sendMessage(Message.raw("You are already in a party.").color(RED));
            }
        }
    }

    // ============================================
    // invite
    // ============================================

    private class InviteSubCommand extends AbstractPlayerCommand {

        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Player name to invite", ArgTypes.STRING);

        InviteSubCommand() {
            super("invite", "Invite a player to your party");
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
                context.sendMessage(Message.raw("Player not found or offline.").color(RED));
                return;
            }

            UUID ownerId = playerRef.getUuid();
            PartyService.InviteResult result = partyService.invitePlayer(ownerId, target.getUuid());
            switch (result) {
                case SUCCESS -> context.sendMessage(
                        Message.raw("Invited ").color(GREEN)
                                .insert(Message.raw(target.getUsername()).color(AQUA))
                                .insert(Message.raw(" to the party.").color(GREEN))
                );
                case NO_PARTY -> context.sendMessage(
                        Message.raw("You don't own a party. Create one with /party create.").color(RED)
                );
                case ALREADY_IN_PARTY -> context.sendMessage(
                        Message.raw("That player is already in a party.").color(RED)
                );
                case PARTY_FULL -> context.sendMessage(
                        Message.raw("Party is full (max " + PartyService.MAX_PARTY_SIZE + ").").color(RED)
                );
            }
        }
    }

    // ============================================
    // kick
    // ============================================

    private class KickSubCommand extends AbstractPlayerCommand {

        private final RequiredArg<String> playerArg =
                this.withRequiredArg("player", "Player name to kick", ArgTypes.STRING);

        KickSubCommand() {
            super("kick", "Kick a player from your party");
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
                context.sendMessage(Message.raw("Player not found or offline.").color(RED));
                return;
            }

            UUID ownerId = playerRef.getUuid();
            UUID targetId = target.getUuid();
            if (ownerId.equals(targetId)) {
                context.sendMessage(Message.raw("You cannot kick yourself.").color(RED));
                return;
            }
            if (!partyService.isPartyOwner(ownerId)) {
                context.sendMessage(Message.raw("You don't own a party.").color(RED));
                return;
            }
            UUID targetOwnerId = partyService.getPartyOwner(targetId).orElse(null);
            if (!ownerId.equals(targetOwnerId)) {
                context.sendMessage(
                        Message.raw(target.getUsername()).color(AQUA)
                                .insert(Message.raw(" is not in your party.").color(RED))
                );
                return;
            }

            if (partyService.kickPlayer(ownerId, targetId)) {
                context.sendMessage(
                        Message.raw("Kicked ").color(GREEN)
                                .insert(Message.raw(target.getUsername()).color(AQUA))
                                .insert(Message.raw(" from the party.").color(GREEN))
                );
            } else {
                context.sendMessage(Message.raw("Failed to kick that player.").color(RED));
            }
        }
    }

    // ============================================
    // leave
    // ============================================

    private class LeaveSubCommand extends AbstractPlayerCommand {

        LeaveSubCommand() {
            super("leave", "Leave your party");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();
            UUID ownerId = partyService.getPartyOwner(playerId).orElse(null);
            if (ownerId == null) {
                context.sendMessage(Message.raw("You are not in a party.").color(YELLOW));
                return;
            }

            if (!partyService.leaveParty(playerId)) {
                context.sendMessage(Message.raw("You are not in a party.").color(YELLOW));
                return;
            }

            if (ownerId.equals(playerId)) {
                context.sendMessage(Message.raw("Party disbanded.").color(GREEN));
            } else {
                context.sendMessage(Message.raw("You left the party.").color(GREEN));
            }
        }
    }

    // ============================================
    // disband
    // ============================================

    private class DisbandSubCommand extends AbstractPlayerCommand {

        DisbandSubCommand() {
            super("disband", "Disband your party");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();
            if (partyService.disbandParty(playerId)) {
                context.sendMessage(Message.raw("Party disbanded.").color(GREEN));
            } else {
                context.sendMessage(Message.raw("You don't own a party.").color(RED));
            }
        }
    }

    // ============================================
    // list
    // ============================================

    private class ListSubCommand extends AbstractPlayerCommand {

        ListSubCommand() {
            super("list", "Show party members");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();
            if (!partyService.isInParty(playerId)) {
                context.sendMessage(Message.raw("You are not in a party.").color(YELLOW));
                return;
            }

            Set<UUID> members = partyService.assembleRoster(playerId);
            UUID ownerId = partyService.getPartyOwner(playerId).orElse(null);

            context.sendMessage(
                    Message.raw("Party Members (" + members.size() + "/" + PartyService.MAX_PARTY_SIZE + "):")
                            .color(GOLD).bold(true)
            );

            for (UUID memberId : members) {
                String name = resolveName(memberId);
                boolean isOwner = memberId.equals(ownerId);
                Message line = Message.raw("  " + name).color(AQUA);
                if (isOwner) {
                    line = line.insert(Message.raw(" (owner)").color(YELLOW));
                }
                context.sendMessage(line);
            }
        }
    }

    // ============================================
    // Helpers
    // ============================================

    @Nonnull
    private static String resolveName(@Nonnull UUID playerId) {
        PlayerRef ref = Universe.get().getPlayer(playerId);
        return ref != null ? ref.getUsername() : playerId.toString();
    }
}
