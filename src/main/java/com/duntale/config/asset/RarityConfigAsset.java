package com.duntale.config.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Asset-store wrapper for the Duntale rarity system tuning.
 *
 * <p>Backed by a single JSON asset at {@code Server/Configs/Scaling/Rarity.json}. It defines the
 * per-source base-rarity ladders, the two-step Luck promotion parameters, the rarity-granted
 * attribute spec, the merchant price multipliers, and the per-rarity display colors/names. Rarity
 * is decoupled from the engine's cosmetic asset quality; this asset is the single source of truth
 * for how rarity is rolled and what it grants.
 *
 * <p>Hot reloads are observed by {@code RarityRegistry} via {@code LoadedAssetsEvent}, mirroring
 * {@link GearCurveConfigAsset}. The committed JSON is produced by
 * {@code scripts/scaling/derive_rarity.py}.
 */
public class RarityConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, RarityConfigAsset>> {

    public static final String ASSET_PATH = "Configs/Scaling";
    private static final String ASSET_ID = "Rarity";

    public static AssetBuilderCodec<String, RarityConfigAsset> CODEC;
    private static AssetStore<String, RarityConfigAsset,
            IndexedLookupTableAssetMap<String, RarityConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected LadderEntry[] ladders = new LadderEntry[0];
    protected PromotionConfig promotion = new PromotionConfig();
    protected AttributesConfig attributes = new AttributesConfig();
    protected PriceMultiplierEntry[] priceMultipliers = new PriceMultiplierEntry[0];
    protected DisplayEntry[] display = new DisplayEntry[0];

    public RarityConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        RarityConfigAsset.class,
                        RarityConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("Ladders", LadderEntry.ARRAY_CODEC),
                        (asset, value) -> asset.ladders = value,
                        asset -> asset.ladders)
                .add()
                .append(new KeyedCodec<>("Promotion", PromotionConfig.CODEC),
                        (asset, value) -> asset.promotion = value,
                        asset -> asset.promotion)
                .add()
                .append(new KeyedCodec<>("Attributes", AttributesConfig.CODEC),
                        (asset, value) -> asset.attributes = value,
                        asset -> asset.attributes)
                .add()
                .append(new KeyedCodec<>("PriceMultipliers", PriceMultiplierEntry.ARRAY_CODEC),
                        (asset, value) -> asset.priceMultipliers = value,
                        asset -> asset.priceMultipliers)
                .add()
                .append(new KeyedCodec<>("Display", DisplayEntry.ARRAY_CODEC),
                        (asset, value) -> asset.display = value,
                        asset -> asset.display)
                .add()
                .build();
    }

    /**
     * Returns the asset-store builder for registration in the plugin's {@code setup()}.
     *
     * @return a configured asset-store builder
     */
    @Nonnull
    public static HytaleAssetStore.Builder<String, RarityConfigAsset,
            IndexedLookupTableAssetMap<String, RarityConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        RarityConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(RarityConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(RarityConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    /**
     * Returns the loaded rarity asset, or {@code null} if none is registered/loaded.
     *
     * @return the {@code Rarity} asset, or {@code null}
     */
    @Nullable
    public static RarityConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, RarityConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    /**
     * Returns the registered asset store.
     *
     * @return the asset store
     */
    @Nonnull
    public static AssetStore<String, RarityConfigAsset,
            IndexedLookupTableAssetMap<String, RarityConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(RarityConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "RarityConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the configured per-source base-rarity ladders.
     *
     * @return a defensive copy of the ladder entries
     */
    @Nonnull
    public LadderEntry[] getLadders() {
        return ladders.clone();
    }

    /**
     * Returns the configured Luck promotion parameters.
     *
     * @return the promotion config (never {@code null})
     */
    @Nonnull
    public PromotionConfig getPromotion() {
        return promotion != null ? promotion : new PromotionConfig();
    }

    /**
     * Returns the configured rarity-granted attribute spec.
     *
     * @return the attribute config (never {@code null})
     */
    @Nonnull
    public AttributesConfig getAttributes() {
        return attributes != null ? attributes : new AttributesConfig();
    }

    /**
     * Returns the configured per-rarity merchant price multipliers.
     *
     * @return a defensive copy of the price multiplier entries
     */
    @Nonnull
    public PriceMultiplierEntry[] getPriceMultipliers() {
        return priceMultipliers.clone();
    }

    /**
     * Returns the configured per-rarity display styling.
     *
     * @return a defensive copy of the display entries
     */
    @Nonnull
    public DisplayEntry[] getDisplay() {
        return display.clone();
    }

    // ============================================
    // Nested codec DTOs
    // ============================================

    /** One {@code (rarity, weight)} entry within a source's base-rarity ladder. */
    public static class RarityWeightEntry {
        public static final BuilderCodec<RarityWeightEntry> CODEC;
        public static final ArrayCodec<RarityWeightEntry> ARRAY_CODEC;

        protected String rarity = "";
        protected int weight = 0;

        static {
            CODEC = BuilderCodec.builder(RarityWeightEntry.class, RarityWeightEntry::new)
                    .append(new KeyedCodec<>("Rarity", Codec.STRING),
                            (e, v) -> e.rarity = v, e -> e.rarity)
                    .add()
                    .append(new KeyedCodec<>("W", Codec.INTEGER),
                            (e, v) -> e.weight = v, e -> e.weight)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, RarityWeightEntry[]::new);
        }

        public RarityWeightEntry() {
        }

        public RarityWeightEntry(@Nonnull String rarity, int weight) {
            this.rarity = rarity;
            this.weight = weight;
        }

        @Nonnull
        public String getRarity() {
            return rarity;
        }

        public int getWeight() {
            return weight;
        }
    }

    /** One source's weighted base-rarity ladder. */
    public static class LadderEntry {
        public static final BuilderCodec<LadderEntry> CODEC;
        public static final ArrayCodec<LadderEntry> ARRAY_CODEC;

        protected String source = "";
        protected RarityWeightEntry[] weights = new RarityWeightEntry[0];

        static {
            CODEC = BuilderCodec.builder(LadderEntry.class, LadderEntry::new)
                    .append(new KeyedCodec<>("Source", Codec.STRING),
                            (e, v) -> e.source = v, e -> e.source)
                    .add()
                    .append(new KeyedCodec<>("Weights", RarityWeightEntry.ARRAY_CODEC),
                            (e, v) -> e.weights = v, e -> e.weights)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, LadderEntry[]::new);
        }

        public LadderEntry() {
        }

        public LadderEntry(@Nonnull String source, @Nonnull RarityWeightEntry[] weights) {
            this.source = source;
            this.weights = weights;
        }

        @Nonnull
        public String getSource() {
            return source;
        }

        @Nonnull
        public RarityWeightEntry[] getWeights() {
            return weights.clone();
        }
    }

    /** One {@code (tiers, weight)} entry within the promotion tier-jump distribution. */
    public static class TierWeightEntry {
        public static final BuilderCodec<TierWeightEntry> CODEC;
        public static final ArrayCodec<TierWeightEntry> ARRAY_CODEC;

        protected int tiers = 1;
        protected int weight = 0;

        static {
            CODEC = BuilderCodec.builder(TierWeightEntry.class, TierWeightEntry::new)
                    .append(new KeyedCodec<>("Tiers", Codec.INTEGER),
                            (e, v) -> e.tiers = v, e -> e.tiers)
                    .add()
                    .append(new KeyedCodec<>("W", Codec.INTEGER),
                            (e, v) -> e.weight = v, e -> e.weight)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, TierWeightEntry[]::new);
        }

        public TierWeightEntry() {
        }

        public TierWeightEntry(int tiers, int weight) {
            this.tiers = tiers;
            this.weight = weight;
        }

        public int getTiers() {
            return tiers;
        }

        public int getWeight() {
            return weight;
        }
    }

    /** The two-step Luck promotion parameters. */
    public static class PromotionConfig {
        public static final BuilderCodec<PromotionConfig> CODEC;

        protected float baseChance = 0f;
        protected float luckCoeff = 0f;
        protected float luckExp = 1f;
        protected float luckRef = 1f;
        protected TierWeightEntry[] tierWeights = new TierWeightEntry[0];
        protected float tierLuckShift = 0f;

        static {
            CODEC = BuilderCodec.builder(PromotionConfig.class, PromotionConfig::new)
                    .append(new KeyedCodec<>("BaseChance", Codec.FLOAT),
                            (e, v) -> e.baseChance = v, e -> e.baseChance)
                    .add()
                    .append(new KeyedCodec<>("LuckCoeff", Codec.FLOAT),
                            (e, v) -> e.luckCoeff = v, e -> e.luckCoeff)
                    .add()
                    .append(new KeyedCodec<>("LuckExp", Codec.FLOAT),
                            (e, v) -> e.luckExp = v, e -> e.luckExp)
                    .add()
                    .append(new KeyedCodec<>("LuckRef", Codec.FLOAT),
                            (e, v) -> e.luckRef = v, e -> e.luckRef)
                    .add()
                    .append(new KeyedCodec<>("TierWeights", TierWeightEntry.ARRAY_CODEC),
                            (e, v) -> e.tierWeights = v, e -> e.tierWeights)
                    .add()
                    .append(new KeyedCodec<>("TierLuckShift", Codec.FLOAT),
                            (e, v) -> e.tierLuckShift = v, e -> e.tierLuckShift)
                    .add()
                    .build();
        }

        public PromotionConfig() {
        }

        /**
         * Creates a promotion config (test/programmatic use).
         *
         * @param baseChance    the flat promotion chance
         * @param luckCoeff     the Luck coefficient
         * @param luckExp       the Luck exponent
         * @param luckRef       the Luck reference (saturation) level
         * @param tierWeights   the tier-jump weight distribution
         * @param tierLuckShift the Luck bias toward higher tier jumps
         */
        public PromotionConfig(float baseChance, float luckCoeff, float luckExp, float luckRef,
                               @Nonnull TierWeightEntry[] tierWeights, float tierLuckShift) {
            this.baseChance = baseChance;
            this.luckCoeff = luckCoeff;
            this.luckExp = luckExp;
            this.luckRef = luckRef;
            this.tierWeights = tierWeights;
            this.tierLuckShift = tierLuckShift;
        }

        public float getBaseChance() {
            return baseChance;
        }

        public float getLuckCoeff() {
            return luckCoeff;
        }

        public float getLuckExp() {
            return luckExp;
        }

        public float getLuckRef() {
            return luckRef;
        }

        @Nonnull
        public TierWeightEntry[] getTierWeights() {
            return tierWeights.clone();
        }

        public float getTierLuckShift() {
            return tierLuckShift;
        }
    }

    /**
     * One rarity's attribute spec: how many attributes roll ({@code [Min, Max]} count) and the
     * level-1 per-attribute value range ({@code [ValueMin, ValueMax]}). Each attribute rolls its
     * value independently within that range (shifted upward with gear level by
     * {@link AttributesConfig#getValuePerLevel()}).
     */
    public static class AttributeCountEntry {
        public static final BuilderCodec<AttributeCountEntry> CODEC;
        public static final ArrayCodec<AttributeCountEntry> ARRAY_CODEC;

        protected String rarity = "";
        protected int min = 0;
        protected int max = 0;
        protected int valueMin = 1;
        protected int valueMax = 1;

        static {
            CODEC = BuilderCodec.builder(AttributeCountEntry.class, AttributeCountEntry::new)
                    .append(new KeyedCodec<>("Rarity", Codec.STRING),
                            (e, v) -> e.rarity = v, e -> e.rarity)
                    .add()
                    .append(new KeyedCodec<>("Min", Codec.INTEGER),
                            (e, v) -> e.min = v, e -> e.min)
                    .add()
                    .append(new KeyedCodec<>("Max", Codec.INTEGER),
                            (e, v) -> e.max = v, e -> e.max)
                    .add()
                    .append(new KeyedCodec<>("ValueMin", Codec.INTEGER),
                            (e, v) -> e.valueMin = v, e -> e.valueMin)
                    .add()
                    .append(new KeyedCodec<>("ValueMax", Codec.INTEGER),
                            (e, v) -> e.valueMax = v, e -> e.valueMax)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, AttributeCountEntry[]::new);
        }

        public AttributeCountEntry() {
        }

        public AttributeCountEntry(@Nonnull String rarity, int min, int max, int valueMin, int valueMax) {
            this.rarity = rarity;
            this.min = min;
            this.max = max;
            this.valueMin = valueMin;
            this.valueMax = valueMax;
        }

        @Nonnull
        public String getRarity() {
            return rarity;
        }

        public int getMin() {
            return min;
        }

        public int getMax() {
            return max;
        }

        public int getValueMin() {
            return valueMin;
        }

        public int getValueMax() {
            return valueMax;
        }
    }

    /** The rarity-granted attribute spec: per-rarity count + value ranges, eligible stats, and the
     * shared level step that shifts the value range up (by {@code floor(level / step)}). */
    public static class AttributesConfig {
        public static final BuilderCodec<AttributesConfig> CODEC;

        protected AttributeCountEntry[] perRarity = new AttributeCountEntry[0];
        protected String[] eligibleStats = new String[0];
        protected int valueLevelStep = 0;

        static {
            CODEC = BuilderCodec.builder(AttributesConfig.class, AttributesConfig::new)
                    .append(new KeyedCodec<>("PerRarity", AttributeCountEntry.ARRAY_CODEC),
                            (e, v) -> e.perRarity = v, e -> e.perRarity)
                    .add()
                    .append(new KeyedCodec<>("EligibleStats", new ArrayCodec<>(Codec.STRING, String[]::new)),
                            (e, v) -> e.eligibleStats = v, e -> e.eligibleStats)
                    .add()
                    .append(new KeyedCodec<>("ValueLevelStep", Codec.INTEGER),
                            (e, v) -> e.valueLevelStep = v, e -> e.valueLevelStep)
                    .add()
                    .build();
        }

        public AttributesConfig() {
        }

        /**
         * Creates an attribute spec (test/programmatic use).
         *
         * @param perRarity      the per-rarity attribute count + value ranges
         * @param eligibleStats  the eligible stat names
         * @param valueLevelStep the number of gear levels per {@code +1} shift of the value range
         *                       ({@code <= 0} disables level scaling)
         */
        public AttributesConfig(@Nonnull AttributeCountEntry[] perRarity, @Nonnull String[] eligibleStats,
                                int valueLevelStep) {
            this.perRarity = perRarity;
            this.eligibleStats = eligibleStats;
            this.valueLevelStep = valueLevelStep;
        }

        @Nonnull
        public AttributeCountEntry[] getPerRarity() {
            return perRarity.clone();
        }

        @Nonnull
        public String[] getEligibleStats() {
            return eligibleStats.clone();
        }

        public int getValueLevelStep() {
            return valueLevelStep;
        }
    }

    /** One rarity's merchant price multiplier. */
    public static class PriceMultiplierEntry {
        public static final BuilderCodec<PriceMultiplierEntry> CODEC;
        public static final ArrayCodec<PriceMultiplierEntry> ARRAY_CODEC;

        protected String rarity = "";
        protected float multiplier = 1.0f;

        static {
            CODEC = BuilderCodec.builder(PriceMultiplierEntry.class, PriceMultiplierEntry::new)
                    .append(new KeyedCodec<>("Rarity", Codec.STRING),
                            (e, v) -> e.rarity = v, e -> e.rarity)
                    .add()
                    .append(new KeyedCodec<>("Mult", Codec.FLOAT),
                            (e, v) -> e.multiplier = v, e -> e.multiplier)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, PriceMultiplierEntry[]::new);
        }

        public PriceMultiplierEntry() {
        }

        public PriceMultiplierEntry(@Nonnull String rarity, float multiplier) {
            this.rarity = rarity;
            this.multiplier = multiplier;
        }

        @Nonnull
        public String getRarity() {
            return rarity;
        }

        public float getMultiplier() {
            return multiplier;
        }
    }

    /** One rarity's display styling (tooltip color + display name). */
    public static class DisplayEntry {
        public static final BuilderCodec<DisplayEntry> CODEC;
        public static final ArrayCodec<DisplayEntry> ARRAY_CODEC;

        protected String rarity = "";
        protected String color = "";
        protected String name = "";

        static {
            CODEC = BuilderCodec.builder(DisplayEntry.class, DisplayEntry::new)
                    .append(new KeyedCodec<>("Rarity", Codec.STRING),
                            (e, v) -> e.rarity = v, e -> e.rarity)
                    .add()
                    .append(new KeyedCodec<>("Color", Codec.STRING),
                            (e, v) -> e.color = v, e -> e.color)
                    .add()
                    .append(new KeyedCodec<>("Name", Codec.STRING),
                            (e, v) -> e.name = v, e -> e.name)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, DisplayEntry[]::new);
        }

        public DisplayEntry() {
        }

        public DisplayEntry(@Nonnull String rarity, @Nonnull String color, @Nonnull String name) {
            this.rarity = rarity;
            this.color = color;
            this.name = name;
        }

        @Nonnull
        public String getRarity() {
            return rarity;
        }

        @Nonnull
        public String getColor() {
            return color;
        }

        @Nonnull
        public String getName() {
            return name;
        }
    }
}
