package com.duntale.zsquad;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the authoritative join-world policy for the entry menu flow.
 *
 * <p>When a shared/menu world exists, all joiners should enter through that world so the
 * entry menu becomes the authoritative UX instead of last-world auto-resume.
 */
final class DungeonJoinRouting {

    private DungeonJoinRouting() {
    }

    @Nonnull
    static JoinRoutingDecision resolve(@Nullable String currentWorldName, @Nullable String sharedWorldName) {
        if (sharedWorldName != null && !sharedWorldName.isBlank()) {
            return new JoinRoutingDecision(sharedWorldName, true);
        }
        return new JoinRoutingDecision(currentWorldName, false);
    }

    record JoinRoutingDecision(@Nullable String targetWorldName, boolean openEntryMenu) {
    }
}
