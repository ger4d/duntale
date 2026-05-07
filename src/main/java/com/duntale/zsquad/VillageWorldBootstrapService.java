package com.duntale.zsquad;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class VillageWorldBootstrapService {

    static final String WORLD_NAME = "village";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String TEMPLATE_ASSET_NAME = "Village";
    private static final String GAMEPLAY_CONFIG_ID = "Village";
    private static final String TEMPLATE_CONFIG_FILE = "instance.bson";

    private final RuntimeAccess runtimeAccess;

    VillageWorldBootstrapService() {
        this(new LiveRuntimeAccess());
    }

    VillageWorldBootstrapService(@Nonnull RuntimeAccess runtimeAccess) {
        this.runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtimeAccess");
    }

    @Nonnull
    CompletableFuture<World> ensureVillageWorldReady() {
        World loadedWorld = runtimeAccess.getLoadedWorld(WORLD_NAME);
        if (loadedWorld != null) {
            LOGGER.atInfo().log("Village world %s already loaded; normalizing config", WORLD_NAME);
            return normalizeWorldConfig(loadedWorld);
        }

        if (runtimeAccess.isWorldLoadable(WORLD_NAME)) {
            LOGGER.atInfo().log("Loading saved village world %s", WORLD_NAME);
            return runtimeAccess.loadWorld(WORLD_NAME)
                    .thenCompose(this::normalizeWorldConfig);
        }

        return createVillageWorld();
    }

    @Nullable
    World getLoadedVillageWorld() {
        return runtimeAccess.getLoadedWorld(WORLD_NAME);
    }

    @Nonnull
    private CompletableFuture<World> createVillageWorld() {
        Path templatePath = runtimeAccess.resolveTemplatePath(TEMPLATE_ASSET_NAME);
        Path templateConfigPath = templatePath.resolve(TEMPLATE_CONFIG_FILE);
        if (!Files.isRegularFile(templateConfigPath)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Village template is missing " + TEMPLATE_CONFIG_FILE + ": " + templateConfigPath)
            );
        }

        Path worldPath = runtimeAccess.validateWorldPath(WORLD_NAME);
        LOGGER.atInfo().log("Creating village world %s from template %s", WORLD_NAME, TEMPLATE_ASSET_NAME);

        return runtimeAccess.loadConfig(templateConfigPath)
                .thenApply(this::prepareVillageConfig)
                .thenCompose(config -> copyTemplate(templatePath, worldPath)
                        .thenCompose(unused -> runtimeAccess.makeWorld(WORLD_NAME, worldPath, config)))
                .thenCompose(this::normalizeWorldConfig)
                .handle((world, throwable) -> handleCreateRace(throwable, world))
                .thenCompose(future -> future);
    }

    @Nonnull
    private CompletableFuture<World> handleCreateRace(@Nullable Throwable throwable, @Nullable World world) {
        if (throwable == null) {
            return CompletableFuture.completedFuture(Objects.requireNonNull(world, "world"));
        }

        Throwable cause = unwrap(throwable);
        World loadedWorld = runtimeAccess.getLoadedWorld(WORLD_NAME);
        if (loadedWorld != null) {
            LOGGER.atWarning().withCause(cause).log(
                    "Village world %s became available during bootstrap; reusing loaded world",
                    WORLD_NAME
            );
            return normalizeWorldConfig(loadedWorld);
        }

        if (runtimeAccess.isWorldLoadable(WORLD_NAME)) {
            LOGGER.atWarning().withCause(cause).log(
                    "Village world %s was created during bootstrap; loading saved world instead",
                    WORLD_NAME
            );
            return runtimeAccess.loadWorld(WORLD_NAME)
                    .thenCompose(this::normalizeWorldConfig);
        }

        return CompletableFuture.failedFuture(cause);
    }

    @Nonnull
    private CompletableFuture<Void> copyTemplate(@Nonnull Path templatePath, @Nonnull Path worldPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                try (var files = Files.walk(templatePath)) {
                    files.forEach(path -> copyTemplatePath(templatePath, worldPath, path));
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private void copyTemplatePath(@Nonnull Path templatePath, @Nonnull Path worldPath, @Nonnull Path path) {
        Path relativePath = templatePath.relativize(path);
        Path targetPath = worldPath.resolve(relativePath.toString());
        try {
            if (Files.isDirectory(path)) {
                Files.createDirectories(targetPath);
                return;
            }

            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    @Nonnull
    private WorldConfig prepareVillageConfig(@Nonnull WorldConfig config) {
        config.setUuid(UUID.randomUUID());
        if (config.getDisplayName() == null || config.getDisplayName().isBlank()) {
            config.setDisplayName("Village");
        }
        normalizeConfig(config);
        config.markChanged();
        return config;
    }

    @Nonnull
    private CompletableFuture<World> normalizeWorldConfig(@Nonnull World world) {
        CompletableFuture<World> future = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    WorldConfig config = world.getWorldConfig();
                    if (normalizeConfig(config)) {
                        config.markChanged();
                    }
                    future.complete(world);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private boolean normalizeConfig(@Nonnull WorldConfig config) {
        boolean changed = false;
        if (!GAMEPLAY_CONFIG_ID.equals(config.getGameplayConfig())) {
            config.setGameplayConfig(GAMEPLAY_CONFIG_ID);
            changed = true;
        }
        if (config.getGameMode() != GameMode.Adventure) {
            config.setGameMode(GameMode.Adventure);
            changed = true;
        }
        if (InstanceWorldConfig.get(config) != null) {
            config.getPluginConfig().remove(InstanceWorldConfig.class);
            changed = true;
        }
        if (config.isDeleteOnRemove()) {
            config.setDeleteOnRemove(false);
            changed = true;
        }
        return changed;
    }

    @Nonnull
    private static Throwable unwrap(@Nonnull Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return unwrap(completionException.getCause());
        }
        return throwable;
    }

    interface RuntimeAccess {

        @Nullable
        World getLoadedWorld(@Nonnull String worldName);

        boolean isWorldLoadable(@Nonnull String worldName);

        @Nonnull
        CompletableFuture<World> loadWorld(@Nonnull String worldName);

        @Nonnull
        Path validateWorldPath(@Nonnull String worldName);

        @Nonnull
        Path resolveTemplatePath(@Nonnull String templateAssetName);

        @Nonnull
        CompletableFuture<WorldConfig> loadConfig(@Nonnull Path configPath);

        @Nonnull
        CompletableFuture<World> makeWorld(
                @Nonnull String worldName,
                @Nonnull Path worldPath,
                @Nonnull WorldConfig config
        );
    }

    private static final class LiveRuntimeAccess implements RuntimeAccess {

        @Override
        @Nullable
        public World getLoadedWorld(@Nonnull String worldName) {
            Universe universe = Universe.get();
            return universe != null ? universe.getWorld(worldName) : null;
        }

        @Override
        public boolean isWorldLoadable(@Nonnull String worldName) {
            return requireUniverse().isWorldLoadable(worldName);
        }

        @Override
        @Nonnull
        public CompletableFuture<World> loadWorld(@Nonnull String worldName) {
            return requireUniverse().loadWorld(worldName);
        }

        @Override
        @Nonnull
        public Path validateWorldPath(@Nonnull String worldName) {
            return requireUniverse().validateWorldPath(worldName);
        }

        @Override
        @Nonnull
        public Path resolveTemplatePath(@Nonnull String templateAssetName) {
            return InstancesPlugin.getInstanceAssetPath(templateAssetName);
        }

        @Override
        @Nonnull
        public CompletableFuture<WorldConfig> loadConfig(@Nonnull Path configPath) {
            return WorldConfig.load(configPath);
        }

        @Override
        @Nonnull
        public CompletableFuture<World> makeWorld(
                @Nonnull String worldName,
                @Nonnull Path worldPath,
                @Nonnull WorldConfig config
        ) {
            return requireUniverse().makeWorld(worldName, worldPath, config);
        }

        @Nonnull
        private Universe requireUniverse() {
            Universe universe = Universe.get();
            if (universe == null) {
                throw new IllegalStateException("Universe is not available");
            }
            return universe;
        }
    }
}