package com.duntale.zsquad.merchant;

import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ECS component attached to merchant NPC entities spawned in dungeons.
 * Stores the dungeon floor level so the merchant UI can filter its catalog.
 *
 * @since 1.3.0
 */
public class MerchantComponent implements Component<EntityStore> {

    private final int floorLevel;

    /**
     * No-arg constructor required by ECS component registration.
     */
    public MerchantComponent() {
        this.floorLevel = 1;
    }

    /**
     * Create a merchant component with a specific floor level.
     *
     * @param floorLevel the dungeon floor level for catalog filtering
     */
    public MerchantComponent(int floorLevel) {
        this.floorLevel = floorLevel;
    }

    /**
     * @return the dungeon floor level associated with this merchant
     */
    public int getFloorLevel() {
        return floorLevel;
    }

    /**
     * @return the registered ECS component type
     */
    @Nonnull
    public static ComponentType<EntityStore, MerchantComponent> getComponentType() {
        return ZSquadPlugin.get().getMerchantComponentType();
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new MerchantComponent(floorLevel);
    }
}
