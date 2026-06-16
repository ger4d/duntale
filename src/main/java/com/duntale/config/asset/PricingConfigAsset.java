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
 * Asset-store wrapper for the reconciled economy tuning numbers.
 *
 * <p>Backed by a single JSON asset at {@code Server/Configs/Scaling/Pricing.json}. It collects the
 * tuning numbers that were previously scattered as hard-coded Java constants across the merchant,
 * death, and NPC-scaling subsystems so they can be solved offline against one driver (the smooth
 * per-floor income target) and hot-reloaded together instead of recompiling. Each consumer keeps its
 * own constant as the safe fallback used while no asset is loaded, so the feature degrades to today's
 * behavior when the asset is absent.
 *
 * <p>The asset carries:
 * <ul>
 *   <li>{@code GoldMappingScale}/{@code GoldMappingExponent} &mdash; the {@code gold = pow(value, exp)
 *       * scale} mapping that turns a gear combat value into a buy price.</li>
 *   <li>{@code ArmorEhpDrWeight} &mdash; the single scalar weighting the effective-HP damage-reduction
 *       term against the flat-HP term so weapon and armor combat values land in the same band.</li>
 *   <li>{@code MinBuyPrice} &mdash; the floor under any computed gear buy price.</li>
 *   <li>{@code RespawnSchedule} &mdash; the income-derived current-floor respawn cost as a per-floor-band
 *       table; {@code RespawnRestartFraction} is the fraction of it charged for a restart one floor
 *       lower.</li>
 *   <li>{@code EliteVariantSteps}/{@code BossVariantSteps} &mdash; the per-level-band HP and damage
 *       multiplier tables for the Elite and Boss variants.</li>
 *   <li>{@code CustomItemPrices} &mdash; fixed unit buy prices for the authored big-ticket items.</li>
 * </ul>
 *
 * <p>Hot reloads are observed by {@code PricingRegistry} via {@code LoadedAssetsEvent}, mirroring
 * {@link GearCurveConfigAsset}. The committed JSON is produced by
 * {@code scripts/scaling/derive_income.py} (the respawn schedule) and
 * {@code scripts/scaling/derive_prices.py} (everything else).
 */
public class PricingConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, PricingConfigAsset>> {

    public static final String ASSET_PATH = "Configs/Scaling";
    private static final String ASSET_ID = "Pricing";

    public static AssetBuilderCodec<String, PricingConfigAsset> CODEC;
    private static AssetStore<String, PricingConfigAsset,
            IndexedLookupTableAssetMap<String, PricingConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected double goldMappingScale = 10.0;
    protected double goldMappingExponent = 1.4;
    protected double armorEhpDrWeight = 1.0;
    protected long minBuyPrice = 25L;
    protected double respawnRestartFraction = 0.6;
    protected RespawnBandEntry[] respawnSchedule = new RespawnBandEntry[0];
    protected VariantStepEntry[] eliteVariantSteps = new VariantStepEntry[0];
    protected VariantStepEntry[] bossVariantSteps = new VariantStepEntry[0];
    protected CustomItemPriceEntry[] customItemPrices = new CustomItemPriceEntry[0];

    public PricingConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        PricingConfigAsset.class,
                        PricingConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("GoldMappingScale", Codec.DOUBLE),
                        (asset, value) -> asset.goldMappingScale = value,
                        asset -> asset.goldMappingScale)
                .add()
                .append(new KeyedCodec<>("GoldMappingExponent", Codec.DOUBLE),
                        (asset, value) -> asset.goldMappingExponent = value,
                        asset -> asset.goldMappingExponent)
                .add()
                .append(new KeyedCodec<>("ArmorEhpDrWeight", Codec.DOUBLE),
                        (asset, value) -> asset.armorEhpDrWeight = value,
                        asset -> asset.armorEhpDrWeight)
                .add()
                .append(new KeyedCodec<>("MinBuyPrice", Codec.LONG),
                        (asset, value) -> asset.minBuyPrice = value,
                        asset -> asset.minBuyPrice)
                .add()
                .append(new KeyedCodec<>("RespawnRestartFraction", Codec.DOUBLE),
                        (asset, value) -> asset.respawnRestartFraction = value,
                        asset -> asset.respawnRestartFraction)
                .add()
                .append(new KeyedCodec<>("RespawnSchedule", RespawnBandEntry.ARRAY_CODEC),
                        (asset, value) -> asset.respawnSchedule = value,
                        asset -> asset.respawnSchedule)
                .add()
                .append(new KeyedCodec<>("EliteVariantSteps", VariantStepEntry.ARRAY_CODEC),
                        (asset, value) -> asset.eliteVariantSteps = value,
                        asset -> asset.eliteVariantSteps)
                .add()
                .append(new KeyedCodec<>("BossVariantSteps", VariantStepEntry.ARRAY_CODEC),
                        (asset, value) -> asset.bossVariantSteps = value,
                        asset -> asset.bossVariantSteps)
                .add()
                .append(new KeyedCodec<>("CustomItemPrices", CustomItemPriceEntry.ARRAY_CODEC),
                        (asset, value) -> asset.customItemPrices = value,
                        asset -> asset.customItemPrices)
                .add()
                .build();
    }

    /**
     * Returns the asset-store builder for registration in the plugin's {@code setup()}.
     *
     * @return a configured asset-store builder
     */
    @Nonnull
    public static HytaleAssetStore.Builder<String, PricingConfigAsset,
            IndexedLookupTableAssetMap<String, PricingConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        PricingConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(PricingConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(PricingConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    /**
     * Returns the loaded pricing asset, or {@code null} if none is registered/loaded.
     *
     * @return the {@code Pricing} asset, or {@code null}
     */
    @Nullable
    public static PricingConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, PricingConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    /**
     * Returns the registered asset store.
     *
     * @return the asset store
     */
    @Nonnull
    public static AssetStore<String, PricingConfigAsset,
            IndexedLookupTableAssetMap<String, PricingConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(PricingConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "PricingConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the linear scale of the combat-value-to-gold mapping.
     *
     * @return the gold mapping scale
     */
    public double getGoldMappingScale() {
        return goldMappingScale;
    }

    /**
     * Returns the curve exponent of the combat-value-to-gold mapping.
     *
     * @return the gold mapping exponent
     */
    public double getGoldMappingExponent() {
        return goldMappingExponent;
    }

    /**
     * Returns the weight of the effective-HP damage-reduction term in the armor combat value.
     *
     * @return the armor effective-HP DR weight
     */
    public double getArmorEhpDrWeight() {
        return armorEhpDrWeight;
    }

    /**
     * Returns the floor under any computed gear buy price.
     *
     * @return the minimum buy price in gold
     */
    public long getMinBuyPrice() {
        return minBuyPrice;
    }

    /**
     * Returns the fraction of the current-floor respawn cost charged for a restart one floor lower.
     *
     * @return the restart cost fraction
     */
    public double getRespawnRestartFraction() {
        return respawnRestartFraction;
    }

    /**
     * Returns the income-derived current-floor respawn cost as a per-floor-band table.
     *
     * @return a defensive copy of the configured respawn schedule bands
     */
    @Nonnull
    public RespawnBandEntry[] getRespawnSchedule() {
        return respawnSchedule.clone();
    }

    /**
     * Returns the Elite variant per-level-band multiplier steps.
     *
     * @return a defensive copy of the configured Elite variant steps
     */
    @Nonnull
    public VariantStepEntry[] getEliteVariantSteps() {
        return eliteVariantSteps.clone();
    }

    /**
     * Returns the Boss variant per-level-band multiplier steps.
     *
     * @return a defensive copy of the configured Boss variant steps
     */
    @Nonnull
    public VariantStepEntry[] getBossVariantSteps() {
        return bossVariantSteps.clone();
    }

    /**
     * Returns the fixed unit buy prices for the authored custom items.
     *
     * @return a defensive copy of the configured custom item price entries
     */
    @Nonnull
    public CustomItemPriceEntry[] getCustomItemPrices() {
        return customItemPrices.clone();
    }

    // ============================================
    // Nested codec DTOs
    // ============================================

    /**
     * One respawn-cost band: the current-floor respawn cost charged for floors at or above
     * {@code minFloor} (until the next band's {@code minFloor}). Bands are evaluated highest-floor
     * first; the restart-lower cost is this cost times the restart fraction.
     */
    public static class RespawnBandEntry {
        public static final BuilderCodec<RespawnBandEntry> CODEC;
        public static final ArrayCodec<RespawnBandEntry> ARRAY_CODEC;

        protected int minFloor = 1;
        protected long cost = 0L;

        static {
            CODEC = BuilderCodec.builder(RespawnBandEntry.class, RespawnBandEntry::new)
                    .append(new KeyedCodec<>("MinFloor", Codec.INTEGER),
                            (e, v) -> e.minFloor = v, e -> e.minFloor)
                    .add()
                    .append(new KeyedCodec<>("Cost", Codec.LONG),
                            (e, v) -> e.cost = v, e -> e.cost)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, RespawnBandEntry[]::new);
        }

        public RespawnBandEntry() {
        }

        /**
         * Creates a respawn-cost band (test/programmatic use).
         *
         * @param minFloor the lowest floor this band's cost applies to
         * @param cost     the current-floor respawn cost in gold for the band
         */
        public RespawnBandEntry(int minFloor, long cost) {
            this.minFloor = minFloor;
            this.cost = cost;
        }

        public int getMinFloor() {
            return minFloor;
        }

        public long getCost() {
            return cost;
        }
    }

    /**
     * One variant multiplier step: the HP and damage multipliers that apply at or above the level
     * reached by {@code minLevelRatio} of the level ceiling. Steps are evaluated highest-ratio-first,
     * and a step with ratio {@code 0.0} is the always-matching base band.
     */
    public static class VariantStepEntry {
        public static final BuilderCodec<VariantStepEntry> CODEC;
        public static final ArrayCodec<VariantStepEntry> ARRAY_CODEC;

        protected float minLevelRatio = 0f;
        protected float hpMult = 1.0f;
        protected float damageMult = 1.0f;

        static {
            CODEC = BuilderCodec.builder(VariantStepEntry.class, VariantStepEntry::new)
                    .append(new KeyedCodec<>("MinLevelRatio", Codec.FLOAT),
                            (e, v) -> e.minLevelRatio = v, e -> e.minLevelRatio)
                    .add()
                    .append(new KeyedCodec<>("HpMult", Codec.FLOAT),
                            (e, v) -> e.hpMult = v, e -> e.hpMult)
                    .add()
                    .append(new KeyedCodec<>("DamageMult", Codec.FLOAT),
                            (e, v) -> e.damageMult = v, e -> e.damageMult)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, VariantStepEntry[]::new);
        }

        public VariantStepEntry() {
        }

        /**
         * Creates a variant multiplier step (test/programmatic use).
         *
         * @param minLevelRatio the fraction of the level ceiling at which this step starts applying
         * @param hpMult        the HP multiplier for this band
         * @param damageMult    the damage multiplier for this band
         */
        public VariantStepEntry(float minLevelRatio, float hpMult, float damageMult) {
            this.minLevelRatio = minLevelRatio;
            this.hpMult = hpMult;
            this.damageMult = damageMult;
        }

        public float getMinLevelRatio() {
            return minLevelRatio;
        }

        public float getHpMult() {
            return hpMult;
        }

        public float getDamageMult() {
            return damageMult;
        }
    }

    /**
     * One authored custom item's fixed unit buy price.
     */
    public static class CustomItemPriceEntry {
        public static final BuilderCodec<CustomItemPriceEntry> CODEC;
        public static final ArrayCodec<CustomItemPriceEntry> ARRAY_CODEC;

        protected String itemId = "";
        protected long buy = 0L;

        static {
            CODEC = BuilderCodec.builder(CustomItemPriceEntry.class, CustomItemPriceEntry::new)
                    .append(new KeyedCodec<>("ItemId", Codec.STRING),
                            (e, v) -> e.itemId = v, e -> e.itemId)
                    .add()
                    .append(new KeyedCodec<>("Buy", Codec.LONG),
                            (e, v) -> e.buy = v, e -> e.buy)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, CustomItemPriceEntry[]::new);
        }

        public CustomItemPriceEntry() {
        }

        /**
         * Creates a custom item price entry (test/programmatic use).
         *
         * @param itemId the custom item asset ID
         * @param buy    the unit buy price in gold
         */
        public CustomItemPriceEntry(@Nonnull String itemId, long buy) {
            this.itemId = itemId;
            this.buy = buy;
        }

        @Nonnull
        public String getItemId() {
            return itemId;
        }

        public long getBuy() {
            return buy;
        }
    }
}
