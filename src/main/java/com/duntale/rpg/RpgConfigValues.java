package com.duntale.rpg;

import com.duntale.config.asset.RpgConfigAsset;

import javax.annotation.Nonnull;

/**
 * Immutable snapshot of all runtime-tunable RPG values.
 *
 * <p>Read live via {@link RpgConfig#values()}. The {@link #DEFAULTS} instance mirrors the
 * compile-time {@link RpgConstants} and is used whenever the {@code RpgConfig} asset is absent.
 *
 * <p>Being an immutable record, a snapshot can be published to other threads simply by storing
 * the reference; see {@link RpgConfig}.
 */
public record RpgConfigValues(
        int minStat,
        int maxStat,
        float speedBase,
        float speedMaxBonus,
        float speedHalfPoint,
        float strengthMaxBonus,
        float strengthHalfPoint,
        float luckMaxDropBonus,
        float luckHalfPoint,
        int luckLevelsPerBonusRoll,
        float staminaPerPoint,
        long agilityBaseThrottleNs,
        float agilityMaxReduction,
        float agilityHalfPoint,
        long agilityMinThrottleNs,
        float resistanceMaxDr,
        float resistanceHalfPoint,
        float vitalityHpPerPoint,
        long maxGoldBalance
) {

    /** Default values, sourced from {@link RpgConstants}. Used when no asset override is present. */
    public static final RpgConfigValues DEFAULTS = new RpgConfigValues(
            RpgConstants.MIN_STAT,
            RpgConstants.MAX_STAT,
            RpgConstants.SPEED_BASE,
            RpgConstants.SPEED_MAX_BONUS,
            RpgConstants.SPEED_HALF_POINT,
            RpgConstants.STRENGTH_MAX_BONUS,
            RpgConstants.STRENGTH_HALF_POINT,
            RpgConstants.LUCK_MAX_DROP_BONUS,
            RpgConstants.LUCK_HALF_POINT,
            RpgConstants.LUCK_LEVELS_PER_BONUS_ROLL,
            RpgConstants.STAMINA_PER_POINT,
            RpgConstants.AGILITY_BASE_THROTTLE_NS,
            RpgConstants.AGILITY_MAX_REDUCTION,
            RpgConstants.AGILITY_HALF_POINT,
            RpgConstants.AGILITY_MIN_THROTTLE_NS,
            RpgConstants.RESISTANCE_MAX_DR,
            RpgConstants.RESISTANCE_HALF_POINT,
            RpgConstants.VITALITY_HP_PER_POINT,
            RpgConstants.MAX_GOLD_BALANCE
    );

    /**
     * Builds a snapshot from a config asset, defensively guarding values that would otherwise
     * break the formulas: half-points are clamped to {@code >= 0} (used as hyperbolic
     * denominators), {@code maxStat} is clamped to {@code >= minStat}, and
     * {@code luckLevelsPerBonusRoll} is clamped to {@code >= 1} (used as a divisor).
     *
     * @param asset the loaded RPG config asset
     * @return an immutable snapshot reflecting the asset's values
     */
    @Nonnull
    public static RpgConfigValues fromAsset(@Nonnull RpgConfigAsset asset) {
        int minStat = asset.getMinStat();
        return new RpgConfigValues(
                minStat,
                Math.max(minStat, asset.getMaxStat()),
                asset.getSpeedBase(),
                asset.getSpeedMaxBonus(),
                Math.max(0.0f, asset.getSpeedHalfPoint()),
                asset.getStrengthMaxBonus(),
                Math.max(0.0f, asset.getStrengthHalfPoint()),
                asset.getLuckMaxDropBonus(),
                Math.max(0.0f, asset.getLuckHalfPoint()),
                Math.max(1, asset.getLuckLevelsPerBonusRoll()),
                asset.getStaminaPerPoint(),
                asset.getAgilityBaseThrottleNs(),
                asset.getAgilityMaxReduction(),
                Math.max(0.0f, asset.getAgilityHalfPoint()),
                asset.getAgilityMinThrottleNs(),
                asset.getResistanceMaxDr(),
                Math.max(0.0f, asset.getResistanceHalfPoint()),
                asset.getVitalityHpPerPoint(),
                asset.getMaxGoldBalance()
        );
    }
}
