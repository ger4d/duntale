package com.duntale.dungeon;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * In-memory party service for current player coordination groups.
 *
 * <p>Parties are <em>current</em> social/coordination groups, not pre-run-only roster
 * tickets. A party is created with {@link #createParty(UUID)}, grown through a pending
 * invite/accept handshake, and remains alive across dungeon start and Continue until its
 * members leave, it is disbanded, the owner transfers ownership, or the server restarts.
 *
 * <p>Active dungeon membership is the source of truth for who belongs to a run. The current
 * party owner is the source of <em>authority</em> for group actions at the moment they are
 * performed. There is no persisted dungeon owner.
 *
 * <p>All state is in-memory and lost on server restart, including pending invites.
 *
 * <p>Thread safety: all public methods synchronize on a single internal lock.
 *
 * @since 1.6.0
 */
public class PartyService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Maximum number of players in a single party, including the owner. */
    public static final int MAX_PARTY_SIZE = 6;

    /** Time-to-live for a pending invite before it expires, in milliseconds. */
    public static final long INVITE_TTL_MILLIS = 60_000L;

    // ============================================
    // Fields
    // ============================================

    private final Object lock = new Object();

    /** Owner UUID to the ordered set of member UUIDs (owner first). */
    private final Map<UUID, Set<UUID>> parties = new HashMap<>();

    /** Member UUID to their party owner UUID. Includes the owner mapping to themselves. */
    private final Map<UUID, UUID> memberToOwner = new HashMap<>();

    /** Invitee UUID to a map of owner UUID to the pending invite from that owner. */
    private final Map<UUID, Map<UUID, PendingInvite>> pendingInvitesByInvitee = new HashMap<>();

    /** Clock source; overridable in tests for deterministic expiry. */
    private final LongSupplier nowMillis;

    // ============================================
    // Constructors
    // ============================================

    /** Creates a party service backed by the system clock. */
    public PartyService() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates a party service with an injectable clock.
     *
     * @param nowMillis supplier of the current epoch time in milliseconds
     */
    PartyService(@Nonnull LongSupplier nowMillis) {
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    // ============================================
    // Party lifecycle
    // ============================================

    /**
     * Creates a new party owned by the given player.
     *
     * <p>The owner is automatically added as the first member.
     * Fails if the player is already in a party.
     *
     * @param ownerId the UUID of the party owner
     * @return {@code true} if the party was created, {@code false} if the player is already in a party
     */
    public boolean createParty(@Nonnull UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        synchronized (lock) {
            if (memberToOwner.containsKey(ownerId)) {
                return false;
            }
            Set<UUID> members = new LinkedHashSet<>();
            members.add(ownerId);
            parties.put(ownerId, members);
            memberToOwner.put(ownerId, ownerId);
            LOGGER.at(Level.FINE).log("Party created by %s", ownerId);
            return true;
        }
    }

    /**
     * Creates a pending invite from the inviter's party to the target player.
     *
     * <p>If the inviter is not currently in any party, a new party is created automatically with
     * the inviter as owner. This does <em>not</em> add the target to the party. The target must
     * accept via {@link #acceptInvite(UUID, UUID)}. Invites expire after {@link #INVITE_TTL_MILLIS}.
     *
     * @param inviterId the UUID of the player issuing the invite
     * @param targetId  the UUID of the player to invite
     * @return the result of the invite attempt
     */
    @Nonnull
    public InviteResult invitePlayer(@Nonnull UUID inviterId, @Nonnull UUID targetId) {
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(targetId, "targetId");
        synchronized (lock) {
            if (inviterId.equals(targetId)) {
                return InviteResult.SELF;
            }
            purgeExpiredInvites();

            UUID ownerId = memberToOwner.get(inviterId);
            if (ownerId == null) {
                Set<UUID> newMembers = new LinkedHashSet<>();
                newMembers.add(inviterId);
                parties.put(inviterId, newMembers);
                memberToOwner.put(inviterId, inviterId);
                ownerId = inviterId;
                LOGGER.at(Level.FINE).log("Auto-created party for inviter %s", inviterId);
            }
            if (!ownerId.equals(inviterId)) {
                return InviteResult.NOT_OWNER;
            }
            if (memberToOwner.containsKey(targetId)) {
                return InviteResult.TARGET_ALREADY_IN_PARTY;
            }
            Set<UUID> members = parties.get(ownerId);
            if (members != null && members.size() >= MAX_PARTY_SIZE) {
                return InviteResult.PARTY_FULL;
            }

            Map<UUID, PendingInvite> invites =
                    pendingInvitesByInvitee.computeIfAbsent(targetId, ignored -> new HashMap<>());
            if (invites.containsKey(ownerId)) {
                return InviteResult.ALREADY_PENDING;
            }
            long now = nowMillis.getAsLong();
            invites.put(ownerId, new PendingInvite(ownerId, targetId, now, now + INVITE_TTL_MILLIS));
            LOGGER.at(Level.FINE).log("Pending invite from %s to %s", ownerId, targetId);
            return InviteResult.SUCCESS;
        }
    }

    /**
     * Accepts a pending invite from the given owner, adding the invitee to that party.
     *
     * @param inviteeId the UUID of the player accepting the invite
     * @param ownerId   the UUID of the owner whose invite is being accepted
     * @return the result of the acceptance attempt
     */
    @Nonnull
    public InviteResponseResult acceptInvite(@Nonnull UUID inviteeId, @Nonnull UUID ownerId) {
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(ownerId, "ownerId");
        synchronized (lock) {
            purgeExpiredInvites();

            Map<UUID, PendingInvite> invites = pendingInvitesByInvitee.get(inviteeId);
            PendingInvite invite = invites == null ? null : invites.get(ownerId);
            if (invite == null) {
                return InviteResponseResult.NO_INVITE;
            }
            if (isExpired(invite)) {
                removeInvite(inviteeId, ownerId);
                return InviteResponseResult.EXPIRED;
            }
            if (memberToOwner.containsKey(inviteeId)) {
                clearInvitesForInvitee(inviteeId);
                return InviteResponseResult.ALREADY_IN_PARTY;
            }
            Set<UUID> members = parties.get(ownerId);
            if (members == null) {
                removeInvite(inviteeId, ownerId);
                return InviteResponseResult.OWNER_NO_LONGER_HAS_PARTY;
            }
            if (members.size() >= MAX_PARTY_SIZE) {
                return InviteResponseResult.PARTY_FULL;
            }

            members.add(inviteeId);
            memberToOwner.put(inviteeId, ownerId);
            clearInvitesForInvitee(inviteeId);
            LOGGER.at(Level.FINE).log("Player %s accepted invite into party owned by %s", inviteeId, ownerId);
            return InviteResponseResult.SUCCESS;
        }
    }

    /**
     * Declines (removes) a pending invite from the given owner to the invitee.
     *
     * @param inviteeId the UUID of the player declining the invite
     * @param ownerId   the UUID of the owner whose invite is being declined
     * @return {@code true} if a matching pending invite was removed, {@code false} otherwise
     */
    public boolean declineInvite(@Nonnull UUID inviteeId, @Nonnull UUID ownerId) {
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(ownerId, "ownerId");
        synchronized (lock) {
            purgeExpiredInvites();
            Map<UUID, PendingInvite> invites = pendingInvitesByInvitee.get(inviteeId);
            if (invites == null || invites.remove(ownerId) == null) {
                return false;
            }
            if (invites.isEmpty()) {
                pendingInvitesByInvitee.remove(inviteeId);
            }
            LOGGER.at(Level.FINE).log("Player %s declined invite from %s", inviteeId, ownerId);
            return true;
        }
    }

    /**
     * Kicks a player from the party owned by the given owner.
     *
     * <p>The owner cannot kick themselves; use {@link #disbandParty(UUID)} or
     * {@link #leaveParty(UUID)} instead.
     *
     * @param ownerId  the UUID of the party owner
     * @param targetId the UUID of the player to kick
     * @return {@code true} if the player was removed, {@code false} otherwise
     */
    public boolean kickPlayer(@Nonnull UUID ownerId, @Nonnull UUID targetId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(targetId, "targetId");
        synchronized (lock) {
            if (ownerId.equals(targetId)) {
                return false;
            }
            Set<UUID> members = parties.get(ownerId);
            if (members == null || !members.remove(targetId)) {
                return false;
            }
            memberToOwner.remove(targetId);
            clearInvitesForInvitee(targetId);
            LOGGER.at(Level.FINE).log("Player %s kicked from party owned by %s", targetId, ownerId);
            return true;
        }
    }

    /**
     * Disbands the party owned by the given player, removing all members.
     *
     * @param ownerId the UUID of the party owner
     * @return {@code true} if the party was disbanded, {@code false} if no party exists for that owner
     */
    public boolean disbandParty(@Nonnull UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        synchronized (lock) {
            Set<UUID> members = parties.remove(ownerId);
            if (members == null) {
                return false;
            }
            for (UUID member : members) {
                memberToOwner.remove(member);
            }
            removeInvitesFromOwner(ownerId);
            LOGGER.at(Level.FINE).log("Party owned by %s disbanded (%d members)", ownerId, members.size());
            return true;
        }
    }

    /**
     * Removes a player from their current party, transferring ownership when needed.
     *
     * <p>A regular member leaving only removes themselves. The owner leaving while other
     * members remain transfers ownership to the next member in join order; the owner leaving
     * alone removes the party entirely. Use {@link #disbandParty(UUID)} to remove all members
     * regardless of ownership.
     *
     * @param playerId the UUID of the player leaving
     * @return a description of the departure outcome
     */
    @Nonnull
    public PartyDeparture leaveParty(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            clearInvitesForInvitee(playerId);
            UUID ownerId = memberToOwner.get(playerId);
            if (ownerId == null) {
                return PartyDeparture.notInParty();
            }

            if (!ownerId.equals(playerId)) {
                Set<UUID> members = parties.get(ownerId);
                if (members != null) {
                    members.remove(playerId);
                }
                memberToOwner.remove(playerId);
                LOGGER.at(Level.FINE).log("Player %s left party owned by %s", playerId, ownerId);
                Set<UUID> remaining = members == null ? Set.of() : new LinkedHashSet<>(members);
                return PartyDeparture.leftAsMember(ownerId, remaining);
            }

            // Owner leaving: transfer ownership if anyone remains, otherwise remove the party.
            Set<UUID> members = parties.remove(ownerId);
            memberToOwner.remove(ownerId);
            removeInvitesFromOwner(ownerId);
            if (members == null) {
                return PartyDeparture.disbanded();
            }
            members.remove(ownerId);
            if (members.isEmpty()) {
                LOGGER.at(Level.FINE).log("Owner %s left empty party; party removed", ownerId);
                return PartyDeparture.disbanded();
            }

            UUID newOwner = members.iterator().next();
            Set<UUID> reordered = new LinkedHashSet<>();
            reordered.add(newOwner);
            for (UUID member : members) {
                if (!member.equals(newOwner)) {
                    reordered.add(member);
                }
            }
            parties.put(newOwner, reordered);
            for (UUID member : reordered) {
                memberToOwner.put(member, newOwner);
            }
            LOGGER.at(Level.FINE).log("Owner %s left; ownership transferred to %s", ownerId, newOwner);
            return PartyDeparture.ownershipTransferred(newOwner, new LinkedHashSet<>(reordered));
        }
    }

    // ============================================
    // Roster / state lookups
    // ============================================

    /**
     * Returns the roster for the party that the given player belongs to.
     *
     * <p>If the player is not in a party, returns a singleton set containing only the player.
     * This is a convenience for solo-or-party lookups; it is <em>not</em> owner-sensitive and
     * must not be used to authorize group actions.
     *
     * @param playerId the UUID of any party member or solo player
     * @return immutable owner-first set of player UUIDs forming the roster
     */
    @Nonnull
    public Set<UUID> assembleRoster(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            UUID ownerId = memberToOwner.get(playerId);
            if (ownerId == null) {
                return Set.of(playerId);
            }
            Set<UUID> members = parties.get(ownerId);
            if (members == null) {
                return Set.of(playerId);
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(members));
        }
    }

    /**
     * Resolves the owner-sensitive starting roster for a player-initiated dungeon run.
     *
     * <ul>
     *   <li>Solo (not in a party): a singleton roster containing only the player.</li>
     *   <li>Party owner: the owner-first current party roster.</li>
     *   <li>Party member who is not owner: a {@link StartRosterStatus#NOT_OWNER} result with
     *       an empty roster, so the caller can reject the start.</li>
     * </ul>
     *
     * @param playerId the initiating player UUID
     * @return the resolved start roster
     */
    @Nonnull
    public StartRoster resolveStartRoster(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            UUID ownerId = memberToOwner.get(playerId);
            if (ownerId == null) {
                return new StartRoster(StartRosterStatus.SOLO, Set.of(playerId));
            }
            if (!ownerId.equals(playerId)) {
                return new StartRoster(StartRosterStatus.NOT_OWNER, Set.of());
            }
            Set<UUID> members = parties.get(ownerId);
            Set<UUID> roster = members == null
                    ? Set.of(playerId)
                    : Collections.unmodifiableSet(new LinkedHashSet<>(members));
            return new StartRoster(StartRosterStatus.OWNER, roster);
        }
    }

    /**
     * Returns the owner-first party roster snapshot only when the given player owns a party.
     *
     * <p>Unlike {@link #assembleRoster(UUID)}, this never exposes the full roster to a
     * non-owner member, so it is safe for owner-sensitive group actions such as Continue.
     *
     * @param playerId the UUID of the player whose owned party is requested
     * @return the owner's roster snapshot, or empty if the player does not own a party
     */
    @Nonnull
    public Optional<PartyRosterSnapshot> tryGetOwnedRoster(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            Set<UUID> members = parties.get(playerId);
            if (members == null) {
                return Optional.empty();
            }
            return Optional.of(new PartyRosterSnapshot(
                    playerId,
                    Collections.unmodifiableSet(new LinkedHashSet<>(members))
            ));
        }
    }

    /**
     * Returns the owner of the party that the given player belongs to.
     *
     * @param playerId the UUID of any party member
     * @return the owner UUID, or empty if the player is not in a party
     */
    @Nonnull
    public Optional<UUID> getPartyOwner(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            return Optional.ofNullable(memberToOwner.get(playerId));
        }
    }

    /**
     * Returns whether the given player is currently in a party.
     *
     * @param playerId the UUID to check
     * @return {@code true} if the player belongs to a party
     */
    public boolean isInParty(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            return memberToOwner.containsKey(playerId);
        }
    }

    /**
     * Returns whether the given player is the owner of a party.
     *
     * @param playerId the UUID to check
     * @return {@code true} if the player owns a party
     */
    public boolean isPartyOwner(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            return parties.containsKey(playerId);
        }
    }

    /**
     * Returns the owners of all non-expired pending invites addressed to the given player.
     *
     * @param inviteeId the prospective invitee UUID
     * @return immutable set of owner UUIDs whose invites are pending and unexpired
     */
    @Nonnull
    public Set<UUID> getPendingInviteOwners(@Nonnull UUID inviteeId) {
        Objects.requireNonNull(inviteeId, "inviteeId");
        synchronized (lock) {
            purgeExpiredInvites();
            Map<UUID, PendingInvite> invites = pendingInvitesByInvitee.get(inviteeId);
            if (invites == null || invites.isEmpty()) {
                return Set.of();
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(invites.keySet()));
        }
    }

    /**
     * Removes the player from their party on disconnect, transferring ownership when needed.
     *
     * @param playerId the UUID of the disconnecting player
     * @return a description of the departure outcome
     */
    @Nonnull
    public PartyDeparture onPlayerDisconnect(@Nonnull UUID playerId) {
        return leaveParty(playerId);
    }

    // ============================================
    // Internal invite helpers (must hold lock)
    // ============================================

    private boolean isExpired(@Nonnull PendingInvite invite) {
        return nowMillis.getAsLong() >= invite.expiresAtMillis();
    }

    private void purgeExpiredInvites() {
        long now = nowMillis.getAsLong();
        Iterator<Map.Entry<UUID, Map<UUID, PendingInvite>>> outer =
                pendingInvitesByInvitee.entrySet().iterator();
        while (outer.hasNext()) {
            Map<UUID, PendingInvite> invites = outer.next().getValue();
            invites.values().removeIf(invite -> now >= invite.expiresAtMillis());
            if (invites.isEmpty()) {
                outer.remove();
            }
        }
    }

    private void removeInvite(@Nonnull UUID inviteeId, @Nonnull UUID ownerId) {
        Map<UUID, PendingInvite> invites = pendingInvitesByInvitee.get(inviteeId);
        if (invites != null) {
            invites.remove(ownerId);
            if (invites.isEmpty()) {
                pendingInvitesByInvitee.remove(inviteeId);
            }
        }
    }

    private void clearInvitesForInvitee(@Nonnull UUID inviteeId) {
        pendingInvitesByInvitee.remove(inviteeId);
    }

    private void removeInvitesFromOwner(@Nonnull UUID ownerId) {
        Iterator<Map.Entry<UUID, Map<UUID, PendingInvite>>> outer =
                pendingInvitesByInvitee.entrySet().iterator();
        while (outer.hasNext()) {
            Map<UUID, PendingInvite> invites = outer.next().getValue();
            invites.remove(ownerId);
            if (invites.isEmpty()) {
                outer.remove();
            }
        }
    }

    // ============================================
    // Result / value types
    // ============================================

    /** Result of an {@link #invitePlayer(UUID, UUID)} attempt. */
    public enum InviteResult {
        /** A pending invite was created. */
        SUCCESS,
        /** The inviting player does not own a party. */
        NO_PARTY,
        /** The inviting player is in a party but is not its owner. */
        NOT_OWNER,
        /** The inviter attempted to invite themselves. */
        SELF,
        /** The target player already belongs to a party. */
        TARGET_ALREADY_IN_PARTY,
        /** The party has reached {@link #MAX_PARTY_SIZE}. */
        PARTY_FULL,
        /** An unexpired invite from this owner to this target already exists. */
        ALREADY_PENDING
    }

    /** Result of an {@link #acceptInvite(UUID, UUID)} attempt. */
    public enum InviteResponseResult {
        /** The invitee joined the party. */
        SUCCESS,
        /** No matching invite exists. */
        NO_INVITE,
        /** The invite existed but has expired. */
        EXPIRED,
        /** The owner no longer has a party. */
        OWNER_NO_LONGER_HAS_PARTY,
        /** The invitee already joined another party. */
        ALREADY_IN_PARTY,
        /** The party filled before the invite could be accepted. */
        PARTY_FULL
    }

    /** Classification of a {@link #resolveStartRoster(UUID)} result. */
    public enum StartRosterStatus {
        /** The player is not in a party; the roster is a singleton. */
        SOLO,
        /** The player owns the party; the roster is the full party. */
        OWNER,
        /** The player is a party member but not the owner; starting is not permitted. */
        NOT_OWNER
    }

    /** Classification of a {@link #leaveParty(UUID)} outcome. */
    public enum DepartureOutcome {
        /** The player was not in a party. */
        NOT_IN_PARTY,
        /** A non-owner member left; the party persists. */
        LEFT_AS_MEMBER,
        /** The party was removed because no members remained. */
        DISBANDED_EMPTY,
        /** The owner left and ownership transferred to another member. */
        OWNERSHIP_TRANSFERRED
    }

    /**
     * Owner-first roster snapshot for owner-sensitive operations.
     *
     * @param ownerId the party owner UUID
     * @param members immutable owner-first set of member UUIDs
     */
    public record PartyRosterSnapshot(@Nonnull UUID ownerId, @Nonnull Set<UUID> members) {
        public PartyRosterSnapshot {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(members, "members");
        }
    }

    /**
     * Resolved starting roster for a player-initiated dungeon run.
     *
     * @param status the classification of the result
     * @param roster the owner-first roster (empty for {@link StartRosterStatus#NOT_OWNER})
     */
    public record StartRoster(@Nonnull StartRosterStatus status, @Nonnull Set<UUID> roster) {
        public StartRoster {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(roster, "roster");
        }
    }

    /**
     * Outcome of a {@link #leaveParty(UUID)} or {@link #onPlayerDisconnect(UUID)} call.
     *
     * @param outcome          the departure classification
     * @param newOwnerId       the new owner UUID when ownership transferred, otherwise {@code null}
     * @param remainingMembers owner-first remaining members after the departure (may be empty)
     */
    public record PartyDeparture(
            @Nonnull DepartureOutcome outcome,
            @Nullable UUID newOwnerId,
            @Nonnull Set<UUID> remainingMembers
    ) {
        public PartyDeparture {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(remainingMembers, "remainingMembers");
        }

        @Nonnull
        static PartyDeparture notInParty() {
            return new PartyDeparture(DepartureOutcome.NOT_IN_PARTY, null, Set.of());
        }

        @Nonnull
        static PartyDeparture disbanded() {
            return new PartyDeparture(DepartureOutcome.DISBANDED_EMPTY, null, Set.of());
        }

        @Nonnull
        static PartyDeparture leftAsMember(@Nonnull UUID ownerId, @Nonnull Set<UUID> remainingMembers) {
            return new PartyDeparture(DepartureOutcome.LEFT_AS_MEMBER, ownerId,
                    Collections.unmodifiableSet(new LinkedHashSet<>(remainingMembers)));
        }

        @Nonnull
        static PartyDeparture ownershipTransferred(@Nonnull UUID newOwnerId, @Nonnull Set<UUID> remainingMembers) {
            return new PartyDeparture(DepartureOutcome.OWNERSHIP_TRANSFERRED, newOwnerId,
                    Collections.unmodifiableSet(new LinkedHashSet<>(remainingMembers)));
        }
    }

    /**
     * A pending party invite awaiting acceptance.
     *
     * @param ownerId         the inviting party owner UUID
     * @param inviteeId       the invited player UUID
     * @param createdAtMillis the epoch time the invite was created
     * @param expiresAtMillis the epoch time the invite expires
     */
    public record PendingInvite(
            @Nonnull UUID ownerId,
            @Nonnull UUID inviteeId,
            long createdAtMillis,
            long expiresAtMillis
    ) {
        public PendingInvite {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(inviteeId, "inviteeId");
        }
    }
}
