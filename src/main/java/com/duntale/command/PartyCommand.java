package com.duntale.command;

import com.duntale.dungeon.PartyService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
 * <p>Running bare {@code /party} opens the interactive {@link PartyPage} UI.
 *
 * @since 1.6.0
 */
public class PartyCommand extends AbstractPlayerCommand {

    private static final String GOLD = "#FFD700";
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
        this.addSubCommand(new AcceptSubCommand());
        this.addSubCommand(new DeclineSubCommand());
        this.addSubCommand(new KickSubCommand());
        this.addSubCommand(new LeaveSubCommand());
        this.addSubCommand(new DisbandSubCommand());
        this.addSubCommand(new ListSubCommand());
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve player.").color(RED));
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new PartyPage(playerRef, partyService));
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

            UUID inviterId = playerRef.getUuid();
            PartyService.InviteResult result = partyService.invitePlayer(inviterId, target.getUuid());
            switch (result) {
                case SUCCESS -> {
                    context.sendMessage(
                            Message.raw("Invite sent to ").color(GREEN)
                                    .insert(Message.raw(target.getUsername()).color(AQUA))
                                    .insert(Message.raw(". It expires in 60s.").color(GREEN))
                    );
                    String inviterName = playerRef.getUsername();
                    target.sendMessage(
                            Message.raw(inviterName).color(AQUA)
                                    .insert(Message.raw(" invited you to their party. Use ").color(GOLD))
                                    .insert(Message.raw("/party accept " + inviterName).color(GREEN))
                                    .insert(Message.raw(" or ").color(GOLD))
                                    .insert(Message.raw("/party decline " + inviterName).color(RED))
                                    .insert(Message.raw(".").color(GOLD))
                    );
                }
                case NO_PARTY -> context.sendMessage(
                        Message.raw("You don't own a party. Create one with /party create.").color(RED)
                );
                case NOT_OWNER -> context.sendMessage(
                        Message.raw("Only the party owner can invite players.").color(RED)
                );
                case SELF -> context.sendMessage(
                        Message.raw("You cannot invite yourself.").color(RED)
                );
                case TARGET_ALREADY_IN_PARTY -> context.sendMessage(
                        Message.raw("That player is already in a party.").color(RED)
                );
                case PARTY_FULL -> context.sendMessage(
                        Message.raw("Party is full (max " + PartyService.MAX_PARTY_SIZE + ").").color(RED)
                );
                case ALREADY_PENDING -> context.sendMessage(
                        Message.raw("You already have a pending invite for that player.").color(RED)
                );
            }
        }
    }

    // ============================================
    // accept
    // ============================================

    private class AcceptSubCommand extends AbstractPlayerCommand {

        private final RequiredArg<String> ownerArg =
                this.withRequiredArg("owner", "Owner name whose invite to accept", ArgTypes.STRING);

        AcceptSubCommand() {
            super("accept", "Accept a pending party invite");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String ownerName = ownerArg.get(context);
            PlayerRef owner = Universe.get().getPlayerByUsername(ownerName, NameMatching.EXACT_IGNORE_CASE);
            if (owner == null) {
                context.sendMessage(Message.raw("That player is not online.").color(RED));
                return;
            }

            UUID inviteeId = playerRef.getUuid();
            UUID ownerId = owner.getUuid();
            PartyService.InviteResponseResult result = partyService.acceptInvite(inviteeId, ownerId);
            switch (result) {
                case SUCCESS -> {
                    context.sendMessage(
                            Message.raw("You joined ").color(GREEN)
                                    .insert(Message.raw(owner.getUsername()).color(AQUA))
                                    .insert(Message.raw("'s party.").color(GREEN))
                    );
                    Message joined = Message.raw(playerRef.getUsername()).color(AQUA)
                            .insert(Message.raw(" joined the party.").color(GREEN));
                    for (UUID memberId : partyService.assembleRoster(ownerId)) {
                        if (!memberId.equals(inviteeId)) {
                            notifyPlayer(memberId, joined);
                        }
                    }
                }
                case NO_INVITE -> context.sendMessage(
                        Message.raw("You have no pending invite from that player.").color(RED)
                );
                case EXPIRED -> context.sendMessage(
                        Message.raw("That invite has expired.").color(RED)
                );
                case OWNER_NO_LONGER_HAS_PARTY -> context.sendMessage(
                        Message.raw("That player no longer has a party.").color(RED)
                );
                case ALREADY_IN_PARTY -> context.sendMessage(
                        Message.raw("You are already in a party.").color(RED)
                );
                case PARTY_FULL -> context.sendMessage(
                        Message.raw("That party is now full.").color(RED)
                );
            }
        }
    }

    // ============================================
    // decline
    // ============================================

    private class DeclineSubCommand extends AbstractPlayerCommand {

        private final RequiredArg<String> ownerArg =
                this.withRequiredArg("owner", "Owner name whose invite to decline", ArgTypes.STRING);

        DeclineSubCommand() {
            super("decline", "Decline a pending party invite");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String ownerName = ownerArg.get(context);
            PlayerRef owner = Universe.get().getPlayerByUsername(ownerName, NameMatching.EXACT_IGNORE_CASE);
            if (owner == null) {
                context.sendMessage(Message.raw("That player is not online.").color(RED));
                return;
            }

            UUID inviteeId = playerRef.getUuid();
            UUID ownerId = owner.getUuid();
            if (partyService.declineInvite(inviteeId, ownerId)) {
                context.sendMessage(Message.raw("Invite declined.").color(YELLOW));
                notifyPlayer(ownerId,
                        Message.raw(playerRef.getUsername()).color(AQUA)
                                .insert(Message.raw(" declined your party invite.").color(YELLOW)));
            } else {
                context.sendMessage(
                        Message.raw("You have no pending invite from that player.").color(RED)
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
                target.sendMessage(
                        Message.raw("You were kicked from ").color(RED)
                                .insert(Message.raw(playerRef.getUsername()).color(AQUA))
                                .insert(Message.raw("'s party.").color(RED))
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
            PartyService.PartyDeparture departure = partyService.leaveParty(playerId);
            switch (departure.outcome()) {
                case NOT_IN_PARTY -> context.sendMessage(
                        Message.raw("You are not in a party.").color(YELLOW)
                );
                case DISBANDED_EMPTY -> context.sendMessage(
                        Message.raw("Party disbanded.").color(GREEN)
                );
                case LEFT_AS_MEMBER -> {
                    context.sendMessage(Message.raw("You left the party.").color(GREEN));
                    Message left = Message.raw(playerRef.getUsername()).color(AQUA)
                            .insert(Message.raw(" left the party.").color(YELLOW));
                    for (UUID memberId : departure.remainingMembers()) {
                        notifyPlayer(memberId, left);
                    }
                }
                case OWNERSHIP_TRANSFERRED -> {
                    context.sendMessage(Message.raw("You left the party.").color(GREEN));
                    UUID newOwnerId = departure.newOwnerId();
                    Message left = Message.raw(playerRef.getUsername()).color(AQUA)
                            .insert(Message.raw(" left the party.").color(YELLOW));
                    for (UUID memberId : departure.remainingMembers()) {
                        if (memberId.equals(newOwnerId)) {
                            notifyPlayer(memberId, Message.raw(playerRef.getUsername()).color(AQUA)
                                    .insert(Message.raw(" left. You are now the party owner.").color(GOLD)));
                        } else {
                            notifyPlayer(memberId, left);
                        }
                    }
                }
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
            PartyService.PartyRosterSnapshot snapshot = partyService.tryGetOwnedRoster(playerId).orElse(null);
            if (snapshot == null) {
                context.sendMessage(Message.raw("You don't own a party.").color(RED));
                return;
            }
            if (partyService.disbandParty(playerId)) {
                context.sendMessage(Message.raw("Party disbanded.").color(GREEN));
                Message disbanded = Message.raw("Your party was disbanded by the owner.").color(YELLOW);
                for (UUID memberId : snapshot.members()) {
                    if (!memberId.equals(playerId)) {
                        notifyPlayer(memberId, disbanded);
                    }
                }
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

    private static void notifyPlayer(@Nonnull UUID playerId, @Nonnull Message message) {
        PlayerRef ref = Universe.get().getPlayer(playerId);
        if (ref != null) {
            ref.sendMessage(message);
        }
    }
}
