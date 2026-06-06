package com.duntale.rpg;

import com.duntale.config.asset.RpgConfigAsset;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runtime holder for the tunable RPG config values.
 *
 * <p>Exposes the current values through the static {@link #values()} accessor backed by a
 * {@code volatile} reference, so reads are lock-free and always see a consistent immutable
 * snapshot. The snapshot is (re)built from {@link RpgConfigAsset} on the initial asset load and
 * on every hot reload, falling back to {@link RpgConfigValues#DEFAULTS} when no asset is present.
 *
 * <p>Lifecycle: construct in the plugin's {@code setup()} (registers the reload listener),
 * call {@link #refresh()} in {@code start()} (guaranteed initial population once asset stores are
 * loaded), and {@link #shutdown()} on plugin teardown.
 */
public final class RpgConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Current snapshot. Volatile so {@link #values()} publishes refreshes to all threads. */
    private static volatile RpgConfigValues current = RpgConfigValues.DEFAULTS;

    private final EventRegistry eventRegistry;

    @Nullable
    private Runnable reloadCallback;

    /**
     * Returns the current immutable snapshot of RPG config values. Never {@code null}.
     *
     * @return the live config snapshot
     */
    @Nonnull
    public static RpgConfigValues values() {
        return current;
    }

    /**
     * Creates the holder and subscribes to asset hot-reload events for {@link RpgConfigAsset}.
     */
    public RpgConfig() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "RpgConfig",
                HytaleServer.get().getEventBus());
        this.eventRegistry.enable();
        registerReloadListener();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReloadListener() {
        this.eventRegistry.registerGlobal((Class) LoadedAssetsEvent.class,
                (Consumer<LoadedAssetsEvent>) this::onAssetsLoaded);
    }

    private void onAssetsLoaded(@Nonnull LoadedAssetsEvent<?, ?, ?> event) {
        if (event.getAssetClass() != RpgConfigAsset.class) {
            return;
        }
        refresh();
        Runnable callback = this.reloadCallback;
        if (callback != null && !event.isInitial()) {
            callback.run();
        }
    }

    /**
     * Rebuilds the in-memory snapshot from the {@link RpgConfigAsset}, or resets it to defaults
     * when no asset is loaded. Safe to call from any thread — it only publishes a {@code volatile}
     * reference to an immutable snapshot.
     */
    public void refresh() {
        RpgConfigAsset asset = RpgConfigAsset.get();
        RpgConfigValues updated = asset != null ? RpgConfigValues.fromAsset(asset) : RpgConfigValues.DEFAULTS;
        current = updated;
        LOGGER.atInfo().log("RPG config %s (speedBase=%.2f, maxStat=%d, maxGoldBalance=%d)",
                asset != null ? "loaded from asset" : "reset to defaults",
                updated.speedBase(), updated.maxStat(), updated.maxGoldBalance());
    }

    /**
     * Sets a callback invoked after a hot reload (not the initial load). Used to re-apply
     * entity-stat-backed values (Vitality/Stamina) to already-online players.
     *
     * @param callback the callback to run on hot reload, or {@code null} to clear
     */
    public void setReloadCallback(@Nullable Runnable callback) {
        this.reloadCallback = callback;
    }

    /**
     * Deregisters the hot-reload listener. Call on plugin teardown.
     */
    public void shutdown() {
        this.eventRegistry.shutdownAndCleanup(false);
    }

    /** Test hook: installs a custom snapshot. */
    static void installForTest(@Nonnull RpgConfigValues values) {
        current = values;
    }

    /** Test hook: resets the snapshot to {@link RpgConfigValues#DEFAULTS}. */
    static void resetForTest() {
        current = RpgConfigValues.DEFAULTS;
    }
}
