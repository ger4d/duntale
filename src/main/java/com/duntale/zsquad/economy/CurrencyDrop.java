package com.duntale.zsquad.economy;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Singleton ECS marker component for gold item entities.
 *
 * <p>Attached to item entities spawned by the loot system that represent
 * gold coins. The {@code GoldPickupSystem} queries for entities with this
 * component to convert them to currency balance.
 */
public final class CurrencyDrop implements Component<EntityStore> {

    public static final CurrencyDrop INSTANCE = new CurrencyDrop();

    private static ComponentType<EntityStore, CurrencyDrop> componentType;

    private CurrencyDrop() {}

    /** Singleton — clone returns the same instance. */
    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return INSTANCE;
    }

    /**
     * Sets the component type after ECS registration.
     *
     * @param type the registered component type
     */
    public static void setComponentType(@Nonnull ComponentType<EntityStore, CurrencyDrop> type) {
        componentType = type;
    }

    /**
     * Returns the registered component type.
     *
     * @return the component type
     * @throws IllegalStateException if not yet registered
     */
    @Nonnull
    public static ComponentType<EntityStore, CurrencyDrop> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("CurrencyDrop component type not registered");
        }
        return componentType;
    }
}
