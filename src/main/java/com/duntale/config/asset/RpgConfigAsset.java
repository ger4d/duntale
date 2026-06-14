package com.duntale.config.asset;

import com.duntale.rpg.RpgConstants;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Asset-store wrapper for the runtime-tunable RPG config (stat curves, stat bounds, gold cap).
 *
 * <p>Backed by a single JSON asset at {@code Server/Configs/Rpg/RpgConfig.json}. Each field is
 * initialized to its {@link RpgConstants} default, so an absent JSON key falls back automatically.
 * Hot reloads are observed by {@code RpgConfig} via {@code LoadedAssetsEvent}.
 */
public class RpgConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, RpgConfigAsset>> {

    public static final String ASSET_PATH = "Configs/Rpg";
    private static final String ASSET_ID = "RpgConfig";

    public static AssetBuilderCodec<String, RpgConfigAsset> CODEC;
    private static AssetStore<String, RpgConfigAsset,
            IndexedLookupTableAssetMap<String, RpgConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;

    // Stat bounds
    protected int minStat = RpgConstants.MIN_STAT;
    protected int maxStat = RpgConstants.MAX_STAT;
    // Speed
    protected float speedBase = RpgConstants.SPEED_BASE;
    protected float speedMaxBonus = RpgConstants.SPEED_MAX_BONUS;
    protected float speedHalfPoint = RpgConstants.SPEED_HALF_POINT;
    // Strength
    protected float strengthMaxBonus = RpgConstants.STRENGTH_MAX_BONUS;
    protected float strengthHalfPoint = RpgConstants.STRENGTH_HALF_POINT;
    // Luck — accelerating gear drop-chance curve
    protected float luckDropCoefficient = RpgConstants.LUCK_DROP_COEFFICIENT;
    protected float luckDropExponent = RpgConstants.LUCK_DROP_EXPONENT;
    protected int luckDropReference = RpgConstants.LUCK_DROP_REFERENCE;
    protected float luckDropMaxChance = RpgConstants.LUCK_DROP_MAX_CHANCE;
    // Stamina
    protected float staminaPerPoint = RpgConstants.STAMINA_PER_POINT;
    // Agility
    protected long agilityBaseThrottleNs = RpgConstants.AGILITY_BASE_THROTTLE_NS;
    protected float agilityMaxReduction = RpgConstants.AGILITY_MAX_REDUCTION;
    protected float agilityHalfPoint = RpgConstants.AGILITY_HALF_POINT;
    protected long agilityMinThrottleNs = RpgConstants.AGILITY_MIN_THROTTLE_NS;
    // Resistance
    protected float resistanceMaxDr = RpgConstants.RESISTANCE_MAX_DR;
    protected float resistanceHalfPoint = RpgConstants.RESISTANCE_HALF_POINT;
    // Vitality
    protected float vitalityHpPerPoint = RpgConstants.VITALITY_HP_PER_POINT;
    // Gold
    protected long maxGoldBalance = RpgConstants.MAX_GOLD_BALANCE;

    public RpgConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        RpgConfigAsset.class,
                        RpgConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("MinStat", Codec.INTEGER),
                        (asset, value) -> asset.minStat = value, asset -> asset.minStat).add()
                .append(new KeyedCodec<>("MaxStat", Codec.INTEGER),
                        (asset, value) -> asset.maxStat = value, asset -> asset.maxStat).add()
                .append(new KeyedCodec<>("SpeedBase", Codec.FLOAT),
                        (asset, value) -> asset.speedBase = value, asset -> asset.speedBase).add()
                .append(new KeyedCodec<>("SpeedMaxBonus", Codec.FLOAT),
                        (asset, value) -> asset.speedMaxBonus = value, asset -> asset.speedMaxBonus).add()
                .append(new KeyedCodec<>("SpeedHalfPoint", Codec.FLOAT),
                        (asset, value) -> asset.speedHalfPoint = value, asset -> asset.speedHalfPoint).add()
                .append(new KeyedCodec<>("StrengthMaxBonus", Codec.FLOAT),
                        (asset, value) -> asset.strengthMaxBonus = value, asset -> asset.strengthMaxBonus).add()
                .append(new KeyedCodec<>("StrengthHalfPoint", Codec.FLOAT),
                        (asset, value) -> asset.strengthHalfPoint = value, asset -> asset.strengthHalfPoint).add()
                .append(new KeyedCodec<>("LuckDropCoefficient", Codec.FLOAT),
                        (asset, value) -> asset.luckDropCoefficient = value,
                        asset -> asset.luckDropCoefficient).add()
                .append(new KeyedCodec<>("LuckDropExponent", Codec.FLOAT),
                        (asset, value) -> asset.luckDropExponent = value,
                        asset -> asset.luckDropExponent).add()
                .append(new KeyedCodec<>("LuckDropReference", Codec.INTEGER),
                        (asset, value) -> asset.luckDropReference = value,
                        asset -> asset.luckDropReference).add()
                .append(new KeyedCodec<>("LuckDropMaxChance", Codec.FLOAT),
                        (asset, value) -> asset.luckDropMaxChance = value,
                        asset -> asset.luckDropMaxChance).add()
                .append(new KeyedCodec<>("StaminaPerPoint", Codec.FLOAT),
                        (asset, value) -> asset.staminaPerPoint = value, asset -> asset.staminaPerPoint).add()
                .append(new KeyedCodec<>("AgilityBaseThrottleNs", Codec.LONG),
                        (asset, value) -> asset.agilityBaseThrottleNs = value,
                        asset -> asset.agilityBaseThrottleNs).add()
                .append(new KeyedCodec<>("AgilityMaxReduction", Codec.FLOAT),
                        (asset, value) -> asset.agilityMaxReduction = value, asset -> asset.agilityMaxReduction).add()
                .append(new KeyedCodec<>("AgilityHalfPoint", Codec.FLOAT),
                        (asset, value) -> asset.agilityHalfPoint = value, asset -> asset.agilityHalfPoint).add()
                .append(new KeyedCodec<>("AgilityMinThrottleNs", Codec.LONG),
                        (asset, value) -> asset.agilityMinThrottleNs = value,
                        asset -> asset.agilityMinThrottleNs).add()
                .append(new KeyedCodec<>("ResistanceMaxDr", Codec.FLOAT),
                        (asset, value) -> asset.resistanceMaxDr = value, asset -> asset.resistanceMaxDr).add()
                .append(new KeyedCodec<>("ResistanceHalfPoint", Codec.FLOAT),
                        (asset, value) -> asset.resistanceHalfPoint = value, asset -> asset.resistanceHalfPoint).add()
                .append(new KeyedCodec<>("VitalityHpPerPoint", Codec.FLOAT),
                        (asset, value) -> asset.vitalityHpPerPoint = value, asset -> asset.vitalityHpPerPoint).add()
                .append(new KeyedCodec<>("MaxGoldBalance", Codec.LONG),
                        (asset, value) -> asset.maxGoldBalance = value, asset -> asset.maxGoldBalance).add()
                .build();
    }

    /**
     * Returns the asset-store builder for registration in the plugin's {@code setup()}.
     *
     * @return a configured asset-store builder
     */
    @Nonnull
    public static HytaleAssetStore.Builder<String, RpgConfigAsset,
            IndexedLookupTableAssetMap<String, RpgConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        RpgConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(RpgConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(RpgConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    /**
     * Returns the loaded RPG config asset, or {@code null} if none is registered/loaded.
     *
     * @return the {@code RpgConfig} asset, or {@code null}
     */
    @Nullable
    public static RpgConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, RpgConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    /**
     * Returns the registered asset store.
     *
     * @return the asset store
     */
    @Nonnull
    public static AssetStore<String, RpgConfigAsset,
            IndexedLookupTableAssetMap<String, RpgConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(RpgConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "RpgConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    public int getMinStat() {
        return minStat;
    }

    public int getMaxStat() {
        return maxStat;
    }

    public float getSpeedBase() {
        return speedBase;
    }

    public float getSpeedMaxBonus() {
        return speedMaxBonus;
    }

    public float getSpeedHalfPoint() {
        return speedHalfPoint;
    }

    public float getStrengthMaxBonus() {
        return strengthMaxBonus;
    }

    public float getStrengthHalfPoint() {
        return strengthHalfPoint;
    }

    public float getLuckDropCoefficient() {
        return luckDropCoefficient;
    }

    public float getLuckDropExponent() {
        return luckDropExponent;
    }

    public int getLuckDropReference() {
        return luckDropReference;
    }

    public float getLuckDropMaxChance() {
        return luckDropMaxChance;
    }

    public float getStaminaPerPoint() {
        return staminaPerPoint;
    }

    public long getAgilityBaseThrottleNs() {
        return agilityBaseThrottleNs;
    }

    public float getAgilityMaxReduction() {
        return agilityMaxReduction;
    }

    public float getAgilityHalfPoint() {
        return agilityHalfPoint;
    }

    public long getAgilityMinThrottleNs() {
        return agilityMinThrottleNs;
    }

    public float getResistanceMaxDr() {
        return resistanceMaxDr;
    }

    public float getResistanceHalfPoint() {
        return resistanceHalfPoint;
    }

    public float getVitalityHpPerPoint() {
        return vitalityHpPerPoint;
    }

    public long getMaxGoldBalance() {
        return maxGoldBalance;
    }
}
