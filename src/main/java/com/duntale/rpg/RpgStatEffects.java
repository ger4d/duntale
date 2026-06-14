package com.duntale.rpg;

/**
 * Static utility for computing effective gameplay values from RPG stat levels.
 *
 * <p>Most formulas use a hyperbolic curve {@code maxBonus * (level / (level + halfPoint))}
 * to provide diminishing returns; the Luck drop chance instead uses an accelerating power curve
 * (see {@link #computeLuckDropChance}). Each stat maps to one or more gameplay values.
 *
 * <p>Tuning values are read live from {@link RpgConfig#values()} on every call, so changes to
 * the {@code RpgConfig} asset (hot reload) take effect immediately.
 */
public final class RpgStatEffects {

    private RpgStatEffects() {}

    /**
     * Core hyperbolic scaling helper.
     *
     * <p>Formula: {@code maxBonus * (level / (level + halfPoint))}.
     * Returns 0 when {@code level + halfPoint} equals 0.
     *
     * @param level    the stat level (non-negative)
     * @param maxBonus the maximum bonus at infinite level
     * @param halfPoint the level at which half the max bonus is reached
     * @return the scaled bonus value
     */
    public static float hyperbolic(int level, float maxBonus, float halfPoint) {
        float denominator = level + halfPoint;
        if (denominator == 0.0f) {
            return 0.0f;
        }
        return maxBonus * (level / denominator);
    }

    /**
     * Computes the effective move speed for the given Speed stat level.
     *
     * <p>Formula: {@code speedBase + speedMaxBonus * (level / (level + speedHalfPoint))}.
     * At level 0 returns {@code speedBase}.
     *
     * @param speedLevel the Speed stat level
     * @return the effective move speed
     */
    public static float computeMoveSpeed(int speedLevel) {
        RpgConfigValues v = RpgConfig.values();
        return v.speedBase() + hyperbolic(speedLevel, v.speedMaxBonus(), v.speedHalfPoint());
    }

    /**
     * Computes the outgoing damage multiplier for the given Strength stat level.
     *
     * <p>Formula: {@code 1.0 + strengthMaxBonus * (level / (level + strengthHalfPoint))}.
     * At level 0 returns {@code 1.0f} (no bonus).
     *
     * @param strengthLevel the Strength stat level
     * @return the damage multiplier (>= 1.0)
     */
    public static float computeStrengthMultiplier(int strengthLevel) {
        RpgConfigValues v = RpgConfig.values();
        return 1.0f + hyperbolic(strengthLevel, v.strengthMaxBonus(), v.strengthHalfPoint());
    }

    /**
     * Computes the effective gear drop chance for a base chance and the given Luck stat level.
     *
     * <p>Accelerating curve:
     * {@code baseChance + coefficient * (min(luck, reference) / reference)^exponent}, clamped to
     * {@code [0, maxChance]}. Luck below 0 is treated as 0, and Luck at or above the reference
     * contributes the full coefficient (so the per-kill bonus cannot grow without bound). With the
     * default tuning and a 0.10 base this yields ~0.10 at Luck 0, ~0.41 at Luck 30, and 0.80 at
     * Luck 50; the {@code maxChance} clamp only engages for tables whose base chance is already high.
     *
     * @param baseChance the table's unmodified gear drop chance
     * @param luckLevel  the Luck stat level (0 = no bonus)
     * @return the Luck-adjusted drop chance, clamped to the configured maximum
     */
    public static double computeLuckDropChance(double baseChance, int luckLevel) {
        RpgConfigValues v = RpgConfig.values();
        int reference = Math.max(1, v.luckDropReference());
        int clampedLuck = Math.clamp(luckLevel, 0, reference);
        double normalized = (double) clampedLuck / reference;
        double bonus = v.luckDropCoefficient() * Math.pow(normalized, v.luckDropExponent());
        return Math.clamp(baseChance + bonus, 0.0, v.luckDropMaxChance());
    }

    /**
     * Computes the bonus stamina for the given Stamina stat level.
     *
     * <p>Formula: {@code staminaLevel * staminaPerPoint}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param staminaLevel the Stamina stat level
     * @return the bonus stamina value
     */
    public static float computeStaminaBonus(int staminaLevel) {
        return staminaLevel * RpgConfig.values().staminaPerPoint();
    }

    /**
     * Computes the attack throttle interval in nanoseconds for the given Agility stat level.
     *
     * <p>Formula: {@code max(agilityMinThrottleNs,
     * agilityBaseThrottleNs * (1 - agilityMaxReduction * (level / (level + agilityHalfPoint))))}.
     * At level 0 returns {@code agilityBaseThrottleNs}.
     *
     * @param agilityLevel the Agility stat level
     * @return the attack throttle in nanoseconds
     */
    public static long computeAttackThrottleNs(int agilityLevel) {
        RpgConfigValues v = RpgConfig.values();
        float reduction = hyperbolic(agilityLevel, v.agilityMaxReduction(), v.agilityHalfPoint());
        return Math.max(v.agilityMinThrottleNs(),
                (long) (v.agilityBaseThrottleNs() * (1.0f - reduction)));
    }

    /**
     * Computes the damage reduction fraction for the given Resistance stat level.
     *
     * <p>Formula: {@code resistanceMaxDr * (level / (level + resistanceHalfPoint))}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param resistanceLevel the Resistance stat level
     * @return the damage reduction as a fraction (0.0 to resistanceMaxDr)
     */
    public static float computeResistanceDR(int resistanceLevel) {
        RpgConfigValues v = RpgConfig.values();
        return hyperbolic(resistanceLevel, v.resistanceMaxDr(), v.resistanceHalfPoint());
    }

    /**
     * Computes the bonus health for the given Vitality stat level.
     *
     * <p>Formula: {@code vitalityLevel * vitalityHpPerPoint}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param vitalityLevel the Vitality stat level
     * @return the bonus health points
     */
    public static float computeVitalityBonus(int vitalityLevel) {
        return vitalityLevel * RpgConfig.values().vitalityHpPerPoint();
    }
}
