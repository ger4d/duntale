package com.duntale.merchant;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * ECS component attached to merchant NPC entities spawned in dungeons.
 * Stores the dungeon floor level and the lazily-generated catalog.
 *
 * @since 1.3.0
 */
public class MerchantComponent implements Component<EntityStore> {

    /** Codec for serialization during chunk save/load. Persists floor level and catalog type; catalog is regenerated. */
    @Nonnull
    public static final BuilderCodec<MerchantComponent> CODEC = BuilderCodec.builder(
                    MerchantComponent.class, MerchantComponent::new)
            .append(new KeyedCodec<>("FloorLevel", Codec.INTEGER),
                    (c, v) -> c.floorLevel = v, c -> c.floorLevel)
            .add()
            .append(new KeyedCodec<>("CatalogType", Codec.STRING),
                    (c, v) -> c.catalogType = v != null ? v : "STANDARD", c -> c.catalogType)
            .add()
            .build();

    private int floorLevel;
    private String catalogType = "STANDARD";

    /** Lazily generated catalog — {@code null} until first merchant interaction. */
    @Nullable
    private List<CatalogEntry> catalog;

    /**
     * No-arg constructor required by ECS component registration.
     */
    public MerchantComponent() {
        this.floorLevel = 1;
        this.catalogType = "STANDARD";
    }

    /**
     * Create a merchant component with a specific floor level.
     *
     * @param floorLevel the dungeon floor level for catalog filtering
     */
    public MerchantComponent(int floorLevel) {
        this.floorLevel = floorLevel;
        this.catalogType = "STANDARD";
    }

    /**
     * Create a merchant component with a specific floor level and catalog type.
     *
     * @param floorLevel  the dungeon floor level for catalog filtering
     * @param catalogType the catalog type (e.g. "STANDARD", "VILLAGE")
     */
    public MerchantComponent(int floorLevel, String catalogType) {
        this.floorLevel = floorLevel;
        this.catalogType = catalogType != null ? catalogType : "STANDARD";
    }

    /**
     * @return the dungeon floor level associated with this merchant
     */
    public int getFloorLevel() {
        return floorLevel;
    }

    /**
     * Returns whether this merchant has a generated catalog.
     *
     * @return {@code true} if the catalog has been generated
     */
    public boolean hasCatalog() {
        return catalog != null && !catalog.isEmpty();
    }

    /**
     * Returns the merchant's catalog, or {@code null} if not yet generated.
     *
     * @return the catalog entries, or {@code null}
     */
    @Nullable
    public List<CatalogEntry> getCatalog() {
        return catalog;
    }

    /**
     * Stores the generated catalog on this merchant.
     *
     * @param catalog the catalog entries
     */
    public void setCatalog(@Nonnull List<CatalogEntry> catalog) {
        this.catalog = List.copyOf(catalog);
    }

    /**
     * @return the catalog type (e.g. "STANDARD", "VILLAGE")
     */
    @Nonnull
    public String getCatalogType() {
        return catalogType != null ? catalogType : "STANDARD";
    }

    /**
     * Sets the catalog type.
     *
     * @param catalogType the catalog type
     */
    public void setCatalogType(@Nonnull String catalogType) {
        this.catalogType = catalogType;
    }

    /**
     * @return the registered ECS component type
     */
    @Nonnull
    public static ComponentType<EntityStore, MerchantComponent> getComponentType() {
        return DuntalePlugin.get().getMerchantComponentType();
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        MerchantComponent copy = new MerchantComponent(floorLevel, catalogType);
        if (catalog != null) {
            copy.catalog = catalog; // Already immutable from setCatalog
        }
        return copy;
    }
}
