package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.generator.GenerationOrchestrator;
import com.duntale.dungeongen.generator.GenerationResult;
import com.duntale.zsquad.ZSquadPlugin;
import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.VoidWorldGenProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * Orchestrates dungeon instance lifecycle: creation, floor transitions, end, and runtime lookups.
 *
 * <p>This service owns the one-active-instance constraint: a player may only belong to one
 * non-{@code ENDED} instance at a time. Roster validation is performed transactionally
 * to prevent concurrent start attempts from bypassing the rule.
 *
 * @since 1.6.0
 */
public class DungeonInstanceService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String INSTANCE_WORLD_PREFIX = "dungeon-";
    private static final int DEFAULT_INSTANCE_ORIGIN_Y = DungeonConfig.withDefaults().origin().y();

    // ============================================
    // Fields
    // ============================================

    private final DatabaseProvider database;
    private final DungeonInstanceRepository instanceRepository;
    private final DungeonMembershipRepository membershipRepository;
    private final PartyService partyService;
    private final RuntimeAdapter runtimeAdapter;

    // ============================================
    // Constructor
    // ============================================

    /**
     * Creates a new dungeon instance service with an internal party service and live Hytale runtime hooks.
     *
     * <p>Callers that need shared party state should prefer
     * {@link #DungeonInstanceService(DatabaseProvider, DungeonInstanceRepository,
     * DungeonMembershipRepository, PartyService)}.
     *
     * @param database             the database provider for transactional operations
     * @param instanceRepository   the dungeon instance repository
     * @param membershipRepository the dungeon membership repository
     */
    public DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository
    ) {
        this(database, instanceRepository, membershipRepository, new PartyService());
    }

    /**
     * Creates a new dungeon instance service backed by the given shared party service.
     *
     * @param database             the database provider for transactional operations
     * @param instanceRepository   the dungeon instance repository
     * @param membershipRepository the dungeon membership repository
     * @param partyService         the shared party service used to assemble starting rosters
     */
    public DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository,
            @Nonnull PartyService partyService
    ) {
        this(database, instanceRepository, membershipRepository, partyService, new LiveRuntimeAdapter());
    }

    DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository,
            @Nonnull PartyService partyService,
            @Nonnull RuntimeAdapter runtimeAdapter
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.instanceRepository = Objects.requireNonNull(instanceRepository, "instanceRepository");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
    }

    // ============================================
    // Public API
    // ============================================

    /**
     * Validates that no player in the given roster already belongs to a non-{@code ENDED}
     * dungeon instance, then atomically persists a new instance row and membership rows.
     *
     * <p>The entire check-and-persist sequence runs inside a single database transaction
     * so that concurrent start attempts for the same player are serialized and the second
     * attempt sees the first's membership rows.
     *
     * @param instance  the dungeon instance metadata to persist
     * @param playerIds the roster of player UUIDs to validate and register
     * @throws RosterValidationException if any player already belongs to a non-ended instance
     * @throws SQLException              if a database access error occurs (transaction is rolled back)
     */
    public void createInstance(@Nonnull DungeonInstance instance, @Nonnull Collection<UUID> playerIds)
            throws SQLException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            throw new IllegalArgumentException("playerIds must not be empty");
        }

        database.transaction(conn -> {
            validateRosterInTransaction(conn, playerIds);
            instanceRepository.createInTransaction(conn, instance);
            membershipRepository.addMembershipsInTransaction(conn, instance.instanceId(), playerIds);
            LOGGER.at(Level.INFO).log("Instance %s created with %d roster members",
                    instance.instanceId(), playerIds.size());
        });
    }

    /**
     * Starts a new single-floor dungeon instance for the provided roster.
     *
     * <p>The service first reserves the roster transactionally in {@code CREATING} state,
     * then creates the per-instance world, runs dungeon generation + assembly, persists the
     * generated entrance/exit metadata while staying in {@code CREATING}, and only marks the
     * instance {@code ACTIVE} after the initial roster transfer phase completes.
     *
     * @param playerIds  the starting roster
     * @param floorLevel the floor number to generate
     * @param theme      the theme identifier / palette name
     * @return a future that completes with the activated dungeon instance metadata
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> createInstance(
            @Nonnull Collection<UUID> playerIds,
            int floorLevel,
            @Nonnull String theme
    ) {
        Objects.requireNonNull(playerIds, "playerIds");
        Objects.requireNonNull(theme, "theme");
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }

        Set<UUID> roster = normalizeRoster(playerIds);
        String normalizedTheme = normalizeTheme(theme);
        Vec3i origin = new Vec3i(0, DEFAULT_INSTANCE_ORIGIN_Y, 0);
        String instanceId = UUID.randomUUID().toString();
        String worldName = INSTANCE_WORLD_PREFIX + instanceId;
        DungeonInstance pendingInstance = new DungeonInstance(
                instanceId,
                worldName,
                floorLevel,
                origin.y(),
                origin,
                origin,
                DungeonInstanceState.CREATING,
                normalizedTheme,
                null,
                System.currentTimeMillis()
        );

        LOGGER.at(Level.INFO).log(
                "Starting dungeon instance %s for %d players (floor=%d, theme=%s)",
                instanceId,
                roster.size(),
                floorLevel,
                normalizedTheme
        );

        try {
            createInstance(pendingInstance, roster);
        } catch (SQLException | RosterValidationException e) {
            return CompletableFuture.failedFuture(e);
        }

        DungeonConfig config = buildGenerationConfig(worldName, floorLevel, origin, normalizedTheme);
        return runtimeAdapter.createWorld(worldName, floorLevel, pendingInstance.seed(), origin)
                .thenCompose(world -> runtimeAdapter.generate(config)
                        .thenCompose(result -> activateInstance(world, pendingInstance, roster, origin, result)))
                .exceptionallyCompose(throwable -> handleCreationFailure(pendingInstance, throwable));
    }

    /**
     * Starts a new single-floor dungeon instance for the given player, using their current party
     * roster when present and a solo roster otherwise.
     *
     * @param playerId   the initiating player UUID
     * @param floorLevel the floor number to generate
     * @param theme      the theme identifier / palette name
     * @return a future that completes with the activated dungeon instance metadata
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> createInstanceForPlayer(
            @Nonnull UUID playerId,
            int floorLevel,
            @Nonnull String theme
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return createInstance(partyService.assembleRoster(playerId), floorLevel, theme);
    }

    // ============================================
    // Internal
    // ============================================

    /**
     * Validates that no player in the given roster already belongs to a non-{@code ENDED} instance.
     *
     * <p>Must be called within the same database transaction as instance creation and
     * membership insertion to prevent concurrent starts from bypassing the constraint.
     *
     * @param conn      the active JDBC connection from the enclosing transaction
     * @param playerIds the player UUIDs to validate
     * @throws RosterValidationException if any player already belongs to a non-ended instance
     * @throws SQLException              if a database access error occurs
     */
    void validateRosterInTransaction(@Nonnull Connection conn, @Nonnull Collection<UUID> playerIds)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(playerIds, "playerIds");
        Set<UUID> blocked = membershipRepository.findPlayersWithNonEndedInstanceInTransaction(conn, playerIds);
        if (!blocked.isEmpty()) {
            LOGGER.at(Level.WARNING).log(
                    "Roster validation failed — players already in active instance: %s", blocked);
            throw new RosterValidationException(blocked);
        }
    }

    @Nonnull
    private CompletableFuture<DungeonInstance> activateInstance(
            @Nonnull InstanceWorld world,
            @Nonnull DungeonInstance pendingInstance,
            @Nonnull Set<UUID> roster,
            @Nonnull Vec3i origin,
            @Nonnull GenerationResult result
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pendingInstance, "pendingInstance");
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(result, "result");

        if (result.assemblyError() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(result.assemblyError()));
        }

        Vec3i entrancePosition = translateGeneratedPosition(
                origin,
                requireGeneratedPosition("entrancePosition", result.entrancePosition())
        );
        Vec3i exitPosition = translateGeneratedPosition(
                origin,
                requireGeneratedPosition("exitPosition", result.exitPosition())
        );

        DungeonInstance readyInstance = new DungeonInstance(
                pendingInstance.instanceId(),
                world.worldName(),
                pendingInstance.floorLevel(),
                origin.y(),
                entrancePosition,
                exitPosition,
                DungeonInstanceState.CREATING,
                pendingInstance.theme(),
                pendingInstance.seed(),
                pendingInstance.createdAt()
        );

        DungeonInstance activeInstance = new DungeonInstance(
                pendingInstance.instanceId(),
                world.worldName(),
                pendingInstance.floorLevel(),
                origin.y(),
                entrancePosition,
                exitPosition,
                DungeonInstanceState.ACTIVE,
                pendingInstance.theme(),
                pendingInstance.seed(),
                pendingInstance.createdAt()
        );

        return runtimeAdapter.finalizeWorld(world, origin, entrancePosition, result)
                .thenCompose(unused -> updatePersistedInstance(readyInstance))
                .thenCompose(unused -> runtimeAdapter.teleportRoster(roster, world, entrancePosition))
                .thenCompose(unused -> updatePersistedInstance(activeInstance).thenApply(ignored -> activeInstance))
                .thenApply(instance -> {
                    LOGGER.at(Level.INFO).log(
                            "Dungeon instance %s activated in world %s",
                            instance.instanceId(),
                            instance.worldName()
                    );
                    return instance;
                });
    }

    @Nonnull
    private CompletableFuture<Void> updatePersistedInstance(@Nonnull DungeonInstance instance) {
        Objects.requireNonNull(instance, "instance");
        try {
            instanceRepository.update(instance);
            return CompletableFuture.completedFuture(null);
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Nonnull
    private <T> CompletableFuture<T> handleCreationFailure(
            @Nonnull DungeonInstance pendingInstance,
            @Nonnull Throwable throwable
    ) {
        Objects.requireNonNull(pendingInstance, "pendingInstance");
        Objects.requireNonNull(throwable, "throwable");

        Throwable failure = unwrapFailure(throwable);
        LOGGER.at(Level.WARNING)
                .withCause(failure)
                .log("Dungeon instance %s failed during creation: %s",
                        pendingInstance.instanceId(),
                        describeFailure(failure));

        try {
            runtimeAdapter.cleanupWorld(pendingInstance.worldName());
        } catch (Exception cleanupError) {
            failure.addSuppressed(cleanupError);
            LOGGER.at(Level.WARNING)
                    .withCause(cleanupError)
                    .log("Failed to clean up world %s after instance creation failure",
                            pendingInstance.worldName());
        }

        try {
            instanceRepository.endInstance(pendingInstance.instanceId());
        } catch (SQLException endError) {
            failure.addSuppressed(endError);
            LOGGER.at(Level.SEVERE)
                    .withCause(endError)
                    .log("Failed to mark dungeon instance %s as ended after creation failure",
                            pendingInstance.instanceId());
        }

        return CompletableFuture.failedFuture(failure);
    }

    @Nonnull
    private static Set<UUID> normalizeRoster(@Nonnull Collection<UUID> playerIds) {
        LinkedHashSet<UUID> roster = new LinkedHashSet<>();
        for (UUID playerId : playerIds) {
            roster.add(Objects.requireNonNull(playerId, "playerIds contains null"));
        }
        if (roster.isEmpty()) {
            throw new IllegalArgumentException("playerIds must not be empty");
        }
        return Set.copyOf(roster);
    }

    @Nonnull
    private static String normalizeTheme(@Nonnull String theme) {
        String trimmed = theme.trim();
        if (trimmed.isEmpty()) {
            return ThemeConfig.defaults().palette();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static DungeonConfig buildGenerationConfig(
            @Nonnull String worldName,
            int floorLevel,
            @Nonnull Vec3i origin,
            @Nonnull String theme
    ) {
        ThemeConfig defaultTheme = ThemeConfig.defaults();
        return new DungeonConfig(
                null,
                null,
                worldName,
                origin,
                LayoutConfig.defaults(),
                new ThemeConfig(
                        theme,
                        defaultTheme.decayFactor(),
                        defaultTheme.overgrowthFactor(),
                        defaultTheme.floodingFactor()
                ),
                PacingConfig.defaults(),
                true,
                floorLevel
        );
    }

    @Nonnull
    private static Vec3i requireGeneratedPosition(@Nonnull String label, @Nullable Vec3i position) {
        Objects.requireNonNull(label, "label");
        if (position == null) {
            throw new IllegalStateException("Generation result did not provide " + label);
        }
        return position;
    }

    @Nonnull
    private static Vec3i translateGeneratedPosition(@Nonnull Vec3i origin, @Nonnull Vec3i relativePosition) {
        return new Vec3i(
                origin.x() + relativePosition.x(),
                origin.y() + relativePosition.y(),
                origin.z() + relativePosition.z()
        );
    }

    @Nonnull
    private static Throwable unwrapFailure(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Nonnull
    private static String describeFailure(@Nonnull Throwable throwable) {
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
    }

    // ============================================
    // Runtime Integration
    // ============================================

    interface RuntimeAdapter {

        @Nonnull
        CompletableFuture<InstanceWorld> createWorld(
                @Nonnull String worldName,
                int floorLevel,
                @Nullable String seed,
                @Nonnull Vec3i origin
        );

        @Nonnull
        CompletableFuture<GenerationResult> generate(@Nonnull DungeonConfig config);

        @Nonnull
        CompletableFuture<Void> finalizeWorld(
                @Nonnull InstanceWorld world,
                @Nonnull Vec3i origin,
                @Nonnull Vec3i entrancePosition,
                @Nonnull GenerationResult result
        );

        @Nonnull
        CompletableFuture<Void> teleportRoster(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull InstanceWorld world,
                @Nonnull Vec3i entrancePosition
        );

        void cleanupWorld(@Nonnull String worldName);
    }

    interface InstanceWorld {

        @Nonnull
        String worldName();
    }

    private static final class LiveRuntimeAdapter implements RuntimeAdapter {

        @Nonnull
        @Override
        public CompletableFuture<InstanceWorld> createWorld(
                @Nonnull String worldName,
                int floorLevel,
                @Nullable String seed,
                @Nonnull Vec3i origin
        ) {
            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Universe is not available"));
            }

            Path savePath = universe.validateWorldPath(worldName);
            WorldConfig worldConfig = new WorldConfig();
            worldConfig.setUuid(UUID.randomUUID());
            worldConfig.setDisplayName("Dungeon Floor " + floorLevel);
            worldConfig.setSeed(resolveWorldSeed(seed));
            worldConfig.setWorldGenProvider(new VoidWorldGenProvider(null, null));
            worldConfig.setSpawnProvider(new GlobalSpawnProvider(toPlayerTransform(origin)));
            worldConfig.setPvpEnabled(true);
            worldConfig.setFallDamageEnabled(true);
            worldConfig.setTicking(true);
            worldConfig.setBlockTicking(false);
            worldConfig.setSpawningNPC(false);
            worldConfig.setSavingPlayers(false);
            worldConfig.setDeleteOnRemove(true);
            worldConfig.setDeleteOnUniverseStart(false);
            worldConfig.setGameTimePaused(true);
            worldConfig.markChanged();

            return universe.makeWorld(worldName, savePath, worldConfig)
                    .thenApply(LiveInstanceWorld::new);
        }

        @Nonnull
        @Override
        public CompletableFuture<GenerationResult> generate(@Nonnull DungeonConfig config) {
            Objects.requireNonNull(config, "config");
            ZSquadPlugin plugin = requirePlugin();
            GenerationOrchestrator orchestrator = plugin.getDungeonOrchestrator();
            if (orchestrator == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Dungeon orchestrator is not initialized")
                );
            }
            return orchestrator.generate(config);
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> finalizeWorld(
                @Nonnull InstanceWorld world,
                @Nonnull Vec3i origin,
                @Nonnull Vec3i entrancePosition,
                @Nonnull GenerationResult result
        ) {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(entrancePosition, "entrancePosition");
            Objects.requireNonNull(result, "result");

            World liveWorld = toLiveWorld(world);
            return queueWorldTask(liveWorld, () -> {
                WorldConfig worldConfig = liveWorld.getWorldConfig();
                worldConfig.setSpawnProvider(new GlobalSpawnProvider(toPlayerTransform(entrancePosition)));
                worldConfig.markChanged();

                Store<EntityStore> store = liveWorld.getEntityStore().getStore();
                ZSquadPlugin plugin = requirePlugin();

                if (!result.spawnerDefinitions().isEmpty()) {
                    plugin.getSpawnerFactory().createSpawners(store, result.spawnerDefinitions(), origin);
                }
                if (!result.merchantDefinitions().isEmpty()) {
                    plugin.getMerchantNpcSpawner().spawnMerchants(store, result.merchantDefinitions(), origin);
                }
            });
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> teleportRoster(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull InstanceWorld world,
                @Nonnull Vec3i entrancePosition
        ) {
            Objects.requireNonNull(playerIds, "playerIds");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(entrancePosition, "entrancePosition");

            World targetWorld = toLiveWorld(world);
            List<CompletableFuture<Void>> futures = new ArrayList<>(playerIds.size());
            for (UUID playerId : playerIds) {
                futures.add(queueTeleport(playerId, targetWorld, entrancePosition));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }

        @Override
        public void cleanupWorld(@Nonnull String worldName) {
            Objects.requireNonNull(worldName, "worldName");
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            World loadedWorld = universe.getWorld(worldName);
            if (loadedWorld == null) {
                return;
            }
            universe.removeWorld(worldName);
        }

        @Nonnull
        private static CompletableFuture<Void> queueTeleport(
                @Nonnull UUID playerId,
                @Nonnull World targetWorld,
                @Nonnull Vec3i entrancePosition
        ) {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null) {
                LOGGER.at(Level.FINE).log("Skipping dungeon teleport for offline player %s", playerId);
                return CompletableFuture.completedFuture(null);
            }

            Ref<EntityStore> reference = playerRef.getReference();
            if (reference == null || !reference.isValid()) {
                LOGGER.at(Level.FINE).log("Skipping dungeon teleport for player %s without an active entity ref", playerId);
                return CompletableFuture.completedFuture(null);
            }

            Store<EntityStore> store = reference.getStore();
            World currentWorld = store.getExternalData().getWorld();
            if (currentWorld == null) {
                LOGGER.at(Level.WARNING).log("Skipping dungeon teleport for player %s because no source world was found", playerId);
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> completion = new CompletableFuture<>();
            try {
                currentWorld.execute(() -> {
                    try {
                        if (reference.isValid()) {
                            Teleport teleportComponent = Teleport.createForPlayer(
                                    targetWorld,
                                    toPlayerTransform(entrancePosition)
                            );
                            CompletableFuture<Void> transferFuture = new CompletableFuture<>();
                            transferFuture.whenComplete((unused, throwable) -> {
                                if (throwable != null) {
                                    LOGGER.at(Level.WARNING)
                                            .withCause(throwable)
                                            .log("Dungeon teleport for player %s into world %s failed after queueing",
                                                    playerId,
                                                    targetWorld.getName());
                                }
                                completion.complete(null);
                            });
                            teleportComponent.setOnComplete(transferFuture);
                            store.addComponent(reference, Teleport.getComponentType(), teleportComponent);
                        } else {
                            LOGGER.at(Level.FINE).log(
                                    "Skipping dungeon teleport for player %s because their entity ref became invalid",
                                    playerId
                            );
                            completion.complete(null);
                        }
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING)
                                .withCause(e)
                                .log("Failed to queue dungeon teleport for player %s into world %s",
                                        playerId,
                                        targetWorld.getName());
                        completion.complete(null);
                    }
                });
            } catch (Exception e) {
                LOGGER.at(Level.WARNING)
                        .withCause(e)
                        .log("Failed to schedule dungeon teleport task for player %s", playerId);
                completion.complete(null);
            }
            return completion;
        }

        @Nonnull
        private static CompletableFuture<Void> queueWorldTask(
                @Nonnull World world,
                @Nonnull CheckedRunnable action
        ) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                world.execute(() -> {
                    try {
                        action.run();
                        future.complete(null);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        @Nonnull
        private static World toLiveWorld(@Nonnull InstanceWorld world) {
            if (world instanceof LiveInstanceWorld liveInstanceWorld) {
                return liveInstanceWorld.world();
            }
            throw new IllegalStateException("Unsupported runtime world implementation: " + world.getClass().getName());
        }

        @Nonnull
        private static ZSquadPlugin requirePlugin() {
            ZSquadPlugin plugin = ZSquadPlugin.get();
            if (plugin == null) {
                throw new IllegalStateException("ZSquadPlugin is not available");
            }
            return plugin;
        }

        @Nonnull
        private static Transform toPlayerTransform(@Nonnull Vec3i position) {
            return new Transform(position.x() + 0.5D, position.y(), position.z() + 0.5D);
        }

        private static long resolveWorldSeed(@Nullable String seed) {
            if (seed == null || seed.isBlank()) {
                return UUID.randomUUID().getMostSignificantBits() ^ System.nanoTime();
            }
            try {
                return Long.parseLong(seed);
            } catch (NumberFormatException ignored) {
                return seed.hashCode();
            }
        }
    }

    private record LiveInstanceWorld(@Nonnull World world) implements InstanceWorld {

        @Override
        @Nonnull
        public String worldName() {
            return world.getName();
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    // ============================================
    // Exception Types
    // ============================================

    /**
     * Thrown when roster validation fails because one or more players already belong
     * to a non-ended dungeon instance.
     *
     * @since 1.6.0
     */
    public static class RosterValidationException extends RuntimeException {

        private final Set<UUID> blockedPlayers;

        /**
         * Creates a new roster validation exception.
         *
         * @param blockedPlayers the players that failed validation
         */
        public RosterValidationException(@Nonnull Set<UUID> blockedPlayers) {
            super("Players already in active instance: " + blockedPlayers);
            this.blockedPlayers = Set.copyOf(blockedPlayers);
        }

        /**
         * Returns the player UUIDs that already belong to a non-ended instance.
         *
         * @return immutable set of blocked player UUIDs
         */
        @Nonnull
        public Set<UUID> getBlockedPlayers() {
            return blockedPlayers;
        }
    }
}
