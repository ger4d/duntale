package com.duntale.zsquad.rpg;

import static com.duntale.zsquad.rpg.RpgConstants.*;

/**
 * Static utility for computing effective gameplay values from RPG stat levels.
 *
 * <p>All formulas use a hyperbolic curve {@code maxBonus * (level / (level + halfPoint))}
 * to provide diminishing returns. Each stat maps to one or more gameplay values.
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
     * <p>Formula: {@code SPEED_BASE + SPEED_MAX_BONUS * (level / (level + SPEED_HALF_POINT))}.
     * At level 0 returns {@link RpgConstants#SPEED_BASE}.
     *
     * @param speedLevel the Speed stat level
     * @return the effective move speed
     */
    public static float computeMoveSpeed(int speedLevel) {
        return SPEED_BASE + hyperbolic(speedLevel, SPEED_MAX_BONUS, SPEED_HALF_POINT);
    }

    /**
     * Computes the outgoing damage multiplier for the given Strength stat level.
     *
     * <p>Formula: {@code 1.0 + STRENGTH_MAX_BONUS * (level / (level + STRENGTH_HALF_POINT))}.
     * At level 0 returns {@code 1.0f} (no bonus).
     *
     * @param strengthLevel the Strength stat level
     * @return the damage multiplier (>= 1.0)
     */
    public static float computeStrengthMultiplier(int strengthLevel) {
        return 1.0f + hyperbolic(strengthLevel, STRENGTH_MAX_BONUS, STRENGTH_HALF_POINT);
    }

    /**
     * Computes the loot drop bonus chance for the given Luck stat level.
     *
     * <p>Formula: {@code LUCK_MAX_DROP_BONUS * (level / (level + LUCK_HALF_POINT))}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param luckLevel the Luck stat level
     * @return the drop bonus as a fraction (0.0 to LUCK_MAX_DROP_BONUS)
     */
    public static float computeLuckDropBonus(int luckLevel) {
        return hyperbolic(luckLevel, LUCK_MAX_DROP_BONUS, LUCK_HALF_POINT);
    }

    /**
     * Computes the number of bonus loot rolls for the given Luck stat level.
     *
     * <p>Formula: {@code Math.floorDiv(luckLevel, LUCK_LEVELS_PER_BONUS_ROLL)}.
     * At level 0 returns {@code 0}.
     *
     * @param luckLevel the Luck stat level
     * @return the number of bonus rolls (>= 0)
     */
    public static int computeLuckBonusRolls(int luckLevel) {
        return Math.floorDiv(luckLevel, LUCK_LEVELS_PER_BONUS_ROLL);
    }

    /**
     * Computes the bonus stamina for the given Stamina stat level.
     *
     * <p>Formula: {@code staminaLevel * STAMINA_PER_POINT}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param staminaLevel the Stamina stat level
     * @return the bonus stamina value
     */
    public static float computeStaminaBonus(int staminaLevel) {
        return staminaLevel * STAMINA_PER_POINT;
    }

    /**
     * Computes the attack throttle interval in nanoseconds for the given Agility stat level.
     *
     * <p>Formula: {@code max(AGILITY_MIN_THROTTLE_NS,
     * AGILITY_BASE_THROTTLE_NS * (1 - AGILITY_MAX_REDUCTION * (level / (level + AGILITY_HALF_POINT))))}.
     * At level 0 returns {@link RpgConstants#AGILITY_BASE_THROTTLE_NS}.
     *
     * @param agilityLevel the Agility stat level
     * @return the attack throttle in nanoseconds
     */
    public static long computeAttackThrottleNs(int agilityLevel) {
        float reduction = hyperbolic(agilityLevel, AGILITY_MAX_REDUCTION, AGILITY_HALF_POINT);
        return Math.max(AGILITY_MIN_THROTTLE_NS,
                (long) (AGILITY_BASE_THROTTLE_NS * (1.0f - reduction)));
    }

    /**
     * Computes the damage reduction fraction for the given Resistance stat level.
     *
     * <p>Formula: {@code RESISTANCE_MAX_DR * (level / (level + RESISTANCE_HALF_POINT))}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param resistanceLevel the Resistance stat level
     * @return the damage reduction as a fraction (0.0 to RESISTANCE_MAX_DR)
     */
    public static float computeResistanceDR(int resistanceLevel) {
        return hyperbolic(resistanceLevel, RESISTANCE_MAX_DR, RESISTANCE_HALF_POINT);
    }

    /**
     * Computes the bonus health for the given Vitality stat level.
     *
     * <p>Formula: {@code vitalityLevel * VITALITY_HP_PER_POINT}.
     * At level 0 returns {@code 0.0f}.
     *
     * @param vitalityLevel the Vitality stat level
     * @return the bonus health points
     */
    public static float computeVitalityBonus(int vitalityLevel) {
        return vitalityLevel * VITALITY_HP_PER_POINT;
    }
}
