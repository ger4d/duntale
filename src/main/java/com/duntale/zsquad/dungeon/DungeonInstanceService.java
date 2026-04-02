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
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.removal.InstanceDataResource;
import com.hypixel.hytale.builtin.instances.removal.WorldEmptyCondition;
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
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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
    // Keeps Continue routing on the new floor if post-transfer ACTIVE persistence is temporarily unavailable.
    private final ConcurrentHashMap<String, DungeonInstance> runtimeActiveInstanceOverrides = new ConcurrentHashMap<>();

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
     * Loads non-ended dungeon instances from SQLite on startup and recovers interrupted state.
     *
     * <p>Instances left in {@code CREATING} state after a restart represent interrupted creation
     * flows and are marked {@code ENDED}. Instances in {@code TRANSITIONING} state are reverted
     * to {@code ACTIVE} since the old floor world is still valid.
     *
     * <p>This method does not re-spawn dungeon-owned ECS content. Persisted worlds are the
     * ground truth — blocks and entities survive in chunk storage. Companions are re-spawned
     * by the existing {@code PlayerReadyEvent} lifecycle flow.
     */
    public void loadOnStartup() {
        List<DungeonInstance> nonEnded;
        try {
            nonEnded = instanceRepository.findAllNonEnded();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE)
                    .withCause(e)
                    .log("Failed to load dungeon instances on startup");
            return;
        }

        if (nonEnded.isEmpty()) {
            LOGGER.at(Level.INFO).log("No active dungeon instances found on startup");
            return;
        }

        int activeCount = 0;
        int endedCount = 0;
        int revertedCount = 0;

        for (DungeonInstance instance : nonEnded) {
            if (instance.state() == DungeonInstanceState.CREATING) {
                try {
                    runtimeAdapter.cleanupWorld(instance.worldName());
                } catch (Exception cleanupError) {
                    LOGGER.at(Level.WARNING)
                            .withCause(cleanupError)
                            .log("Failed to clean up world %s for interrupted instance %s",
                                    instance.worldName(),
                                    instance.instanceId());
                }
                try {
                    instanceRepository.endInstance(instance.instanceId());
                    endedCount++;
                    LOGGER.at(Level.WARNING).log(
                            "Ended interrupted CREATING instance %s (world=%s)",
                            instance.instanceId(),
                            instance.worldName()
                    );
                } catch (SQLException e) {
                    LOGGER.at(Level.SEVERE)
                            .withCause(e)
                            .log("Failed to end interrupted instance %s",
                                    instance.instanceId());
                }
            } else if (instance.state() == DungeonInstanceState.TRANSITIONING) {
                try {
                    DungeonInstance reverted = new DungeonInstance(
                            instance.instanceId(),
                            instance.worldName(),
                            instance.floorLevel(),
                            instance.floorY(),
                            instance.entrancePosition(),
                            instance.exitPosition(),
                            DungeonInstanceState.ACTIVE,
                            instance.theme(),
                            instance.seed(),
                            instance.createdAt()
                    );
                    instanceRepository.update(reverted);
                    revertedCount++;
                    LOGGER.at(Level.WARNING).log(
                            "Reverted interrupted TRANSITIONING instance %s to ACTIVE (world=%s)",
                            instance.instanceId(),
                            instance.worldName()
                    );
                } catch (SQLException e) {
                    LOGGER.at(Level.SEVERE)
                            .withCause(e)
                            .log("Failed to revert transitioning instance %s",
                                    instance.instanceId());
                }
            } else {
                activeCount++;
            }
        }

        LOGGER.at(Level.INFO).log(
                "Startup: %d active instance(s) restored, %d interrupted creation(s) ended, %d interrupted transition(s) reverted",
                activeCount,
                endedCount,
                revertedCount
        );
    }

    /**
     * Returns the active (non-ended) dungeon instance for the given player, if one exists.
     *
     * <p>Lookups stay DB-first in v1. The only exception is a narrow in-process override used
     * to keep Continue routing on the transferred floor if post-transfer {@code ACTIVE}
     * persistence is temporarily unavailable.
     *
     * @param playerId the player UUID
     * @return the player's active instance, or {@code null} if they have none
     * @throws SQLException if a database access error occurs
     */
    @Nullable
    public DungeonInstance getActiveInstance(@Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Optional<String> instanceId = membershipRepository.findNonEndedInstanceIdByPlayer(playerId);
        if (instanceId.isEmpty()) {
            return null;
        }
        return findRuntimeAwareInstance(instanceId.get());
    }

    /**
     * Resolves the player's Continue destination from persisted instance membership.
     *
     * <p>Only {@link DungeonInstanceState#ACTIVE} instances are joinable through Continue.
     * Transitional states such as {@code CREATING} and {@code TRANSITIONING} remain visible to the
     * caller so entry UX can keep the player in the shared/menu flow while the instance is not yet
     * ready.
     *
     * @param playerId the player UUID
     * @return the resolved Continue route
     * @throws SQLException if a database access error occurs
     */
    @Nonnull
    public ContinueRoute resolveContinueRoute(@Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        return new ContinueRoute(getActiveInstance(playerId));
    }

    /**
     * Returns the dungeon instance associated with the given world name, if one exists.
     *
     * <p>Lookups are DB-first in v1. A hot cache layer should only be added if profiling
     * demonstrates the need.
     *
     * @param worldName the world name
     * @return the instance for that world, or {@code null} if no instance uses that world
     * @throws SQLException if a database access error occurs
     */
    @Nullable
    public DungeonInstance getInstanceByWorld(@Nonnull String worldName) throws SQLException {
        Objects.requireNonNull(worldName, "worldName");
        return instanceRepository.findByWorldName(worldName).orElse(null);
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

    /**
     * Transitions a live dungeon instance from the current floor to floor {@code N + 1}.
     *
     * <p>The service marks the instance {@code TRANSITIONING}, creates a new world for the next
     * floor, runs the generation and assembly pipeline, persists the updated floor metadata,
     * teleports the roster to the new entrance, arms the old world for engine-managed removal
     * when it becomes empty, and marks the instance {@code ACTIVE} again.
     *
     * <p>Only {@code ACTIVE} instances may transition. The {@code TRANSITIONING} state acts as
     * a guard against concurrent transition requests — no explicit lock is needed.
     *
     * <p>If generation or world creation fails before transfer completes, players remain in the
     * old world, the instance is reverted to {@code ACTIVE}, and the unusable new world is cleaned up.
     * Failures after the transfer boundary surface to the caller without reverting the new floor.
     *
     * @param instanceId the ID of the instance to advance
     * @return a future that completes with the updated instance metadata after the transition
     * @throws SQLException              if a database access error occurs during the initial state change
     * @throws IllegalArgumentException  if the instance does not exist
     * @throws IllegalStateException     if the instance is not in {@code ACTIVE} state
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> transitionFloor(@Nonnull String instanceId)
            throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        repairRuntimeActiveOverride(instanceId);

        DungeonInstance claimed = instanceRepository.claimTransitionState(instanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot transition instance " + instanceId
                                + "; either it does not exist or is not in ACTIVE state"));

        int nextFloor = claimed.floorLevel() + 1;
        String nextWorldName = INSTANCE_WORLD_PREFIX + claimed.instanceId() + "-f" + nextFloor;
        Vec3i origin = new Vec3i(0, DEFAULT_INSTANCE_ORIGIN_Y, 0);
        String oldWorldName = claimed.worldName();
        Set<UUID> roster = membershipRepository.findPlayerIdsByInstance(instanceId);

        LOGGER.at(Level.INFO).log(
                "Starting floor transition for instance %s: floor %d → %d (newWorld=%s)",
                instanceId,
                claimed.floorLevel(),
                nextFloor,
                nextWorldName
        );

        DungeonConfig config = buildGenerationConfig(nextWorldName, nextFloor, origin, claimed.theme());
        return runtimeAdapter.createWorld(nextWorldName, nextFloor, claimed.seed(), origin)
                .thenCompose(newWorld -> runtimeAdapter.generate(config)
                        .thenCompose(result -> activateTransitionedFloor(
                                newWorld, claimed, roster, nextFloor, origin, result, oldWorldName)))
                .exceptionallyCompose(throwable -> handleTransitionFailure(
                        claimed, nextWorldName, throwable));
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
    private CompletableFuture<DungeonInstance> activateTransitionedFloor(
            @Nonnull InstanceWorld newWorld,
            @Nonnull DungeonInstance previousFloor,
            @Nonnull Set<UUID> roster,
            int nextFloor,
            @Nonnull Vec3i origin,
            @Nonnull GenerationResult result,
            @Nonnull String oldWorldName
    ) {
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

        DungeonInstance updatedInstance = new DungeonInstance(
                previousFloor.instanceId(),
                newWorld.worldName(),
                nextFloor,
                origin.y(),
                entrancePosition,
                exitPosition,
                DungeonInstanceState.TRANSITIONING,
                previousFloor.theme(),
                previousFloor.seed(),
                previousFloor.createdAt()
        );

        DungeonInstance activeInstance = new DungeonInstance(
                previousFloor.instanceId(),
                newWorld.worldName(),
                nextFloor,
                origin.y(),
                entrancePosition,
                exitPosition,
                DungeonInstanceState.ACTIVE,
                previousFloor.theme(),
                previousFloor.seed(),
                previousFloor.createdAt()
        );

        return runtimeAdapter.finalizeWorld(newWorld, origin, entrancePosition, result)
                .thenCompose(unused -> updatePersistedInstance(updatedInstance))
                .thenCompose(unused -> runtimeAdapter.teleportRoster(roster, newWorld, entrancePosition))
                // === Transfer boundary: past this point, new world metadata is authoritative ===
                .thenCompose(unused -> completePostTransferTransition(
                        activeInstance, oldWorldName, nextFloor, newWorld.worldName()));
    }

    @Nonnull
    private <T> CompletableFuture<T> handleTransitionFailure(
            @Nonnull DungeonInstance previousFloor,
            @Nonnull String newWorldName,
            @Nonnull Throwable throwable
    ) {
        Throwable failure = unwrapFailure(throwable);

        if (failure instanceof TransferCompletedException transferCompleted) {
            runtimeActiveInstanceOverrides.putIfAbsent(
                    transferCompleted.getActiveInstance().instanceId(),
                    transferCompleted.getActiveInstance()
            );
            LOGGER.at(Level.SEVERE)
                    .withCause(failure.getCause())
                    .log("Post-transfer failure for instance %s (new world is authoritative,"
                                    + " metadata not reverted): %s",
                            previousFloor.instanceId(),
                            describeFailure(failure.getCause()));
            return CompletableFuture.failedFuture(failure.getCause());
        }

        LOGGER.at(Level.WARNING)
                .withCause(failure)
                .log("Floor transition failed for instance %s (pre-transfer): %s",
                        previousFloor.instanceId(),
                        describeFailure(failure));

        try {
            runtimeAdapter.cleanupWorld(newWorldName);
        } catch (Exception cleanupError) {
            failure.addSuppressed(cleanupError);
            LOGGER.at(Level.WARNING)
                    .withCause(cleanupError)
                    .log("Failed to clean up new world %s after transition failure",
                            newWorldName);
        }

        DungeonInstance reverted = new DungeonInstance(
                previousFloor.instanceId(),
                previousFloor.worldName(),
                previousFloor.floorLevel(),
                previousFloor.floorY(),
                previousFloor.entrancePosition(),
                previousFloor.exitPosition(),
                DungeonInstanceState.ACTIVE,
                previousFloor.theme(),
                previousFloor.seed(),
                previousFloor.createdAt()
        );

        try {
            instanceRepository.update(reverted);
        } catch (SQLException revertError) {
            failure.addSuppressed(revertError);
            LOGGER.at(Level.SEVERE)
                    .withCause(revertError)
                    .log("Failed to revert instance %s to ACTIVE after transition failure",
                            previousFloor.instanceId());
        }

        return CompletableFuture.failedFuture(failure);
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
    private CompletableFuture<DungeonInstance> completePostTransferTransition(
            @Nonnull DungeonInstance activeInstance,
            @Nonnull String oldWorldName,
            int nextFloor,
            @Nonnull String newWorldName
    ) {
        Objects.requireNonNull(activeInstance, "activeInstance");
        Objects.requireNonNull(oldWorldName, "oldWorldName");
        Objects.requireNonNull(newWorldName, "newWorldName");

        return persistPostTransferActiveState(activeInstance)
                .thenCompose(completion -> runtimeAdapter.armWorldRemoval(oldWorldName)
                        .handle((unused, throwable) -> completion.withCleanupFailure(
                                throwable == null ? null : unwrapFailure(throwable))))
                .thenCompose(completion -> {
                    Throwable combinedFailure = completion.combinedFailure();
                    if (combinedFailure != null) {
                        return CompletableFuture.failedFuture(
                                new TransferCompletedException(activeInstance, combinedFailure));
                    }
                    LOGGER.at(Level.INFO).log(
                            "Floor transition complete for instance %s: now on floor %d in world %s",
                            activeInstance.instanceId(),
                            nextFloor,
                            newWorldName
                    );
                    return CompletableFuture.completedFuture(activeInstance);
                });
    }

    @Nonnull
    private CompletableFuture<PostTransferCompletion> persistPostTransferActiveState(
            @Nonnull DungeonInstance activeInstance
    ) {
        Objects.requireNonNull(activeInstance, "activeInstance");
        runtimeActiveInstanceOverrides.put(activeInstance.instanceId(), activeInstance);

        return updatePersistedInstance(activeInstance)
                .thenApply(unused -> {
                    runtimeActiveInstanceOverrides.remove(activeInstance.instanceId(), activeInstance);
                    return PostTransferCompletion.success(activeInstance);
                })
                .exceptionallyCompose(throwable -> retryPersistPostTransferActiveState(
                        activeInstance,
                        unwrapFailure(throwable)
                ));
    }

    @Nonnull
    private CompletableFuture<PostTransferCompletion> retryPersistPostTransferActiveState(
            @Nonnull DungeonInstance activeInstance,
            @Nonnull Throwable firstFailure
    ) {
        LOGGER.at(Level.WARNING)
                .withCause(firstFailure)
                .log("Failed to persist ACTIVE state for instance %s after transfer; retrying once",
                        activeInstance.instanceId());

        return updatePersistedInstance(activeInstance)
                .thenApply(unused -> {
                    runtimeActiveInstanceOverrides.remove(activeInstance.instanceId(), activeInstance);
                    LOGGER.at(Level.WARNING).log(
                            "Recovered ACTIVE state for instance %s after retry",
                            activeInstance.instanceId()
                    );
                    return PostTransferCompletion.success(activeInstance);
                })
                .exceptionally(throwable -> {
                    Throwable finalFailure = unwrapFailure(throwable);
                    if (finalFailure != firstFailure) {
                        finalFailure.addSuppressed(firstFailure);
                    }
                    LOGGER.at(Level.SEVERE)
                            .withCause(finalFailure)
                            .log("Failed to persist ACTIVE state for instance %s after floor transition;"
                                            + " keeping runtime override until database recovers or the server"
                                            + " restarts",
                                    activeInstance.instanceId());
                    return PostTransferCompletion.activationFailure(activeInstance, finalFailure);
                });
    }

    @Nullable
    private DungeonInstance findRuntimeAwareInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        DungeonInstance runtimeOverride = runtimeActiveInstanceOverrides.get(instanceId);
        DungeonInstance persisted = instanceRepository.findById(instanceId).orElse(null);

        if (persisted == null) {
            return runtimeOverride;
        }
        if (persisted.state() == DungeonInstanceState.ACTIVE) {
            if (runtimeOverride != null) {
                runtimeActiveInstanceOverrides.remove(instanceId, runtimeOverride);
            }
            return persisted;
        }
        if (persisted.state() == DungeonInstanceState.ENDED) {
            runtimeActiveInstanceOverrides.remove(instanceId);
            return persisted;
        }
        return runtimeOverride != null ? runtimeOverride : persisted;
    }

    private void repairRuntimeActiveOverride(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        DungeonInstance runtimeOverride = runtimeActiveInstanceOverrides.get(instanceId);
        if (runtimeOverride == null) {
            return;
        }

        DungeonInstance persisted = instanceRepository.findById(instanceId).orElse(null);
        if (persisted == null || persisted.state() == DungeonInstanceState.ENDED) {
            runtimeActiveInstanceOverrides.remove(instanceId, runtimeOverride);
            return;
        }
        if (persisted.state() == DungeonInstanceState.ACTIVE) {
            runtimeActiveInstanceOverrides.remove(instanceId, runtimeOverride);
            return;
        }

        instanceRepository.update(runtimeOverride);
        runtimeActiveInstanceOverrides.remove(instanceId, runtimeOverride);
        LOGGER.at(Level.WARNING).log(
                "Recovered ACTIVE state for post-transfer instance %s from runtime override",
                instanceId
        );
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

        /**
         * Arms the given world for engine-managed removal when it becomes empty.
         *
         * <p>This mirrors the built-in instance removal pattern: sets
         * {@code InstanceDataResource.hadPlayer = true} and configures
         * {@code WorldEmptyCondition.REMOVE_WHEN_EMPTY} so the engine removes the world
         * once the last player leaves.
         *
         * @param worldName the world to arm for removal
         * @return a future that completes after the old world has been armed on its WorldThread
         */
        @Nonnull
        CompletableFuture<Void> armWorldRemoval(@Nonnull String worldName);
    }

    /**
     * Result of resolving a player's Continue action.
     *
     * @param instance the persisted dungeon instance membership, or {@code null} when the player
     *                 has no non-ended dungeon run
     */
    public record ContinueRoute(@Nullable DungeonInstance instance) {

        /**
         * Returns whether Continue should resume a dungeon instance immediately.
         *
         * @return {@code true} when the player belongs to an {@code ACTIVE} instance
         */
        public boolean routesToInstance() {
            return instance != null && instance.state() == DungeonInstanceState.ACTIVE;
        }

        /**
         * Returns whether the player has a dungeon run that exists but is not yet joinable.
         *
         * @return {@code true} when the instance is still creating or transitioning
         */
        public boolean isPending() {
            return instance != null && switch (instance.state()) {
                case CREATING, TRANSITIONING -> true;
                case ACTIVE, ENDED -> false;
            };
        }
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

        @Override
        public CompletableFuture<Void> armWorldRemoval(@Nonnull String worldName) {
            Objects.requireNonNull(worldName, "worldName");
            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Universe is not available — cannot arm world " + worldName)
                );
            }
            World world = universe.getWorld(worldName);
            if (world == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("World " + worldName + " not found — cannot arm for removal")
                );
            }
            return queueWorldTask(world, () -> {
                Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
                chunkStore.getResource(InstanceDataResource.getResourceType()).setHadPlayer(true);
                WorldConfig worldConfig = world.getWorldConfig();
                InstanceWorldConfig.ensureAndGet(worldConfig)
                        .setRemovalConditions(WorldEmptyCondition.REMOVE_WHEN_EMPTY);
                worldConfig.markChanged();
            });
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
    /**
     * Marker exception indicating a floor transition failure that occurred after the roster
     * transfer completed. The new world metadata is authoritative and must not be reverted.
     */
    static class TransferCompletedException extends RuntimeException {

        private final DungeonInstance activeInstance;

        TransferCompletedException(@Nonnull DungeonInstance activeInstance, @Nonnull Throwable cause) {
            super("Post-transfer failure for instance " + activeInstance.instanceId(), cause);
            this.activeInstance = activeInstance;
        }

        @Nonnull
        DungeonInstance getActiveInstance() {
            return activeInstance;
        }
    }

    private record PostTransferCompletion(
            @Nonnull DungeonInstance activeInstance,
            @Nullable Throwable activationFailure,
            @Nullable Throwable cleanupFailure
    ) {

        @Nonnull
        private static PostTransferCompletion success(@Nonnull DungeonInstance activeInstance) {
            return new PostTransferCompletion(activeInstance, null, null);
        }

        @Nonnull
        private static PostTransferCompletion activationFailure(
                @Nonnull DungeonInstance activeInstance,
                @Nonnull Throwable activationFailure
        ) {
            return new PostTransferCompletion(activeInstance, activationFailure, null);
        }

        @Nonnull
        private PostTransferCompletion withCleanupFailure(@Nullable Throwable cleanupFailure) {
            return new PostTransferCompletion(activeInstance, activationFailure, cleanupFailure);
        }

        @Nullable
        private Throwable combinedFailure() {
            if (activationFailure != null) {
                if (cleanupFailure != null && cleanupFailure != activationFailure) {
                    activationFailure.addSuppressed(cleanupFailure);
                }
                return activationFailure;
            }
            return cleanupFailure;
        }
    }

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
