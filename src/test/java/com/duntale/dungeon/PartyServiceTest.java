package com.duntale.dungeon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PartyService")
class PartyServiceTest {

    private PartyService service;

    @BeforeEach
    void setUp() {
        service = new PartyService();
    }

    /** Helper: invite and accept, asserting both steps succeed. */
    private void join(UUID owner, UUID member) {
        assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, member));
        assertEquals(PartyService.InviteResponseResult.SUCCESS, service.acceptInvite(member, owner));
    }

    // ============================================
    // Create Party
    // ============================================

    @Nested
    @DisplayName("createParty")
    class CreateParty {

        @Test
        @DisplayName("Should create a party with the owner as the sole member")
        void shouldCreatePartyWithOwnerAsSoleMember() {
            UUID owner = UUID.randomUUID();

            assertTrue(service.createParty(owner));
            assertTrue(service.isInParty(owner));
            assertTrue(service.isPartyOwner(owner));
            assertEquals(Optional.of(owner), service.getPartyOwner(owner));
            assertEquals(Set.of(owner), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should reject creation when player is already in a party")
        void shouldRejectCreationWhenAlreadyInParty() {
            UUID owner = UUID.randomUUID();

            assertTrue(service.createParty(owner));
            assertFalse(service.createParty(owner));
        }

        @Test
        @DisplayName("Should reject creation when player is a member of another party")
        void shouldRejectCreationWhenMemberOfAnotherParty() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            assertFalse(service.createParty(member));
        }
    }

    // ============================================
    // Invite Player (pending invite handshake)
    // ============================================

    @Nested
    @DisplayName("invitePlayer")
    class InvitePlayer {

        @Test
        @DisplayName("Should create a pending invite without adding the target to the party")
        void shouldCreatePendingInviteWithoutJoining() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));

            // The invitee is NOT a member until they accept.
            assertFalse(service.isInParty(invitee));
            assertEquals(Set.of(owner), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should reject self-invite")
        void shouldRejectSelfInvite() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            assertEquals(PartyService.InviteResult.SELF, service.invitePlayer(owner, owner));
        }

        @Test
        @DisplayName("Should auto-create a party for the inviter when they have none")
        void shouldAutoCreatePartyWhenInviterHasNone() {
            UUID inviter = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(inviter, invitee));
            assertTrue(service.isInParty(inviter));
            assertFalse(service.isInParty(invitee));
        }

        @Test
        @DisplayName("Should reject invite from a non-owner member")
        void shouldRejectInviteFromNonOwner() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            assertEquals(PartyService.InviteResult.NOT_OWNER, service.invitePlayer(member, invitee));
        }

        @Test
        @DisplayName("Should reject invite when target is already in a party")
        void shouldRejectInviteWhenTargetAlreadyInParty() {
            UUID ownerA = UUID.randomUUID();
            UUID ownerB = UUID.randomUUID();
            UUID player = UUID.randomUUID();

            service.createParty(ownerA);
            service.createParty(ownerB);
            join(ownerA, player);

            assertEquals(PartyService.InviteResult.TARGET_ALREADY_IN_PARTY,
                    service.invitePlayer(ownerB, player));
        }

        @Test
        @DisplayName("Should reject a duplicate pending invite from the same owner")
        void shouldRejectDuplicatePendingInvite() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));
            assertEquals(PartyService.InviteResult.ALREADY_PENDING, service.invitePlayer(owner, invitee));
        }

        @Test
        @DisplayName("Should reject invite when party is full")
        void shouldRejectInviteWhenPartyIsFull() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            for (int i = 0; i < PartyService.MAX_PARTY_SIZE - 1; i++) {
                join(owner, UUID.randomUUID());
            }

            assertEquals(PartyService.InviteResult.PARTY_FULL, service.invitePlayer(owner, UUID.randomUUID()));
        }
    }

    // ============================================
    // Accept Invite
    // ============================================

    @Nested
    @DisplayName("acceptInvite")
    class AcceptInvite {

        @Test
        @DisplayName("Should add the invitee to the party on accept")
        void shouldAddInviteeOnAccept() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));
            assertEquals(PartyService.InviteResponseResult.SUCCESS, service.acceptInvite(invitee, owner));

            assertTrue(service.isInParty(invitee));
            assertEquals(Optional.of(owner), service.getPartyOwner(invitee));
            assertEquals(Set.of(owner, invitee), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should reject accept when no invite exists")
        void shouldRejectAcceptWhenNoInvite() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResponseResult.NO_INVITE, service.acceptInvite(invitee, owner));
        }

        @Test
        @DisplayName("Should clear pending invites once the invitee joins another party")
        void shouldClearInvitesWhenJoiningAnotherParty() {
            UUID ownerA = UUID.randomUUID();
            UUID ownerB = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(ownerA);
            service.createParty(ownerB);
            // ownerB invites first but invitee joins ownerA, which clears the stale invite.
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(ownerB, invitee));
            join(ownerA, invitee);

            assertEquals(PartyService.InviteResponseResult.NO_INVITE,
                    service.acceptInvite(invitee, ownerB));
            assertTrue(service.isInParty(invitee));
        }

        @Test
        @DisplayName("Should reject accept when the owner no longer has a party")
        void shouldRejectAcceptWhenOwnerDisbanded() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));
            assertTrue(service.disbandParty(owner));

            assertEquals(PartyService.InviteResponseResult.NO_INVITE, service.acceptInvite(invitee, owner));
        }

        @Test
        @DisplayName("Should reject accept when the party filled before acceptance")
        void shouldRejectAcceptWhenPartyFilled() {
            UUID owner = UUID.randomUUID();
            UUID lateInvitee = UUID.randomUUID();

            service.createParty(owner);
            // Invite the late invitee while there is still room.
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, lateInvitee));
            // Fill the rest of the party.
            for (int i = 0; i < PartyService.MAX_PARTY_SIZE - 1; i++) {
                join(owner, UUID.randomUUID());
            }

            assertEquals(PartyService.InviteResponseResult.PARTY_FULL, service.acceptInvite(lateInvitee, owner));
        }

        @Test
        @DisplayName("Should not join when the invite has expired")
        void shouldNotJoinWhenExpired() {
            AtomicLong clock = new AtomicLong(0L);
            PartyService timed = new PartyService(clock::get);
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            timed.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, timed.invitePlayer(owner, invitee));

            // At/after the TTL boundary the invite is purged, so acceptance finds no invite.
            clock.set(PartyService.INVITE_TTL_MILLIS);
            assertEquals(PartyService.InviteResponseResult.NO_INVITE, timed.acceptInvite(invitee, owner));
            assertFalse(timed.isInParty(invitee));
        }
    }

    // ============================================
    // Decline Invite
    // ============================================

    @Nested
    @DisplayName("declineInvite")
    class DeclineInvite {

        @Test
        @DisplayName("Should remove a pending invite on decline")
        void shouldRemovePendingInviteOnDecline() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));
            assertTrue(service.declineInvite(invitee, owner));

            assertEquals(PartyService.InviteResponseResult.NO_INVITE, service.acceptInvite(invitee, owner));
        }

        @Test
        @DisplayName("Should return false when there is no invite to decline")
        void shouldReturnFalseWhenNoInvite() {
            assertFalse(service.declineInvite(UUID.randomUUID(), UUID.randomUUID()));
        }
    }

    // ============================================
    // Kick Player
    // ============================================

    @Nested
    @DisplayName("kickPlayer")
    class KickPlayer {

        @Test
        @DisplayName("Should kick a member from the party")
        void shouldKickMemberFromParty() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            assertTrue(service.kickPlayer(owner, member));
            assertFalse(service.isInParty(member));
            assertEquals(Set.of(owner), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should not allow owner to kick themselves")
        void shouldNotAllowOwnerToKickThemselves() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            assertFalse(service.kickPlayer(owner, owner));
            assertTrue(service.isInParty(owner));
        }

        @Test
        @DisplayName("Should return false when kicking non-member")
        void shouldReturnFalseWhenKickingNonMember() {
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();

            service.createParty(owner);
            assertFalse(service.kickPlayer(owner, stranger));
        }

        @Test
        @DisplayName("Should return false when owner has no party")
        void shouldReturnFalseWhenOwnerHasNoParty() {
            assertFalse(service.kickPlayer(UUID.randomUUID(), UUID.randomUUID()));
        }
    }

    // ============================================
    // Disband Party
    // ============================================

    @Nested
    @DisplayName("disbandParty")
    class DisbandParty {

        @Test
        @DisplayName("Should disband party and remove all members")
        void shouldDisbandPartyAndRemoveAllMembers() {
            UUID owner = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();

            service.createParty(owner);
            join(owner, memberA);
            join(owner, memberB);

            assertTrue(service.disbandParty(owner));

            assertFalse(service.isInParty(owner));
            assertFalse(service.isInParty(memberA));
            assertFalse(service.isInParty(memberB));
            assertFalse(service.isPartyOwner(owner));
        }

        @Test
        @DisplayName("Should return false when no party exists")
        void shouldReturnFalseWhenNoPartyExists() {
            assertFalse(service.disbandParty(UUID.randomUUID()));
        }
    }

    // ============================================
    // Leave Party
    // ============================================

    @Nested
    @DisplayName("leaveParty")
    class LeaveParty {

        @Test
        @DisplayName("Should remove member from party without affecting others")
        void shouldRemoveMemberWithoutAffectingOthers() {
            UUID owner = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();

            service.createParty(owner);
            join(owner, memberA);
            join(owner, memberB);

            PartyService.PartyDeparture departure = service.leaveParty(memberA);
            assertEquals(PartyService.DepartureOutcome.LEFT_AS_MEMBER, departure.outcome());
            assertEquals(owner, departure.newOwnerId());

            assertFalse(service.isInParty(memberA));
            assertTrue(service.isInParty(owner));
            assertTrue(service.isInParty(memberB));
            assertEquals(Set.of(owner, memberB), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should transfer ownership when the owner leaves a non-empty party")
        void shouldTransferOwnershipWhenOwnerLeaves() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            PartyService.PartyDeparture departure = service.leaveParty(owner);
            assertEquals(PartyService.DepartureOutcome.OWNERSHIP_TRANSFERRED, departure.outcome());
            assertEquals(member, departure.newOwnerId());

            assertFalse(service.isInParty(owner));
            assertTrue(service.isInParty(member));
            assertTrue(service.isPartyOwner(member));
            assertEquals(Set.of(member), service.assembleRoster(member));
        }

        @Test
        @DisplayName("Should disband the party when the lone owner leaves")
        void shouldDisbandWhenLoneOwnerLeaves() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            PartyService.PartyDeparture departure = service.leaveParty(owner);
            assertEquals(PartyService.DepartureOutcome.DISBANDED_EMPTY, departure.outcome());
            assertFalse(service.isInParty(owner));
        }

        @Test
        @DisplayName("Should report NOT_IN_PARTY when player is not in a party")
        void shouldReportNotInPartyWhenNotInParty() {
            PartyService.PartyDeparture departure = service.leaveParty(UUID.randomUUID());
            assertEquals(PartyService.DepartureOutcome.NOT_IN_PARTY, departure.outcome());
        }
    }

    // ============================================
    // resolveStartRoster
    // ============================================

    @Nested
    @DisplayName("resolveStartRoster")
    class ResolveStartRoster {

        @Test
        @DisplayName("Should return SOLO singleton for a player without a party")
        void shouldReturnSoloForPlayerWithoutParty() {
            UUID solo = UUID.randomUUID();
            PartyService.StartRoster roster = service.resolveStartRoster(solo);

            assertEquals(PartyService.StartRosterStatus.SOLO, roster.status());
            assertEquals(Set.of(solo), roster.roster());
        }

        @Test
        @DisplayName("Should return OWNER roster for the party owner")
        void shouldReturnOwnerRosterForOwner() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            PartyService.StartRoster roster = service.resolveStartRoster(owner);
            assertEquals(PartyService.StartRosterStatus.OWNER, roster.status());
            assertEquals(Set.of(owner, member), roster.roster());
        }

        @Test
        @DisplayName("Should reject a non-owner member with an empty roster")
        void shouldReturnNotOwnerForMember() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            PartyService.StartRoster roster = service.resolveStartRoster(member);
            assertEquals(PartyService.StartRosterStatus.NOT_OWNER, roster.status());
            assertTrue(roster.roster().isEmpty());
        }

        @Test
        @DisplayName("Should return an owner-first roster")
        void shouldReturnOwnerFirstRoster() {
            UUID owner = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();

            service.createParty(owner);
            join(owner, memberA);
            join(owner, memberB);

            List<UUID> ordered = new ArrayList<>(service.resolveStartRoster(owner).roster());
            assertEquals(owner, ordered.get(0));
        }
    }

    // ============================================
    // tryGetOwnedRoster
    // ============================================

    @Nested
    @DisplayName("tryGetOwnedRoster")
    class TryGetOwnedRoster {

        @Test
        @DisplayName("Should return the roster snapshot only for the owner")
        void shouldReturnSnapshotForOwner() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            Optional<PartyService.PartyRosterSnapshot> snapshot = service.tryGetOwnedRoster(owner);
            assertTrue(snapshot.isPresent());
            assertEquals(owner, snapshot.get().ownerId());
            assertEquals(Set.of(owner, member), snapshot.get().members());
        }

        @Test
        @DisplayName("Should return empty for a non-owner member")
        void shouldReturnEmptyForMember() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            assertTrue(service.tryGetOwnedRoster(member).isEmpty());
        }

        @Test
        @DisplayName("Should return empty for a solo player")
        void shouldReturnEmptyForSoloPlayer() {
            assertTrue(service.tryGetOwnedRoster(UUID.randomUUID()).isEmpty());
        }
    }

    // ============================================
    // Assemble Roster
    // ============================================

    @Nested
    @DisplayName("assembleRoster")
    class AssembleRoster {

        @Test
        @DisplayName("Should return singleton set for solo player")
        void shouldReturnSingletonForSoloPlayer() {
            UUID solo = UUID.randomUUID();
            assertEquals(Set.of(solo), service.assembleRoster(solo));
        }

        @Test
        @DisplayName("Should return full party roster for any member")
        void shouldReturnFullRosterForAnyMember() {
            UUID owner = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();

            service.createParty(owner);
            join(owner, memberA);
            join(owner, memberB);

            Set<UUID> expected = Set.of(owner, memberA, memberB);
            assertEquals(expected, service.assembleRoster(owner));
            assertEquals(expected, service.assembleRoster(memberA));
            assertEquals(expected, service.assembleRoster(memberB));
        }

        @Test
        @DisplayName("Should return an immutable roster set")
        void shouldReturnImmutableRoster() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            Set<UUID> roster = service.assembleRoster(owner);
            assertThrows(UnsupportedOperationException.class, () -> roster.add(UUID.randomUUID()));
        }
    }

    // ============================================
    // Disconnect
    // ============================================

    @Nested
    @DisplayName("onPlayerDisconnect")
    class OnPlayerDisconnect {

        @Test
        @DisplayName("Should remove member on disconnect")
        void shouldRemoveMemberOnDisconnect() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            PartyService.PartyDeparture departure = service.onPlayerDisconnect(member);
            assertEquals(PartyService.DepartureOutcome.LEFT_AS_MEMBER, departure.outcome());
            assertFalse(service.isInParty(member));
            assertTrue(service.isInParty(owner));
        }

        @Test
        @DisplayName("Should transfer ownership when the owner disconnects with members remaining")
        void shouldTransferOwnershipWhenOwnerDisconnects() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            join(owner, member);

            PartyService.PartyDeparture departure = service.onPlayerDisconnect(owner);
            assertEquals(PartyService.DepartureOutcome.OWNERSHIP_TRANSFERRED, departure.outcome());
            assertEquals(member, departure.newOwnerId());
            assertFalse(service.isInParty(owner));
            assertTrue(service.isPartyOwner(member));
        }

        @Test
        @DisplayName("Should report NOT_IN_PARTY for a player not in a party")
        void shouldReportNotInPartyForPlayerNotInParty() {
            PartyService.PartyDeparture departure = service.onPlayerDisconnect(UUID.randomUUID());
            assertEquals(PartyService.DepartureOutcome.NOT_IN_PARTY, departure.outcome());
        }
    }
}
