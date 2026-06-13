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
 * Asset-store wrapper for the NPC archetype-anchor mapping.
 *
 * <p>Backed by a single JSON asset at {@code Server/Configs/Scaling/NpcArchetypes.json}. It carries
 * two arrays: the six archetype anchors ({@code Archetypes}) and the per-role mapping
 * ({@code Roles}). The mapping normalizes each enemy NPC's HP and damage to its archetype anchor
 * plus a small (&plusmn;15%) flavor offset, so enemies of the same archetype stay comparable
 * regardless of their authored asset stats; unmapped roles keep the legacy asset-base scaling.
 *
 * <p>Hot reloads are observed by {@code NpcArchetypeRegistry} via {@code LoadedAssetsEvent}, mirroring
 * {@link RpgConfigAsset}/{@code RpgConfig}. The committed JSON is produced by
 * {@code scripts/scaling/derive_archetypes.py}.
 */
public class NpcArchetypeConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, NpcArchetypeConfigAsset>> {

    public static final String ASSET_PATH = "Configs/Scaling";
    private static final String ASSET_ID = "NpcArchetypes";

    public static AssetBuilderCodec<String, NpcArchetypeConfigAsset> CODEC;
    private static AssetStore<String, NpcArchetypeConfigAsset,
            IndexedLookupTableAssetMap<String, NpcArchetypeConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected ArchetypeEntry[] archetypes = new ArchetypeEntry[0];
    protected RoleEntry[] roles = new RoleEntry[0];

    public NpcArchetypeConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        NpcArchetypeConfigAsset.class,
                        NpcArchetypeConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("Archetypes", ArchetypeEntry.ARRAY_CODEC),
                        (asset, value) -> asset.archetypes = value,
                        asset -> asset.archetypes)
                .add()
                .append(new KeyedCodec<>("Roles", RoleEntry.ARRAY_CODEC),
                        (asset, value) -> asset.roles = value,
                        asset -> asset.roles)
                .add()
                .build();
    }

    /**
     * Returns the asset-store builder for registration in the plugin's {@code setup()}.
     *
     * @return a configured asset-store builder
     */
    @Nonnull
    public static HytaleAssetStore.Builder<String, NpcArchetypeConfigAsset,
            IndexedLookupTableAssetMap<String, NpcArchetypeConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        NpcArchetypeConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(NpcArchetypeConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(NpcArchetypeConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    /**
     * Returns the loaded NPC archetype asset, or {@code null} if none is registered/loaded.
     *
     * @return the {@code NpcArchetypes} asset, or {@code null}
     */
    @Nullable
    public static NpcArchetypeConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, NpcArchetypeConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    /**
     * Returns the registered asset store.
     *
     * @return the asset store
     */
    @Nonnull
    public static AssetStore<String, NpcArchetypeConfigAsset,
            IndexedLookupTableAssetMap<String, NpcArchetypeConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(NpcArchetypeConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "NpcArchetypeConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the configured archetype anchors.
     *
     * @return a defensive copy of the configured archetype entries
     */
    @Nonnull
    public ArchetypeEntry[] getArchetypes() {
        return archetypes.clone();
    }

    /**
     * Returns the configured per-role mappings.
     *
     * @return a defensive copy of the configured role entries
     */
    @Nonnull
    public RoleEntry[] getRoles() {
        return roles.clone();
    }

    // ============================================
    // Nested codec DTOs
    // ============================================

    /**
     * One archetype anchor: a base HP and base damage at level 1, scaled at runtime by the
     * existing {@code CombatScaling} sigmoid curves.
     */
    public static class ArchetypeEntry {
        public static final BuilderCodec<ArchetypeEntry> CODEC;
        public static final ArrayCodec<ArchetypeEntry> ARRAY_CODEC;

        protected String name = "";
        protected int baseHp = 0;
        protected float baseDamage = 0f;

        static {
            CODEC = BuilderCodec.builder(ArchetypeEntry.class, ArchetypeEntry::new)
                    .append(new KeyedCodec<>("Name", Codec.STRING),
                            (e, v) -> e.name = v, e -> e.name)
                    .add()
                    .append(new KeyedCodec<>("BaseHp", Codec.INTEGER),
                            (e, v) -> e.baseHp = v, e -> e.baseHp)
                    .add()
                    .append(new KeyedCodec<>("BaseDamage", Codec.FLOAT),
                            (e, v) -> e.baseDamage = v, e -> e.baseDamage)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, ArchetypeEntry[]::new);
        }

        public ArchetypeEntry() {
        }

        /**
         * Creates an archetype anchor entry (test/programmatic use).
         *
         * @param name       the archetype name
         * @param baseHp     the level-1 base HP anchor
         * @param baseDamage the level-1 base damage anchor
         */
        public ArchetypeEntry(@Nonnull String name, int baseHp, float baseDamage) {
            this.name = name;
            this.baseHp = baseHp;
            this.baseDamage = baseDamage;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        public int getBaseHp() {
            return baseHp;
        }

        public float getBaseDamage() {
            return baseDamage;
        }
    }

    /**
     * One role &rarr; archetype mapping with per-role flavor offsets and the offline-parsed asset
     * base damage used to correct the engine's per-attack damage to the archetype average.
     */
    public static class RoleEntry {
        public static final BuilderCodec<RoleEntry> CODEC;
        public static final ArrayCodec<RoleEntry> ARRAY_CODEC;

        protected String role = "";
        protected String archetype = "";
        protected float hpOffset = 0f;
        protected float damageOffset = 0f;
        protected float assetBaseDamage = 0f;

        static {
            CODEC = BuilderCodec.builder(RoleEntry.class, RoleEntry::new)
                    .append(new KeyedCodec<>("Role", Codec.STRING),
                            (e, v) -> e.role = v, e -> e.role)
                    .add()
                    .append(new KeyedCodec<>("Archetype", Codec.STRING),
                            (e, v) -> e.archetype = v, e -> e.archetype)
                    .add()
                    .append(new KeyedCodec<>("HpOffset", Codec.FLOAT),
                            (e, v) -> e.hpOffset = v, e -> e.hpOffset)
                    .add()
                    .append(new KeyedCodec<>("DamageOffset", Codec.FLOAT),
                            (e, v) -> e.damageOffset = v, e -> e.damageOffset)
                    .add()
                    .append(new KeyedCodec<>("AssetBaseDamage", Codec.FLOAT),
                            (e, v) -> e.assetBaseDamage = v, e -> e.assetBaseDamage)
                    .add()
                    .build();
            ARRAY_CODEC = new ArrayCodec<>(CODEC, RoleEntry[]::new);
        }

        public RoleEntry() {
        }

        /**
         * Creates a role-mapping entry (test/programmatic use).
         *
         * @param role            the NPC role name
         * @param archetype       the archetype name this role maps to
         * @param hpOffset        the per-role HP flavor offset
         * @param damageOffset    the per-role damage flavor offset
         * @param assetBaseDamage the offline-parsed average asset attack damage
         */
        public RoleEntry(@Nonnull String role, @Nonnull String archetype,
                         float hpOffset, float damageOffset, float assetBaseDamage) {
            this.role = role;
            this.archetype = archetype;
            this.hpOffset = hpOffset;
            this.damageOffset = damageOffset;
            this.assetBaseDamage = assetBaseDamage;
        }

        @Nonnull
        public String getRole() {
            return role;
        }

        @Nonnull
        public String getArchetype() {
            return archetype;
        }

        public float getHpOffset() {
            return hpOffset;
        }

        public float getDamageOffset() {
            return damageOffset;
        }

        public float getAssetBaseDamage() {
            return assetBaseDamage;
        }
    }
}
