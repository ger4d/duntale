package com.duntale.command;

import com.duntale.dungeon.PartyService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interactive UI page for managing the viewer's party.
 *
 * <p>Renders the current party roster (up to {@link PartyService#MAX_PARTY_SIZE} fixed
 * member rows), the viewer's pending incoming invites (up to {@link #INVITE_SLOTS} fixed
 * rows), and footer actions (leave / disband). Owners additionally see a per-member
 * "Kick" button. Sending invites is intentionally left to the chat command
 * {@code /party invite <name>} because it requires a target player name.
 *
 * <p>All party reads/writes go through {@link PartyService}, whose methods are
 * thread-safe. After any action the page rebuilds its command and event bindings and
 * pushes them to the client via {@link #sendUpdate(UICommandBuilder, UIEventBuilder, boolean)}.
 *
 * @see PartyService
 */
public class PartyPage extends InteractiveCustomUIPage<PartyPage.PartyPageData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String COLOR_GOLD = "#FFD700";
    private static final String COLOR_YELLOW = "#FFEE55";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_AQUA = "#55FFFF";

    /** Number of fixed pending-invite rows declared in the {@code .ui} asset. */
    private static final int INVITE_SLOTS = 3;

    private final PartyService partyService;

    /**
     * Creates a new party management page.
     *
     * @param playerRef    the player opening the page
     * @param partyService the party service backing all reads and writes
     */
    public PartyPage(@Nonnull PlayerRef playerRef, @Nonnull PartyService partyService) {
        super(playerRef, CustomPageLifetime.CanDismiss, PartyPageData.CODEC);
        this.partyService = partyService;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/Party/PartyPage.ui");
        applyState(cmd, events);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PartyPageData data) {
        UUID viewerId = playerRef.getUuid();

        if (data.kick != null) {
            handleKick(viewerId, data.kick);
        } else if (data.accept != null) {
            handleAccept(viewerId, data.accept);
        } else if (data.decline != null) {
            handleDecline(viewerId, data.decline);
        } else if (data.leave != null) {
            handleLeave(viewerId);
        } else if (data.disband != null) {
            handleDisband(viewerId);
        } else {
            return;
        }

        refreshDisplay();
    }

    // ── Action handlers ──────────────────────────────────────────────

    private void handleKick(@Nonnull UUID ownerId, @Nonnull String targetIdString) {
        UUID targetId = parseUuid(targetIdString);
        if (targetId == null || targetId.equals(ownerId)) {
            return;
        }
        if (!partyService.isPartyOwner(ownerId)) {
            playerRef.sendMessage(Message.raw("You don't own a party.").color(COLOR_RED));
            return;
        }
        UUID targetOwnerId = partyService.getPartyOwner(targetId).orElse(null);
        if (!ownerId.equals(targetOwnerId)) {
            return;
        }
        if (partyService.kickPlayer(ownerId, targetId)) {
            playerRef.sendMessage(
                    Message.raw("Kicked ").color(COLOR_GREEN)
                            .insert(Message.raw(resolveName(targetId)).color(COLOR_AQUA))
                            .insert(Message.raw(" from the party.").color(COLOR_GREEN))
            );
            notifyPlayer(targetId,
                    Message.raw("You were kicked from ").color(COLOR_RED)
                            .insert(Message.raw(playerRef.getUsername()).color(COLOR_AQUA))
                            .insert(Message.raw("'s party.").color(COLOR_RED)));
        }
    }

    private void handleAccept(@Nonnull UUID inviteeId, @Nonnull String ownerIdString) {
        UUID ownerId = parseUuid(ownerIdString);
        if (ownerId == null) {
            return;
        }
        PartyService.InviteResponseResult result = partyService.acceptInvite(inviteeId, ownerId);
        if (result == PartyService.InviteResponseResult.SUCCESS) {
            playerRef.sendMessage(
                    Message.raw("You joined ").color(COLOR_GREEN)
                            .insert(Message.raw(resolveName(ownerId)).color(COLOR_AQUA))
                            .insert(Message.raw("'s party.").color(COLOR_GREEN))
            );
            Message joined = Message.raw(playerRef.getUsername()).color(COLOR_AQUA)
                    .insert(Message.raw(" joined the party.").color(COLOR_GREEN));
            for (UUID memberId : partyService.assembleRoster(ownerId)) {
                if (!memberId.equals(inviteeId)) {
                    notifyPlayer(memberId, joined);
                }
            }
        } else {
            playerRef.sendMessage(Message.raw("Could not join that party.").color(COLOR_RED));
        }
    }

    private void handleDecline(@Nonnull UUID inviteeId, @Nonnull String ownerIdString) {
        UUID ownerId = parseUuid(ownerIdString);
        if (ownerId == null) {
            return;
        }
        if (partyService.declineInvite(inviteeId, ownerId)) {
            playerRef.sendMessage(Message.raw("Invite declined.").color(COLOR_YELLOW));
            notifyPlayer(ownerId,
                    Message.raw(playerRef.getUsername()).color(COLOR_AQUA)
                            .insert(Message.raw(" declined your party invite.").color(COLOR_YELLOW)));
        }
    }

    private void handleLeave(@Nonnull UUID playerId) {
        PartyService.PartyDeparture departure = partyService.leaveParty(playerId);
        switch (departure.outcome()) {
            case NOT_IN_PARTY -> playerRef.sendMessage(
                    Message.raw("You are not in a party.").color(COLOR_YELLOW));
            case DISBANDED_EMPTY -> playerRef.sendMessage(
                    Message.raw("Party disbanded.").color(COLOR_GREEN));
            case LEFT_AS_MEMBER -> {
                playerRef.sendMessage(Message.raw("You left the party.").color(COLOR_GREEN));
                Message left = Message.raw(playerRef.getUsername()).color(COLOR_AQUA)
                        .insert(Message.raw(" left the party.").color(COLOR_YELLOW));
                for (UUID memberId : departure.remainingMembers()) {
                    notifyPlayer(memberId, left);
                }
            }
            case OWNERSHIP_TRANSFERRED -> {
                playerRef.sendMessage(Message.raw("You left the party.").color(COLOR_GREEN));
                UUID newOwnerId = departure.newOwnerId();
                Message left = Message.raw(playerRef.getUsername()).color(COLOR_AQUA)
                        .insert(Message.raw(" left the party.").color(COLOR_YELLOW));
                for (UUID memberId : departure.remainingMembers()) {
                    if (memberId.equals(newOwnerId)) {
                        notifyPlayer(memberId, Message.raw(playerRef.getUsername()).color(COLOR_AQUA)
                                .insert(Message.raw(" left. You are now the party owner.").color(COLOR_GOLD)));
                    } else {
                        notifyPlayer(memberId, left);
                    }
                }
            }
        }
    }

    private void handleDisband(@Nonnull UUID playerId) {
        PartyService.PartyRosterSnapshot snapshot = partyService.tryGetOwnedRoster(playerId).orElse(null);
        if (snapshot == null) {
            playerRef.sendMessage(Message.raw("You don't own a party.").color(COLOR_RED));
            return;
        }
        if (partyService.disbandParty(playerId)) {
            playerRef.sendMessage(Message.raw("Party disbanded.").color(COLOR_GREEN));
            Message disbanded = Message.raw("Your party was disbanded by the owner.").color(COLOR_YELLOW);
            for (UUID memberId : snapshot.members()) {
                if (!memberId.equals(playerId)) {
                    notifyPlayer(memberId, disbanded);
                }
            }
        }
    }

    // ── Rendering ────────────────────────────────────────────────────

    /**
     * Rebuilds the page command and event bindings from current party state and
     * pushes them to the client.
     */
    private void refreshDisplay() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        applyState(cmd, events);
        sendUpdate(cmd, events, false);
    }

    /**
     * Populates labels, visibility flags, and event bindings for the current party
     * state. Used by both the initial {@code build} and every {@code refreshDisplay}.
     *
     * @param cmd    the command builder to populate
     * @param events the event builder to (re)bind interactive elements against
     */
    private void applyState(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        UUID viewerId = playerRef.getUuid();
        UUID ownerId = partyService.getPartyOwner(viewerId).orElse(null);
        boolean inParty = ownerId != null;
        boolean isOwner = partyService.isPartyOwner(viewerId);

        List<UUID> members = inParty
                ? new ArrayList<>(partyService.assembleRoster(viewerId))
                : List.of();

        // Header / status line.
        if (!inParty) {
            cmd.set("#StatusLine.Text", "You are not in a party.");
        } else if (isOwner) {
            cmd.set("#StatusLine.Text",
                    "Your party (" + members.size() + "/" + PartyService.MAX_PARTY_SIZE + ") — you are the owner.");
        } else {
            cmd.set("#StatusLine.Text",
                    resolveName(ownerId) + "'s party (" + members.size() + "/" + PartyService.MAX_PARTY_SIZE + ").");
        }

        cmd.set("#MembersHeader.Visible", inParty);
        cmd.set("#NoPartyHint.Visible", !inParty);

        // Member rows (fixed slots).
        for (int i = 0; i < PartyService.MAX_PARTY_SIZE; i++) {
            String row = "#MemberRow" + i;
            if (i < members.size()) {
                UUID memberId = members.get(i);
                boolean isOwnerRow = memberId.equals(ownerId);
                cmd.set(row + ".Visible", true);
                cmd.set("#MemberName" + i + ".Text", resolveName(memberId));
                cmd.set("#MemberRole" + i + ".Text", isOwnerRow ? "Owner" : "Member");

                boolean kickVisible = isOwner && !isOwnerRow;
                cmd.set("#MemberKick" + i + ".Visible", kickVisible);
                if (kickVisible) {
                    events.addEventBinding(
                            CustomUIEventBindingType.Activating,
                            "#MemberKick" + i,
                            EventData.of("Kick", memberId.toString()),
                            false);
                }
            } else {
                cmd.set(row + ".Visible", false);
                cmd.set("#MemberKick" + i + ".Visible", false);
            }
        }

        // Pending invites (only relevant when not already in a party).
        List<UUID> inviteOwners = inParty
                ? List.of()
                : new ArrayList<>(partyService.getPendingInviteOwners(viewerId));
        boolean hasInvites = !inviteOwners.isEmpty();
        cmd.set("#InvitesHeader.Visible", hasInvites);

        for (int i = 0; i < INVITE_SLOTS; i++) {
            String row = "#InviteRow" + i;
            if (i < inviteOwners.size()) {
                UUID inviteOwnerId = inviteOwners.get(i);
                cmd.set(row + ".Visible", true);
                cmd.set("#InviteName" + i + ".Text", resolveName(inviteOwnerId) + "'s party");
                events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#InviteAccept" + i,
                        EventData.of("Accept", inviteOwnerId.toString()),
                        false);
                events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#InviteDecline" + i,
                        EventData.of("Decline", inviteOwnerId.toString()),
                        false);
            } else {
                cmd.set(row + ".Visible", false);
            }
        }

        // Footer actions.
        cmd.set("#LeaveBtn.Visible", inParty);
        cmd.set("#DisbandBtn.Visible", isOwner);
        if (inParty) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating, "#LeaveBtn",
                    EventData.of("Leave", "1"), false);
        }
        if (isOwner) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating, "#DisbandBtn",
                    EventData.of("Disband", "1"), false);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static UUID parseUuid(@Nonnull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            LOGGER.at(Level.WARNING).log("Invalid UUID from party UI: %s", value);
            return null;
        }
    }

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

    // ── Event Data ───────────────────────────────────────────────────

    /**
     * Event data received from party UI button clicks. Exactly one field is set per
     * event, identified by the binding key.
     */
    public static class PartyPageData {

        /** Codec for deserialising party UI button click events. */
        public static final BuilderCodec<PartyPageData> CODEC = BuilderCodec.builder(
                        PartyPageData.class, PartyPageData::new)
                .append(new KeyedCodec<>("Kick", Codec.STRING),
                        (e, v) -> e.kick = v, e -> e.kick).add()
                .append(new KeyedCodec<>("Accept", Codec.STRING),
                        (e, v) -> e.accept = v, e -> e.accept).add()
                .append(new KeyedCodec<>("Decline", Codec.STRING),
                        (e, v) -> e.decline = v, e -> e.decline).add()
                .append(new KeyedCodec<>("Leave", Codec.STRING),
                        (e, v) -> e.leave = v, e -> e.leave).add()
                .append(new KeyedCodec<>("Disband", Codec.STRING),
                        (e, v) -> e.disband = v, e -> e.disband).add()
                .build();

        String kick;
        String accept;
        String decline;
        String leave;
        String disband;
    }
}
