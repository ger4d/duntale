package com.duntale.progression;

import com.duntale.config.asset.PricingConfigAsset;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runtime registry resolving the reconciled economy tuning numbers.
 *
 * <p>Rebuilds an immutable {@link Snapshot} from {@link PricingConfigAsset} on the initial asset load
 * and on every hot reload, falling back to an {@link Snapshot#EMPTY empty snapshot} when no asset is
 * present. While empty, {@link #isLoaded()} reports {@code false} and callers keep their own
 * hard-coded constants, so the feature degrades to today's behavior. Reads via the accessors are
 * lock-free against a {@code volatile} reference and never tear: an in-flight read sees either the
 * whole old snapshot or the whole new one.
 *
 * <p>Lifecycle mirrors {@link GearCurveRegistry}: construct in the plugin's {@code setup()}
 * (registers the reload listener), call {@link #refresh()} in {@code start()} (initial population
 * once asset stores are loaded), and {@link #shutdown()} on plugin teardown.
 */
public final class PricingRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Current snapshot. Volatile so refreshes publish to all threads without locking. */
    private volatile Snapshot current = Snapshot.EMPTY;

    @Nullable
    private final EventRegistry eventRegistry;

    /** Optional callback fired after each reload so consumers (e.g. the merchant) can re-apply. */
    @Nullable
    private volatile Runnable reloadCallback;

    /**
     * Creates the registry and subscribes to asset hot-reload events for {@link PricingConfigAsset}.
     */
    public PricingRegistry() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "PricingRegistry",
                HytaleServer.get().getEventBus());
        this.eventRegistry.enable();
        registerReloadListener();
    }

    private PricingRegistry(@Nonnull Snapshot snapshot) {
        this.eventRegistry = null;
        this.current = snapshot;
    }

    /**
     * Test hook: builds a registry with a fixed snapshot and no event subscription.
     *
     * @param snapshot the snapshot to serve
     * @return a registry serving {@code snapshot}
     */
    @Nonnull
    public static PricingRegistry forTest(@Nonnull Snapshot snapshot) {
        return new PricingRegistry(snapshot);
    }

    /**
     * Registers a callback fired after every reload (and not on the initial construction). Used to
     * re-apply derived values that are pushed once rather than read on demand &mdash; e.g. the
     * merchant's fixed custom-item prices and base price cache.
     *
     * @param callback the reload callback, or {@code null} to clear it
     */
    public void setReloadCallback(@Nullable Runnable callback) {
        this.reloadCallback = callback;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReloadListener() {
        this.eventRegistry.registerGlobal((Class) LoadedAssetsEvent.class,
                (Consumer<LoadedAssetsEvent>) this::onAssetsLoaded);
    }

    private void onAssetsLoaded(@Nonnull LoadedAssetsEvent<?, ?, ?> event) {
        if (event.getAssetClass() != PricingConfigAsset.class) {
            return;
        }
        refresh();
        Runnable callback = reloadCallback;
        if (callback != null) {
            callback.run();
        }
    }

    /**
     * Returns whether a pricing asset is loaded. When {@code false}, callers must keep their own
     * hard-coded constants for every value.
     *
     * @return {@code true} when an asset snapshot is loaded
     */
    public boolean isLoaded() {
        return current.loaded();
    }

    /**
     * Returns the linear scale of the combat-value-to-gold mapping.
     *
     * @return the gold mapping scale
     */
    public double goldMappingScale() {
        return current.goldMappingScale();
    }

    /**
     * Returns the curve exponent of the combat-value-to-gold mapping.
     *
     * @return the gold mapping exponent
     */
    public double goldMappingExponent() {
        return current.goldMappingExponent();
    }

    /**
     * Returns the weight of the effective-HP damage-reduction term in the armor combat value.
     *
     * @return the armor effective-HP DR weight
     */
    public double armorEhpDrWeight() {
        return current.armorEhpDrWeight();
    }

    /**
     * Returns the floor under any computed gear buy price.
     *
     * @return the minimum buy price in gold
     */
    public long minBuyPrice() {
        return current.minBuyPrice();
    }

    /**
     * Returns the fraction of the current-floor respawn cost charged for a restart one floor lower.
     *
     * @return the restart cost fraction
     */
    public double respawnRestartFraction() {
        return current.respawnRestartFraction();
    }

    /**
     * Returns whether the loaded asset carries a per-floor respawn schedule.
     *
     * @return {@code true} when at least one respawn band is mapped
     */
    public boolean hasRespawnSchedule() {
        return !current.respawnSchedule().isEmpty();
    }

    /**
     * Resolves the income-derived current-floor respawn cost for a floor: the cost of the highest band
     * whose {@code minFloor} the floor has reached.
     *
     * @param floor the current floor level
     * @return the band cost, or {@code null} when no schedule is loaded (callers fall back)
     */
    @Nullable
    public Long resolveRespawnCost(int floor) {
        Long resolved = null;
        for (RespawnBand band : current.respawnSchedule()) {
            if (floor >= band.minFloor()) {
                resolved = band.cost();
            }
        }
        return resolved;
    }

    /**
     * Returns whether the loaded asset carries Elite and Boss variant multiplier tables. When
     * {@code false}, callers keep their own hard-coded variant tables.
     *
     * @return {@code true} when at least one variant step is mapped
     */
    public boolean hasVariantSteps() {
        return !current.eliteSteps().isEmpty() || !current.bossSteps().isEmpty();
    }

    /**
     * Returns the Elite variant multiplier steps, highest-ratio-first.
     *
     * @return the Elite variant steps (possibly empty)
     */
    @Nonnull
    public List<VariantStep> eliteVariantSteps() {
        return current.eliteSteps();
    }

    /**
     * Returns the Boss variant multiplier steps, highest-ratio-first.
     *
     * @return the Boss variant steps (possibly empty)
     */
    @Nonnull
    public List<VariantStep> bossVariantSteps() {
        return current.bossSteps();
    }

    /**
     * Resolves the fixed unit buy price for an authored custom item.
     *
     * @param itemId the custom item asset ID
     * @return the unit buy price, or {@code null} when the item is unmapped
     */
    @Nullable
    public Long customPrice(@Nonnull String itemId) {
        return current.customPrices().get(itemId);
    }

    /**
     * Returns the full custom-item price map.
     *
     * @return custom item asset ID &rarr; unit buy price
     */
    @Nonnull
    public Map<String, Long> customPrices() {
        return current.customPrices();
    }

    /**
     * Rebuilds the in-memory snapshot from {@link PricingConfigAsset}, or resets it to
     * {@link Snapshot#EMPTY} when no asset is loaded. Safe to call from any thread — it only
     * publishes a {@code volatile} reference to an immutable snapshot.
     */
    public void refresh() {
        PricingConfigAsset asset = PricingConfigAsset.get();
        Snapshot updated = asset != null ? build(asset) : Snapshot.EMPTY;
        current = updated;
        LOGGER.atInfo().log("Pricing registry %s (%d respawn bands, %d elite steps, %d custom prices)",
                asset != null ? "loaded from asset" : "reset to empty",
                updated.respawnSchedule().size(), updated.eliteSteps().size(),
                updated.customPrices().size());
    }

    /**
     * Deregisters the hot-reload listener. Call on plugin teardown.
     */
    public void shutdown() {
        if (this.eventRegistry != null) {
            this.eventRegistry.shutdownAndCleanup(false);
        }
    }

    /**
     * Builds an immutable snapshot from a loaded pricing asset.
     *
     * @param asset the loaded pricing asset
     * @return the resolved snapshot
     */
    @Nonnull
    static Snapshot build(@Nonnull PricingConfigAsset asset) {
        return build(
                asset.getGoldMappingScale(),
                asset.getGoldMappingExponent(),
                asset.getArmorEhpDrWeight(),
                asset.getMinBuyPrice(),
                asset.getRespawnRestartFraction(),
                asset.getRespawnSchedule(),
                asset.getEliteVariantSteps(),
                asset.getBossVariantSteps(),
                asset.getCustomItemPrices());
    }

    /**
     * Builds an immutable snapshot from raw config components, mapping the entry DTOs into the
     * snapshot's value types (respawn bands sorted ascending) and skipping blank-id custom prices.
     *
     * @param goldMappingScale       the combat-value-to-gold linear scale
     * @param goldMappingExponent    the combat-value-to-gold curve exponent
     * @param armorEhpDrWeight       the weight of the armor effective-HP DR term
     * @param minBuyPrice            the floor under any computed gear buy price
     * @param respawnRestartFraction the restart-lower cost fraction
     * @param respawnSchedule        the per-floor-band respawn cost entries
     * @param eliteSteps             the Elite variant step entries
     * @param bossSteps              the Boss variant step entries
     * @param customPrices           the custom item price entries
     * @return the resolved snapshot
     */
    @Nonnull
    static Snapshot build(
            double goldMappingScale,
            double goldMappingExponent,
            double armorEhpDrWeight,
            long minBuyPrice,
            double respawnRestartFraction,
            @Nonnull PricingConfigAsset.RespawnBandEntry[] respawnSchedule,
            @Nonnull PricingConfigAsset.VariantStepEntry[] eliteSteps,
            @Nonnull PricingConfigAsset.VariantStepEntry[] bossSteps,
            @Nonnull PricingConfigAsset.CustomItemPriceEntry[] customPrices) {
        return new Snapshot(
                goldMappingScale,
                goldMappingExponent,
                armorEhpDrWeight,
                minBuyPrice,
                respawnRestartFraction,
                toBands(respawnSchedule),
                toSteps(eliteSteps),
                toSteps(bossSteps),
                toCustomPrices(customPrices),
                true
        );
    }

    @Nonnull
    private static List<RespawnBand> toBands(@Nonnull PricingConfigAsset.RespawnBandEntry[] entries) {
        List<RespawnBand> bands = new ArrayList<>(entries.length);
        for (PricingConfigAsset.RespawnBandEntry entry : entries) {
            bands.add(new RespawnBand(entry.getMinFloor(), entry.getCost()));
        }
        bands.sort(Comparator.comparingInt(RespawnBand::minFloor));
        return List.copyOf(bands);
    }

    @Nonnull
    private static List<VariantStep> toSteps(@Nonnull PricingConfigAsset.VariantStepEntry[] entries) {
        List<VariantStep> steps = new ArrayList<>(entries.length);
        for (PricingConfigAsset.VariantStepEntry entry : entries) {
            steps.add(new VariantStep(entry.getMinLevelRatio(), entry.getHpMult(), entry.getDamageMult()));
        }
        return List.copyOf(steps);
    }

    @Nonnull
    private static Map<String, Long> toCustomPrices(@Nonnull PricingConfigAsset.CustomItemPriceEntry[] entries) {
        Map<String, Long> prices = new HashMap<>();
        for (PricingConfigAsset.CustomItemPriceEntry entry : entries) {
            if (entry.getItemId() == null || entry.getItemId().isBlank()) {
                continue;
            }
            prices.put(entry.getItemId(), entry.getBuy());
        }
        return Map.copyOf(prices);
    }

    // ============================================
    // Value types
    // ============================================

    /**
     * One respawn-cost band: the current-floor respawn cost charged for floors at or above
     * {@code minFloor}.
     *
     * @param minFloor the lowest floor this band's cost applies to
     * @param cost     the current-floor respawn cost in gold for the band
     */
    public record RespawnBand(int minFloor, long cost) {
    }

    /**
     * One variant multiplier band: the HP and damage multipliers applied at or above the level
     * reached by {@code minLevelRatio} of the level ceiling.
     *
     * @param minLevelRatio the fraction of the level ceiling at which this band starts applying
     * @param hpMult        the HP multiplier for this band
     * @param damageMult    the damage multiplier for this band
     */
    public record VariantStep(float minLevelRatio, float hpMult, float damageMult) {
    }

    /**
     * An immutable, lock-free snapshot of the reconciled economy tuning numbers.
     *
     * @param goldMappingScale       the combat-value-to-gold linear scale
     * @param goldMappingExponent    the combat-value-to-gold curve exponent
     * @param armorEhpDrWeight       the weight of the armor effective-HP DR term
     * @param minBuyPrice            the floor under any computed gear buy price
     * @param respawnRestartFraction the restart-lower cost fraction
     * @param respawnSchedule        the per-floor-band respawn cost table, sorted ascending by floor
     * @param eliteSteps             the Elite variant multiplier steps, highest-ratio-first
     * @param bossSteps              the Boss variant multiplier steps, highest-ratio-first
     * @param customPrices           custom item asset ID &rarr; unit buy price
     * @param loaded                 whether this snapshot came from a loaded asset
     */
    public record Snapshot(
            double goldMappingScale,
            double goldMappingExponent,
            double armorEhpDrWeight,
            long minBuyPrice,
            double respawnRestartFraction,
            @Nonnull List<RespawnBand> respawnSchedule,
            @Nonnull List<VariantStep> eliteSteps,
            @Nonnull List<VariantStep> bossSteps,
            @Nonnull Map<String, Long> customPrices,
            boolean loaded
    ) {
        /**
         * The empty snapshot served before any asset loads. Carries today's hard-coded defaults so
         * accessors return sane values, but reports {@link #loaded() not loaded} so consumers keep
         * their own constants.
         */
        public static final Snapshot EMPTY = new Snapshot(
                10.0, 1.4, 1.0, 25L, 0.6,
                List.of(), List.of(), List.of(), Map.of(), false);
    }
}
