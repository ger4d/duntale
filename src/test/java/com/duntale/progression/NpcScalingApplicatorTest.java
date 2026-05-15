package com.duntale.progression;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NpcScalingApplicator")
class NpcScalingApplicatorTest {

    @Test
    @DisplayName("Should format a normal nameplate as [Lv.N] Role")
    void shouldFormatNormalNameplate() {
        NpcScalingProfile profile = applicator().createProfile("Skeleton", 7, CombatScaling.NpcVariant.NORMAL);

        assertEquals("[Lv.7] Skeleton", profile.displayName());
    }

    @Test
    @DisplayName("Should format an elite nameplate as [Lv.N *] Role")
    void shouldFormatEliteNameplate() {
        NpcScalingProfile profile = applicator().createProfile("Skeleton", 14, CombatScaling.NpcVariant.ELITE);

        assertEquals("[Lv.14 *] Skeleton", profile.displayName());
    }

    @Test
    @DisplayName("Should format a boss nameplate as [Lv.N BOSS] Role")
    void shouldFormatBossNameplate() {
        NpcScalingProfile profile = applicator().createProfile("Scarak_Louse", 21, CombatScaling.NpcVariant.BOSS);

        assertEquals("[Lv.21 BOSS] Scarak_Louse", profile.displayName());
    }

    @Test
    @DisplayName("Should clamp created damage multipliers to at least 1.0")
    void shouldClampCreatedDamageMultiplier() {
        NpcScalingProfile profile = applicator().createProfile("Skeleton", 1, CombatScaling.NpcVariant.NORMAL);

        assertTrue(profile.damageMultiplier() >= 1.0f);
    }

    @Test
    @DisplayName("Should allow only built-in special spawn roles")
    void shouldAllowOnlyBuiltInSpecialSpawnRoles() {
        assertTrue(BuiltInNpcSpawnScalingSystem.isAllowlistedRole("Skeleton"));
        assertTrue(BuiltInNpcSpawnScalingSystem.isAllowlistedRole("Scarak_Louse"));
        assertFalse(BuiltInNpcSpawnScalingSystem.isAllowlistedRole("Zombie"));
    }

    @Test
    @DisplayName("Should gate duplicate scaling on CombatScalingComponent presence rather than visual scale state")
    void shouldGateDuplicateScalingOnCombatScalingComponentPresence() {
        assertTrue(BuiltInNpcSpawnScalingSystem.shouldScaleSpawn("Skeleton", false));
        assertFalse(BuiltInNpcSpawnScalingSystem.shouldScaleSpawn("Skeleton", true));
    }

    private static NpcScalingApplicator applicator() {
        return new NpcScalingApplicator(mockCombatScalingType());
    }

    private static ComponentType<EntityStore, CombatScalingComponent> mockCombatScalingType() {
        return new ComponentType<>();
    }
}