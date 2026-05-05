package com.duntale.zsquad.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}