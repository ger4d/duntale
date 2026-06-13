package com.duntale.progression;

import java.util.concurrent.ThreadLocalRandom;

/**
 * All combat scaling formulas in one place.
 *
 * <p>Every method is a pure function of its arguments — no state, no database, no I/O.
 * Tuning constants live here so they can be adjusted in a single location.
 */
public final class CombatScaling {

    /** Minimum supported dungeon, NPC, and gear level. */
    public static final int MIN_LEVEL = 1;

    /** Maximum supported dungeon, NPC, and gear level. */
    public static final int MAX_LEVEL = 100;

    private static final float SIGMOID_AT_MIN_LEVEL = rawSigmoid(MIN_LEVEL);
    private static final float SIGMOID_AT_MAX_LEVEL = rawSigmoid(MAX_LEVEL);

    private CombatScaling() {}

    // ── Sigmoid curve ────────────────────────────────────────────────
    private static final float MIDPOINT = MAX_LEVEL / 2.0f;
    // Preserve the old 60-floor curve shape while extending the playable ceiling.
    private static final float STEEPNESS = 7.2f / MAX_LEVEL;

    // ── NPC scaling ──────────────────────────────────────────────────
    private static final float NPC_HP_K = 8.0f;
    private static final float NPC_DMG_K = 5.0f;

    // ── Companion scaling ────────────────────────────────────────────
    private static final float COMPANION_HP_K = 8.0f;
    private static final float COMPANION_DMG_K = 5.0f;

    // ── Gear scaling ─────────────────────────────────────────────────
    private static final float WEAPON_K = 6.0f;
    private static final float ARMOR_K = 4.0f;

    // ── Deployable scaling ───────────────────────────────────────────
    private static final float TURRET_DMG_K = 6.0f;

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
        int clampedLevel = clampLevel(level);
        float raw = rawSigmoid(clampedLevel);
        float denominator = SIGMOID_AT_MAX_LEVEL - SIGMOID_AT_MIN_LEVEL;
        if (denominator <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min((raw - SIGMOID_AT_MIN_LEVEL) / denominator, 1f));
    }

    /**
     * Returns whether a level is within the supported dungeon, NPC, and gear bounds.
     *
     * @param level the level to check
     * @return {@code true} when the level is supported
     */
    public static boolean isSupportedLevel(int level) {
        return level >= MIN_LEVEL && level <= MAX_LEVEL;
    }

    /**
     * Clamps a level to the supported dungeon, NPC, and gear bounds.
     *
     * @param level the requested level
     * @return the clamped level
     */
    public static int clampLevel(int level) {
        return Math.clamp(level, MIN_LEVEL, MAX_LEVEL);
    }

    // ── NPC scaling ──────────────────────────────────────────────────

    /**
     * Computes the scaled HP for an NPC at the given level and variant.
     *
     * @param baseHp  the NPC's base HP from its role definition
    * @param level   the dungeon level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
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
    * @param level   the dungeon level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
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
        if (level >= threshold(0.75f)) return 6.5f;
        if (level >= threshold(0.50f)) return 5.5f;
        if (level >= threshold(1.0f / 3.0f)) return 4.5f;
        if (level >= threshold(1.0f / 6.0f)) return 3.5f;
        return 2.5f;
    }

    private static float eliteDamageMultiplier(int level) {
        if (level >= threshold(0.75f)) return 2.25f;
        if (level >= threshold(0.50f)) return 2.f;
        if (level >= threshold(1.0f / 3.0f)) return 1.75f;
        if (level >= threshold(1.0f / 6.0f)) return 1.5f;
        return 1.25f;
    }

    // Boss multipliers
    private static float bossHpMultiplier(int level) {
        // Old thresholds for reference:
        // if (level >= threshold(0.75f)) return 4.75f;
        // if (level >= threshold(0.50f)) return 4.0f;
        // if (level >= threshold(1.0f / 3.0f)) return 3.25f;
        // if (level >= threshold(1.0f / 6.0f)) return 2.5f;
        if (level >= threshold(0.75f)) return 25f;
        if (level >= threshold(0.50f)) return 22.5f;
        if (level >= threshold(1.0f / 3.0f)) return 17.5f;
        if (level >= threshold(1.0f / 6.0f)) return 15f;
        return 10.f;
    }

    private static float bossDamageMultiplier(int level) {
        if (level >= threshold(0.75f)) return 5.2f;
        if (level >= threshold(0.50f)) return 4.5f;
        if (level >= threshold(1.0f / 3.0f)) return 3.2f;
        if (level >= threshold(1.0f / 6.0f)) return 2.5f;
        return 2.0f;
    }

    private static int threshold(float ratio) {
        return Math.max(MIN_LEVEL, Math.round(MAX_LEVEL * ratio));
    }

    // ── Companion scaling ────────────────────────────────────────────

    /**
     * Computes the scaled HP for a companion at the given level.
     *
     * @param baseHp the companion's base HP from its role definition
    * @param level  the player's level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @return the scaled HP value
     */
    public static int companionScaledHp(int baseHp, int level) {
        float scaled = baseHp + COMPANION_HP_K * baseHp * sigmoid(level);
        return Math.round(scaled);
    }

    /**
     * Computes the damage multiplier for a companion at the given level.
     *
    * @param level the player's level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
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
    * @param level the weapon's gear level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @return the damage multiplier (always >= 1.0)
     */
    public static float weaponMult(int level) {
        return 1.0f + WEAPON_K * sigmoid(level);
    }

    /**
     * Computes the legacy armor damage reduction for a single piece at the given level, derived from
     * the item's authored asset resist. Retained for the fallback path when no authored curves are
     * loaded (or a piece's slot is unmapped).
     *
     * @param baseResist sum of {@code DamageResistance.Physical[].Amount} from the item asset
     *                   (NOT {@code BaseDamageResistance}, which is 0 for most armor)
    * @param level      the armor's gear level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @return the effective DR for this piece, capped at {@link #MAX_ARMOR_DR}
     */
    public static float armorDR(float baseResist, int level) {
        float resistMult = Math.max(1.0f + (ARMOR_K - 1.0f) * sigmoid(level), 1.0f);
        float dr = baseResist * resistMult;
        return Math.min(dr, MAX_ARMOR_DR);
    }

    /**
     * Computes a single armor piece's authored damage reduction: its slot's share of the total
     * on-level DR budget, where the budget rises with level along the shared sigmoid from
     * {@code minDr} (level 1) to {@code maxDr} (the level ceiling).
     *
     * <p>The piece's own asset resist is not consulted — power comes from slot and level alone. No
     * per-piece cap is applied here; the caller sums all worn pieces and clamps the total to
     * {@link #MAX_ARMOR_DR}.
     *
     * @param slotShare the slot's share of the total DR budget (e.g. {@code 0.40} for chest)
    * @param level     the armor's gear level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @param minDr     the total on-level DR at level 1
     * @param maxDr     the total on-level DR at the level ceiling
     * @return the uncapped DR contribution for this piece
     */
    public static float armorBudgetDR(float slotShare, int level, float minDr, float maxDr) {
        float budget = minDr + (maxDr - minDr) * sigmoid(level);
        return slotShare * budget;
    }

    /**
     * Computes a weapon's authored per-hit damage: its family anchor scaled by the level curve and
     * nudged by rarity. Used by the display/feedback sites; the damage interception applies the
     * equivalent value as a corrective ratio over the live asset per-hit.
     *
     * @param anchor      the family's level-1 per-hit anchor
    * @param level       the weapon's gear level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @param rarityNudge the rarity power multiplier ({@code 1.0} for unstamped/common)
     * @return the authored per-hit damage before variance
     */
    public static float weaponAuthoredPerHit(float anchor, int level, float rarityNudge) {
        return anchor * weaponMult(level) * rarityNudge;
    }

    // ── Deployable (turret) scaling ──────────────────────────────────

    /**
     * Computes the per-arrow damage multiplier for a player-owned turret at the given level.
     *
     * <p>Mirrors {@link #weaponMult(int)} so a deployed turret scales with its owner's
     * progression level the same way a held weapon scales with its gear level.
     *
    * @param level the owner's progression level ({@value #MIN_LEVEL}-{@value #MAX_LEVEL})
     * @return the damage multiplier (always &gt;= 1.0)
     */
    public static float turretDamageMult(int level) {
        return 1.0f + TURRET_DMG_K * sigmoid(level);
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
