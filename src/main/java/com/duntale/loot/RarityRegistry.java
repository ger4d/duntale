package com.duntale.loot;

import com.duntale.config.asset.RarityConfigAsset;
import com.duntale.rpg.RpgStat;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runtime registry resolving the authored rarity tuning for rolling, pricing, and display.
 *
 * <p>Rebuilds an immutable {@link Snapshot} from {@link RarityConfigAsset} on the initial asset
 * load and on every hot reload, falling back to an {@link Snapshot#EMPTY empty snapshot} when no
 * asset is present. While empty, {@link #isLoaded()} reports {@code false} and callers skip all
 * rarity stamping (gear stays Common/inert), so the feature degrades safely. Reads via the
 * {@code resolve}-style accessors are lock-free against a {@code volatile} reference and never tear.
 *
 * <p>Lifecycle mirrors {@code GearCurveRegistry}: construct in the plugin's {@code setup()}
 * (registers the reload listener), call {@link #refresh()} in {@code start()}, and
 * {@link #shutdown()} on plugin teardown.
 */
public final class RarityRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Price multiplier applied when a rarity is absent or unmapped: no markup. */
    private static final float DEFAULT_PRICE_MULT = 1.0f;

    /** Display color applied when a rarity is unmapped (neutral gray). */
    private static final String DEFAULT_COLOR = "#AAAAAA";

    /** Current snapshot. Volatile so refreshes publish to all threads without locking. */
    private volatile Snapshot current = Snapshot.EMPTY;

    @Nullable
    private final EventRegistry eventRegistry;

    /**
     * Creates the registry and subscribes to asset hot-reload events for {@link RarityConfigAsset}.
     */
    public RarityRegistry() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "RarityRegistry",
                HytaleServer.get().getEventBus());
        this.eventRegistry.enable();
        registerReloadListener();
    }

    private RarityRegistry(@Nonnull Snapshot snapshot) {
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
    static RarityRegistry forTest(@Nonnull Snapshot snapshot) {
        return new RarityRegistry(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReloadListener() {
        this.eventRegistry.registerGlobal((Class) LoadedAssetsEvent.class,
                (Consumer<LoadedAssetsEvent>) this::onAssetsLoaded);
    }

    private void onAssetsLoaded(@Nonnull LoadedAssetsEvent<?, ?, ?> event) {
        if (event.getAssetClass() != RarityConfigAsset.class) {
            return;
        }
        refresh();
    }

    /**
     * Returns whether rarity tuning is loaded. When {@code false}, callers must skip all rarity
     * stamping so generation behaves exactly as it did before the rarity system existed.
     *
     * @return {@code true} when a non-empty asset snapshot is loaded
     */
    public boolean isLoaded() {
        return current.loaded();
    }

    /**
     * Resolves a source's weighted base-rarity ladder.
     *
     * @param source the roll source
     * @return an immutable list of {@code (rarity, weight)} entries, empty when unmapped
     */
    @Nonnull
    public List<WeightedRarity> ladder(@Nonnull RaritySource source) {
        return current.ladders().getOrDefault(source, List.of());
    }

    /**
     * Resolves the highest-tier rarity reachable from a source's base ladder (before promotion).
     *
     * @param source the roll source
     * @return the top rarity in the ladder, or {@code null} when the ladder is unmapped/empty
     */
    @Nullable
    public Rarity topRarity(@Nonnull RaritySource source) {
        Rarity top = null;
        for (WeightedRarity entry : ladder(source)) {
            if (top == null || entry.rarity().tierIndex() > top.tierIndex()) {
                top = entry.rarity();
            }
        }
        return top;
    }

    /**
     * Returns the Luck promotion parameters.
     *
     * @return the promotion parameters
     */
    @Nonnull
    public Promotion promotion() {
        return current.promotion();
    }

    /**
     * Resolves the attribute-count range for a rarity.
     *
     * @param rarity the rarity
     * @return the {@code [min, max]} count range, or {@code (0, 0)} when unmapped
     */
    @Nonnull
    public AttrCount attrCount(@Nonnull Rarity rarity) {
        return current.attrCounts().getOrDefault(rarity, AttrCount.NONE);
    }

    /**
     * Returns the stats eligible to be rolled as gear attributes.
     *
     * @return an immutable list of eligible stats
     */
    @Nonnull
    public List<RpgStat> eligibleStats() {
        return current.eligibleStats();
    }

    /**
     * Returns the number of gear levels per {@code +1} shift of the attribute value range
     * ({@code <= 0} disables level scaling).
     *
     * @return the value level step
     */
    public int attrValueLevelStep() {
        return current.attrValueLevelStep();
    }

    /**
     * Resolves a rarity's merchant price multiplier.
     *
     * @param rarity the rarity, or {@code null} for unstamped (Common-equivalent) gear
     * @return the price multiplier, or {@code 1.0} when absent/unmapped
     */
    public float priceMult(@Nullable Rarity rarity) {
        if (rarity == null) {
            return DEFAULT_PRICE_MULT;
        }
        Float mult = current.priceMultipliers().get(rarity);
        return mult != null ? mult : DEFAULT_PRICE_MULT;
    }

    /**
     * Resolves a rarity's display color.
     *
     * @param rarity the rarity
     * @return the hex color (e.g. {@code "#FF8800"}), or a neutral gray default when unmapped
     */
    @Nonnull
    public String displayColor(@Nonnull Rarity rarity) {
        String color = current.displayColors().get(rarity);
        return color != null && !color.isBlank() ? color : DEFAULT_COLOR;
    }

    /**
     * Resolves a rarity's display name.
     *
     * @param rarity the rarity
     * @return the display name, or the rarity's id when unmapped
     */
    @Nonnull
    public String displayName(@Nonnull Rarity rarity) {
        String name = current.displayNames().get(rarity);
        return name != null && !name.isBlank() ? name : rarity.id();
    }

    /**
     * Rebuilds the in-memory snapshot from {@link RarityConfigAsset}, or resets it to
     * {@link Snapshot#EMPTY} when no asset is loaded. Safe to call from any thread.
     */
    public void refresh() {
        RarityConfigAsset asset = RarityConfigAsset.get();
        Snapshot updated = asset != null
                ? build(asset.getLadders(), asset.getPromotion(), asset.getAttributes(),
                        asset.getPriceMultipliers(), asset.getDisplay())
                : Snapshot.EMPTY;
        current = updated;
        LOGGER.atInfo().log("Rarity registry %s (%d ladders, %d attr-ranges, %d price tiers)",
                asset != null ? "loaded from asset" : "reset to empty",
                updated.ladders().size(), updated.attrCounts().size(),
                updated.priceMultipliers().size());
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
     * Builds the immutable snapshot from raw config pieces, skipping entries with unknown
     * source/rarity/stat tokens.
     *
     * @param ladderEntries the per-source ladder entries
     * @param promo         the promotion config
     * @param attrCfg       the attribute spec
     * @param priceEntries  the per-rarity price multiplier entries
     * @param displayEntries the per-rarity display entries
     * @return the resolved snapshot
     */
    @Nonnull
    static Snapshot build(@Nonnull RarityConfigAsset.LadderEntry[] ladderEntries,
                          @Nonnull RarityConfigAsset.PromotionConfig promo,
                          @Nonnull RarityConfigAsset.AttributesConfig attrCfg,
                          @Nonnull RarityConfigAsset.PriceMultiplierEntry[] priceEntries,
                          @Nonnull RarityConfigAsset.DisplayEntry[] displayEntries) {
        Map<RaritySource, List<WeightedRarity>> ladders = new EnumMap<>(RaritySource.class);
        for (RarityConfigAsset.LadderEntry entry : ladderEntries) {
            RaritySource source = RaritySource.fromId(entry.getSource());
            if (source == null) {
                continue;
            }
            List<WeightedRarity> weights = new ArrayList<>();
            for (RarityConfigAsset.RarityWeightEntry weight : entry.getWeights()) {
                Rarity rarity = Rarity.fromId(weight.getRarity());
                if (rarity != null && weight.getWeight() > 0) {
                    weights.add(new WeightedRarity(rarity, weight.getWeight()));
                }
            }
            if (!weights.isEmpty()) {
                ladders.put(source, List.copyOf(weights));
            }
        }

        List<TierWeight> tierWeights = new ArrayList<>();
        for (RarityConfigAsset.TierWeightEntry tier : promo.getTierWeights()) {
            if (tier.getTiers() > 0 && tier.getWeight() > 0) {
                tierWeights.add(new TierWeight(tier.getTiers(), tier.getWeight()));
            }
        }
        Promotion promotion = new Promotion(promo.getBaseChance(), promo.getLuckCoeff(),
                promo.getLuckExp(), Math.max(1.0f, promo.getLuckRef()),
                List.copyOf(tierWeights), promo.getTierLuckShift());

        Map<Rarity, AttrCount> attrCounts = new EnumMap<>(Rarity.class);
        for (RarityConfigAsset.AttributeCountEntry count : attrCfg.getPerRarity()) {
            Rarity rarity = Rarity.fromId(count.getRarity());
            if (rarity != null) {
                int min = Math.max(0, count.getMin());
                int max = Math.max(min, count.getMax());
                int valueMin = Math.max(1, count.getValueMin());
                int valueMax = Math.max(valueMin, count.getValueMax());
                attrCounts.put(rarity, new AttrCount(min, max, valueMin, valueMax));
            }
        }
        List<RpgStat> eligibleStats = new ArrayList<>();
        for (String name : attrCfg.getEligibleStats()) {
            RpgStat stat = parseStat(name);
            if (stat != null && !eligibleStats.contains(stat)) {
                eligibleStats.add(stat);
            }
        }

        Map<Rarity, Float> priceMultipliers = new EnumMap<>(Rarity.class);
        for (RarityConfigAsset.PriceMultiplierEntry price : priceEntries) {
            Rarity rarity = Rarity.fromId(price.getRarity());
            if (rarity != null) {
                priceMultipliers.put(rarity, price.getMultiplier());
            }
        }

        Map<Rarity, String> displayColors = new EnumMap<>(Rarity.class);
        Map<Rarity, String> displayNames = new EnumMap<>(Rarity.class);
        for (RarityConfigAsset.DisplayEntry disp : displayEntries) {
            Rarity rarity = Rarity.fromId(disp.getRarity());
            if (rarity != null) {
                displayColors.put(rarity, disp.getColor());
                displayNames.put(rarity, disp.getName());
            }
        }

        return new Snapshot(
                Map.copyOf(ladders),
                promotion,
                Map.copyOf(attrCounts),
                List.copyOf(eligibleStats),
                attrCfg.getValueLevelStep(),
                Map.copyOf(priceMultipliers),
                Map.copyOf(displayColors),
                Map.copyOf(displayNames)
        );
    }

    @Nullable
    private static RpgStat parseStat(@Nullable String name) {
        if (name == null) {
            return null;
        }
        try {
            return RpgStat.valueOf(name.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ============================================
    // Value types
    // ============================================

    /** One {@code (rarity, weight)} entry in a source's base ladder. */
    public record WeightedRarity(@Nonnull Rarity rarity, int weight) {
    }

    /** One {@code (tiers, weight)} entry in the promotion tier-jump distribution. */
    public record TierWeight(int tiers, int weight) {
    }

    /**
     * A rarity's attribute spec: the count range ({@code [min, max]}) and the level-1 per-attribute
     * value range ({@code [valueMin, valueMax]}) that each rolled attribute draws from.
     */
    public record AttrCount(int min, int max, int valueMin, int valueMax) {
        /** The no-attributes spec. */
        public static final AttrCount NONE = new AttrCount(0, 0, 1, 1);
    }

    /** The Luck promotion parameters. */
    public record Promotion(
            float baseChance,
            float luckCoeff,
            float luckExp,
            float luckRef,
            @Nonnull List<TierWeight> tierWeights,
            float tierLuckShift
    ) {
        /** Promotion that never fires (no base chance, no tier jumps). */
        public static final Promotion NONE = new Promotion(0f, 0f, 1f, 1f, List.of(), 0f);
    }

    /**
     * An immutable, lock-free snapshot of the authored rarity tuning.
     *
     * @param ladders          source &rarr; weighted base-rarity ladder
     * @param promotion        the Luck promotion parameters
     * @param attrCounts       rarity &rarr; attribute count + value-range spec
     * @param eligibleStats    stats eligible to roll as attributes
     * @param attrValueLevelStep gear levels per {@code +1} shift of the value range
     * @param priceMultipliers rarity &rarr; merchant price multiplier
     * @param displayColors    rarity &rarr; tooltip hex color
     * @param displayNames     rarity &rarr; display name
     */
    public record Snapshot(
            @Nonnull Map<RaritySource, List<WeightedRarity>> ladders,
            @Nonnull Promotion promotion,
            @Nonnull Map<Rarity, AttrCount> attrCounts,
            @Nonnull List<RpgStat> eligibleStats,
            int attrValueLevelStep,
            @Nonnull Map<Rarity, Float> priceMultipliers,
            @Nonnull Map<Rarity, String> displayColors,
            @Nonnull Map<Rarity, String> displayNames
    ) {
        /** The empty snapshot served before any asset loads — drives the safe-degrade path. */
        public static final Snapshot EMPTY = new Snapshot(
                Map.of(), Promotion.NONE, Map.of(), List.of(), 0, Map.of(), Map.of(), Map.of());

        /**
         * Returns whether this snapshot carries authored rarity ladders.
         *
         * @return {@code true} when at least one source ladder is present
         */
        public boolean loaded() {
            return !ladders.isEmpty();
        }
    }
}
