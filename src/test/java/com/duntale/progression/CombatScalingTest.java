package com.duntale.progression;

import com.duntale.progression.PricingRegistry.Snapshot;
import com.duntale.progression.PricingRegistry.VariantStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CombatScaling")
class CombatScalingTest {

    @AfterEach
    void clearVariantHook() {
        // The variant-table hook is static; clear it so a configured test never leaks into others.
        CombatScaling.setPricingRegistry(null);
    }

    @Test
    @DisplayName("Should continue increasing weapon scaling above level 60 up to level 100")
    void shouldContinueIncreasingWeaponScalingAboveLevelSixtyUpToLevelOneHundred() {
        assertTrue(CombatScaling.weaponMult(100) > CombatScaling.weaponMult(60));
    }

    @Test
    @DisplayName("Should clamp weapon scaling at level 100")
    void shouldClampWeaponScalingAtLevelOneHundred() {
        assertEquals(CombatScaling.weaponMult(100), CombatScaling.weaponMult(150));
    }

    @Test
    @DisplayName("Should continue increasing NPC scaling above level 60 up to level 100")
    void shouldContinueIncreasingNpcScalingAboveLevelSixtyUpToLevelOneHundred() {
        assertTrue(CombatScaling.npcScaledHp(100, 100, CombatScaling.NpcVariant.NORMAL)
                > CombatScaling.npcScaledHp(100, 60, CombatScaling.NpcVariant.NORMAL));
    }

    @Test
    @DisplayName("Authored DR should rise monotonically with level for a slot")
    void shouldIncreaseAuthoredDrWithLevel() {
        float low = CombatScaling.armorBudgetDR(0.40f, 15, 0.10f, 0.55f);
        float high = CombatScaling.armorBudgetDR(0.40f, 60, 0.10f, 0.55f);
        assertTrue(high > low);
    }

    @Test
    @DisplayName("Authored DR should equal share x budget at the level floor and ceiling")
    void shouldAnchorAuthoredDrAtFloorAndCeiling() {
        // sigmoid is 0 at MIN_LEVEL and 1 at MAX_LEVEL, so DR collapses to share x min / share x max.
        assertEquals(0.40f * 0.10f, CombatScaling.armorBudgetDR(0.40f, 1, 0.10f, 0.55f), 1e-4f);
        assertEquals(0.40f * 0.55f, CombatScaling.armorBudgetDR(0.40f, 100, 0.10f, 0.55f), 1e-4f);
    }

    @Test
    @DisplayName("A full on-level set should sum to the budget and stay under the combined cap")
    void shouldKeepFullSetUnderCap() {
        // Shares sum to 1.0, so a complete set lands on the budget curve (max < MAX_ARMOR_DR).
        float total = CombatScaling.armorBudgetDR(0.40f, 100, 0.10f, 0.55f)
                + CombatScaling.armorBudgetDR(0.25f, 100, 0.10f, 0.55f)
                + CombatScaling.armorBudgetDR(0.20f, 100, 0.10f, 0.55f)
                + CombatScaling.armorBudgetDR(0.15f, 100, 0.10f, 0.55f);
        assertEquals(0.55f, total, 1e-4f);
        assertTrue(total < CombatScaling.MAX_ARMOR_DR);
    }

    @Test
    @DisplayName("Authored per-hit should equal anchor x weaponMult x rarity nudge")
    void shouldComputeAuthoredPerHit() {
        float expected = 12.0f * CombatScaling.weaponMult(30) * 1.075f;
        assertEquals(expected, CombatScaling.weaponAuthoredPerHit(12.0f, 30, 1.075f), 1e-4f);
    }

    @Test
    @DisplayName("Configured variant tables should drive Elite/Boss HP and damage multipliers")
    void shouldDriveVariantMultipliersFromConfig() {
        CombatScaling.setPricingRegistry(PricingRegistry.forTest(new Snapshot(
                10.0, 1.4, 1.0, 25L, 0.6, List.of(),
                List.of(new VariantStep(0.0f, 3.0f, 1.5f)),
                List.of(new VariantStep(0.0f, 12.0f, 2.5f)),
                Map.of(), true)));

        int normalHp = CombatScaling.npcScaledHp(100, 10, CombatScaling.NpcVariant.NORMAL);
        int eliteHp = CombatScaling.npcScaledHp(100, 10, CombatScaling.NpcVariant.ELITE);
        int bossHp = CombatScaling.npcScaledHp(100, 10, CombatScaling.NpcVariant.BOSS);
        assertEquals(3.0, (double) eliteHp / normalHp, 0.02);
        assertEquals(12.0, (double) bossHp / normalHp, 0.05);

        float normalDmg = CombatScaling.npcDamageMult(10, CombatScaling.NpcVariant.NORMAL);
        assertEquals(1.5, CombatScaling.npcDamageMult(10, CombatScaling.NpcVariant.ELITE) / normalDmg, 1e-4);
        assertEquals(2.5, CombatScaling.npcDamageMult(10, CombatScaling.NpcVariant.BOSS) / normalDmg, 1e-4);
    }

    @Test
    @DisplayName("Should fall back to the hard-coded base variant multipliers when no table is configured")
    void shouldUseHardCodedVariantsWhenUnconfigured() {
        int normalHp = CombatScaling.npcScaledHp(100, 10, CombatScaling.NpcVariant.NORMAL);
        int eliteHp = CombatScaling.npcScaledHp(100, 10, CombatScaling.NpcVariant.ELITE);
        // Hard-coded Elite base band (level below the lowest threshold) is x2.5 HP.
        assertEquals(2.5, (double) eliteHp / normalHp, 0.02);
    }
}