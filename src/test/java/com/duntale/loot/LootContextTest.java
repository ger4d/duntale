package com.duntale.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("LootContext")
class LootContextTest {

    @Test
    @DisplayName("A kill context carries the NPC level as both the NPC level and the floor level")
    void killContextCarriesNpcLevelAsFloor() {
        LootContext context = LootContext.forNpcKill(17);

        assertEquals(17, context.npcLevel());
        assertEquals(17, context.floorLevel());
    }

    @Test
    @DisplayName("An NPC-level context carries no floor")
    void npcLevelContextHasNoFloor() {
        LootContext context = LootContext.forNpcLevel(17);

        assertEquals(17, context.npcLevel());
        assertNull(context.floorLevel());
    }

    @Test
    @DisplayName("A floor context carries no NPC level")
    void floorContextHasNoNpcLevel() {
        LootContext context = LootContext.forFloorLevel(17);

        assertNull(context.npcLevel());
        assertEquals(17, context.floorLevel());
    }
}
