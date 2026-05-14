package com.duntale.rpg;

/**
 * All tunable constants and stat bounds for the RPG system.
 */
public final class RpgConstants {
    private RpgConstants() {}

    // Stat bounds
    public static final int MIN_STAT = 0;
    public static final int MAX_STAT = 100;

    // Speed (CTM)
    public static final float SPEED_BASE = 8.0f;
    public static final float SPEED_MAX_BONUS = 4.0f;
    public static final float SPEED_HALF_POINT = 25.0f;

    // Strength
    public static final float STRENGTH_MAX_BONUS = 1.0f;
    public static final float STRENGTH_HALF_POINT = 25.0f;

    // Luck
    public static final float LUCK_MAX_DROP_BONUS = 0.30f;
    public static final float LUCK_HALF_POINT = 20.0f;
    public static final int LUCK_LEVELS_PER_BONUS_ROLL = 15;

    // Stamina
    public static final float STAMINA_PER_POINT = 5.0f;

    // Agility (CTM)
    public static final long AGILITY_BASE_THROTTLE_NS = 400_000_000L;
    public static final float AGILITY_MAX_REDUCTION = 0.65f;
    public static final float AGILITY_HALF_POINT = 25.0f;
    public static final long AGILITY_MIN_THROTTLE_NS = 140_000_000L;

    // Resistance
    public static final float RESISTANCE_MAX_DR = 0.40f;
    public static final float RESISTANCE_HALF_POINT = 30.0f;

    // Vitality
    public static final float VITALITY_HP_PER_POINT = 10.0f;

    // Gold
    public static final long MAX_GOLD_BALANCE = 999_999_999L;
}
