package com.duntale.progression;

import com.duntale.loot.GearAttribute;
import com.duntale.rpg.RpgConfig;
import com.duntale.rpg.RpgStat;
import com.duntale.rpg.RpgStatApplicator;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains each player's equipped-gear attribute bonus and the armor flat-HP grant.
 *
 * <p>On every equip-affecting change ({@code recompute} is driven by armor changes, held-weapon
 * swaps, world-join and world transitions), this service re-sums the {@link GearAttribute}s carried
 * by the four armor slots and the active held item into a per-player bonus map. The map feeds
 * {@code RpgService.getEffectiveStat} via {@link #getBonus(UUID, RpgStat)} (live-read stats pick the
 * new bonus up on their next read), while ceiling stats (Vitality/Stamina) are re-asserted
 * immediately through {@link RpgStatApplicator}. The persisted {@code RpgProfile} is never mutated.
 *
 * <p>When the authored armor-HP curve is loaded, this also computes the authored armor HP and the
 * matching engine-HP suppression so equipped armor's HP comes from the level/slot budget rather than
 * each item's asset value.
 *
 * <p><strong>Threading:</strong> {@code recompute} mutates the player entity and must run on the
 * WorldThread. {@link #getBonus(UUID, RpgStat)} is a lock-free read of a {@code volatile}-published
 * immutable map and is safe from any thread.
 */
public class GearAttributeService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final RpgStatApplicator rpgStatApplicator;
    private final GearCurveRegistry gearCurves;
    private final AssetCatalog assetCatalog;

    /** Per-player published bonus map. Each value is an immutable {@code EnumMap} snapshot. */
    private final Map<UUID, Map<RpgStat, Integer>> bonuses = new ConcurrentHashMap<>();

    @Nullable
    private BonusChangeListener changeListener;

    /**
     * Creates a new gear attribute service.
     *
     * @param rpgStatApplicator the applicator for Vitality/Stamina + armor-HP entity modifiers
     * @param gearCurves        the authored gear-curve registry (drives the armor-HP budget)
     * @param assetCatalog      the asset catalog (resolves each armor piece's engine HP)
     */
    public GearAttributeService(@Nonnull RpgStatApplicator rpgStatApplicator,
                                @Nonnull GearCurveRegistry gearCurves,
                                @Nonnull AssetCatalog assetCatalog) {
        this.rpgStatApplicator = Objects.requireNonNull(rpgStatApplicator, "rpgStatApplicator");
        this.gearCurves = Objects.requireNonNull(gearCurves, "gearCurves");
        this.assetCatalog = Objects.requireNonNull(assetCatalog, "assetCatalog");
    }

    /**
     * Sets the listener fired after a recompute that actually changed the bonus map (wired to the
     * scoreboard HUD refresh). Setting {@code null} clears it.
     *
     * @param listener the change listener, or {@code null}
     */
    public void setChangeListener(@Nullable BonusChangeListener listener) {
        this.changeListener = listener;
    }

    /**
     * Returns the equipped-gear bonus for a stat (zero when no gear contributes). Matches
     * {@code RpgService.GearBonusProvider}.
     *
     * @param playerId the player's UUID
     * @param stat     the stat
     * @return the additive gear bonus
     */
    public int getBonus(@Nonnull UUID playerId, @Nonnull RpgStat stat) {
        Map<RpgStat, Integer> map = bonuses.get(playerId);
        return map != null ? map.getOrDefault(stat, 0) : 0;
    }

    /**
     * Recomputes the player's gear bonus from their currently equipped armor and active held item,
     * republishes it, re-asserts the Vitality/Stamina ceilings (now including gear), and re-applies
     * the authored armor-HP grant + engine suppression.
     *
     * <p>Must run on the WorldThread.
     *
     * @param playerId the player's UUID
     * @param ref      the player's entity reference
     * @param store    the entity store
     */
    public void recompute(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref,
                          @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return;
        }
        boolean armorHpLoaded = gearCurves.hasArmorHp();
        EnumMap<RpgStat, Integer> bonus = new EnumMap<>(RpgStat.class);
        float[] armorHp = {0f, 0f}; // [authored, engine]

        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor != null) {
            ItemContainer container = armor.getInventory();
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                ItemStack piece = container.getItemStack(slot);
                if (ItemStack.isEmpty(piece)) {
                    continue;
                }
                accumulateAttributes(piece, bonus);
                if (armorHpLoaded) {
                    accumulateArmorHp(piece, armorHp);
                }
            }
        }

        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null) {
            ItemStack held = hotbar.getActiveItem();
            if (!ItemStack.isEmpty(held)) {
                accumulateAttributes(held, bonus);
            }
        }

        Map<RpgStat, Integer> published = Map.copyOf(bonus);
        Map<RpgStat, Integer> previous = bonuses.put(playerId, published);

        // Vitality/Stamina ceilings re-summed from the now-published gear bonus.
        rpgStatApplicator.reassert(playerId, ref, store);
        rpgStatApplicator.applyArmorHp(playerId, ref, store, armorHp[0], armorHp[1]);

        if (!published.equals(previous != null ? previous : Map.of())) {
            BonusChangeListener listener = this.changeListener;
            if (listener != null) {
                listener.onBonusChanged(playerId);
            }
        }
    }

    /**
     * Drops the player's cached gear bonus (call on player leave). The entity modifiers vanish with
     * the entity; a later world-join recompute rebuilds everything from the equipped gear.
     *
     * @param playerId the player's UUID
     */
    public void clear(@Nonnull UUID playerId) {
        bonuses.remove(playerId);
    }

    // ============================================
    // Internal logic
    // ============================================

    static void accumulateAttributes(@Nonnull ItemStack stack, @Nonnull EnumMap<RpgStat, Integer> bonus) {
        accumulateAttributes(GearLevelService.getAttributes(stack), bonus);
    }

    static void accumulateAttributes(@Nonnull List<GearAttribute> attributes,
                                     @Nonnull EnumMap<RpgStat, Integer> bonus) {
        if (attributes.isEmpty()) {
            return;
        }
        int maxStat = RpgConfig.values().maxStat();
        for (GearAttribute attribute : attributes) {
            int sum = bonus.getOrDefault(attribute.stat(), 0) + attribute.value();
            // Cap only the upper bound so negative attribute values can reduce a stat.
            bonus.put(attribute.stat(), Math.min(sum, maxStat));
        }
    }

    private void accumulateArmorHp(@Nonnull ItemStack piece, @Nonnull float[] armorHp) {
        Integer level = GearLevelService.getArmorLevel(piece);
        if (level == null) {
            return;
        }
        ItemArmor itemArmor = piece.getItem().getArmor();
        if (itemArmor == null || itemArmor.getArmorSlot() == null) {
            return;
        }
        Float share = gearCurves.armorHpShare(itemArmor.getArmorSlot().name());
        if (share == null) {
            return;
        }
        armorHp[0] += CombatScaling.armorBudgetHp(share, level, gearCurves.hpBudgetMin(), gearCurves.hpBudgetMax());

        AssetCatalog.ArmorBaseRow row = assetCatalog.getArmorBase(piece.getItem().getId());
        if (row != null && row.healthBonus() > 0) {
            armorHp[1] += row.healthBonus();
        }
    }

    /**
     * Listener fired after a recompute that changed the published bonus map.
     */
    @FunctionalInterface
    public interface BonusChangeListener {

        /**
         * Called when a player's equipped-gear bonus map changed.
         *
         * @param playerId the player's UUID
         */
        void onBonusChanged(@Nonnull UUID playerId);
    }
}
