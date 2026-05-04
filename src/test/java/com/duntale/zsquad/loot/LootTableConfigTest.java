package com.duntale.zsquad.loot;

import com.duntale.zsquad.loot.LootEntry.GearType;
import com.duntale.zsquad.loot.config.asset.LootEntryConfig;
import com.duntale.zsquad.loot.config.asset.LootTableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LootTableConfig")
class LootTableConfigTest {

    @Test
    @DisplayName("Should convert simple entries into runtime simple loot entries")
    void shouldConvertSimpleEntry() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 2, 4, 5.0, 0, 0);

        LootEntry.Simple simple = assertInstanceOf(LootEntry.Simple.class, config.toLootEntry());

        assertEquals("Gold_Coin", simple.itemId());
        assertEquals(2, simple.quantityMin());
        assertEquals(4, simple.quantityMax());
        assertEquals(5.0, simple.weight());
        assertNull(simple.minNpcLevel());
        assertNull(simple.maxNpcLevel());
    }

    @Test
    @DisplayName("Should convert leveled weapon entries into runtime leveled loot entries")
    void shouldConvertLeveledWeaponEntry() {
        LootEntryConfig config = entry("LEVELED", "Weapon_Sword_Iron", "WEAPON", 18, 22, 1, 1, 2.5, 0, 0);

        LootEntry.Leveled leveled = assertInstanceOf(LootEntry.Leveled.class, config.toLootEntry());

        assertEquals("Weapon_Sword_Iron", leveled.itemId());
        assertEquals(GearType.WEAPON, leveled.gearType());
        assertEquals(18, leveled.gearLevelMin());
        assertEquals(22, leveled.gearLevelMax());
    }

    @Test
    @DisplayName("Should convert leveled armor entries into runtime leveled loot entries")
    void shouldConvertLeveledArmorEntry() {
        LootEntryConfig config = entry("LEVELED", "Armor_Iron_Chest", "ARMOR", 18, 22, 1, 1, 1.5, 0, 0);

        LootEntry.Leveled leveled = assertInstanceOf(LootEntry.Leveled.class, config.toLootEntry());

        assertEquals(GearType.ARMOR, leveled.gearType());
        assertEquals("Armor_Iron_Chest", leveled.itemId());
    }

    @Test
    @DisplayName("Should map zero NPC-level bounds to null")
    void shouldMapZeroBoundsToNull() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 0, 0);

        LootEntry.Simple simple = assertInstanceOf(LootEntry.Simple.class, config.toLootEntry());

        assertNull(simple.minNpcLevel());
        assertNull(simple.maxNpcLevel());
    }

    @Test
    @DisplayName("Should preserve positive NPC-level bounds")
    void shouldPreservePositiveBounds() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 10, 20);

        LootEntry.Simple simple = assertInstanceOf(LootEntry.Simple.class, config.toLootEntry());

        assertEquals(10, simple.minNpcLevel());
        assertEquals(20, simple.maxNpcLevel());
    }

    @Test
    @DisplayName("Should reject invalid entry type")
    void shouldRejectInvalidEntryType() {
        LootEntryConfig config = entry("UNKNOWN", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject invalid gear type")
    void shouldRejectInvalidGearType() {
        LootEntryConfig config = entry("LEVELED", "Armor_Iron_Chest", "TRINKET", 18, 22, 1, 1, 1.5, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject quantity minimum below one")
    void shouldRejectQuantityMinBelowOne() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 0, 3, 5.0, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject quantity maximum below quantity minimum")
    void shouldRejectQuantityMaxBelowQuantityMin() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 4, 3, 5.0, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject gear level minimum below one")
    void shouldRejectGearLevelMinBelowOne() {
        LootEntryConfig config = entry("LEVELED", "Weapon_Sword_Iron", "WEAPON", 0, 22, 1, 1, 2.5, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject gear level maximum below gear level minimum")
    void shouldRejectGearLevelMaxBelowGearLevelMin() {
        LootEntryConfig config = entry("LEVELED", "Weapon_Sword_Iron", "WEAPON", 18, 17, 1, 1, 2.5, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject non-positive weight")
    void shouldRejectNonPositiveWeight() {
        LootEntryConfig config = entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 0.0, 0, 0);

        assertThrows(IllegalArgumentException.class, config::toLootEntry);
    }

    @Test
    @DisplayName("Should reject negative rolls")
    void shouldRejectNegativeRolls() {
        LootTableConfig config = table("NegativeRolls", -1, 1.0,
                entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 0, 0));

        assertThrows(IllegalArgumentException.class, config::toLootTable);
    }

    @Test
    @DisplayName("Should reject drop chance outside zero to one")
    void shouldRejectDropChanceOutsideRange() {
        LootTableConfig config = table("BadChance", 1, 1.5,
                entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 0, 0));

        assertThrows(IllegalArgumentException.class, config::toLootTable);
    }

    @Test
    @DisplayName("Should reject empty entry arrays")
    void shouldRejectEmptyEntries() {
        LootTableConfig config = table("EmptyEntries", 1, 1.0);

        assertThrows(IllegalArgumentException.class, config::toLootTable);
    }

    private static LootEntryConfig entry(String type,
                                         String itemId,
                                         String gearType,
                                         int gearLevelMin,
                                         int gearLevelMax,
                                         int quantityMin,
                                         int quantityMax,
                                         double weight,
                                         int minNpcLevel,
                                         int maxNpcLevel) {
        LootEntryConfig config = new LootEntryConfig();
        setField(config, "type", type);
        setField(config, "itemId", itemId);
        setField(config, "gearType", gearType);
        setField(config, "gearLevelMin", gearLevelMin);
        setField(config, "gearLevelMax", gearLevelMax);
        setField(config, "quantityMin", quantityMin);
        setField(config, "quantityMax", quantityMax);
        setField(config, "weight", weight);
        setField(config, "minNpcLevel", minNpcLevel);
        setField(config, "maxNpcLevel", maxNpcLevel);
        return config;
    }

    private static LootTableConfig table(String id,
                                         int rolls,
                                         double dropChance,
                                         LootEntryConfig... entries) {
        LootTableConfig config = new LootTableConfig();
        setField(config, "id", id);
        setField(config, "rolls", rolls);
        setField(config, "dropChance", dropChance);
        setField(config, "entries", entries);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set field " + fieldName, e);
        }
    }
}