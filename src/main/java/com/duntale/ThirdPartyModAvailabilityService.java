package com.duntale;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Resolves whether third-party mods are available for runtime integrations.
 *
 * <p>A mod is considered available when its plugin is loaded. Callers may also
 * provide a sentinel item ID when the integration depends on the mod's asset
 * pack being present and resolvable.
 */
public class ThirdPartyModAvailabilityService {

    private final BiPredicate<PluginIdentifier, String> availabilityCheck;

    /**
     * Creates a service that checks the live server plugin registry.
     */
    public ThirdPartyModAvailabilityService() {
        this(ThirdPartyModAvailabilityService::detectAvailability);
    }

    /**
     * Creates a service with a custom availability resolver.
     *
     * @param availabilityCheck the resolver used to answer availability checks
     */
    public ThirdPartyModAvailabilityService(@Nonnull BiPredicate<PluginIdentifier, String> availabilityCheck) {
        this.availabilityCheck = Objects.requireNonNull(availabilityCheck, "availabilityCheck");
    }

    /**
     * Returns whether the given third-party mod is available.
     *
     * @param pluginIdentifier    the third-party plugin identifier
     * @param sentinelItemId      an optional item asset ID that must resolve when the integration requires assets
     * @return {@code true} when the plugin is loaded and the sentinel asset resolves if provided
     */
    public boolean isAvailable(@Nonnull PluginIdentifier pluginIdentifier,
                               @Nullable String sentinelItemId) {
        return availabilityCheck.test(pluginIdentifier, sentinelItemId);
    }

    private static boolean detectAvailability(@Nonnull PluginIdentifier pluginIdentifier,
                                              @Nullable String sentinelItemId) {
        PluginManager pluginManager = PluginManager.get();
        if (pluginManager == null || pluginManager.getPlugin(pluginIdentifier) == null) {
            return false;
        }

        if (sentinelItemId == null || sentinelItemId.isBlank()) {
            return true;
        }

        var assetStore = Item.getAssetStore();
        if (assetStore == null || assetStore.getAssetMap() == null) {
            return false;
        }

        return assetStore.getAssetMap().getAsset(sentinelItemId) != null;
    }
}