package com.duntale.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("NpcLootSystem")
class NpcLootSystemTest {

    @Nested
    @DisplayName("resolveAttackerUuid")
    class ResolveAttackerUuid {

        @Test
        @DisplayName("Should credit direct player attacker using their own UUID")
        void shouldCreditDirectPlayerAttacker() {
            UUID playerUuid = UUID.randomUUID();

            UUID result = AttackerResolver.resolveAttackerUuid(true, playerUuid, null, null);

            assertEquals(playerUuid, result);
        }

        @Test
        @DisplayName("Should return null for player attacker missing UUIDComponent")
        void shouldReturnNullForPlayerMissingUuid() {
            UUID result = AttackerResolver.resolveAttackerUuid(true, null, null, null);

            assertNull(result);
        }

        @Test
        @DisplayName("Should credit companion kill to the companion owner")
        void shouldCreditCompanionKillToOwner() {
            UUID ownerUuid = UUID.randomUUID();

            UUID result = AttackerResolver.resolveAttackerUuid(false, null, ownerUuid, null);

            assertEquals(ownerUuid, result);
        }

        @Test
        @DisplayName("Should credit deployable (turret) kill to the deployable owner")
        void shouldCreditDeployableKillToOwner() {
            UUID ownerUuid = UUID.randomUUID();

            UUID result = AttackerResolver.resolveAttackerUuid(false, null, null, ownerUuid);

            assertEquals(ownerUuid, result);
        }

        @Test
        @DisplayName("Should prioritize the companion owner over the deployable owner")
        void shouldPrioritizeCompanionOverDeployableOwner() {
            UUID companionOwnerUuid = UUID.randomUUID();
            UUID deployableOwnerUuid = UUID.randomUUID();

            UUID result = AttackerResolver.resolveAttackerUuid(false, null, companionOwnerUuid, deployableOwnerUuid);

            assertEquals(companionOwnerUuid, result);
        }

        @Test
        @DisplayName("Should prioritize the direct player over any owner attribution")
        void shouldPrioritizePlayerOverOwners() {
            UUID playerUuid = UUID.randomUUID();
            UUID companionOwnerUuid = UUID.randomUUID();
            UUID deployableOwnerUuid = UUID.randomUUID();

            UUID result = AttackerResolver.resolveAttackerUuid(true, playerUuid, companionOwnerUuid, deployableOwnerUuid);

            assertEquals(playerUuid, result);
        }

        @Test
        @DisplayName("Should return null for unrelated NPC attacker")
        void shouldReturnNullForUnrelatedNpcAttacker() {
            UUID result = AttackerResolver.resolveAttackerUuid(false, null, null, null);

            assertNull(result);
        }
    }
}
