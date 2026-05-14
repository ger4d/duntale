package com.duntale.volume;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Matches authored TriggerVolume events for the dungeon instance portal.
 */
public final class DungeonInstancePortalTriggerService {

    private final String volumeId;

    /**
     * Creates a trigger matcher for the authored portal volume id.
     *
     * @param volumeId the exact authored TriggerVolume id to match
     */
    public DungeonInstancePortalTriggerService(@Nonnull String volumeId) {
        this.volumeId = Objects.requireNonNull(volumeId, "volumeId");
    }

    /**
     * Returns whether the event is an ENTER into the configured portal volume.
     *
     * @param event the TriggerVolume event to inspect
     * @return {@code true} when the event is an exact volume-id ENTER match
     */
    public boolean matches(@Nonnull TriggerVolumeEvent event) {
        Objects.requireNonNull(event, "event");
        return event.getTriggerEventType() == TriggerEventType.ENTER
                && volumeId.equals(event.getVolumeId());
    }
}