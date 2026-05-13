package com.duntale.zsquad.portal;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.dungeon.DungeonInstance;
import com.duntale.zsquad.dungeon.DungeonInstanceState;
import com.hypixel.hytale.builtin.triggervolumes.EntityTargetType;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.builtin.triggervolumes.shape.BoxShape;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonEndPortalService")
class DungeonEndPortalServiceTest {

    private static final String INSTANCE_ID = "0d73f1b6-3ab0-4df3-9226-a2cf52fd30fb";

    private final DungeonEndPortalService service = new DungeonEndPortalService();

    @Test
    @DisplayName("Should build deterministic volume ids")
    void shouldBuildDeterministicVolumeIds() {
        assertEquals(
                "dungeon_end_portal_0d73f1b6-3ab0-4df3-9226-a2cf52fd30fb_f3",
                service.volumeIdFor(INSTANCE_ID, 3)
        );
    }

    @Test
    @DisplayName("Should parse valid portal ids")
    void shouldParseValidPortalIds() {
        Optional<DungeonEndPortalService.EndPortalTarget> target =
                service.parseTarget("dungeon_end_portal_0d73f1b6-3ab0-4df3-9226-a2cf52fd30fb_f3");

        assertTrue(target.isPresent());
        assertEquals(INSTANCE_ID, target.orElseThrow().instanceId());
        assertEquals(3, target.orElseThrow().floorLevel());
    }

    @Test
    @DisplayName("Should reject malformed portal ids")
    void shouldRejectMalformedPortalIds() {
        assertTrue(service.parseTarget("dungeon_instance_portal").isEmpty());
        assertTrue(service.parseTarget("dungeon_end_portal__f3").isEmpty());
        assertTrue(service.parseTarget("dungeon_end_portal_" + INSTANCE_ID).isEmpty());
        assertTrue(service.parseTarget("dungeon_end_portal_" + INSTANCE_ID + "_f0").isEmpty());
        assertTrue(service.parseTarget("dungeon_end_portal_" + INSTANCE_ID + "_fabc").isEmpty());
    }

    @Test
    @DisplayName("Should match only enter events for dynamic portal ids")
    void shouldMatchOnlyEnterEventsForDynamicPortalIds() {
        assertTrue(service.matchesEnterEvent(event(service.volumeIdFor(INSTANCE_ID, 2), TriggerEventType.ENTER)));
        assertFalse(service.matchesEnterEvent(event(service.volumeIdFor(INSTANCE_ID, 2), TriggerEventType.EXIT)));
        assertFalse(service.matchesEnterEvent(event(service.volumeIdFor(INSTANCE_ID, 2), TriggerEventType.TICK)));
    }

    @Test
    @DisplayName("Should reject the authored village dungeon portal id")
    void shouldRejectAuthoredVillagePortalId() {
        assertFalse(service.matchesEnterEvent(event("dungeon_instance_portal", TriggerEventType.ENTER)));
    }

    @Test
    @DisplayName("Should build centered player-only box volume entries with cooldown")
    void shouldBuildCenteredPlayerOnlyBoxVolumeEntriesWithCooldown() {
        DungeonInstance instance = testInstance(4, new Vec3i(18, 42, 27));

        VolumeEntry entry = service.buildVolumeEntry("Dungeon-World", instance);

        assertEquals(service.volumeIdFor(INSTANCE_ID, 4), entry.getId());
        assertEquals("dungeon-world", entry.getWorldName());
        assertEquals(new Vector3d(18.5D, 42.0D, 27.5D), entry.getPosition());
        assertEquals(Set.of(EntityTargetType.PLAYER), entry.getTargetTypes());
        assertTrue(entry.isEnabled());
        assertEquals(DungeonEndPortalService.VOLUME_COOLDOWN_SECONDS, entry.getCooldown());

        BoxShape shape = assertInstanceOf(BoxShape.class, entry.getShape());
        assertEquals(new Vector3d(-1.25D, 0.0D, -1.25D), shape.getMin());
        assertEquals(new Vector3d(1.25D, 2.75D, 1.25D), shape.getMax());
    }

    private static TriggerVolumeEvent event(@Nonnull String volumeId, @Nonnull TriggerEventType eventType) {
        VolumeEntry volumeEntry = new VolumeEntry(
                volumeId,
                "Village",
                new Vector3d(0.0D, 0.0D, 0.0D),
                new BoxShape(new Vector3d(0.0D, 0.0D, 0.0D), new Vector3d(1.0D, 1.0D, 1.0D)),
                List.of(),
                Set.of(EntityTargetType.PLAYER),
                true
        );
        return new TriggerVolumeEvent("Village", eventType, volumeEntry, null, UUID.randomUUID());
    }

    @Nonnull
    private static DungeonInstance testInstance(int floorLevel, @Nonnull Vec3i exitPosition) {
        return new DungeonInstance(
                INSTANCE_ID,
                "dungeon-world",
                floorLevel,
                20,
                new Vec3i(0, 20, 0),
                exitPosition,
                DungeonInstanceState.ACTIVE,
                "crypt",
                "seed",
                123L
        );
    }
}