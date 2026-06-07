package com.duntale.items;

import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the custom utility items offered by the dungeon merchant.
 *
 * <p>Holds the item asset IDs, unit buy prices, regen-effect IDs, trap-damage
 * signatures, and behaviour tuning constants shared by the merchant catalog
 * ({@code CatalogGenerator}), the resale registry ({@code MerchantPriceRegistry}),
 * the custom interactions, and the passive ECS systems in this package.
 */
public final class CustomItems {

    private CustomItems() {
    }

    // ============================================
    // Item asset IDs
    // ============================================

    /** Passive ring that cancels spike/snapjaw trap damage while carried. */
    public static final String IMMUNITY_TRAP_RING = "Immunity_Trap_Ring";

    /** Right-click speed boots, tier I (+2 move speed). */
    public static final String SPEED_BOOTS_I = "Speed_Boots_I";

    /** Right-click speed boots, tier II (+3 move speed). */
    public static final String SPEED_BOOTS_II = "Speed_Boots_II";

    /** Right-click speed boots, tier III (+4 move speed). */
    public static final String SPEED_BOOTS_III = "Speed_Boots_III";

    /** Passive healing necklace, tier I (+1% max HP / 2s while carried). */
    public static final String HEALING_NECKLACE_I = "Healing_Necklace_I";

    /** Passive healing necklace, tier II (+3% max HP / 1.75s while carried). */
    public static final String HEALING_NECKLACE_II = "Healing_Necklace_II";

    /** Right-click flask that heals HP by draining stamina. */
    public static final String VAMPIRE_JUICE = "Vampire_Juice";

    /** Right-click token that grants one RPG stat point and is consumed. */
    public static final String STAT_POINT_TOKEN = "Stat_Point_Token";

    // ============================================
    // Healing necklace regen EntityEffect IDs
    // ============================================

    /** Infinite regen effect applied while a tier-I healing necklace is carried. */
    public static final String HEALING_NECKLACE_I_EFFECT = "Healing_Necklace_I_Regen";

    /** Infinite regen effect applied while a tier-II healing necklace is carried. */
    public static final String HEALING_NECKLACE_II_EFFECT = "Healing_Necklace_II_Regen";

    // ============================================
    // Activation VFX (custom EntityEffect / ParticleSystem asset IDs)
    // ============================================

    /**
     * Cosmetic EntityEffect applied for the boost duration when Speed Boots are
     * activated. It attaches a model-following blue/white speed trail at the
     * player's feet ({@code Duntale_Speed_Trail}) and auto-detaches on expiry.
     */
    public static final String SPEED_BOOST_EFFECT = "Duntale_Speed_Boost";

    /**
     * Cosmetic EntityEffect applied on a successful Vampire Juice heal; attaches the
     * model-following heal burst ({@code Duntale_Heal_Burst}) to the player's torso so
     * the particles track the model for the effect's short duration.
     */
    public static final String VAMPIRE_HEAL_EFFECT = "Duntale_Heal_Aura";

    /** Custom short-lived sparkle burst played when a stat point is granted. */
    public static final String STAT_POINT_VFX = "Duntale_Stat_Sparkle";

    // ============================================
    // Vampire Juice tuning (percent of max stat)
    // ============================================

    /** Health restored per use, as a percentage of max health. */
    public static final float VAMPIRE_HEAL_PCT = 5.0f;

    /** Stamina drained per use, as a percentage of max stamina (also the minimum to use). */
    public static final float VAMPIRE_STAMINA_PCT = 10.0f;

    /**
     * Seconds to suppress stamina regeneration after the drain, applied by setting the
     * {@code StaminaRegenDelay} stat to this many seconds negative (it climbs back to 0 at
     * +1/s, gating stamina regen). Without it, natural stamina regen refills the drained
     * stamina almost instantly, unlike dodge/block/bow which set this delay themselves.
     */
    public static final float VAMPIRE_STAMINA_REGEN_DELAY_SECONDS = 2.5f;

    // ============================================
    // Trap immunity
    // ============================================

    /**
     * {@code EntityEffect.Locale} values that identify spike/snapjaw trap damage.
     *
     * <p>Trap blocks deal damage through an inline {@code ApplyEffect} whose
     * {@code Locale} is {@code "spikes"} (Survival_Trap_Spike_*) or
     * {@code "snapjaw"} (Survival_Trap_Snapjaw). Matching the locale on the
     * damaging {@code ActiveEntityEffect} is a deterministic trap signature.
     */
    public static final Set<String> TRAP_DAMAGE_LOCALES = Set.of("spikes", "snapjaw");

    // ============================================
    // Merchant pricing
    // ============================================

    /** Unit buy price (in gold) for each custom item; resale is a fraction of this. */
    public static final Map<String, Long> BUY_PRICES = Map.of(
            IMMUNITY_TRAP_RING, 35_000L,
            SPEED_BOOTS_I, 30_000L,
            SPEED_BOOTS_II, 45_000L,
            SPEED_BOOTS_III, 70_000L,
            HEALING_NECKLACE_I, 45_000L,
            HEALING_NECKLACE_II, 125_000L,
            VAMPIRE_JUICE, 50_000L,
            STAT_POINT_TOKEN, 7_500L);
}
