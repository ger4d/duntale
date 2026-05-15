package com.duntale.audio;

import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceState;
import com.duntale.dungeongen.config.Vec3i;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("BackgroundMusicService")
class BackgroundMusicServiceTest {

    @Nested
    @DisplayName("applyForWorld")
    class ApplyForWorld {

        @Test
        @DisplayName("Should force the dungeon playlist in dungeon worlds")
        void shouldForceTheDungeonPlaylistInDungeonWorlds() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(41);
            ForcedMusicTracker tracker = new ForcedMusicTracker();
            service.tracker = tracker;

            service.applyForWorld(null, null, dungeonInstance());

            assertSame(tracker, service.tracker);
            assertEquals(41, tracker.getCurrentContainerIndex());
        }

        @Test
        @DisplayName("Should clear forced music outside dungeon worlds")
        void shouldClearForcedMusicOutsideDungeonWorlds() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(41);
            ForcedMusicTracker tracker = new ForcedMusicTracker();
            tracker.setCurrentContainerIndex(19);
            service.tracker = tracker;

            service.applyForWorld(null, null, null);

            assertEquals(BackgroundMusicService.CLEAR_CONTAINER_ID, tracker.getCurrentContainerIndex());
        }

        @Test
        @DisplayName("Should create a tracker before applying music state")
        void shouldCreateATrackerBeforeApplyingMusicState() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(41);

            service.applyForWorld(null, null, dungeonInstance());

            assertEquals(1, service.trackerCreateCount);
            assertNotNull(service.tracker);
            assertEquals(41, service.tracker.getCurrentContainerIndex());
        }

        @Test
        @DisplayName("Should clear music when the playlist asset id is missing")
        void shouldClearMusicWhenThePlaylistAssetIdIsMissing() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(Integer.MIN_VALUE);
            ForcedMusicTracker tracker = new ForcedMusicTracker();
            tracker.setCurrentContainerIndex(23);
            service.tracker = tracker;

            service.applyForWorld(null, null, dungeonInstance());

            assertEquals(BackgroundMusicService.CLEAR_CONTAINER_ID, tracker.getCurrentContainerIndex());
        }

        @Test
        @DisplayName("Should skip tracker mutation when the desired music state is already applied")
        void shouldSkipTrackerMutationWhenTheDesiredMusicStateIsAlreadyApplied() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(41);
            CountingForcedMusicTracker tracker = new CountingForcedMusicTracker();
            tracker.setCurrentContainerIndex(41);
            tracker.resetMutationCount();
            service.tracker = tracker;

            service.applyForWorld(null, null, dungeonInstance());

            assertEquals(0, tracker.mutationCount);
            assertEquals(41, tracker.getCurrentContainerIndex());
        }

        @Test
        @DisplayName("Should requeue dungeon music sync after a cross-world clear")
        void shouldRequeueDungeonMusicSyncAfterACrossWorldClear() {
            TestBackgroundMusicService service = new TestBackgroundMusicService(41);
            CountingForcedMusicTracker tracker = new CountingForcedMusicTracker();
            tracker.setCurrentContainerIndex(41);
            tracker.setLastSentContainerIndex(41);
            tracker.resetMutationCount();
            service.tracker = tracker;

            service.applyForWorld(null, null, dungeonInstance());

            assertEquals(0, tracker.mutationCount);
            assertEquals(41, tracker.getCurrentContainerIndex());
            assertEquals(BackgroundMusicService.CLEAR_CONTAINER_ID, tracker.getLastSentContainerIndex());
        }
    }

    private static DungeonInstance dungeonInstance() {
        return new DungeonInstance(
                "instance-1",
                "dungeon-123",
                1,
                64.0,
                new Vec3i(0, 64, 0),
                new Vec3i(1, 64, 1),
                DungeonInstanceState.ACTIVE,
                "crypt",
                null,
                0L
        );
    }

    private static class TestBackgroundMusicService extends BackgroundMusicService {

        private final int dungeonContainerIndex;
        @Nullable
        private ForcedMusicTracker tracker;
        private int trackerCreateCount;

        private TestBackgroundMusicService(int dungeonContainerIndex) {
            this.dungeonContainerIndex = dungeonContainerIndex;
        }

        @Nonnull
        @Override
        ForcedMusicTracker getOrCreateTracker(@Nonnull Ref<EntityStore> entityRef, @Nonnull Store<EntityStore> store) {
            if (tracker == null) {
                tracker = new ForcedMusicTracker();
                trackerCreateCount++;
            }
            return tracker;
        }

        @Override
        int getDungeonContainerIndex() {
            return dungeonContainerIndex;
        }
    }

    private static final class CountingForcedMusicTracker extends ForcedMusicTracker {

        private int mutationCount;

        @Override
        public void setCurrentContainerIndex(int index) {
            mutationCount++;
            super.setCurrentContainerIndex(index);
        }

        private void resetMutationCount() {
            mutationCount = 0;
        }
    }
}