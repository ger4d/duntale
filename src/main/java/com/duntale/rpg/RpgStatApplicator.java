package com.duntale.rpg;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies the {@link RpgStat#VITALITY} and {@link RpgStat#STAMINA} stats to a player's
 * {@link EntityStatMap} as keyed, MAX-target additive {@link StaticModifier}s.
 *
 * <p>Unlike the other RPG stats (which are read live at the point of use), Vitality and
 * Stamina raise the ceiling of a persistent entity stat. They are written as namespaced
 * modifiers ({@value #VITALITY_MODIFIER_KEY} / {@value #STAMINA_MODIFIER_KEY}) so they
 * coexist with the built-in {@code Armor_*}, {@code Effect_*}, {@code *Weapon_*} and
 * {@code *Utility_*} modifiers — each source manages only its own keys, while the engine
 * re-sums every modifier from the asset base on each recompute. Armor, potions, broken-item
 * penalties and respawns therefore never remove this bonus.
 *
 * <p>A freshly built {@link EntityStatMap} (new world, dungeon transition, relog) starts from
 * asset defaults with no modifiers, so {@link #reassert} must be called whenever the player
 * entity becomes ready in a world.
 *
 * <p><strong>Threading:</strong> all methods mutate the entity and must run on the WorldThread.
 */
public class RpgStatApplicator {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Entity stat asset id whose max is raised by {@link RpgStat#VITALITY}. */
    static final String HEALTH_STAT = "Health";
    /** Entity stat asset id whose max is raised by {@link RpgStat#STAMINA}. */
    static final String STAMINA_STAT = "Stamina";

    /** Modifier key for the Vitality max-health bonus (namespaced to avoid built-in key collisions). */
    static final String VITALITY_MODIFIER_KEY = "Duntale_Vitality";
    /** Modifier key for the Stamina max-stamina bonus (namespaced to avoid built-in key collisions). */
    static final String STAMINA_MODIFIER_KEY = "Duntale_Stamina";
    /** Modifier key for the authored armor flat-HP grant (replaces the engine's per-piece armor HP). */
    static final String ARMOR_HP_MODIFIER_KEY = "Duntale_ArmorHp";
    /** Modifier key for the negative offset that cancels the engine's built-in armor HP. */
    static final String ARMOR_HP_SUPPRESS_MODIFIER_KEY = "Duntale_ArmorHpSuppress";

    /** Stats that map to a persistent entity stat ceiling, with their entity-stat id and modifier key. */
    static final Map<RpgStat, StatBinding> BINDINGS = Map.of(
            RpgStat.VITALITY, new StatBinding(HEALTH_STAT, VITALITY_MODIFIER_KEY),
            RpgStat.STAMINA, new StatBinding(STAMINA_STAT, STAMINA_MODIFIER_KEY)
    );

    private final RpgService rpgService;

    /**
     * Creates a new applicator.
     *
     * @param rpgService the RPG service for stat lookups
     */
    public RpgStatApplicator(@Nonnull RpgService rpgService) {
        this.rpgService = Objects.requireNonNull(rpgService, "rpgService");
    }

    /**
     * Re-asserts the player's Vitality and Stamina max modifiers from their current stat
     * levels, without changing current health/stamina values.
     *
     * <p>Idempotent. Call whenever the player entity becomes ready in a world (join, world
     * transition, relog).
     *
     * @param playerId the player's UUID
     * @param ref      the player's entity reference
     * @param store    the entity store
     */
    public void reassert(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        EntityStatMap statMap = resolveStatMap(playerId, ref, store);
        if (statMap == null) {
            return;
        }
        for (Map.Entry<RpgStat, StatBinding> entry : BINDINGS.entrySet()) {
            RpgStat stat = entry.getKey();
            applyStat(statMap, entry.getValue(), bonusFor(stat, rpgService.getEffectiveStat(playerId, stat)), false);
        }
    }

    /**
     * Applies the authored armor flat-HP grant and the matching suppression of the engine's
     * built-in armor HP, as keyed MAX-additive modifiers on the Health stat.
     *
     * <p>The engine grants each equipped armor piece its own asset {@code Health} bonus; this method
     * cancels that total ({@code Duntale_ArmorHpSuppress = -engineArmorHp}) and replaces it with the
     * authored, level-and-slot-driven budget ({@code Duntale_ArmorHp = authoredArmorHp}). Pass
     * {@code 0}/{@code 0} to clear both (e.g. when no authored armor-HP curve is loaded), which
     * restores the engine's native armor HP.
     *
     * <p>Idempotent. Max-only — current health is left to clamp to the new ceiling.
     *
     * @param playerId       the player's UUID
     * @param ref            the player's entity reference
     * @param store          the entity store
     * @param authoredArmorHp the authored armor HP to grant (&ge; 0)
     * @param engineArmorHp   the engine's built-in armor HP to cancel (&ge; 0)
     */
    public void applyArmorHp(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref,
                             @Nonnull Store<EntityStore> store, float authoredArmorHp, float engineArmorHp) {
        EntityStatMap statMap = resolveStatMap(playerId, ref, store);
        if (statMap == null) {
            return;
        }
        int index = EntityStatType.getAssetMap().getIndex(HEALTH_STAT);
        if (index < 0) {
            LOGGER.atWarning().log("Unknown entity stat '%s' — armor HP modifiers not applied", HEALTH_STAT);
            return;
        }
        float authored = Math.max(0f, authoredArmorHp);
        float engine = Math.max(0f, engineArmorHp);
        putOrRemoveMax(statMap, index, ARMOR_HP_MODIFIER_KEY, authored);
        putOrRemoveMax(statMap, index, ARMOR_HP_SUPPRESS_MODIFIER_KEY, -engine);
        if (authored > 0f || engine > 0f) {
            // Net armor HP = authored - suppressed-engine. A large negative net flags a mismatch
            // between the parsed asset HP and what the engine actually granted (worth tuning).
            LOGGER.atFine().log("Armor HP for %s: authored +%.1f, engine suppressed -%.1f (net %+.1f)",
                    playerId, authored, engine, authored - engine);
        }
    }

    private static void putOrRemoveMax(@Nonnull EntityStatMap statMap, int index,
                                       @Nonnull String key, float amount) {
        if (amount == 0f) {
            statMap.removeModifier(index, key);
        } else {
            statMap.putModifier(index, key, new StaticModifier(
                    Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, amount));
        }
    }

    /**
     * Applies the updated max modifier for a single changed stat and raises (or lowers) the
     * current value by the change in bonus, so an assigned point immediately grants usable
     * health/stamina.
     *
     * <p>No-op for stats other than {@link RpgStat#VITALITY} and {@link RpgStat#STAMINA}.
     *
     * @param playerId the player's UUID
     * @param stat     the stat that changed
     * @param ref      the player's entity reference
     * @param store    the entity store
     */
    public void applyDelta(@Nonnull UUID playerId, @Nonnull RpgStat stat,
                           @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        StatBinding binding = BINDINGS.get(stat);
        if (binding == null) {
            return;
        }
        EntityStatMap statMap = resolveStatMap(playerId, ref, store);
        if (statMap == null) {
            return;
        }
        applyStat(statMap, binding, bonusFor(stat, rpgService.getEffectiveStat(playerId, stat)), true);
    }

    /**
     * Writes (or removes) the keyed MAX modifier for a stat, optionally adjusting the current
     * value by the change in bonus.
     */
    private void applyStat(@Nonnull EntityStatMap statMap, @Nonnull StatBinding binding,
                           float bonus, boolean bumpCurrent) {
        int index = EntityStatType.getAssetMap().getIndex(binding.statId());
        if (index < 0) {
            LOGGER.atWarning().log("Unknown entity stat '%s' — RPG modifier '%s' not applied",
                    binding.statId(), binding.modifierKey());
            return;
        }
        float previousBonus = currentBonus(statMap, index, binding.modifierKey());
        if (bonus <= 0.0f) {
            statMap.removeModifier(index, binding.modifierKey());
        } else {
            statMap.putModifier(index, binding.modifierKey(), new StaticModifier(
                    Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, bonus));
        }
        if (bumpCurrent && bonus != previousBonus) {
            // Raise (or lower) current value by the change in headroom; clamps to [min, max].
            statMap.addStatValue(index, bonus - previousBonus);
        }
    }

    /**
     * Computes the entity-stat bonus for a managed stat at the given level.
     *
     * @param stat  the RPG stat
     * @param level the stat level
     * @return the bonus to add to the entity stat's max, or {@code 0} for unmanaged stats
     */
    static float bonusFor(@Nonnull RpgStat stat, int level) {
        return switch (stat) {
            case VITALITY -> RpgStatEffects.computeVitalityBonus(level);
            case STAMINA -> RpgStatEffects.computeStaminaBonus(level);
            default -> 0.0f;
        };
    }

    private static float currentBonus(@Nonnull EntityStatMap statMap, int index, @Nonnull String key) {
        return statMap.getModifier(index, key) instanceof StaticModifier existing ? existing.getAmount() : 0.0f;
    }

    @Nullable
    private EntityStatMap resolveStatMap(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            LOGGER.atWarning().log("Missing EntityStatMap for player %s — RPG stat modifiers not applied", playerId);
        }
        return statMap;
    }

    /** Binds an {@link RpgStat} to the entity-stat asset id it boosts and the modifier key it uses. */
    record StatBinding(@Nonnull String statId, @Nonnull String modifierKey) {
    }
}
