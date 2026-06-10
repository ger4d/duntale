package com.duntale.portal;

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

@DisplayName("VillageWarpPortalService")
class VillageWarpPortalServiceTest {

    private final VillageWarpPortalService service = new VillageWarpPortalService();

    @Test
    @DisplayName("Should match enter events with village warp portal prefix")
    void shouldMatchEnterEventsWithVillageWarpPortalPrefix() {
        String volumeId = "village_warp_portal_dungeon-123_" + UUID.randomUUID().toString();
        assertTrue(service.matches(event(volumeId, TriggerEventType.ENTER)));
        assertFalse(service.matches(event(volumeId, TriggerEventType.EXIT)));
        assertFalse(service.matches(event(volumeId, TriggerEventType.TICK)));
    }

    @Test
    @DisplayName("Should reject events with other volume id prefixes")
    void shouldRejectEventsWithOtherVolumeIdPrefixes() {
        assertFalse(service.matches(event("dungeon_end_portal_123", TriggerEventType.ENTER)));
        assertFalse(service.matches(event("dungeon_instance_portal", TriggerEventType.ENTER)));
    }

    private static TriggerVolumeEvent event(@Nonnull String volumeId, @Nonnull TriggerEventType eventType) {
        VolumeEntry volumeEntry = new VolumeEntry(
                volumeId,
                "dungeon-123",
                new Vector3d(0.0D, 0.0D, 0.0D),
                new BoxShape(new Vector3d(0.0D, 0.0D, 0.0D), new Vector3d(1.0D, 1.0D, 1.0D)),
                List.of(),
                Set.of(EntityTargetType.PLAYER),
                true
        );
        return new TriggerVolumeEvent("dungeon-123", eventType, volumeEntry, null, UUID.randomUUID());
    }
}
