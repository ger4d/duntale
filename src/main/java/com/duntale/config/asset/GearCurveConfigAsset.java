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
 * Asset-store wrapper for the authored gear-power curves.
 *
 * <p>Backed by a single JSON asset at {@code Server/Configs/Scaling/GearCurves.json}. It severs the
 * coupling between an item's hand-authored asset stats and its in-game power: weapon per-hit and
 * armor damage reduction are driven by gear <em>level</em> (plus an optional rarity nudge) rather
 * than each item's individual asset numbers. This normalizes outliers (e.g. a "Common" longsword
 * authored at 223 damage) to its family anchor, and lets third-party items inherit sane power from
 * their stamped level alone.
 *
 * <p>The asset carries:
 * <ul>
 *   <li>{@code WeaponFamilies} &mdash; per-family level-1 per-hit anchor. All melee families share one
 *       anchor (melee attack cadence is a single Agility-driven throttle, identical for every weapon,
 *       so equal per-hit means equal DPS); ranged families carry their own anchor.</li>
 *   <li>{@code RarityNudges} &mdash; small per-rarity multipliers (a tight ladder by design).</li>
 *   <li>{@code ArmorSlots} &mdash; each slot's share of the total on-level DR budget.</li>
 *   <li>{@code DefaultWeaponAnchor} &mdash; fallback per-hit for unmapped families.</li>
 *   <li>{@code ArmorDrBudgetMin}/{@code ArmorDrBudgetMax} &mdash; total on-level DR at the level floor
 *       and ceiling; the combined per-piece sum is still capped downstream.</li>
 * </ul>
 *
 * <p>Hot reloads are observed by {@code GearCurveRegistry} via {@code LoadedAssetsEvent}, mirroring
 * {@link NpcArchetypeConfigAsset}. The committed JSON is produced by
 * {@code scripts/scaling/derive_gear_curves.py}.
 */
public class GearCurveConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, GearCurveConfigAsset>> {

    public static final String ASSET_PATH = "Configs/Scaling";
    private static final String ASSET_ID = "GearCurves";

    public static AssetBuilderCodec<String, GearCurveConfigAsset> CODEC;
    private static AssetStore<String, GearCurveConfigAsset,
            IndexedLookupTableAssetMap<String, GearCurveConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected float defaultWeaponAnchor = 0f;
    protected float armorDrBudgetMin = 0f;
    protected float armorDrBudgetMax = 0f;
    protected WeaponFamilyEntry[] weaponFamilies = new WeaponFamilyEntry[0];
    protected RarityNudgeEntry[] rarityNudges = new RarityNudgeEntry[0];
    protected ArmorSlotEntry[] armorSlots = new ArmorSlotEntry[0];

    public GearCurveConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        GearCurveConfigAsset.class,
                        GearCurveConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("DefaultWeaponAnchor", Codec.FLOAT),
                        (asset, value) -> asset.defaultWeaponAnchor = value,
                        asset -> asset.defaultWeaponAnchor)
                .add()
                .append(new KeyedCodec<>("ArmorDrBudgetMin", Codec.FLOAT),
                        (asset, value) -> asset.armorDrBudgetMin = value,
                        asset -> asset.armorDrBudgetMin)
                .add()
                .append(new KeyedCodec<>("ArmorDrBudgetMax", Codec.FLOAT),
                        (asset, value) -> asset.armorDrBudgetMax = value,
                        asset -> asset.armorDrBudgetMax)
                .add()
                .append(new KeyedCodec<>("WeaponFamilies", WeaponFamilyEntry.ARRAY_CODEC),
                        (asset, value) -> asset.weaponFamilies = value,
                        asset -> asset.weaponFamilies)
                .add()
                .append(new KeyedCodec<>("RarityNudges", RarityNudgeEntry.ARRAY_CODEC),
                        (asset, value) -> asset.rarityNudges = value,
                        asset -> asset.rarityNudges)
                .add()
                .append(new KeyedCodec<>("ArmorSlots", ArmorSlotEntry.ARRAY_CODEC),
                        (asset, value) -> asset.armorSlots = value,
                        asset -> asset.armorSlots)
                .add()
                .build();
    }

    /**
     * Returns the asset-store builder for registration in the plugin's {@code setup()}.
     *
     * @return a configured asset-store builder
     */
    @Nonnull
    public static HytaleAssetStore.Builder<String, GearCurveConfigAsset,
            IndexedLookupTableAssetMap<String, GearCurveConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        GearCurveConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(GearCurveConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(GearCurveConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    /**
     * Returns the loaded gear curve asset, or {@code null} if none is registered/loaded.
     *
     * @return the {@code GearCurves} asset, or {@code null}
     */
    @Nullable
    public static GearCurveConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, GearCurveConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    /**
     * Returns the registered asset store.
     *
     * @return the asset store
     */
    @Nonnull
    public static AssetStore<String, GearCurveConfigAsset,
            IndexedLookupTableAssetMap<String, GearCurveConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(GearCurveConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "GearCurveConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the level-1 per-hit anchor used for weapon families not listed in
     * {@link #getWeaponFamilies()}.
     *
     * @return the default weapon anchor
     */
    public float getDefaultWeaponAnchor() {
        return defaultWeaponAnchor;
    }

    /**
     * Returns the total on-level damage reduction at the level floor (level 1).
     *
     * @return the minimum DR budget
     */
    public float getArmorDrBudgetMin() {
        return armorDrBudgetMin;
    }

    /**
     * Returns the total on-level damage reduction at the level ceiling.
     *
     * @return the maximum DR budget
     */
    public float getArmorDrBudgetMax() {
        return armorDrBudgetMax;
    }

    /**
     * Returns the configured weapon family anchors.
     *
     * @return a defensive copy of the configured weapon family entries
     */
    @Nonnull
    public WeaponFamilyEntry[] getWeaponFamilies() {
        return weaponFamilies.clone();
    }

    /**
     * Returns the configured rarity nudges.
     *
     * @return a defensive copy of the configured rarity nudge entries
     */
    @Nonnull
    public RarityNudgeEntry[] getRarityNudges() {
        return rarityNudges.clone();
    }

    /**
     * Returns the configured armor slot DR shares.
     *
     * @return a defensive copy of the configured armor slot entries
     */
    @Nonnull
    public ArmorSlotEntry[] getArmorSlots() {
        return armorSlots.clone();
    }

    // ============================================
    // Nested codec DTOs
    // ============================================

    /**
     * One weapon family's level-1 per-hit anchor, scaled at runtime by the existing
     * {@code CombatScaling.weaponMult} level curve.
     */
    public static class WeaponFamilyEntry {
        public static final BuilderCodec<WeaponFamilyEntry> CODEC;
        public static final ArrayCodec<WeaponFamilyEntry> ARRAY_CODEC;

        protected String name = "";
        protected float anchorDamage = 0f;

        static {
            CODEC = BuilderCodec.builder(WeaponFamilyEntry.class, WeaponFamilyEntry::new)
                    .append(new KeyedCodec<>("Name", Codec.STRING),
                            (e, v) -> e.name = v, e -> e.name)
                    .add()
                    .append(new KeyedCodec<>("AnchorDamage", Codec.FLOAT),
                            (e, v) -> e.anchorDamage = v, e -> e.anchorDamage)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, WeaponFamilyEntry[]::new);
        }

        public WeaponFamilyEntry() {
        }

        /**
         * Creates a weapon family anchor entry (test/programmatic use).
         *
         * @param name         the weapon family name (e.g. "Sword")
         * @param anchorDamage the level-1 per-hit anchor
         */
        public WeaponFamilyEntry(@Nonnull String name, float anchorDamage) {
            this.name = name;
            this.anchorDamage = anchorDamage;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        public float getAnchorDamage() {
            return anchorDamage;
        }
    }

    /**
     * One rarity tier's power multiplier (a deliberately tight ladder). Inert until items are
     * stamped with a rarity; everything resolves to the {@code 1.0} default in the interim.
     */
    public static class RarityNudgeEntry {
        public static final BuilderCodec<RarityNudgeEntry> CODEC;
        public static final ArrayCodec<RarityNudgeEntry> ARRAY_CODEC;

        protected String rarity = "";
        protected float multiplier = 1.0f;

        static {
            CODEC = BuilderCodec.builder(RarityNudgeEntry.class, RarityNudgeEntry::new)
                    .append(new KeyedCodec<>("Rarity", Codec.STRING),
                            (e, v) -> e.rarity = v, e -> e.rarity)
                    .add()
                    .append(new KeyedCodec<>("Multiplier", Codec.FLOAT),
                            (e, v) -> e.multiplier = v, e -> e.multiplier)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, RarityNudgeEntry[]::new);
        }

        public RarityNudgeEntry() {
        }

        /**
         * Creates a rarity nudge entry (test/programmatic use).
         *
         * @param rarity     the rarity name (e.g. "Legendary")
         * @param multiplier the power multiplier for that rarity
         */
        public RarityNudgeEntry(@Nonnull String rarity, float multiplier) {
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

    /**
     * One armor slot's share of the total on-level DR budget. Shares are expected to sum to
     * {@code 1.0} across a full set, so a complete on-level set lands on the budget curve.
     */
    public static class ArmorSlotEntry {
        public static final BuilderCodec<ArmorSlotEntry> CODEC;
        public static final ArrayCodec<ArmorSlotEntry> ARRAY_CODEC;

        protected String slot = "";
        protected float drShare = 0f;

        static {
            CODEC = BuilderCodec.builder(ArmorSlotEntry.class, ArmorSlotEntry::new)
                    .append(new KeyedCodec<>("Slot", Codec.STRING),
                            (e, v) -> e.slot = v, e -> e.slot)
                    .add()
                    .append(new KeyedCodec<>("DrShare", Codec.FLOAT),
                            (e, v) -> e.drShare = v, e -> e.drShare)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, ArmorSlotEntry[]::new);
        }

        public ArmorSlotEntry() {
        }

        /**
         * Creates an armor slot share entry (test/programmatic use).
         *
         * @param slot    the armor slot name (e.g. "Chest")
         * @param drShare the slot's share of the total DR budget
         */
        public ArmorSlotEntry(@Nonnull String slot, float drShare) {
            this.slot = slot;
            this.drShare = drShare;
        }

        @Nonnull
        public String getSlot() {
            return slot;
        }

        public float getDrShare() {
            return drShare;
        }
    }
}
