package com.duntale.zsquad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("CustomizeCharacterService")
class CustomizeCharacterServiceTest {

    @Test
    @DisplayName("Should choose different free slots in round-robin order")
    void shouldChooseDifferentFreeSlotsInRoundRobinOrder() {
        CustomizeCharacterService service = new CustomizeCharacterService(null, null, null, null);
        List<CustomizeCharacterConfig.SetupSlot> slots = List.of(slot(0.0D), slot(5.0D));

        CustomizeCharacterService.SlotReservation first = service.reserveSlot(UUID.randomUUID(), slots);
        CustomizeCharacterService.SlotReservation second = service.reserveSlot(UUID.randomUUID(), slots);

        assertEquals(0, first.slotIndex());
        assertEquals(1, second.slotIndex());
    }

    @Test
    @DisplayName("Should skip occupied slots when a later slot is free")
    void shouldSkipOccupiedSlotsWhenALaterSlotIsFree() {
        CustomizeCharacterService service = new CustomizeCharacterService(null, null, null, null);
        List<CustomizeCharacterConfig.SetupSlot> slots = List.of(slot(0.0D), slot(5.0D));
        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();
        UUID playerThree = UUID.randomUUID();

        service.reserveSlot(playerOne, slots);
        service.reserveSlot(playerTwo, slots);
        service.releaseReservation(playerTwo);

        CustomizeCharacterService.SlotReservation reservation = service.reserveSlot(playerThree, slots);

        assertEquals(1, reservation.slotIndex());
    }

    @Test
    @DisplayName("Should fall back deterministically when all slots are occupied")
    void shouldFallBackDeterministicallyWhenAllSlotsAreOccupied() {
        CustomizeCharacterService service = new CustomizeCharacterService(null, null, null, null);
        List<CustomizeCharacterConfig.SetupSlot> slots = List.of(slot(0.0D), slot(5.0D));

        service.reserveSlot(UUID.randomUUID(), slots);
        service.reserveSlot(UUID.randomUUID(), slots);

        CustomizeCharacterService.SlotReservation reservation = service.reserveSlot(UUID.randomUUID(), slots);

        assertEquals(0, reservation.slotIndex());
    }

    @Test
    @DisplayName("Should use fallback mode when no setup slots exist")
    void shouldUseFallbackModeWhenNoSetupSlotsExist() {
        CustomizeCharacterService service = new CustomizeCharacterService(null, null, null, null);

        CustomizeCharacterService.SlotReservation reservation = service.reserveSlot(UUID.randomUUID(), List.of());

        assertEquals(-1, reservation.slotIndex());
        assertNull(reservation.slot());
    }

    @Test
    @DisplayName("Should release a reservation so the slot can be reused")
    void shouldReleaseAReservationSoTheSlotCanBeReused() {
        CustomizeCharacterService service = new CustomizeCharacterService(null, null, null, null);
        List<CustomizeCharacterConfig.SetupSlot> slots = List.of(slot(0.0D));
        UUID player = UUID.randomUUID();

        service.reserveSlot(player, slots);
        service.releaseReservation(player);

        CustomizeCharacterService.SlotReservation reservation = service.reserveSlot(UUID.randomUUID(), slots);

        assertEquals(0, reservation.slotIndex());
    }

    private static CustomizeCharacterConfig.SetupSlot slot(double x) {
        return new CustomizeCharacterConfig.SetupSlot(x, 64.0D, 0.0D, 0.0F, null);
    }
}