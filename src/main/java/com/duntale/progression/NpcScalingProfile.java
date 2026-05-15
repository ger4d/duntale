package com.duntale.progression;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Immutable scaling data shared between holder-stage and ref-stage NPC setup.
 */
public record NpcScalingProfile(
        @Nonnull String roleName,
        int level,
        @Nonnull CombatScaling.NpcVariant variant,
        @Nonnull String displayName,
        float damageMultiplier
) {

    public NpcScalingProfile {
        Objects.requireNonNull(roleName, "roleName");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(displayName, "displayName");
        damageMultiplier = Math.max(damageMultiplier, 1.0f);
    }
}