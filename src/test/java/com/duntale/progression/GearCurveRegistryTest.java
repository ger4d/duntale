package com.duntale.progression;

import com.duntale.config.asset.GearCurveConfigAsset.ArmorSlotEntry;
import com.duntale.config.asset.GearCurveConfigAsset.RarityNudgeEntry;
import com.duntale.config.asset.GearCurveConfigAsset.WeaponFamilyEntry;
import com.duntale.progression.GearCurveRegistry.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GearCurveRegistry")
class GearCurveRegistryTest {

    private static final WeaponFamilyEntry[] FAMILIES = {
            new WeaponFamilyEntry("Sword", 12.0f),
            new WeaponFamilyEntry("Bow", 9.0f),
    };
    private static final RarityNudgeEntry[] RARITIES = {
            new RarityNudgeEntry("Common", 1.0f),
            new RarityNudgeEntry("Legendary", 1.075f),
    };
    private static final ArmorSlotEntry[] SLOTS = {
            new ArmorSlotEntry("Chest", 0.40f),
            new ArmorSlotEntry("Hands", 0.15f),
    };

    private static Snapshot snapshot() {
        return GearCurveRegistry.build(FAMILIES, 12.0f, RARITIES, SLOTS, 0.10f, 0.55f);
    }

    @Test
    @DisplayName("Should map families, rarities, and slots from config entries")
    void shouldMapEntries() {
        GearCurveRegistry registry = GearCurveRegistry.forTest(snapshot());

        assertTrue(registry.isLoaded());
        assertEquals(12.0f, registry.weaponAnchor("Sword"), 1e-4f);
        assertEquals(9.0f, registry.weaponAnchor("Bow"), 1e-4f);
        assertEquals(1.075f, registry.rarityNudge("Legendary"), 1e-4f);
        assertEquals(0.40f, registry.slotShare("Chest"), 1e-4f);
        assertEquals(0.10f, registry.drBudgetMin(), 1e-4f);
        assertEquals(0.55f, registry.drBudgetMax(), 1e-4f);
    }

    @Test
    @DisplayName("Should fall back to the default anchor for unmapped or null families")
    void shouldDefaultUnmappedFamily() {
        GearCurveRegistry registry = GearCurveRegistry.forTest(snapshot());

        assertEquals(12.0f, registry.weaponAnchor("Halberd"), 1e-4f);
        assertEquals(12.0f, registry.weaponAnchor(null), 1e-4f);
    }

    @Test
    @DisplayName("Should default rarity nudge to 1.0 when absent or unmapped")
    void shouldDefaultRarityNudge() {
        GearCurveRegistry registry = GearCurveRegistry.forTest(snapshot());

        assertEquals(1.0f, registry.rarityNudge(null), 1e-4f);
        assertEquals(1.0f, registry.rarityNudge("Mythical"), 1e-4f);
    }

    @Test
    @DisplayName("Should return null slot share for an unmapped slot (legacy fallback)")
    void shouldReturnNullForUnmappedSlot() {
        GearCurveRegistry registry = GearCurveRegistry.forTest(snapshot());

        assertNull(registry.slotShare("Cloak"));
    }

    @Test
    @DisplayName("Should report not-loaded for the empty snapshot and serve safe defaults")
    void shouldDegradeForEmptySnapshot() {
        GearCurveRegistry registry = GearCurveRegistry.forTest(Snapshot.EMPTY);

        assertFalse(registry.isLoaded());
        assertEquals(0f, registry.weaponAnchor("Sword"), 1e-4f);
        assertEquals(1.0f, registry.rarityNudge("Legendary"), 1e-4f);
        assertNull(registry.slotShare("Chest"));
    }

    @Test
    @DisplayName("Should treat an asset present but empty as not loaded")
    void shouldTreatEmptyEntriesAsNotLoaded() {
        Snapshot empty = GearCurveRegistry.build(
                new WeaponFamilyEntry[0], 0f, new RarityNudgeEntry[0],
                new ArmorSlotEntry[0], 0f, 0f);

        assertFalse(empty.loaded());
    }
}
