package com.duntale;

import com.duntale.companion.CompanionService;
import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceService;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves the player's entry destination from persisted companion and dungeon state.
 */
final class PlayerEntryService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final PreferenceLookup preferenceLookup;
    private final ActiveInstanceLookup activeInstanceLookup;

    PlayerEntryService(
            @Nonnull CompanionService companionService,
            @Nonnull DungeonInstanceService dungeonInstanceService
    ) {
        this(companionService::hasStoredPreference, dungeonInstanceService::getActiveInstance);
    }

    PlayerEntryService(
            @Nonnull PreferenceLookup preferenceLookup,
            @Nonnull ActiveInstanceLookup activeInstanceLookup
    ) {
        this.preferenceLookup = Objects.requireNonNull(preferenceLookup, "preferenceLookup");
        this.activeInstanceLookup = Objects.requireNonNull(activeInstanceLookup, "activeInstanceLookup");
    }

    @Nonnull
    EntryDecision resolve(
            @Nonnull UUID playerId,
            @Nullable String currentWorldName,
            @Nullable String sharedWorldName
    ) {
        Objects.requireNonNull(playerId, "playerId");

        if (sharedWorldName == null || sharedWorldName.isBlank()) {
            LOGGER.atWarning().log(
                    "No shared world configured for player %s; preserving current world entry",
                    playerId
            );
            return new EntryDecision(currentWorldName, EntryDestination.VILLAGE);
        }

        try {
            if (!preferenceLookup.hasStoredPreference(playerId)) {
                return new EntryDecision(sharedWorldName, EntryDestination.CUSTOMIZE_CHARACTER);
            }
        } catch (SQLException e) {
            return failClosed(playerId, sharedWorldName, e, "companion preference");
        }

        try {
            DungeonInstance activeInstance = activeInstanceLookup.getActiveInstance(playerId);
            if (activeInstance != null) {
                return new EntryDecision(sharedWorldName, EntryDestination.DUNGEON_ENTRY);
            }
        } catch (SQLException e) {
            return failClosed(playerId, sharedWorldName, e, "active dungeon instance");
        }

        return new EntryDecision(sharedWorldName, EntryDestination.VILLAGE);
    }

    @Nonnull
    private EntryDecision failClosed(
            @Nonnull UUID playerId,
            @Nonnull String sharedWorldName,
            @Nonnull SQLException exception,
            @Nonnull String failedLookup
    ) {
        LOGGER.atWarning()
                .withCause(exception)
                .log("Failed to resolve %s for player %s; routing to village", failedLookup, playerId);
        return new EntryDecision(sharedWorldName, EntryDestination.VILLAGE);
    }

    enum EntryDestination {
        CUSTOMIZE_CHARACTER,
        DUNGEON_ENTRY,
        VILLAGE
    }

    record EntryDecision(
            @Nullable String targetWorldName,
            @Nonnull EntryDestination destination
    ) {
        EntryDecision {
            Objects.requireNonNull(destination, "destination");
        }
    }

    @FunctionalInterface
    interface PreferenceLookup {
        boolean hasStoredPreference(@Nonnull UUID playerId) throws SQLException;
    }

    @FunctionalInterface
    interface ActiveInstanceLookup {
        @Nullable DungeonInstance getActiveInstance(@Nonnull UUID playerId) throws SQLException;
    }
}