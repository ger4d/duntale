package com.duntale.audio;

import com.duntale.dungeon.DungeonInstance;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BackgroundMusicService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final String DUNGEON_CONTAINER_ID = "MC_Duntale_Dungeon_Rotation";
    static final int CLEAR_CONTAINER_ID = 0;

    /**
     * Applies the correct forced-music state for the player's current world context.
     *
     * @param entityRef player entity reference in the current world store
     * @param store current world entity store
     * @param dungeonInstance resolved dungeon instance for the current world, or {@code null} outside dungeons
     */
    public void applyForWorld(@Nonnull Ref<EntityStore> entityRef,
                              @Nonnull Store<EntityStore> store,
                              @Nullable DungeonInstance dungeonInstance) {
        ForcedMusicTracker tracker = getOrCreateTracker(entityRef, store);
        int desiredContainerIndex = resolveDesiredContainerIndex(dungeonInstance != null);
        int currentContainerIndex = tracker.getCurrentContainerIndex();
        if (currentContainerIndex == desiredContainerIndex) {
            if (desiredContainerIndex > CLEAR_CONTAINER_ID
                    && tracker.getLastSentContainerIndex() != CLEAR_CONTAINER_ID) {
                int previousLastSentContainerIndex = tracker.getLastSentContainerIndex();
                tracker.setLastSentContainerIndex(CLEAR_CONTAINER_ID);
                LOGGER.atInfo().log(
                        "Re-queued dungeon music sync for instance %s in world %s "
                                + "(container=%d, previousLastSent=%d)",
                        dungeonInstance != null ? dungeonInstance.instanceId() : "<none>",
                        dungeonInstance != null ? dungeonInstance.worldName() : "<none>",
                        desiredContainerIndex,
                        previousLastSentContainerIndex
                );
            }
            return;
        }

        if (dungeonInstance != null
                || currentContainerIndex > CLEAR_CONTAINER_ID
                || desiredContainerIndex > CLEAR_CONTAINER_ID) {
            LOGGER.atInfo().log(
                    "Updating forced music for %s in world %s (%d -> %d, lastSent=%d)",
                    dungeonInstance != null ? dungeonInstance.instanceId() : "shared-world",
                    dungeonInstance != null ? dungeonInstance.worldName() : "<none>",
                    currentContainerIndex,
                    desiredContainerIndex,
                    tracker.getLastSentContainerIndex()
            );
        }
        tracker.setCurrentContainerIndex(desiredContainerIndex);
    }

    @Nonnull
    ForcedMusicTracker getOrCreateTracker(@Nonnull Ref<EntityStore> entityRef, @Nonnull Store<EntityStore> store) {
        store.ensureComponent(entityRef, ForcedMusicTracker.getComponentType());
        ForcedMusicTracker tracker = store.getComponent(entityRef, ForcedMusicTracker.getComponentType());
        if (tracker == null) {
            throw new IllegalStateException("ForcedMusicTracker was not available after ensureComponent");
        }
        return tracker;
    }

    int resolveDesiredContainerIndex(boolean forceDungeonPlaylist) {
        if (!forceDungeonPlaylist) {
            return CLEAR_CONTAINER_ID;
        }

        int containerIndex = getDungeonContainerIndex();
        if (containerIndex > CLEAR_CONTAINER_ID) {
            return containerIndex;
        }

        LOGGER.atWarning().log("Dungeon music container asset not found: %s", DUNGEON_CONTAINER_ID);
        return CLEAR_CONTAINER_ID;
    }

    int getDungeonContainerIndex() {
        return MusicContainer.getAssetMap().getIndex(DUNGEON_CONTAINER_ID);
    }
}