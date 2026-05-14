package com.duntale.dungeon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PartyService")
class PartyServiceTest {

    private PartyService service;

    @BeforeEach
    void setUp() {
        service = new PartyService();
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
            service.invitePlayer(owner, member);

            assertFalse(service.createParty(member));
        }
    }

    // ============================================
    // Invite Player
    // ============================================

    @Nested
    @DisplayName("invitePlayer")
    class InvitePlayer {

        @Test
        @DisplayName("Should invite a player to an existing party")
        void shouldInvitePlayerToExistingParty() {
            UUID owner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            service.createParty(owner);
            assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, invitee));

            assertTrue(service.isInParty(invitee));
            assertEquals(Optional.of(owner), service.getPartyOwner(invitee));
            assertEquals(Set.of(owner, invitee), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should reject invite when owner has no party")
        void shouldRejectInviteWhenNoParty() {
            UUID notOwner = UUID.randomUUID();
            UUID invitee = UUID.randomUUID();

            assertEquals(PartyService.InviteResult.NO_PARTY, service.invitePlayer(notOwner, invitee));
            assertFalse(service.isInParty(invitee));
        }

        @Test
        @DisplayName("Should reject invite when target is already in a party")
        void shouldRejectInviteWhenTargetAlreadyInParty() {
            UUID ownerA = UUID.randomUUID();
            UUID ownerB = UUID.randomUUID();
            UUID player = UUID.randomUUID();

            service.createParty(ownerA);
            service.createParty(ownerB);
            service.invitePlayer(ownerA, player);

            assertEquals(PartyService.InviteResult.ALREADY_IN_PARTY, service.invitePlayer(ownerB, player));
        }

        @Test
        @DisplayName("Should reject invite when party is full")
        void shouldRejectInviteWhenPartyIsFull() {
            UUID owner = UUID.randomUUID();
            service.createParty(owner);

            for (int i = 0; i < PartyService.MAX_PARTY_SIZE - 1; i++) {
                assertEquals(PartyService.InviteResult.SUCCESS, service.invitePlayer(owner, UUID.randomUUID()));
            }

            assertEquals(PartyService.InviteResult.PARTY_FULL, service.invitePlayer(owner, UUID.randomUUID()));
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
            service.invitePlayer(owner, member);

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
            service.invitePlayer(owner, memberA);
            service.invitePlayer(owner, memberB);

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
            service.invitePlayer(owner, memberA);
            service.invitePlayer(owner, memberB);

            assertTrue(service.leaveParty(memberA));

            assertFalse(service.isInParty(memberA));
            assertTrue(service.isInParty(owner));
            assertTrue(service.isInParty(memberB));
            assertEquals(Set.of(owner, memberB), service.assembleRoster(owner));
        }

        @Test
        @DisplayName("Should disband party when owner leaves")
        void shouldDisbandPartyWhenOwnerLeaves() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            service.invitePlayer(owner, member);

            assertTrue(service.leaveParty(owner));

            assertFalse(service.isInParty(owner));
            assertFalse(service.isInParty(member));
        }

        @Test
        @DisplayName("Should return false when player is not in a party")
        void shouldReturnFalseWhenNotInParty() {
            assertFalse(service.leaveParty(UUID.randomUUID()));
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
            service.invitePlayer(owner, memberA);
            service.invitePlayer(owner, memberB);

            Set<UUID> expected = Set.of(owner, memberA, memberB);
            assertEquals(expected, service.assembleRoster(owner));
            assertEquals(expected, service.assembleRoster(memberA));
            assertEquals(expected, service.assembleRoster(memberB));
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
            service.invitePlayer(owner, member);

            service.onPlayerDisconnect(member);
            assertFalse(service.isInParty(member));
            assertTrue(service.isInParty(owner));
        }

        @Test
        @DisplayName("Should disband party when owner disconnects")
        void shouldDisbandWhenOwnerDisconnects() {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            service.createParty(owner);
            service.invitePlayer(owner, member);

            service.onPlayerDisconnect(owner);
            assertFalse(service.isInParty(owner));
            assertFalse(service.isInParty(member));
        }

        @Test
        @DisplayName("Should be no-op for player not in a party")
        void shouldBeNoOpForPlayerNotInParty() {
            service.onPlayerDisconnect(UUID.randomUUID());
            // no exception thrown
        }
    }
}
