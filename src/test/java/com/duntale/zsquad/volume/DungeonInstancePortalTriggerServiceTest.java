package com.duntale.zsquad.volume;

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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonInstancePortalTriggerService")
class DungeonInstancePortalTriggerServiceTest {

    private static final String PORTAL_VOLUME_ID = "dungeon_instance_portal";

    private final DungeonInstancePortalTriggerService service =
            new DungeonInstancePortalTriggerService(PORTAL_VOLUME_ID);

    @Test
    @DisplayName("Should match ENTER events for the authored portal volume")
    void shouldMatchEnterEventsForAuthoredPortalVolume() {
        assertTrue(service.matches(event(PORTAL_VOLUME_ID, TriggerEventType.ENTER)));
    }

    @Test
    @DisplayName("Should reject EXIT and TICK events for the authored portal volume")
    void shouldRejectNonEnterEventsForAuthoredPortalVolume() {
        assertFalse(service.matches(event(PORTAL_VOLUME_ID, TriggerEventType.EXIT)));
        assertFalse(service.matches(event(PORTAL_VOLUME_ID, TriggerEventType.TICK)));
    }

    @Test
    @DisplayName("Should reject other volume ids")
    void shouldRejectOtherVolumeIds() {
        assertFalse(service.matches(event("other_portal", TriggerEventType.ENTER)));
    }

    @Test
    @DisplayName("Should match volume ids exactly and case-sensitively")
    void shouldMatchVolumeIdsExactlyAndCaseSensitively() {
        assertFalse(service.matches(event("Dungeon_Instance_Portal", TriggerEventType.ENTER)));
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
}