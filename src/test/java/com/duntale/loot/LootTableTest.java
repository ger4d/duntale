package com.duntale.loot;

import com.duntale.loot.LootEntry.GearType;
import com.duntale.progression.GearLevelService;
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

    @Test
    @DisplayName("A gear entry with no explicit band stamps gear at the killed NPC's level")
    void noBandGearStampsAtNpcLevel() {
        LootTable table = new LootTable(List.of(weaponEntry("Weapon_Sword_Iron", null, null)), 1, 1.0);

        for (int npcLevel : new int[]{1, 10, 25, 60, 100}) {
            ItemStack drop = table.roll(npcLevel, 0).getFirst();
            assertEquals(npcLevel, GearLevelService.getWeaponLevel(drop),
                    "no-band gear should stamp at the kill level " + npcLevel);
        }
    }

    @Test
    @DisplayName("A gear entry with an explicit band still honors that band")
    void explicitBandGearHonorsBand() {
        LootTable table = new LootTable(List.of(weaponEntry("Weapon_Sword_Iron", 40, 40)), 1, 1.0);

        // Killed at floor 10, but the explicit band pins the stamped level to 40.
        ItemStack drop = table.roll(10, 0).getFirst();
        assertEquals(40, GearLevelService.getWeaponLevel(drop));
    }

    @Test
    @DisplayName("A floor-gated entry is eligible on a matching kill and excluded otherwise")
    void floorGatedEntryRespectsFloorOnKill() {
        // Regression for the null-floor trap: before the kill context carried a floor, this entry was
        // silently filtered out on every kill.
        LootEntry gated = new LootEntry(
                "Weapon_Sword_Iron", 1.0,
                List.of(new LootCondition.FloorLevelRange(50, null)),
                List.of(new LootModifier.GearLevel(GearType.WEAPON, null, null))
        );
        LootTable table = new LootTable(List.of(gated), 1, 1.0);

        // Below the gate: excluded -> no eligible entries -> empty drop.
        assertTrue(table.roll(40, 0).isEmpty(), "floor-gated entry must be excluded below its gate");

        // At/above the gate: eligible, and stamped at the kill level.
        ItemStack drop = table.roll(55, 0).getFirst();
        assertEquals("Weapon_Sword_Iron", drop.getItemId());
        assertEquals(55, GearLevelService.getWeaponLevel(drop));
    }

    private static LootEntry weaponEntry(String itemId, Integer minLevel, Integer maxLevel) {
        return new LootEntry(itemId, 1.0, List.of(),
                List.of(new LootModifier.GearLevel(GearType.WEAPON, minLevel, maxLevel)));
    }
}