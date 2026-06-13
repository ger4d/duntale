package com.duntale.progression;

import com.duntale.config.asset.NpcArchetypeConfigAsset.ArchetypeEntry;
import com.duntale.config.asset.NpcArchetypeConfigAsset.RoleEntry;
import com.duntale.progression.NpcArchetypeRegistry.ResolvedArchetype;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NpcArchetypeRegistry")
class NpcArchetypeRegistryTest {

    private static final ArchetypeEntry[] ANCHORS = {
            new ArchetypeEntry("Swarm", 14, 4.0f),
            new ArchetypeEntry("Heavy", 200, 20.0f),
    };

    @Test
    @DisplayName("Should pre-apply flavor offsets into the resolved anchor values")
    void shouldPreApplyOffsets() {
        Map<String, ResolvedArchetype> snapshot = NpcArchetypeRegistry.build(ANCHORS, new RoleEntry[]{
                new RoleEntry("Werewolf", "Heavy", 0.10f, -0.05f, 66.0f),
        });

        ResolvedArchetype resolved = snapshot.get("Werewolf");
        assertEquals("Heavy", resolved.name());
        assertEquals(Math.round(200 * 1.10f), resolved.effectiveBaseHp());        // 220
        assertEquals(20.0f * 0.95f, resolved.effectiveBaseDamage(), 1e-4f);       // 19.0
        assertEquals(66.0f, resolved.assetBaseDamage(), 1e-4f);
    }

    @Test
    @DisplayName("Should clamp out-of-range offsets to +/-15%")
    void shouldClampOutOfRangeOffsets() {
        Map<String, ResolvedArchetype> snapshot = NpcArchetypeRegistry.build(ANCHORS, new RoleEntry[]{
                new RoleEntry("Scarak_Louse", "Swarm", -0.80f, 0.50f, 5.0f),
        });

        ResolvedArchetype resolved = snapshot.get("Scarak_Louse");
        // HP offset clamped to -0.15: round(14 * 0.85) = 12; damage offset clamped to +0.15: 4 * 1.15 = 4.6.
        assertEquals(Math.round(14 * 0.85f), resolved.effectiveBaseHp());
        assertEquals(4.0f * 1.15f, resolved.effectiveBaseDamage(), 1e-4f);
    }

    @Test
    @DisplayName("Should treat a role with an unknown archetype as unmapped")
    void shouldTreatUnknownArchetypeAsUnmapped() {
        Map<String, ResolvedArchetype> snapshot = NpcArchetypeRegistry.build(ANCHORS, new RoleEntry[]{
                new RoleEntry("Mystery", "DoesNotExist", 0.0f, 0.0f, 5.0f),
                new RoleEntry("Werewolf", "Heavy", 0.0f, 0.0f, 66.0f),
        });

        assertNull(snapshot.get("Mystery"));
        assertTrue(snapshot.containsKey("Werewolf"));
    }

    @Test
    @DisplayName("Should resolve mapped roles and return null for unmapped lookups")
    void shouldResolveViaInstance() {
        NpcArchetypeRegistry registry = NpcArchetypeRegistry.forTest(
                NpcArchetypeRegistry.build(ANCHORS, new RoleEntry[]{
                        new RoleEntry("Werewolf", "Heavy", 0.0f, 0.0f, 66.0f),
                }));

        assertEquals("Heavy", registry.resolve("Werewolf").name());
        assertNull(registry.resolve("Zombie"));
    }
}
