package com.duntale.progression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable scaling data shared between holder-stage and ref-stage NPC setup.
 *
 * <p>{@code archetype}/{@code anchorBaseHp} carry the resolved archetype mapping: when
 * {@code anchorBaseHp > 0} the role is normalized to an archetype anchor and HP derives from
 * {@code anchorBaseHp} rather than the asset base; both are {@code null}/{@code 0} for unmapped roles.
 */
public record NpcScalingProfile(
        @Nonnull String roleName,
        int level,
        @Nonnull CombatScaling.NpcVariant variant,
        @Nonnull String displayName,
        float damageMultiplier,
        @Nullable String archetype,
        int anchorBaseHp
) {

    /**
     * Minimum damage multiplier. Archetype normalization may reduce a high-base-damage role's
     * multiplier below 1.0 to hit its archetype average; the floor only guards against zero/negative
     * values. The legacy (unmapped) path is floored at 1.0 by the applicator before construction.
     */
    private static final float MIN_DAMAGE_MULTIPLIER = 0.01f;

    public NpcScalingProfile {
        Objects.requireNonNull(roleName, "roleName");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(displayName, "displayName");
        damageMultiplier = Math.max(damageMultiplier, MIN_DAMAGE_MULTIPLIER);
    }
}