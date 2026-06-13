package com.duntale.progression;

import com.duntale.progression.NpcArchetypeRegistry.ResolvedArchetype;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("Should normalize a mapped role's HP and damage to its archetype anchor")
    void shouldNormalizeMappedRoleToArchetype() {
        // Heavy anchor: HP 200, damage 20; role asset base damage 40 -> corrective ratio 0.5.
        NpcScalingApplicator applicator = applicatorWith(Map.of(
                "Werewolf", new ResolvedArchetype("Heavy", 200, 20.0f, 40.0f)));

        NpcScalingProfile profile = applicator.createProfile("Werewolf", 1, CombatScaling.NpcVariant.NORMAL);

        assertEquals("Heavy", profile.archetype());
        assertEquals(200, profile.anchorBaseHp());

        // npcDamageMult(1, NORMAL) == 1.0; expected multiplier ~= 0.5 (+/-5% variance).
        float expected = CombatScaling.npcDamageMult(1, CombatScaling.NpcVariant.NORMAL) * (20.0f / 40.0f);
        assertWithinVariance(expected, profile.damageMultiplier());
    }

    @Test
    @DisplayName("Should allow a corrective damage multiplier below 1.0 when normalizing down")
    void shouldAllowDamageMultiplierBelowOneWhenNormalizingDown() {
        NpcScalingApplicator applicator = applicatorWith(Map.of(
                "Werewolf", new ResolvedArchetype("Heavy", 200, 20.0f, 40.0f)));

        NpcScalingProfile profile = applicator.createProfile("Werewolf", 1, CombatScaling.NpcVariant.NORMAL);

        assertTrue(profile.damageMultiplier() < 1.0f,
                "down-normalized multiplier should drop below the legacy 1.0 floor");
    }

    @Test
    @DisplayName("Should keep legacy scaling for unmapped roles")
    void shouldKeepLegacyScalingForUnmappedRoles() {
        NpcScalingProfile profile = applicator().createProfile("Zombie", 5, CombatScaling.NpcVariant.NORMAL);

        assertNull(profile.archetype());
        assertEquals(0, profile.anchorBaseHp());
        assertTrue(profile.damageMultiplier() >= 1.0f);
    }

    @Test
    @DisplayName("Should fall back to legacy damage but keep anchor HP when asset base damage is non-positive")
    void shouldFallBackToLegacyDamageWhenAssetBaseDamageNonPositive() {
        NpcScalingApplicator applicator = applicatorWith(Map.of(
                "Spirit_Ember", new ResolvedArchetype("Caster", 60, 12.0f, 0.0f)));

        NpcScalingProfile profile = applicator.createProfile("Spirit_Ember", 10, CombatScaling.NpcVariant.NORMAL);

        assertEquals("Caster", profile.archetype());
        assertEquals(60, profile.anchorBaseHp());
        // Damage uses the legacy path (>= 1.0), not a divide-by-zero corrective ratio.
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
        return applicatorWith(Map.of());
    }

    private static NpcScalingApplicator applicatorWith(Map<String, ResolvedArchetype> snapshot) {
        return new NpcScalingApplicator(mockCombatScalingType(), NpcArchetypeRegistry.forTest(snapshot));
    }

    private static ComponentType<EntityStore, CombatScalingComponent> mockCombatScalingType() {
        return new ComponentType<>();
    }

    /** Asserts an actual value lies within the CombatScaling +/-5% variance band of an expected value. */
    private static void assertWithinVariance(float expected, float actual) {
        float lo = expected * 0.95f - 1e-4f;
        float hi = expected * 1.05f + 1e-4f;
        assertTrue(actual >= lo && actual <= hi,
                "expected " + actual + " within [" + lo + ", " + hi + "]");
    }
}