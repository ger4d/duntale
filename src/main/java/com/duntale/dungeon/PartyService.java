package com.duntale.dungeon;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * In-memory party service for assembling dungeon run rosters.
 *
 * <p>Parties are transient pre-run groups used to seed the initial roster for
 * dungeon instance creation. Once a dungeon run starts, the instance roster
 * is authoritative and party state is no longer relevant.
 *
 * <p>All state is in-memory and lost on server restart. This is acceptable
 * because parties only exist before a dungeon run begins.
 *
 * <p>Thread safety: all public methods synchronize on a single internal lock.
 *
 * @since 1.6.0
 */
public class PartyService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Maximum number of players in a single party, including the owner. */
    public static final int MAX_PARTY_SIZE = 6;

    // ============================================
    // Fields
    // ============================================

    private final Object lock = new Object();

    /** Owner UUID to the set of member UUIDs (including the owner). */
    private final Map<UUID, Set<UUID>> parties = new HashMap<>();

    /** Member UUID to their party owner UUID. Includes the owner mapping to themselves. */
    private final Map<UUID, UUID> memberToOwner = new HashMap<>();

    // ============================================
    // Public API
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
     * Invites a player to the party owned by the given owner.
     *
     * @param ownerId  the UUID of the party owner
     * @param playerId the UUID of the player to invite
     * @return the result of the invite attempt
     */
    @Nonnull
    public InviteResult invitePlayer(@Nonnull UUID ownerId, @Nonnull UUID playerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            Set<UUID> members = parties.get(ownerId);
            if (members == null) {
                return InviteResult.NO_PARTY;
            }
            if (memberToOwner.containsKey(playerId)) {
                return InviteResult.ALREADY_IN_PARTY;
            }
            if (members.size() >= MAX_PARTY_SIZE) {
                return InviteResult.PARTY_FULL;
            }
            members.add(playerId);
            memberToOwner.put(playerId, ownerId);
            LOGGER.at(Level.FINE).log("Player %s invited to party owned by %s", playerId, ownerId);
            return InviteResult.SUCCESS;
        }
    }

    /**
     * Kicks a player from the party owned by the given owner.
     *
     * <p>The owner cannot kick themselves; use {@link #disbandParty(UUID)} instead.
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
            LOGGER.at(Level.FINE).log("Party owned by %s disbanded (%d members)", ownerId, members.size());
            return true;
        }
    }

    /**
     * Removes a player from their current party.
     *
     * <p>If the player is the party owner, the entire party is disbanded.
     * If the player is a regular member, only they are removed.
     *
     * @param playerId the UUID of the player leaving
     * @return {@code true} if the player was removed from a party, {@code false} if not in a party
     */
    public boolean leaveParty(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            UUID ownerId = memberToOwner.get(playerId);
            if (ownerId == null) {
                return false;
            }
            if (ownerId.equals(playerId)) {
                return disbandParty(playerId);
            }
            Set<UUID> members = parties.get(ownerId);
            if (members != null) {
                members.remove(playerId);
            }
            memberToOwner.remove(playerId);
            LOGGER.at(Level.FINE).log("Player %s left party owned by %s", playerId, ownerId);
            return true;
        }
    }

    /**
     * Returns the roster for the party that the given player belongs to.
     *
     * <p>If the player is not in a party, returns a singleton set containing only the player.
     * This simplifies roster assembly: callers always get a valid non-empty set.
     *
     * @param playerId the UUID of any party member or solo player
     * @return immutable set of player UUIDs forming the roster
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
            return Set.copyOf(members);
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
     * Removes the player from their party on disconnect.
     *
     * <p>If the disconnecting player is a party owner, the party is disbanded.
     *
     * @param playerId the UUID of the disconnecting player
     */
    public void onPlayerDisconnect(@Nonnull UUID playerId) {
        leaveParty(playerId);
    }

    // ============================================
    // Result Types
    // ============================================

    /** Result of a {@link #invitePlayer(UUID, UUID)} attempt. */
    public enum InviteResult {
        /** The player was successfully added to the party. */
        SUCCESS,
        /** The inviting player does not own a party. */
        NO_PARTY,
        /** The target player already belongs to a party. */
        ALREADY_IN_PARTY,
        /** The party has reached {@link #MAX_PARTY_SIZE}. */
        PARTY_FULL
    }
}
