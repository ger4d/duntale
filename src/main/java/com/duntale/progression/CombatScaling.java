package com.duntale.progression;

import java.util.concurrent.ThreadLocalRandom;

/**
 * All combat scaling formulas in one place.
 *
 * <p>Every method is a pure function of its arguments — no state, no database, no I/O.
 * Tuning constants live here so they can be adjusted in a single location.
 */
public final class CombatScaling {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 60;
    private static final float SIGMOID_AT_MIN_LEVEL = rawSigmoid(MIN_LEVEL);
    private static final float SIGMOID_AT_MAX_LEVEL = rawSigmoid(MAX_LEVEL);

    private CombatScaling() {}

    // ── Sigmoid curve ────────────────────────────────────────────────
    private static final float MIDPOINT = 30.0f;
    private static final float STEEPNESS = 0.12f;

    // ── NPC scaling ──────────────────────────────────────────────────
    private static final float NPC_HP_K = 8.0f;
    private static final float NPC_DMG_K = 5.0f;

    // ── Companion scaling ────────────────────────────────────────────
    private static final float COMPANION_HP_K = 8.0f;
    private static final float COMPANION_DMG_K = 5.0f;

    // ── Gear scaling ─────────────────────────────────────────────────
    private static final float WEAPON_K = 6.0f;
    private static final float ARMOR_K = 4.0f;

    /** Maximum combined armor damage reduction (65%). */
    public static final float MAX_ARMOR_DR = 0.65f;

    // ── Variance ─────────────────────────────────────────────────────
    private static final float STAT_VARIANCE = 0.05f;

    // ── Visual ───────────────────────────────────────────────────────
    /** Visual scale factor applied to elite NPC entities. */
    public static final float ELITE_VISUAL_SCALE = 1.2f;

    // ── NPC variant enum — mutually exclusive ────────────────────────

    /** Mutually exclusive NPC variant. Replaces the old {@code boolean elite} + {@code boolean boss} pair. */
    public enum NpcVariant { NORMAL, ELITE, BOSS }

    // ── Core sigmoid ─────────────────────────────────────────────────

    private static float rawSigmoid(int level) {
        return 1.0f / (1.0f + (float) Math.exp(-STEEPNESS * (level - MIDPOINT)));
    }

    private static float sigmoid(int level) {
        int clampedLevel = Math.max(MIN_LEVEL, Math.min(level, MAX_LEVEL));
        float raw = rawSigmoid(clampedLevel);
        float denominator = SIGMOID_AT_MAX_LEVEL - SIGMOID_AT_MIN_LEVEL;
        if (denominator <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min((raw - SIGMOID_AT_MIN_LEVEL) / denominator, 1f));
    }

    // ── NPC scaling ──────────────────────────────────────────────────

    /**
     * Computes the scaled HP for an NPC at the given level and variant.
     *
     * @param baseHp  the NPC's base HP from its role definition
     * @param level   the dungeon level (1-60)
     * @param variant the NPC variant (NORMAL, ELITE, or BOSS)
     * @return the scaled HP value
     */
    public static int npcScaledHp(int baseHp, int level, NpcVariant variant) {
        float scaled = baseHp + NPC_HP_K * baseHp * sigmoid(level);
        scaled *= variantHpMultiplier(level, variant);
        return Math.round(scaled);
    }

    /**
     * Computes the damage multiplier for an NPC at the given level and variant.
     *
     * @param level   the dungeon level (1-60)
     * @param variant the NPC variant
     * @return the damage multiplier (always >= 1.0)
     */
    public static float npcDamageMult(int level, NpcVariant variant) {
        float mult = 1.0f + NPC_DMG_K * sigmoid(level);
        mult *= variantDamageMultiplier(level, variant);
        return mult;
    }

    // ── Per-variant level-range multipliers ──────────────────────────

    private static float variantHpMultiplier(int level, NpcVariant variant) {
        return switch (variant) {
            case ELITE -> eliteHpMultiplier(level);
            case BOSS -> bossHpMultiplier(level);
            default -> 1.0f;
        };
    }

    private static float variantDamageMultiplier(int level, NpcVariant variant) {
        return switch (variant) {
            case ELITE -> eliteDamageMultiplier(level);
            case BOSS -> bossDamageMultiplier(level);
            default -> 1.0f;
        };
    }

    // Elite multipliers
    private static float eliteHpMultiplier(int level) {
        if (level >= 45) return 3.0f;
        if (level >= 30) return 2.5f;
        if (level >= 20) return 2.0f;
        if (level >= 10) return 1.5f;
        return 1.25f;
    }

    private static float eliteDamageMultiplier(int level) {
        if (level >= 45) return 2.0f;
        if (level >= 30) return 1.8f;
        if (level >= 20) return 1.5f;
        if (level >= 10) return 1.3f;
        return 1.1f;
    }

    // Boss multipliers
    private static float bossHpMultiplier(int level) {
        if (level >= 45) return 4.75f;
        if (level >= 30) return 4.0f;
        if (level >= 20) return 3.25f;
        if (level >= 10) return 2.5f;
        return 1.75f;
    }

    private static float bossDamageMultiplier(int level) {
        if (level >= 45) return 2.5f;
        if (level >= 30) return 2.0f;
        if (level >= 20) return 1.7f;
        if (level >= 10) return 1.4f;
        return 1.2f;
    }

    // ── Companion scaling ────────────────────────────────────────────

    /**
     * Computes the scaled HP for a companion at the given level.
     *
     * @param baseHp the companion's base HP from its role definition
     * @param level  the player's level (1-60)
     * @return the scaled HP value
     */
    public static int companionScaledHp(int baseHp, int level) {
        float scaled = baseHp + COMPANION_HP_K * baseHp * sigmoid(level);
        return Math.round(scaled);
    }

    /**
     * Computes the damage multiplier for a companion at the given level.
     *
     * @param level the player's level (1-60)
     * @return the damage multiplier (always >= 1.0)
     */
    public static float companionDamageMult(int level) {
        return 1.0f + COMPANION_DMG_K * sigmoid(level);
    }

    // ── Gear scaling ─────────────────────────────────────────────────

    /**
     * Computes the weapon damage multiplier at the given level.
     * Uniform across all weapon types.
     *
     * @param level the weapon's gear level (1-60)
     * @return the damage multiplier (always >= 1.0)
     */
    public static float weaponMult(int level) {
        return 1.0f + WEAPON_K * sigmoid(level);
    }

    /**
     * Computes the armor damage reduction for a single piece at the given level.
     *
     * @param baseResist sum of {@code DamageResistance.Physical[].Amount} from the item asset
     *                   (NOT {@code BaseDamageResistance}, which is 0 for most armor)
     * @param level      the armor's gear level (1-60)
     * @return the effective DR for this piece, capped at {@link #MAX_ARMOR_DR}
     */
    public static float armorDR(float baseResist, int level) {
        float resistMult = Math.max(1.0f + (ARMOR_K - 1.0f) * sigmoid(level), 1.0f);
        float dr = baseResist * resistMult;
        return Math.min(dr, MAX_ARMOR_DR);
    }

    // ── Variance ─────────────────────────────────────────────────────

    /**
     * Applies +/-5% random variance to a value.
     *
     * @param value the base value
     * @return the value with variance applied
     */
    public static float applyVariance(float value) {
        float factor = 1.0f + (ThreadLocalRandom.current().nextFloat() * 2 * STAT_VARIANCE - STAT_VARIANCE);
        return value * factor;
    }
}
