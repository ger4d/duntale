package com.duntale.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LootTable")
class LootTableTest {

    @Test
    @DisplayName("Should clamp no-replacement rolls to the number of eligible entries")
    void shouldClampNoReplacementRollsToEligibleEntries() {
        LootTable table = new LootTable(List.of(
                new LootEntry("Gold_Coin", 1.0),
                new LootEntry("Weapon_Sword_Iron", 1.0),
                new LootEntry("Armor_Iron_Chest", 1.0)
        ), 1);

        List<ItemStack> drops = table.roll(LootContext.forFloorLevel(5), 5, false);
        Set<String> itemIds = drops.stream().map(ItemStack::getItemId).collect(Collectors.toSet());

        assertEquals(3, drops.size());
        assertEquals(3, itemIds.size());
    }

    @Test
    @DisplayName("Should roll the gear and gold pools independently")
    void shouldRollGearAndGoldIndependently() {
        LootTable table = new LootTable(
                List.of(new LootEntry("Weapon_Sword_Iron", 1.0)), 1, 1.0,
                List.of(new LootEntry("Gold_Coin", 1.0)), 1.0);

        Set<String> itemIds = table.roll(10, 0).stream()
                .map(ItemStack::getItemId).collect(Collectors.toSet());

        assertEquals(Set.of("Weapon_Sword_Iron", "Gold_Coin"), itemIds);
    }

    @Test
    @DisplayName("Should drop only gold when the gear chance is zero")
    void shouldDropOnlyGoldWhenGearChanceZero() {
        LootTable table = new LootTable(
                List.of(new LootEntry("Weapon_Sword_Iron", 1.0)), 1, 0.0,
                List.of(new LootEntry("Gold_Coin", 1.0)), 1.0);

        List<ItemStack> drops = table.roll(10, 0);

        assertEquals(1, drops.size());
        assertEquals("Gold_Coin", drops.getFirst().getItemId());
    }

    @Test
    @DisplayName("Should drop nothing when both pool chances are zero")
    void shouldDropNothingWhenBothChancesZero() {
        LootTable table = new LootTable(
                List.of(new LootEntry("Weapon_Sword_Iron", 1.0)), 1, 0.0,
                List.of(new LootEntry("Gold_Coin", 1.0)), 0.0);

        assertTrue(table.roll(10, 0).isEmpty());
    }

    @Test
    @DisplayName("A gear-only table has no gold pool and rolls only gear")
    void gearOnlyTableHasNoGoldPool() {
        LootTable table = new LootTable(List.of(new LootEntry("Weapon_Sword_Iron", 1.0)), 1, 1.0);

        List<ItemStack> drops = table.roll(10, 0);

        assertEquals(1, drops.size());
        assertEquals("Weapon_Sword_Iron", drops.getFirst().getItemId());
        assertTrue(table.rollGold(10).isEmpty());
    }
}