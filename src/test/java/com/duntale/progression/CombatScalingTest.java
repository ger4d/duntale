package com.duntale.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CombatScaling")
class CombatScalingTest {

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
}