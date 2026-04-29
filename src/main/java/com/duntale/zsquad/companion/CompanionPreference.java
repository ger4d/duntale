package com.duntale.zsquad.companion;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Stored companion preference data used for companion spawning and entry routing.
 *
 * @param roleName the authoritative companion role name
 * @param displayName the optional player-chosen companion display name
 */
public record CompanionPreference(
        @Nonnull String roleName,
        @Nullable String displayName
) {

    /**
     * Validates companion preference data.
     */
    public CompanionPreference {
        Objects.requireNonNull(roleName, "roleName");
    }
}