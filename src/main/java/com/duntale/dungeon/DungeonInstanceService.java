package com.duntale.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.config.asset.DungeonThemeConfig;
import com.duntale.dungeongen.generator.GenerationOrchestrator;
import com.duntale.dungeongen.generator.GenerationResult;
import com.duntale.DuntalePlugin;
import com.duntale.db.DatabaseProvider;
import com.duntale.db.SqlFunction;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.removal.InstanceDataResource;
import com.hypixel.hytale.builtin.instances.removal.WorldEmptyCondition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Orchestrates dungeon instance lifecycle: creation, floor transitions, end, and runtime lookups.
 *
 * <p>This service owns the one-active-instance constraint: a player may only belong to one
 * non-{@code ENDED} instance at a time. Roster validation is performed transactionally
 * to prevent concurrent start attempts from bypassing the rule.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * DungeonInstanceService service = new DungeonInstanceService(database, instanceRepository, membershipRepository, partyService, floorConfigService);
 * CompletableFuture<DungeonInstance> active = service.createInstance(List.of(playerId), 1);
 * }</pre>
 *
 * @since 1.6.0
 */
public class DungeonInstanceService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String INSTANCE_WORLD_PREFIX = "dungeon-";
    private static final String SHARED_WORLD_NAME = "village";
    // Hytale filler cleanup scans 15 blocks below non-unit block hitboxes.
    private static final int DEFAULT_INSTANCE_ORIGIN_Y = 20;
    private static final long POST_FINALIZE_TELEPORT_DELAY_MS = 150L;

    // ============================================
    // Fields
    // ============================================

    private final DatabaseProvider database;
    private final DungeonInstanceRepository instanceRepository;
    private final DungeonMembershipRepository membershipRepository;
    private final PartyService partyService;
    private final FloorConfigService floorConfigService;
    private final RuntimeAdapter runtimeAdapter;
    private final Predicate<String> themeAvailabilityChecker;
    // Keeps Continue routing on the new floor if post-transfer ACTIVE persistence is temporarily unavailable.
    private final ConcurrentHashMap<String, DungeonInstance> runtimeActiveInstanceOverrides = new ConcurrentHashMap<>();
    // Tracks both worlds for live floor transitions so admin force-end can clean them up safely.
    private final ConcurrentHashMap<String, TransitionRuntimeState> runtimeTransitionStates = new ConcurrentHashMap<>();

    // ============================================
    // Constructor
    // ============================================

    /**
     * Creates a new dungeon instance service with live Hytale runtime hooks.
     *
     * @param database             the database provider for transactional operations
     * @param instanceRepository   the dungeon instance repository
     * @param membershipRepository the dungeon membership repository
     * @param partyService         the shared party service used to assemble starting rosters
     * @param floorConfigService   the floor config service for per-floor generation overrides
     */
    public DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository,
            @Nonnull PartyService partyService,
            @Nonnull FloorConfigService floorConfigService
    ) {
        this(database, instanceRepository, membershipRepository, partyService, floorConfigService,
            new LiveRuntimeAdapter(), DungeonInstanceService::isRuntimeThemeAvailable);
    }

    DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository,
            @Nonnull PartyService partyService,
            @Nonnull FloorConfigService floorConfigService,
            @Nonnull RuntimeAdapter runtimeAdapter
    ) {
        this(database, instanceRepository, membershipRepository, partyService, floorConfigService,
            runtimeAdapter, DungeonInstanceService::isRuntimeThemeAvailable);
        }

        DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository,
            @Nonnull PartyService partyService,
            @Nonnull FloorConfigService floorConfigService,
            @Nonnull RuntimeAdapter runtimeAdapter,
            @Nonnull Predicate<String> themeAvailabilityChecker
        ) {
        this.database = Objects.requireNonNull(database, "database");
        this.instanceRepository = Objects.requireNonNull(instanceRepository, "instanceRepository");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.floorConfigService = Objects.requireNonNull(floorConfigService, "floorConfigService");
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        this.themeAvailabilityChecker = Objects.requireNonNull(themeAvailabilityChecker, "themeAvailabilityChecker");
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
     * @return a future that completes with the activated dungeon instance metadata
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> createInstance(
            @Nonnull Collection<UUID> playerIds,
            int floorLevel
    ) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }

        Set<UUID> roster = normalizeRoster(playerIds);
        String activeTheme = resolveActiveThemeForFloor(floorLevel);
        int dayTime = resolveDayTimeForFloor(floorLevel);
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
                activeTheme,
                null,
                System.currentTimeMillis()
        );

        LOGGER.at(Level.INFO).log(
                "Starting dungeon instance %s for %d players (floor=%d, theme=%s)",
                instanceId,
                roster.size(),
                floorLevel,
                activeTheme
        );

        try {
            createInstance(pendingInstance, roster);
        } catch (SQLException | RosterValidationException e) {
            return CompletableFuture.failedFuture(e);
        }

        return launchInstanceWorld(pendingInstance, roster, floorLevel, origin, activeTheme, dayTime);
    }

    /**
     * Runs the asynchronous world-creation pipeline for an already-reserved pending instance.
     *
     * <p>Shared by both the strict-validation start path ({@link #createInstance(Collection, int)})
     * and the migration-aware player start path ({@link #createInstanceForPlayer(UUID, int)}). The
     * pending instance row and {@code ACTIVE} membership rows must already be persisted before this
     * is called.
     *
     * @param pendingInstance the reserved {@code CREATING} instance
     * @param roster          the starting roster
     * @param floorLevel      the floor number
     * @param origin          the world origin
     * @param activeTheme     the resolved theme id
     * @param dayTime         the resolved day time
     * @return a future that completes with the activated instance
     */
    @Nonnull
    private CompletableFuture<DungeonInstance> launchInstanceWorld(
            @Nonnull DungeonInstance pendingInstance,
            @Nonnull Set<UUID> roster,
            int floorLevel,
            @Nonnull Vec3i origin,
            @Nonnull String activeTheme,
            int dayTime
    ) {
        DungeonConfig config = buildGenerationConfig(
                pendingInstance.worldName(), floorLevel, origin, activeTheme);
        return runtimeAdapter.createWorld(
                        pendingInstance.worldName(), floorLevel, pendingInstance.seed(), origin, dayTime)
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
     * Routes the initiating player (and, when they own a party, eligible party members) into their
     * existing {@code ACTIVE} dungeon instance.
     *
     * <p>The target world is resolved before any membership mutation; if it is not loaded the call
     * fails without changing state. When the initiator is a solo player or a non-owner party member,
     * only the initiator is routed. When the initiator owns a party, currently-online party members
    * are expanded into the instance subject to the {@link PartyService#MAX_PARTY_SIZE} active-member
    * cap (all-or-nothing): members already active here are kept, members who previously
    * {@code LEFT} are reactivated, members with no membership are added, and members in another
    * safe active instance are migrated into this instance.
     *
     * @param playerId the initiating player UUID
     * @return a future that completes with the teleport outcome
     */
    @Nonnull
    public CompletableFuture<ContinueTeleportResult> continueInstanceForPlayer(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");

        DungeonInstance instance;
        try {
            instance = getActiveInstance(playerId);
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (instance == null || instance.state() != DungeonInstanceState.ACTIVE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No active dungeon instance to continue for player " + playerId));
        }
        if (!runtimeAdapter.isWorldLoaded(instance.worldName())) {
            return CompletableFuture.failedFuture(new ContinueWorldUnavailableException(instance));
        }

        PartyService.StartRoster start;
        try {
            start = partyService.resolveStartRoster(playerId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        Set<UUID> targets;
        boolean ownerInitiated;
        if (start.status() == PartyService.StartRosterStatus.OWNER) {
            Optional<PartyService.PartyRosterSnapshot> snapshot = partyService.tryGetOwnedRoster(playerId);
            if (snapshot.isEmpty()) {
                targets = Set.of(playerId);
                ownerInitiated = false;
            } else {
                RosterExpansionResult expansion;
                try {
                    expansion = expandRosterIntoInstance(instance, snapshot.get().members());
                } catch (SQLException e) {
                    return CompletableFuture.failedFuture(e);
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
                scheduleAbandonmentCleanup(expansion.abandonments());
                targets = expansion.targets();
                ownerInitiated = true;
            }
        } else {
            targets = Set.of(playerId);
            ownerInitiated = false;
        }

        boolean owner = ownerInitiated;
        return runtimeAdapter
                .teleportTargetsIfOutsideWorld(targets, instance.worldName(), instance.entrancePosition())
                .thenApply(outcome -> new ContinueTeleportResult(
                        owner, outcome.teleported(), outcome.alreadyInWorld(), outcome.offlineSkipped()));
    }

    /**
     * Categorizes party members against the target instance and brings every eligible member into
     * the instance atomically, enforcing the active-member cap as an all-or-nothing operation.
     *
     * <p>Members already {@code ACTIVE} in the target are left untouched. Members with a
     * {@code LEFT} row in the target are reactivated. Members with no membership are added. Members
     * who are {@code ACTIVE} in a different non-ended instance are migrated: their prior membership
     * is marked {@code LEFT} (force-ending and scheduling cleanup of any prior instance they empty)
     * and they are added or reactivated here, mirroring the player-initiated start behavior. A
     * member whose prior instance is mid-creation or mid-transition cannot be migrated and fails
     * the whole expansion.
     *
        * @param instance     the target instance
        * @param partyMembers the party roster to expand into the instance
        * @return the expansion result holding the target roster, membership changes, and abandonments
     * @throws SQLException                     if a database access error occurs
     * @throws ContinueRosterExpansionException if the expansion would exceed the active-member cap
     * @throws UnsafePriorInstanceException     if a member's prior instance is mid-creation or
     *                                          mid-transition
     */
    @Nonnull
    private RosterExpansionResult expandRosterIntoInstance(
            @Nonnull DungeonInstance instance,
            @Nonnull Set<UUID> partyMembers
    ) throws SQLException {
        return database.transaction((SqlFunction<RosterExpansionResult>)
            conn -> expandRosterIntoInstanceInTransaction(conn, instance, partyMembers));
    }

    @Nonnull
    private RosterExpansionResult expandRosterIntoInstanceInTransaction(
            @Nonnull Connection conn,
            @Nonnull DungeonInstance instance,
            @Nonnull Set<UUID> partyMembers
    ) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(partyMembers, "partyMembers");
        String instanceId = instance.instanceId();
        List<UUID> reactivate = new ArrayList<>();
        List<UUID> add = new ArrayList<>();
        List<MigrationTarget> migrate = new ArrayList<>();
        for (UUID member : partyMembers) {
            Optional<DungeonMembershipRepository.MembershipState> stateHere =
                    membershipRepository.findMembershipStateInTransaction(conn, instanceId, member);
            if (stateHere.isPresent()
                    && stateHere.get() == DungeonMembershipRepository.MembershipState.ACTIVE) {
                continue;
            }

            boolean reactivateHere = stateHere.isPresent()
                    && stateHere.get() == DungeonMembershipRepository.MembershipState.LEFT;

            Optional<String> otherActive =
                    membershipRepository.findActiveNonEndedInstanceIdInTransaction(conn, member);
            if (otherActive.isPresent() && !otherActive.get().equals(instanceId)) {
                String priorId = otherActive.get();
                DungeonInstance prior = instanceRepository.findByIdInTransaction(conn, priorId).orElse(null);
                if (prior != null
                        && (prior.state() == DungeonInstanceState.CREATING
                            || prior.state() == DungeonInstanceState.TRANSITIONING)) {
                    throw new UnsafePriorInstanceException(member, priorId, prior.state());
                }
                migrate.add(new MigrationTarget(
                        member, priorId, prior != null ? prior.worldName() : null));
            }

            if (reactivateHere) {
                reactivate.add(member);
            } else {
                add.add(member);
            }
        }

        int activeCount = membershipRepository.countActiveMembersInTransaction(conn, instanceId);
        if (activeCount + reactivate.size() + add.size() > PartyService.MAX_PARTY_SIZE) {
            throw new ContinueRosterExpansionException(Set.of(), true);
        }

        List<PreviousInstanceAbandonment> abandonments = new ArrayList<>();
        for (MigrationTarget target : migrate) {
            membershipRepository.markLeftInTransaction(conn, target.priorInstanceId(), target.member());
            int remaining =
                    membershipRepository.countActiveMembersInTransaction(conn, target.priorInstanceId());
            boolean emptied = remaining == 0;
            if (emptied) {
                instanceRepository.forceClaimEndStateInTransaction(conn, target.priorInstanceId());
            }
            if (target.priorWorldName() != null) {
                abandonments.add(new PreviousInstanceAbandonment(
                        target.priorInstanceId(),
                        target.priorWorldName(),
                        Set.of(target.member()),
                        emptied));
            }
        }

        for (UUID member : reactivate) {
            membershipRepository.reactivateInTransaction(conn, instanceId, member);
        }
        for (UUID member : add) {
            membershipRepository.addActiveMembershipInTransaction(conn, instanceId, member);
        }
        Set<UUID> migrated = new LinkedHashSet<>();
        for (MigrationTarget target : migrate) {
            migrated.add(target.member());
        }
        return new RosterExpansionResult(
                new LinkedHashSet<>(partyMembers),
                new LinkedHashSet<>(reactivate),
                new LinkedHashSet<>(add),
                migrated,
                abandonments);
    }

    /**
     * Result of a party-to-instance expansion: the target roster, membership changes, and any
     * prior-instance abandonments produced by migrating members out of other active instances.
     */
    private record RosterExpansionResult(
            @Nonnull Set<UUID> targets,
            @Nonnull Set<UUID> reactivatedMembers,
            @Nonnull Set<UUID> addedMembers,
            @Nonnull Set<UUID> migratedMembers,
            @Nonnull List<PreviousInstanceAbandonment> abandonments
    ) {

        private RosterExpansionResult {
            targets = Set.copyOf(targets);
            reactivatedMembers = Set.copyOf(reactivatedMembers);
            addedMembers = Set.copyOf(addedMembers);
            migratedMembers = Set.copyOf(migratedMembers);
            abandonments = List.copyOf(abandonments);
        }

        @Nonnull
        private Set<UUID> expandedMembers() {
            LinkedHashSet<UUID> expanded = new LinkedHashSet<>();
            expanded.addAll(reactivatedMembers);
            expanded.addAll(addedMembers);
            expanded.addAll(migratedMembers);
            return Set.copyOf(expanded);
        }
    }

    /**
     * A party member that must be migrated out of a prior active instance during a Continue
     * expansion.
     */
    private record MigrationTarget(
            @Nonnull UUID member,
            @Nonnull String priorInstanceId,
            @Nullable String priorWorldName
    ) {
    }

    /**
     * Removes the given player from their current {@code ACTIVE} dungeon instance by marking their
    * membership {@code LEFT}. If no active members remain afterward, the instance is force-ended
    * and its world is armed for engine-managed removal once empty.
     *
     * @param playerId the leaving player UUID
     * @return a future that completes with the leave result
     */
    @Nonnull
    public CompletableFuture<DungeonLeaveResult> leaveInstanceForPlayer(@Nonnull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");

        DungeonInstance instance;
        try {
            instance = getActiveInstance(playerId);
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (instance == null) {
            return CompletableFuture.completedFuture(
                    new DungeonLeaveResult(LeaveStatus.NOT_IN_INSTANCE, null));
        }

        String instanceId = instance.instanceId();
        boolean emptied;
        try {
            emptied = database.transaction(conn -> {
                membershipRepository.markLeftInTransaction(conn, instanceId, playerId);
                int remaining = membershipRepository.countActiveMembersInTransaction(conn, instanceId);
                if (remaining == 0) {
                    instanceRepository.forceClaimEndStateInTransaction(conn, instanceId);
                    return true;
                }
                return false;
            });
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(e);
        }

        if (emptied) {
            try {
            return runtimeAdapter.armWorldRemoval(instance.worldName())
                .exceptionally(throwable -> {
                    Throwable cleanupError = unwrapFailure(throwable);
                    LOGGER.at(Level.WARNING)
                        .withCause(cleanupError)
                        .log("Failed to arm world %s for removal after last member left instance %s",
                            instance.worldName(), instanceId);
                    return null;
                })
                .thenApply(unused -> new DungeonLeaveResult(LeaveStatus.ENDED_LAST_MEMBER, instance));
            } catch (Exception cleanupError) {
            LOGGER.at(Level.WARNING)
                .withCause(cleanupError)
                .log("Failed to arm world %s for removal after last member left instance %s",
                    instance.worldName(), instanceId);
            return CompletableFuture.completedFuture(
                new DungeonLeaveResult(LeaveStatus.ENDED_LAST_MEMBER, instance));
            }
        }
        return CompletableFuture.completedFuture(
                new DungeonLeaveResult(LeaveStatus.LEFT_WITH_REMAINING, instance));
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
     * Returns the dungeon instance with the given ID, or {@code null} if it does not exist.
     *
     * @param instanceId the instance identifier
     * @return the instance, or {@code null} if not found
     * @throws SQLException if a database access error occurs
     * @since 1.6.0
     */
    @Nullable
    public DungeonInstance getInstanceById(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return instanceRepository.findById(instanceId).orElse(null);
    }

    /**
     * Returns all non-ended dungeon instances, ordered by creation time.
     *
     * @return list of non-ended instances
     * @throws SQLException if a database access error occurs
     * @since 1.6.0
     */
    @Nonnull
    public List<DungeonInstance> listNonEndedInstances() throws SQLException {
        return instanceRepository.findAllNonEnded();
    }

    /**
     * Returns the set of player UUIDs belonging to the given instance.
     *
     * @param instanceId the instance identifier
     * @return set of player UUIDs in the instance roster
     * @throws SQLException if a database access error occurs
     * @since 1.6.0
     */
    @Nonnull
    public Set<UUID> getRoster(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return membershipRepository.findPlayerIdsByInstance(instanceId);
    }

    /**
     * Starts a new single-floor dungeon instance for the given player, using their current party
     * roster when present and a solo roster otherwise.
     *
     * @param playerId   the initiating player UUID
     * @param floorLevel the floor number to generate
     * @return a future that completes with the activated dungeon instance metadata
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> createInstanceForPlayer(
            @Nonnull UUID playerId,
            int floorLevel
    ) {
        Objects.requireNonNull(playerId, "playerId");
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }

        PartyService.StartRoster start;
        try {
            start = partyService.resolveStartRoster(playerId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (start.status() == PartyService.StartRosterStatus.NOT_OWNER) {
            return CompletableFuture.failedFuture(new PartyStartPermissionException(playerId));
        }

        Set<UUID> roster;
        try {
            roster = normalizeRoster(start.roster());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (roster.size() > PartyService.MAX_PARTY_SIZE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "start roster of " + roster.size() + " exceeds max party size " + PartyService.MAX_PARTY_SIZE));
        }

        return createMigratedInstance(roster, floorLevel);
    }

    /**
     * Reserves a new instance for a player-initiated start, migrating each roster member out of
     * any prior {@code ACTIVE} instance instead of rejecting the start.
     *
     * <p>For every roster member with an existing active membership, the member is marked
     * {@code LEFT} in that prior instance; once a prior instance has no remaining active members,
     * it is force-ended and its world scheduled for cleanup. Prior instances still in
     * {@code CREATING} or {@code TRANSITIONING} state cannot be migrated from and fail the start.
     *
     * @param roster     the normalized owner-first roster
     * @param floorLevel the floor number to generate
     * @return a future that completes with the activated instance
     */
    @Nonnull
    private CompletableFuture<DungeonInstance> createMigratedInstance(
            @Nonnull Set<UUID> roster,
            int floorLevel
    ) {
        String activeTheme = resolveActiveThemeForFloor(floorLevel);
        int dayTime = resolveDayTimeForFloor(floorLevel);
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
                activeTheme,
                null,
                System.currentTimeMillis()
        );

        LOGGER.at(Level.INFO).log(
                "Starting dungeon instance %s for %d players with migration (floor=%d, theme=%s)",
                instanceId,
                roster.size(),
                floorLevel,
                activeTheme
        );

        List<PreviousInstanceAbandonment> abandonments;
        try {
            prepareMigrationRuntime(roster);
            abandonments = persistMigratedInstance(pendingInstance, roster);
        } catch (SQLException | UnsafePriorInstanceException e) {
            return CompletableFuture.failedFuture(e);
        }

        scheduleAbandonmentCleanup(abandonments);

        return launchInstanceWorld(pendingInstance, roster, floorLevel, origin, activeTheme, dayTime);
    }

    /**
     * Resolves any runtime ACTIVE overrides for prior instances of the roster before migration so
     * the migration transaction observes a consistent persisted state.
     *
     * @param roster the roster being migrated
     * @throws SQLException if a database access error occurs
     */
    private void prepareMigrationRuntime(@Nonnull Set<UUID> roster) throws SQLException {
        Set<String> priorIds = new HashSet<>();
        for (UUID member : roster) {
            membershipRepository.findNonEndedInstanceIdByPlayer(member).ifPresent(priorIds::add);
        }
        for (String priorId : priorIds) {
            repairRuntimeActiveOverride(priorId);
        }
    }

    /**
     * Persists the migrated reservation atomically: marks roster members {@code LEFT} in any prior
     * active instance, force-ends now-empty prior instances, then creates the new instance row and
     * its {@code ACTIVE} membership rows.
     *
     * @param instance the pending instance to create
     * @param roster   the normalized roster
     * @return the list of prior-instance abandonments produced by the migration
     * @throws SQLException                  if a database access error occurs
     * @throws UnsafePriorInstanceException  if a roster member's prior instance is mid-creation or
     *                                       mid-transition
     */
    @Nonnull
    private List<PreviousInstanceAbandonment> persistMigratedInstance(
            @Nonnull DungeonInstance instance,
            @Nonnull Set<UUID> roster
    ) throws SQLException {
        return database.transaction(conn -> {
            List<PreviousInstanceAbandonment> abandonments = new ArrayList<>();
            for (UUID member : roster) {
                Optional<String> priorOpt =
                        membershipRepository.findActiveNonEndedInstanceIdInTransaction(conn, member);
                if (priorOpt.isEmpty()) {
                    continue;
                }
                String priorId = priorOpt.get();
                DungeonInstance prior = instanceRepository.findByIdInTransaction(conn, priorId).orElse(null);
                if (prior == null) {
                    continue;
                }
                if (prior.state() == DungeonInstanceState.CREATING
                        || prior.state() == DungeonInstanceState.TRANSITIONING) {
                    throw new UnsafePriorInstanceException(member, priorId, prior.state());
                }

                membershipRepository.markLeftInTransaction(conn, priorId, member);
                int remaining = membershipRepository.countActiveMembersInTransaction(conn, priorId);
                boolean emptied = remaining == 0;
                if (emptied) {
                    instanceRepository.forceClaimEndStateInTransaction(conn, priorId);
                }
                abandonments.add(new PreviousInstanceAbandonment(
                        priorId, prior.worldName(), Set.of(member), emptied));
            }

            instanceRepository.createInTransaction(conn, instance);
            membershipRepository.addMembershipsInTransaction(conn, instance.instanceId(), roster);
            return abandonments;
        });
    }

    /**
    * Arms world removal for prior instances that became empty during a migration.
     *
     * @param abandonments the abandonments produced by a migration transaction
     */
    private void scheduleAbandonmentCleanup(@Nonnull List<PreviousInstanceAbandonment> abandonments) {
        Set<String> emptiedWorlds = new HashSet<>();
        for (PreviousInstanceAbandonment abandonment : abandonments) {
            if (abandonment.emptiedInstance()) {
                emptiedWorlds.add(abandonment.worldName());
            }
        }
        for (String worldName : emptiedWorlds) {
            try {
                runtimeAdapter.armWorldRemoval(worldName)
                        .exceptionally(throwable -> {
                            Throwable cleanupError = unwrapFailure(throwable);
                            LOGGER.at(Level.WARNING)
                                    .withCause(cleanupError)
                                    .log("Failed to arm emptied prior dungeon world %s for removal during migration",
                                            worldName);
                            return null;
                        });
            } catch (Exception cleanupError) {
                LOGGER.at(Level.WARNING)
                        .withCause(cleanupError)
                        .log("Failed to arm emptied prior dungeon world %s for removal during migration", worldName);
            }
        }
    }

    /**
     * Force-ends the current instance and creates a new instance for the captured roster.
     *
     * <p>The roster is loaded before the old instance is ended, so this restart does not depend
     * on the initiating player's current party membership. The old instance remains ended if the
     * subsequent creation fails.
     *
     * @param instanceId the instance to restart from
     * @param floorLevel the target floor level for the new instance
     * @return a future that completes with the new active instance
     * @throws SQLException if roster lookup or force-end preparation fails
     * @throws IllegalArgumentException if {@code floorLevel < 1} or the instance does not exist
     * @throws IllegalStateException if the instance cannot be force-ended or has an empty roster
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> restartInstanceAtFloor(
            @Nonnull String instanceId,
            int floorLevel
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }

        Set<UUID> roster = getRoster(instanceId);
        if (roster.isEmpty()) {
            throw new IllegalStateException("Cannot restart instance " + instanceId + ": roster is empty");
        }

        return forceEndInstance(instanceId)
                .thenCompose(unused -> createInstance(roster, floorLevel));
    }

    /**
     * Prepares a floor transition without party expansion.
     *
     * <p>This is the operator/admin preparation path: it validates that the instance is currently
     * {@code ACTIVE} and returns the current active roster. Runtime code can then select the online
     * members physically present in the old instance world before calling
     * {@link #transitionFloor(FloorTransitionRequest)}.
     *
     * @param instanceId the ID of the instance to prepare
     * @return the preparation result with the current active roster and no party mutations
     * @throws SQLException          if a database access error occurs
     * @throws IllegalStateException if the instance does not exist or is not {@code ACTIVE}
     */
    @Nonnull
    public FloorTransitionPreparation prepareFloorTransition(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        repairRuntimeActiveOverride(instanceId);

        return database.transaction(conn -> {
            DungeonInstance instance = requireActiveTransitionInstanceInTransaction(conn, instanceId);
            Set<UUID> activeRoster = membershipRepository.findPlayerIdsByInstanceInTransaction(conn, instanceId);
            return new FloorTransitionPreparation(
                    instance,
                    activeRoster,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    List.of());
        });
    }

    /**
     * Expands the trigger player's current party into an {@code ACTIVE} instance before a floor
     * transition.
     *
     * <p>The expansion uses the same all-or-nothing membership rules as owner-initiated Continue:
     * already-active members are kept, {@code LEFT} members are reactivated, new members are added,
     * and members active in another safe instance are migrated out of that prior instance. Prior
     * instances in {@code CREATING} or {@code TRANSITIONING} block the entire preparation.
     *
     * @param instanceId         the ID of the instance to prepare
     * @param triggerPartyRoster the trigger player's current solo-or-party roster
     * @return the preparation result after membership expansion
     * @throws SQLException                       if a database access error occurs
     * @throws IllegalStateException              if the instance does not exist or is not {@code ACTIVE}
     * @throws ContinueRosterExpansionException   if expansion would exceed the active-member cap
     * @throws UnsafePriorInstanceException       if a party member is active in an unsafe prior instance
     */
    @Nonnull
    public FloorTransitionPreparation preparePartyAwareFloorTransition(
            @Nonnull String instanceId,
            @Nonnull Set<UUID> triggerPartyRoster
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(triggerPartyRoster, "triggerPartyRoster");
        Set<UUID> partyRoster = normalizeRoster(triggerPartyRoster);
        repairRuntimeActiveOverride(instanceId);

        FloorTransitionPreparation preparation = database.transaction(conn -> {
            DungeonInstance instance = requireActiveTransitionInstanceInTransaction(conn, instanceId);
            RosterExpansionResult expansion = expandRosterIntoInstanceInTransaction(conn, instance, partyRoster);
            Set<UUID> activeRoster = membershipRepository.findPlayerIdsByInstanceInTransaction(conn, instanceId);
            return new FloorTransitionPreparation(
                    instance,
                    activeRoster,
                    partyRoster,
                    expansion.expandedMembers(),
                    expansion.reactivatedMembers(),
                    expansion.addedMembers(),
                    expansion.migratedMembers(),
                    expansion.abandonments());
        });
        scheduleAbandonmentCleanup(preparation.abandonments());
        return preparation;
    }

    @Nonnull
    private DungeonInstance requireActiveTransitionInstanceInTransaction(
            @Nonnull Connection conn,
            @Nonnull String instanceId
    ) throws SQLException {
        DungeonInstance instance = instanceRepository.findByIdInTransaction(conn, instanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot transition instance " + instanceId
                                + "; either it does not exist or is not in ACTIVE state"));
        if (instance.state() != DungeonInstanceState.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot transition instance " + instanceId + ": current state is " + instance.state());
        }
        return instance;
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
        FloorTransitionPreparation preparation = prepareFloorTransition(instanceId);
        return transitionFloor(new FloorTransitionRequest(instanceId, preparation.activeRosterAfterExpansion()));
        }

        /**
         * Transitions a live dungeon instance and teleports only the supplied runtime transfer players.
         *
         * <p>The transfer set must be the online/runtime-selected subset prepared by the caller. Every
         * player in the set is validated against the instance's current {@code ACTIVE} membership before
         * the service claims {@code TRANSITIONING}; invalid or empty transfer sets fail without changing
         * floor metadata.
         *
         * @param instanceId       the ID of the instance to advance
         * @param transferPlayers  the active players to teleport to the next floor
         * @return a future that completes with the updated instance metadata after the transition
         * @throws SQLException              if a database access error occurs during the initial state change
         * @throws IllegalArgumentException  if the transfer set is empty or contains inactive players
         * @throws IllegalStateException     if the instance is not in {@code ACTIVE} state
         */
        @Nonnull
        public CompletableFuture<DungeonInstance> transitionFloor(
            @Nonnull String instanceId,
            @Nonnull Set<UUID> transferPlayers
        ) throws SQLException {
        return transitionFloor(new FloorTransitionRequest(instanceId, transferPlayers));
        }

        /**
         * Transitions a live dungeon instance and teleports only the request's runtime transfer set.
         *
         * @param request the transition request containing the instance and transfer players
         * @return a future that completes with the updated instance metadata after the transition
         * @throws SQLException              if a database access error occurs during the initial state change
         * @throws IllegalArgumentException  if the transfer set contains inactive players
         * @throws IllegalStateException     if the instance is not in {@code ACTIVE} state
         */
        @Nonnull
        public CompletableFuture<DungeonInstance> transitionFloor(@Nonnull FloorTransitionRequest request)
            throws SQLException {
        Objects.requireNonNull(request, "request");
        String instanceId = request.instanceId();
        Set<UUID> transferPlayers = request.transferPlayers();
        repairRuntimeActiveOverride(instanceId);
        validateTransitionTransferPlayers(instanceId, transferPlayers);

        DungeonInstance claimed = instanceRepository.claimTransitionState(instanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot transition instance " + instanceId
                                + "; either it does not exist or is not in ACTIVE state"));

        int nextFloor = request.targetFloorLevel() != null ? request.targetFloorLevel() : claimed.floorLevel() + 1;
        String nextWorldName = INSTANCE_WORLD_PREFIX + claimed.instanceId() + "-f" + nextFloor;
        TransitionRuntimeState transitionState = new TransitionRuntimeState(claimed.worldName(), nextWorldName);
        runtimeTransitionStates.put(instanceId, transitionState);
        Vec3i origin = new Vec3i(0, DEFAULT_INSTANCE_ORIGIN_Y, 0);
        String oldWorldName = claimed.worldName();

        LOGGER.at(Level.INFO).log(
            "Starting floor transition for instance %s: floor %d → %d (newWorld=%s, transferPlayers=%d)",
                instanceId,
                claimed.floorLevel(),
                nextFloor,
            nextWorldName,
            transferPlayers.size()
        );

        if (nextFloor == claimed.floorLevel()) {
            throw new IllegalArgumentException("Cannot transition to the current floor level (" + nextFloor + ")");
        }

        CompletableFuture<DungeonInstance> transitionFuture;
        try {
            String nextTheme = resolveActiveThemeForFloor(nextFloor);
            int dayTime = resolveDayTimeForFloor(nextFloor);
            DungeonConfig config = buildGenerationConfig(nextWorldName, nextFloor, origin, nextTheme);
            transitionFuture = runtimeAdapter.createWorld(nextWorldName, nextFloor, claimed.seed(), origin, dayTime)
                    .thenCompose(newWorld -> runtimeAdapter.generate(config)
                            .thenCompose(result -> activateTransitionedFloor(
                                    newWorld,
                                    claimed,
                                    transferPlayers,
                                    nextFloor,
                                    nextTheme,
                                    origin,
                                    result,
                                    oldWorldName,
                                    transitionState)));
        } catch (Throwable t) {
            transitionFuture = CompletableFuture.failedFuture(t);
        }

        return transitionFuture.exceptionallyCompose(throwable -> handleTransitionFailure(
                claimed, nextWorldName, transitionState, throwable));
    }

    /**
     * Resets a stuck dungeon instance's state back to ACTIVE.
     *
     * @param instanceId the ID of the instance to reset
     * @throws SQLException             if a database access error occurs
     * @throws IllegalArgumentException if the instance does not exist
     * @throws IllegalStateException    if the instance is already ended
     */
    public void resetInstanceStateToActive(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        DungeonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        if (instance.state() == DungeonInstanceState.ENDED) {
            throw new IllegalStateException("Cannot reset state of an ENDED instance");
        }

        DungeonInstance updated = new DungeonInstance(
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
        instanceRepository.update(updated);
        runtimeTransitionStates.remove(instanceId);
    }

    private void validateTransitionTransferPlayers(
            @Nonnull String instanceId,
            @Nonnull Set<UUID> transferPlayers
    ) throws SQLException {
        if (transferPlayers.isEmpty()) {
            throw new IllegalArgumentException("transferPlayers must not be empty");
        }
        Set<UUID> activeRoster = membershipRepository.findPlayerIdsByInstance(instanceId);
        LinkedHashSet<UUID> inactivePlayers = new LinkedHashSet<>(transferPlayers);
        inactivePlayers.removeAll(activeRoster);
        if (!inactivePlayers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot transition instance " + instanceId
                            + ": transfer players are not ACTIVE members: " + inactivePlayers);
        }
    }

    /**
     * Ends a dungeon instance, evacuates online roster members to the shared village hub,
     * and arms the instance world for engine-managed removal when it becomes empty.
     *
     * <p>The instance is marked {@code ENDED} synchronously before any async work begins
     * so that {@link #resolveContinueRoute} immediately stops routing players back into
     * the instance.
     *
     * <p>Only {@link DungeonInstanceState#ACTIVE ACTIVE} instances may be ended through this
     * method. If the instance is already {@code ENDED}, the service retries any pending
     * evacuation / cleanup work instead of returning early.
     *
     * @param instanceId the instance identifier
     * @return a future that completes when evacuation and world cleanup arming finish
     * @throws SQLException             if a database access error occurs
     * @throws IllegalArgumentException if the instance does not exist
     * @throws IllegalStateException    if the instance is not in {@code ACTIVE} or {@code ENDED} state
     */
    @Nonnull
    public CompletableFuture<Void> endInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        repairRuntimeActiveOverride(instanceId);
        EndPreparation preparation = prepareEndInstance(instanceId);
        runtimeActiveInstanceOverrides.remove(instanceId);
        String worldName = preparation.instance().worldName();
        List<String> cleanupWorldNames = resolveCleanupWorldNames(instanceId, worldName);

        if (preparation.retryingCleanup()) {
            LOGGER.at(Level.INFO).log(
                    "Retrying cleanup for ended instance %s across %s",
                    instanceId,
                    describeCleanupTargets(cleanupWorldNames)
            );
        } else {
            LOGGER.at(Level.INFO).log(
                    "Instance %s ended (was %s), evacuating roster across %s",
                    instanceId,
                    preparation.instance().state(),
                    describeCleanupTargets(cleanupWorldNames)
            );
        }

        return performEndCleanup(instanceId, preparation.roster(), cleanupWorldNames, "End");
    }

    /**
     * Force-ends a dungeon instance from any non-{@code ENDED} state, including
     * {@code CREATING} and {@code TRANSITIONING}.
     *
     * <p>This is the admin variant of {@link #endInstance}. It bypasses the
     * {@code ACTIVE}-only restriction so operators can recover stuck instances.
     *
     * @param instanceId the instance identifier
     * @return a future that completes when evacuation and world cleanup arming finish
     * @throws SQLException             if a database access error occurs
     * @throws IllegalArgumentException if the instance does not exist
     * @throws IllegalStateException    if the instance is already {@code ENDED}
     * @since 1.6.0
     */
    @Nonnull
    public CompletableFuture<Void> forceEndInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        DungeonInstance runtimeOverride = runtimeActiveInstanceOverrides.get(instanceId);
        TransitionRuntimeState transitionState = runtimeTransitionStates.get(instanceId);

        EndPreparation preparation = database.transaction(conn -> {
            DungeonInstance instance = instanceRepository.findByIdInTransaction(conn, instanceId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot force-end instance " + instanceId + ": not found"));

            if (instance.state() == DungeonInstanceState.ENDED) {
                throw new IllegalStateException(
                        "Cannot force-end instance " + instanceId + ": already ENDED");
            }
            if (instance.state() == DungeonInstanceState.TRANSITIONING
                    && transitionState == null
                    && runtimeOverride == null) {
                throw new IllegalStateException(
                        "Cannot force-end instance " + instanceId
                                + ": the live transition context is unavailable");
            }

            if (!instanceRepository.forceClaimEndStateInTransaction(conn, instanceId)) {
                throw new IllegalStateException(
                        "Cannot force-end instance " + instanceId + ": claim failed unexpectedly");
            }
            if (instance.state() == DungeonInstanceState.TRANSITIONING && transitionState != null) {
                transitionState.markForceEnded();
            }

            return EndPreparation.claimed(
                    instance,
                    membershipRepository.findPlayerIdsByInstanceInTransaction(conn, instanceId)
            );
        });

        runtimeActiveInstanceOverrides.remove(instanceId);
        String worldName = preparation.instance().worldName();
        List<String> cleanupWorldNames = resolveCleanupWorldNames(instanceId, worldName);
        LOGGER.at(Level.WARNING).log(
                "Admin force-ended instance %s (was %s), evacuating roster across %s",
                instanceId,
                preparation.instance().state(),
                describeCleanupTargets(cleanupWorldNames)
        );

        return performEndCleanup(instanceId, preparation.roster(), cleanupWorldNames, "Force-end");
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
    private EndPreparation prepareEndInstance(@Nonnull String instanceId) throws SQLException {
        return database.transaction(conn -> {
            DungeonInstance instance = instanceRepository.findByIdInTransaction(conn, instanceId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot end instance " + instanceId + ": not found"));

            if (instance.state() == DungeonInstanceState.ENDED) {
                return EndPreparation.retryingCleanup(
                        instance,
                        membershipRepository.findPlayerIdsByInstanceInTransaction(conn, instanceId)
                );
            }

            if (instance.state() != DungeonInstanceState.ACTIVE) {
                throw new IllegalStateException(
                        "Cannot end instance " + instanceId + ": current state is " + instance.state());
            }

            if (!instanceRepository.claimEndStateInTransaction(conn, instanceId)) {
                throw new IllegalStateException(
                        "Cannot end instance " + instanceId + ": current state is " + instance.state());
            }

            return EndPreparation.claimed(
                    instance,
                    membershipRepository.findPlayerIdsByInstanceInTransaction(conn, instanceId)
            );
        });
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

        return runtimeAdapter.finalizeWorld(world, origin, readyInstance, result)
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
            @Nonnull String nextTheme,
            @Nonnull Vec3i origin,
            @Nonnull GenerationResult result,
            @Nonnull String oldWorldName,
            @Nonnull TransitionRuntimeState transitionState
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
            nextTheme,
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
            nextTheme,
            previousFloor.seed(),
            previousFloor.createdAt()
        );

        LOGGER.atInfo().log(
            "Generated floor %d for instance %s, preparing to transition from world %s to %s",
            nextFloor,
            previousFloor.instanceId(),
            oldWorldName,
            newWorld.worldName()
        );

        return ensureTransitionNotForceEnded(previousFloor.instanceId(), transitionState)
            .thenCompose(unused -> runtimeAdapter.finalizeWorld(newWorld, origin, updatedInstance, result))
                .thenCompose(unused -> updatePersistedInstanceWhileTransitioning(
                    updatedInstance,
                    transitionState,
                    "persist transition metadata"
                ))
                .thenCompose(unused -> ensureTransitionNotForceEnded(previousFloor.instanceId(), transitionState))
                .thenCompose(unused -> CompletableFuture.runAsync(
                    () -> {},
                    CompletableFuture.delayedExecutor(POST_FINALIZE_TELEPORT_DELAY_MS, TimeUnit.MILLISECONDS)
                ))
                .thenCompose(unused -> runtimeAdapter.teleportRoster(roster, newWorld, entrancePosition))
                .thenCompose(unused -> ensureTransitionNotForceEnded(previousFloor.instanceId(), transitionState))
                // === Transfer boundary: past this point, new world metadata is authoritative ===
                .thenCompose(unused -> completePostTransferTransition(
                    activeInstance, oldWorldName, nextFloor, newWorld.worldName(), transitionState
                ));
    }

    @Nonnull
    private <T> CompletableFuture<T> handleTransitionFailure(
            @Nonnull DungeonInstance previousFloor,
            @Nonnull String newWorldName,
            @Nonnull TransitionRuntimeState transitionState,
            @Nonnull Throwable throwable
    ) {
        Throwable failure = unwrapFailure(throwable);

        if (failure instanceof TransferCompletedException transferCompleted) {
            runtimeTransitionStates.remove(previousFloor.instanceId(), transitionState);
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
        if (failure instanceof TransitionCancelledException) {
            LOGGER.at(Level.WARNING).log(
                    "Floor transition for instance %s was cancelled because the instance was force-ended",
                    previousFloor.instanceId()
            );
            return CompletableFuture.failedFuture(failure);
        }

        LOGGER.at(Level.WARNING)
                .withCause(failure)
                .log("Floor transition failed for instance %s (pre-transfer): %s",
                        previousFloor.instanceId(),
                        describeFailure(failure));

        try {
            if (!newWorldName.equals(previousFloor.worldName())) {
                runtimeAdapter.cleanupWorld(newWorldName);
            }
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
            if (!instanceRepository.updateIfState(reverted, DungeonInstanceState.TRANSITIONING)
                    && !transitionState.isForceEnded()) {
                LOGGER.at(Level.WARNING).log(
                        "Skipped reverting instance %s to ACTIVE because its state changed unexpectedly",
                        previousFloor.instanceId()
                );
            }
        } catch (SQLException revertError) {
            failure.addSuppressed(revertError);
            LOGGER.at(Level.SEVERE)
                    .withCause(revertError)
                    .log("Failed to revert instance %s to ACTIVE after transition failure",
                            previousFloor.instanceId());
        }
        if (!transitionState.isForceEnded()) {
            runtimeTransitionStates.remove(previousFloor.instanceId(), transitionState);
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
            @Nonnull String newWorldName,
            @Nonnull TransitionRuntimeState transitionState
    ) {
        Objects.requireNonNull(activeInstance, "activeInstance");
        Objects.requireNonNull(oldWorldName, "oldWorldName");
        Objects.requireNonNull(newWorldName, "newWorldName");
        Objects.requireNonNull(transitionState, "transitionState");

        return persistPostTransferActiveState(activeInstance, transitionState)
                .thenCompose(completion -> runtimeAdapter.armWorldRemoval(oldWorldName)
                        .handle((unused, throwable) -> completion.withCleanupFailure(
                                throwable == null ? null : unwrapFailure(throwable))))
                .thenCompose(completion -> {
                    Throwable combinedFailure = completion.combinedFailure();
                    if (combinedFailure != null) {
                        return CompletableFuture.failedFuture(
                                new TransferCompletedException(activeInstance, combinedFailure));
                    }
                    runtimeTransitionStates.remove(activeInstance.instanceId(), transitionState);
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
            @Nonnull DungeonInstance activeInstance,
            @Nonnull TransitionRuntimeState transitionState
    ) {
        Objects.requireNonNull(activeInstance, "activeInstance");
        Objects.requireNonNull(transitionState, "transitionState");
        runtimeActiveInstanceOverrides.put(activeInstance.instanceId(), activeInstance);

        return updatePersistedInstanceWhileTransitioning(
                activeInstance,
                transitionState,
                "persist ACTIVE state"
        )
                .thenApply(unused -> {
                    runtimeActiveInstanceOverrides.remove(activeInstance.instanceId(), activeInstance);
                    return PostTransferCompletion.success(activeInstance);
                })
                .exceptionallyCompose(throwable -> retryPersistPostTransferActiveState(
                        activeInstance,
                        transitionState,
                        unwrapFailure(throwable)
                ));
    }

    @Nonnull
    private CompletableFuture<PostTransferCompletion> retryPersistPostTransferActiveState(
            @Nonnull DungeonInstance activeInstance,
            @Nonnull TransitionRuntimeState transitionState,
            @Nonnull Throwable firstFailure
    ) {
        if (firstFailure instanceof TransitionCancelledException) {
            runtimeActiveInstanceOverrides.remove(activeInstance.instanceId(), activeInstance);
            return CompletableFuture.failedFuture(firstFailure);
        }
        LOGGER.at(Level.WARNING)
                .withCause(firstFailure)
                .log("Failed to persist ACTIVE state for instance %s after transfer; retrying once",
                        activeInstance.instanceId());

        return updatePersistedInstanceWhileTransitioning(
                activeInstance,
                transitionState,
                "persist ACTIVE state"
        )
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
        return Collections.unmodifiableSet(roster);
    }

    @Nonnull
    private DungeonConfig buildGenerationConfig(
            @Nonnull String worldName,
            int floorLevel,
            @Nonnull Vec3i origin,
            @Nonnull String theme
    ) {
        DungeonConfig resolved = floorConfigService.resolveConfigForFloor(floorLevel, theme);
        return new DungeonConfig(
                null,
                null,
                worldName,
                origin,
                resolved.layout(),
                resolved.theme(),
                resolved.pacing(),
                true,
                floorLevel
        );
    }

    @Nonnull
    private String resolveActiveThemeForFloor(int floorLevel) {
        List<String> resolvedThemes = floorConfigService.getThemeVariantsForFloor(floorLevel);
        List<String> availableThemes = resolvedThemes.stream()
                .filter(themeAvailabilityChecker)
                .toList();
        if (availableThemes.isEmpty()) {
            return ThemeConfig.defaults().palette();
        }
        return availableThemes.get(ThreadLocalRandom.current().nextInt(availableThemes.size()));
    }

    private int resolveDayTimeForFloor(int floorLevel) {
        List<Integer> dayTimes = floorConfigService.getDayTimesForFloor(floorLevel);
        return dayTimes.get(ThreadLocalRandom.current().nextInt(dayTimes.size()));
    }

    private static boolean isRuntimeThemeAvailable(@Nonnull String themeId) {
        Objects.requireNonNull(themeId, "themeId");
        return AssetRegistry.getAssetStore(DungeonThemeConfig.class) != null
                && DungeonThemeConfig.get(themeId) != null;
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

    @Nullable
    private static Throwable unwrapFailureOrNull(@Nullable Throwable throwable) {
        return throwable != null ? unwrapFailure(throwable) : null;
    }

    @Nonnull
    private static CompletableFuture<Void> ensureTransitionNotForceEnded(
            @Nonnull String instanceId,
            @Nonnull TransitionRuntimeState transitionState
    ) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(transitionState, "transitionState");
        if (!transitionState.isForceEnded()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(new TransitionCancelledException(instanceId));
    }

    @Nonnull
    private CompletableFuture<Void> updatePersistedInstanceWhileTransitioning(
            @Nonnull DungeonInstance instance,
            @Nonnull TransitionRuntimeState transitionState,
            @Nonnull String actionDescription
    ) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(transitionState, "transitionState");
        Objects.requireNonNull(actionDescription, "actionDescription");
        try {
            if (instanceRepository.updateIfState(instance, DungeonInstanceState.TRANSITIONING)) {
                return CompletableFuture.completedFuture(null);
            }
            if (transitionState.isForceEnded()) {
                return CompletableFuture.failedFuture(new TransitionCancelledException(instance.instanceId()));
            }
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot " + actionDescription + " for instance " + instance.instanceId()
                            + ": lifecycle state changed unexpectedly"
            ));
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Nonnull
    private List<String> resolveCleanupWorldNames(@Nonnull String instanceId, @Nonnull String persistedWorldName) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(persistedWorldName, "persistedWorldName");
        TransitionRuntimeState transitionState = runtimeTransitionStates.get(instanceId);
        if (transitionState == null) {
            return List.of(persistedWorldName);
        }
        return transitionState.cleanupWorldNames(persistedWorldName);
    }

    @Nonnull
    private CompletableFuture<Void> performEndCleanup(
            @Nonnull String instanceId,
            @Nonnull Set<UUID> roster,
            @Nonnull List<String> cleanupWorldNames,
            @Nonnull String flowName
    ) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(cleanupWorldNames, "cleanupWorldNames");
        Objects.requireNonNull(flowName, "flowName");
        CleanupFailureTracker failures = new CleanupFailureTracker();

        return runEvacuationPass(roster, cleanupWorldNames, failures)
                .thenCompose(unused -> runRemovalArmingPass(cleanupWorldNames, failures))
                .thenCompose(unused -> {
                    IllegalStateException failure = failures.toException(instanceId);
                    if (failure == null) {
                        runtimeTransitionStates.remove(instanceId);
                        return CompletableFuture.completedFuture(null);
                    }
                    LOGGER.at(Level.WARNING)
                            .withCause(failure)
                            .log("%s flow for instance %s did not complete cleanly", flowName, instanceId);
                    return CompletableFuture.failedFuture(failure);
                });
    }

    @Nonnull
    private CompletableFuture<Void> runEvacuationPass(
            @Nonnull Set<UUID> roster,
            @Nonnull List<String> cleanupWorldNames,
            @Nonnull CleanupFailureTracker failures
    ) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String worldName : cleanupWorldNames) {
            future = future.thenCompose(unused -> runtimeAdapter.evacuateToSharedWorld(roster, worldName)
                    .handle((ignored, throwable) -> {
                        Throwable failure = unwrapFailureOrNull(throwable);
                        if (failure != null) {
                            failures.recordEvacuationFailure(worldName, failure);
                        }
                        return null;
                    }));
        }
        return future;
    }

    @Nonnull
    private CompletableFuture<Void> runRemovalArmingPass(
            @Nonnull List<String> cleanupWorldNames,
            @Nonnull CleanupFailureTracker failures
    ) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String worldName : cleanupWorldNames) {
            future = future.thenCompose(unused -> runtimeAdapter.armWorldRemoval(worldName)
                    .handle((ignored, throwable) -> {
                        Throwable failure = unwrapFailureOrNull(throwable);
                        if (failure != null) {
                            failures.recordArmFailure(worldName, failure);
                        }
                        return null;
                    }));
        }
        return future;
    }

    @Nonnull
    private static String describeCleanupTargets(@Nonnull List<String> cleanupWorldNames) {
        Objects.requireNonNull(cleanupWorldNames, "cleanupWorldNames");
        return cleanupWorldNames.size() == 1
                ? "world " + cleanupWorldNames.get(0)
                : "worlds " + cleanupWorldNames;
    }

    @Nonnull
    static CompletableFuture<Transform> resolveSpawnOnWorldThread(
            @Nonnull Function<Supplier<Transform>, CompletableFuture<Transform>> dispatcher,
            @Nonnull Supplier<Transform> spawnLookup,
            @Nonnull String missingSpawnMessage
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(spawnLookup, "spawnLookup");
        Objects.requireNonNull(missingSpawnMessage, "missingSpawnMessage");
        return dispatcher.apply(spawnLookup)
                .thenCompose(transform -> transform != null
                        ? CompletableFuture.completedFuture(transform)
                        : CompletableFuture.failedFuture(new IllegalStateException(missingSpawnMessage)));
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
            @Nonnull Vec3i origin,
            int dayTime
        );

        @Nonnull
        CompletableFuture<GenerationResult> generate(@Nonnull DungeonConfig config);

        @Nonnull
        CompletableFuture<Void> finalizeWorld(
                @Nonnull InstanceWorld world,
                @Nonnull Vec3i origin,
            @Nonnull DungeonInstance floorInstance,
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

        /**
         * Evacuates the given players to the shared village hub world.
         *
         * <p>Only online players still present in the specified source world are teleported to
         * the default world's spawn position. Offline, invalid, or already-moved players are
         * skipped.
         *
         * @param playerIds        the players to evacuate
         * @param sourceWorldName the dungeon world players must still be in to be evacuated
         * @return a future that completes after all evacuation teleports have been queued
         */
        @Nonnull
        CompletableFuture<Void> evacuateToSharedWorld(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull String sourceWorldName
        );

        /**
         * Returns whether the named world is currently loaded in the runtime.
         *
         * @param worldName the world to check
         * @return {@code true} if the world is loaded
         */
        boolean isWorldLoaded(@Nonnull String worldName);

        /**
         * Teleports the given players into the target world, skipping players who are already in
         * it and players who are offline or invalid.
         *
         * @param playerIds        the players to route into the world
         * @param targetWorldName  the destination world name
         * @param entrancePosition the destination entrance position
         * @return a future that completes with the per-player outcome
         */
        @Nonnull
        CompletableFuture<TeleportOutcome> teleportTargetsIfOutsideWorld(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull String targetWorldName,
                @Nonnull Vec3i entrancePosition
        );
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
            @Nonnull Vec3i origin,
            int dayTime
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
            worldConfig.setPvpEnabled(false);
            worldConfig.setFallDamageEnabled(true);
            worldConfig.setTicking(true);
            worldConfig.setBlockTicking(false);
            worldConfig.setSpawningNPC(false);
            worldConfig.setSavingPlayers(true);
            worldConfig.setDeleteOnRemove(false);
            worldConfig.setDeleteOnUniverseStart(false);
            worldConfig.setGameTimePaused(true);
            worldConfig.setGameTime(WorldTimeResource.ZERO_YEAR.plus(dayTime, ChronoUnit.HOURS));
            worldConfig.setGameplayConfig("Dungeon");
            worldConfig.setGameMode(GameMode.Adventure);
            worldConfig.markChanged();

            return universe.makeWorld(worldName, savePath, worldConfig)
                    .thenApply(LiveInstanceWorld::new);
        }

        @Nonnull
        @Override
        public CompletableFuture<GenerationResult> generate(@Nonnull DungeonConfig config) {
            Objects.requireNonNull(config, "config");
            DuntalePlugin plugin = requirePlugin();
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
                @Nonnull DungeonInstance floorInstance,
                @Nonnull GenerationResult result
        ) {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(floorInstance, "floorInstance");
            Objects.requireNonNull(result, "result");

            World liveWorld = toLiveWorld(world);
            return queueWorldTask(liveWorld, () -> {
                WorldConfig worldConfig = liveWorld.getWorldConfig();
                worldConfig.setSpawnProvider(new GlobalSpawnProvider(toPlayerTransform(floorInstance.entrancePosition())));
                worldConfig.markChanged();

                Store<EntityStore> store = liveWorld.getEntityStore().getStore();
                DuntalePlugin plugin = requirePlugin();

                if (!result.spawnerDefinitions().isEmpty()) {
                    plugin.getSpawnerFactory().createSpawners(store, result.spawnerDefinitions(), origin);
                }
                if (!result.merchantDefinitions().isEmpty()) {
                    plugin.getMerchantNpcSpawner().spawnMerchants(store, result.merchantDefinitions(), origin);
                }
                if (!result.chestDefinitions().isEmpty()) {
                    // Chests fill on first open (with the opener's Luck) rather than up front.
                    plugin.getChestLootService().registerChests(
                            liveWorld,
                            origin,
                            result.chestDefinitions(),
                            floorInstance.floorLevel()
                    );
                }
                plugin.getDungeonEndPortalService().ensurePortal(liveWorld, store, floorInstance);
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
            // Release any unopened chest registrations for this world before it is removed.
            requirePlugin().getChestLootService().forgetWorld(worldName);
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
                LOGGER.at(Level.FINE).log(
                        "World %s is already absent — removal arming is no longer needed",
                        worldName
                );
                return CompletableFuture.completedFuture(null);
            }
            return queueWorldTask(world, () -> {
                Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
                chunkStore.getResource(InstanceDataResource.getResourceType()).setHadPlayer(true);
                WorldConfig worldConfig = world.getWorldConfig();
                worldConfig.setDeleteOnRemove(true);
                InstanceWorldConfig.ensureAndGet(worldConfig)
                        .setRemovalConditions(WorldEmptyCondition.REMOVE_WHEN_EMPTY);
                worldConfig.markChanged();
            });
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> evacuateToSharedWorld(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull String sourceWorldName
        ) {
            Objects.requireNonNull(playerIds, "playerIds");
            Objects.requireNonNull(sourceWorldName, "sourceWorldName");
            if (playerIds.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Universe is not available for player evacuation"));
            }

            World sharedWorld = universe.getWorld(SHARED_WORLD_NAME);
            if (sharedWorld == null) {
                sharedWorld = universe.getDefaultWorld();
            }
            if (sharedWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No shared world available for player evacuation"));
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>(playerIds.size());
            for (UUID playerId : playerIds) {
                futures.add(queueEvacuation(playerId, sourceWorldName, sharedWorld));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }

        @Override
        public boolean isWorldLoaded(@Nonnull String worldName) {
            Objects.requireNonNull(worldName, "worldName");
            Universe universe = Universe.get();
            return universe != null && universe.getWorld(worldName) != null;
        }

        @Nonnull
        @Override
        public CompletableFuture<TeleportOutcome> teleportTargetsIfOutsideWorld(
                @Nonnull Collection<UUID> playerIds,
                @Nonnull String targetWorldName,
                @Nonnull Vec3i entrancePosition
        ) {
            Objects.requireNonNull(playerIds, "playerIds");
            Objects.requireNonNull(targetWorldName, "targetWorldName");
            Objects.requireNonNull(entrancePosition, "entrancePosition");

            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Universe is not available for dungeon teleport"));
            }
            World targetWorld = universe.getWorld(targetWorldName);
            if (targetWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Target world " + targetWorldName + " is not loaded"));
            }

            Set<UUID> teleported = new LinkedHashSet<>();
            Set<UUID> alreadyInWorld = new LinkedHashSet<>();
            Set<UUID> offlineSkipped = new LinkedHashSet<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>(playerIds.size());

            for (UUID playerId : playerIds) {
                PlayerRef playerRef = universe.getPlayer(playerId);
                if (playerRef == null) {
                    offlineSkipped.add(playerId);
                    continue;
                }
                Ref<EntityStore> reference = playerRef.getReference();
                if (reference == null || !reference.isValid()) {
                    offlineSkipped.add(playerId);
                    continue;
                }
                Store<EntityStore> store = reference.getStore();
                World currentWorld = store.getExternalData().getWorld();
                if (currentWorld == null) {
                    offlineSkipped.add(playerId);
                    continue;
                }
                if (currentWorld != null && currentWorld.getName().equalsIgnoreCase(targetWorldName)) {
                    alreadyInWorld.add(playerId);
                    continue;
                }
                teleported.add(playerId);
                futures.add(queueTeleport(playerId, targetWorld, entrancePosition));
            }

            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(unused -> new TeleportOutcome(
                            Set.copyOf(teleported),
                            Set.copyOf(alreadyInWorld),
                            Set.copyOf(offlineSkipped)));
        }

        @Nonnull
        private static CompletableFuture<Void> queueEvacuation(
                @Nonnull UUID playerId,
                @Nonnull String sourceWorldName,
                @Nonnull World sharedWorld
        ) {
            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Universe is not available for player evacuation"));
            }

            PlayerRef playerRef = universe.getPlayer(playerId);
            if (playerRef == null) {
                LOGGER.at(Level.FINE).log("Skipping evacuation for offline player %s", playerId);
                return CompletableFuture.completedFuture(null);
            }

            UUID currentWorldId = playerRef.getWorldUuid();
            if (currentWorldId == null) {
                LOGGER.at(Level.FINE).log(
                        "Skipping evacuation for player %s because they are not currently in a world", playerId);
                return CompletableFuture.completedFuture(null);
            }

            World currentWorld = universe.getWorld(currentWorldId);
            if (currentWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Cannot evacuate player " + playerId + ": source world is not available")
                );
            }

            if (!currentWorld.getName().equalsIgnoreCase(sourceWorldName)) {
                LOGGER.at(Level.FINE).log(
                        "Skipping evacuation for player %s because they already left world %s",
                        playerId,
                        sourceWorldName
                );
                return CompletableFuture.completedFuture(null);
            }

            return resolveSpawnOnWorldThread(
                    action -> queueWorldTask(sharedWorld, action::get),
                    () -> sharedWorld.getWorldConfig()
                            .getSpawnProvider()
                            .getSpawnPoint(sharedWorld, playerId),
                    "Cannot evacuate player " + playerId
                            + ": shared world " + sharedWorld.getName()
                            + " does not provide a spawn point"
            ).thenCompose(spawnTransform -> queueTeleportToTransform(
                    playerId,
                    sourceWorldName,
                    sharedWorld,
                    spawnTransform
            ));
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
        private static CompletableFuture<Void> queueTeleportToTransform(
                @Nonnull UUID playerId,
                @Nonnull String sourceWorldName,
                @Nonnull World targetWorld,
                @Nonnull Transform targetTransform
        ) {
            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Universe is not available for player teleport")
                );
            }

            PlayerRef playerRef = universe.getPlayer(playerId);
            if (playerRef == null) {
                LOGGER.at(Level.FINE).log("Skipping evacuation for offline player %s", playerId);
                return CompletableFuture.completedFuture(null);
            }

            Ref<EntityStore> reference = playerRef.getReference();
            if (reference == null || !reference.isValid()) {
                LOGGER.at(Level.FINE).log(
                        "Skipping evacuation for player %s without an active entity ref", playerId);
                return CompletableFuture.completedFuture(null);
            }

            UUID currentWorldId = playerRef.getWorldUuid();
            if (currentWorldId == null) {
                LOGGER.at(Level.FINE).log(
                        "Skipping evacuation for player %s because they are not currently in a world", playerId);
                return CompletableFuture.completedFuture(null);
            }

            World currentWorld = universe.getWorld(currentWorldId);
            if (currentWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Cannot evacuate player " + playerId + ": source world is not available")
                );
            }
            if (!currentWorld.getName().equalsIgnoreCase(sourceWorldName)) {
                LOGGER.at(Level.FINE).log(
                        "Skipping evacuation for player %s because they already left world %s",
                        playerId,
                        sourceWorldName
                );
                return CompletableFuture.completedFuture(null);
            }

            Store<EntityStore> store = reference.getStore();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            try {
                currentWorld.execute(() -> {
                    try {
                        if (!reference.isValid()) {
                            LOGGER.at(Level.FINE).log(
                                    "Skipping evacuation for player %s because their entity ref became invalid",
                                    playerId
                            );
                            completion.complete(null);
                            return;
                        }

                        World liveSourceWorld = store.getExternalData().getWorld();
                        if (liveSourceWorld == null) {
                            completion.completeExceptionally(new IllegalStateException(
                                    "Cannot evacuate player " + playerId + ": no live source world found"));
                            return;
                        }
                        if (!liveSourceWorld.getName().equalsIgnoreCase(sourceWorldName)) {
                            LOGGER.at(Level.FINE).log(
                                    "Skipping evacuation for player %s because they already left world %s",
                                    playerId,
                                    sourceWorldName
                            );
                            completion.complete(null);
                            return;
                        }

                        Teleport teleportComponent = Teleport.createForPlayer(targetWorld, targetTransform);
                        CompletableFuture<Void> transferFuture = new CompletableFuture<>();
                        transferFuture.whenComplete((unused, throwable) -> {
                            if (throwable != null) {
                                completion.completeExceptionally(throwable);
                                return;
                            }
                            completion.complete(null);
                        });
                        teleportComponent.setOnComplete(transferFuture);
                        store.addComponent(reference, Teleport.getComponentType(), teleportComponent);
                    } catch (Exception e) {
                        completion.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                completion.completeExceptionally(e);
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
        private static <T> CompletableFuture<T> queueWorldTask(
                @Nonnull World world,
                @Nonnull CheckedSupplier<T> action
        ) {
            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                world.execute(() -> {
                    try {
                        future.complete(action.get());
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
        private static DuntalePlugin requirePlugin() {
            DuntalePlugin plugin = DuntalePlugin.get();
            if (plugin == null) {
                throw new IllegalStateException("DuntalePlugin is not available");
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

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    // ============================================
    // Exception Types
    // ============================================

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

    private static final class TransitionCancelledException extends RuntimeException {

        private TransitionCancelledException(@Nonnull String instanceId) {
            super("Transition for instance " + instanceId + " was cancelled because it was force-ended");
        }
    }

    private static final class TransitionRuntimeState {

        private final String oldWorldName;
        private final String newWorldName;
        private volatile boolean forceEnded;

        private TransitionRuntimeState(@Nonnull String oldWorldName, @Nonnull String newWorldName) {
            this.oldWorldName = Objects.requireNonNull(oldWorldName, "oldWorldName");
            this.newWorldName = Objects.requireNonNull(newWorldName, "newWorldName");
        }

        private void markForceEnded() {
            this.forceEnded = true;
        }

        private boolean isForceEnded() {
            return forceEnded;
        }

        @Nonnull
        private List<String> cleanupWorldNames(@Nonnull String persistedWorldName) {
            LinkedHashSet<String> worlds = new LinkedHashSet<>();
            worlds.add(oldWorldName);
            worlds.add(newWorldName);
            worlds.add(Objects.requireNonNull(persistedWorldName, "persistedWorldName"));
            return List.copyOf(worlds);
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

    private static final class CleanupFailureTracker {

        private final List<String> evacuationWorlds = new ArrayList<>();
        private final List<String> armWorlds = new ArrayList<>();
        private final List<Throwable> causes = new ArrayList<>();

        private void recordEvacuationFailure(@Nonnull String worldName, @Nonnull Throwable failure) {
            evacuationWorlds.add(Objects.requireNonNull(worldName, "worldName"));
            causes.add(Objects.requireNonNull(failure, "failure"));
        }

        private void recordArmFailure(@Nonnull String worldName, @Nonnull Throwable failure) {
            armWorlds.add(Objects.requireNonNull(worldName, "worldName"));
            causes.add(Objects.requireNonNull(failure, "failure"));
        }

        @Nullable
        private IllegalStateException toException(@Nonnull String instanceId) {
            Objects.requireNonNull(instanceId, "instanceId");
            if (causes.isEmpty()) {
                return null;
            }

            StringBuilder message = new StringBuilder("Failed to end instance ")
                    .append(instanceId)
                    .append(" cleanly:");
            if (!evacuationWorlds.isEmpty()) {
                message.append(" evacuation failed for ").append(evacuationWorlds);
            }
            if (!armWorlds.isEmpty()) {
                if (!evacuationWorlds.isEmpty()) {
                    message.append(';');
                }
                message.append(" removal arming failed for ").append(armWorlds);
            }

            IllegalStateException failure = new IllegalStateException(message.toString(), causes.get(0));
            for (int i = 1; i < causes.size(); i++) {
                Throwable suppressed = causes.get(i);
                if (suppressed != causes.get(0)) {
                    failure.addSuppressed(suppressed);
                }
            }
            return failure;
        }
    }

    private record EndPreparation(
            @Nonnull DungeonInstance instance,
            @Nonnull Set<UUID> roster,
            boolean retryingCleanup
    ) {

        @Nonnull
        private static EndPreparation claimed(@Nonnull DungeonInstance instance, @Nonnull Set<UUID> roster) {
            return new EndPreparation(instance, Set.copyOf(roster), false);
        }

        @Nonnull
        private static EndPreparation retryingCleanup(@Nonnull DungeonInstance instance, @Nonnull Set<UUID> roster) {
            return new EndPreparation(instance, Set.copyOf(roster), true);
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

    /**
     * Database-side preparation result for a floor transition before runtime player filtering.
     *
     * @param instance                   the active instance metadata before transition claim
     * @param activeRosterAfterExpansion the active roster after any party expansion
     * @param triggerPartyRoster         the trigger player's current party roster, or empty for
     *                                   non-party/admin preparations
     * @param expandedPartyMembers       party members whose membership changed during expansion
     * @param reactivatedMembers         party members reactivated from {@code LEFT}
     * @param addedMembers               party members added with a new active membership
     * @param migratedMembers            party members migrated out of another active instance
     * @param abandonments               prior-instance abandonments produced by migration
     */
    public record FloorTransitionPreparation(
            @Nonnull DungeonInstance instance,
            @Nonnull Set<UUID> activeRosterAfterExpansion,
            @Nonnull Set<UUID> triggerPartyRoster,
            @Nonnull Set<UUID> expandedPartyMembers,
            @Nonnull Set<UUID> reactivatedMembers,
            @Nonnull Set<UUID> addedMembers,
            @Nonnull Set<UUID> migratedMembers,
            @Nonnull List<PreviousInstanceAbandonment> abandonments
    ) {

        /** Canonical constructor with defensive copying. */
        public FloorTransitionPreparation {
            Objects.requireNonNull(instance, "instance");
            activeRosterAfterExpansion = Set.copyOf(activeRosterAfterExpansion);
            triggerPartyRoster = Set.copyOf(triggerPartyRoster);
            expandedPartyMembers = Set.copyOf(expandedPartyMembers);
            reactivatedMembers = Set.copyOf(reactivatedMembers);
            addedMembers = Set.copyOf(addedMembers);
            migratedMembers = Set.copyOf(migratedMembers);
            abandonments = List.copyOf(abandonments);
        }
    }

    /**
     * Runtime-selected floor transition request.
     *
     * @param instanceId      the instance to transition
     * @param transferPlayers the active players to teleport to the next floor
     */
    public record FloorTransitionRequest(
            @Nonnull String instanceId,
            @Nonnull Set<UUID> transferPlayers,
            @Nullable Integer targetFloorLevel
    ) {

        /** Canonical constructor with validation and defensive copying. */
        public FloorTransitionRequest {
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            transferPlayers = normalizeRoster(transferPlayers);
        }

        public FloorTransitionRequest(
                @Nonnull String instanceId,
                @Nonnull Set<UUID> transferPlayers
        ) {
            this(instanceId, transferPlayers, null);
        }
    }

    /**
     * Records the effect of migrating a single roster member out of a prior active instance.
     *
     * @param instanceId      the prior instance the member left
     * @param worldName       the prior instance world name
     * @param playersLeft     the member(s) marked {@code LEFT} for this record
     * @param emptiedInstance {@code true} if this departure left the prior instance with no
     *                        remaining active members
     */
    public record PreviousInstanceAbandonment(
            @Nonnull String instanceId,
            @Nonnull String worldName,
            @Nonnull Set<UUID> playersLeft,
            boolean emptiedInstance
    ) {

        /** Canonical constructor with defensive copying. */
        public PreviousInstanceAbandonment {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(worldName, "worldName");
            playersLeft = Set.copyOf(playersLeft);
        }
    }

    /**
     * Per-player outcome of a {@link RuntimeAdapter#teleportTargetsIfOutsideWorld} call.
     *
     * @param teleported     players for whom a teleport was queued
     * @param alreadyInWorld players already located in the target world
     * @param offlineSkipped players skipped because they were offline or invalid
     */
    public record TeleportOutcome(
            @Nonnull Set<UUID> teleported,
            @Nonnull Set<UUID> alreadyInWorld,
            @Nonnull Set<UUID> offlineSkipped
    ) {

        /** Canonical constructor with defensive copying. */
        public TeleportOutcome {
            teleported = Set.copyOf(teleported);
            alreadyInWorld = Set.copyOf(alreadyInWorld);
            offlineSkipped = Set.copyOf(offlineSkipped);
        }
    }

    /**
     * Result of a {@link #continueInstanceForPlayer(UUID)} call.
     *
     * @param partyOwnerInitiated {@code true} when the initiator owned a party and group expansion
     *                            was attempted
     * @param teleported          players for whom a teleport was queued
     * @param alreadyInWorld      players already located in the target world
     * @param offlineSkipped      players skipped because they were offline or invalid
     */
    public record ContinueTeleportResult(
            boolean partyOwnerInitiated,
            @Nonnull Set<UUID> teleported,
            @Nonnull Set<UUID> alreadyInWorld,
            @Nonnull Set<UUID> offlineSkipped
    ) {

        /** Canonical constructor with defensive copying. */
        public ContinueTeleportResult {
            teleported = Set.copyOf(teleported);
            alreadyInWorld = Set.copyOf(alreadyInWorld);
            offlineSkipped = Set.copyOf(offlineSkipped);
        }
    }

    /** Classification of a {@link #leaveInstanceForPlayer(UUID)} result. */
    public enum LeaveStatus {

        /** The player was not in any active instance. */
        NOT_IN_INSTANCE,

        /** The player left, but other active members remain in the instance. */
        LEFT_WITH_REMAINING,

        /** The player was the last active member, so the instance was ended. */
        ENDED_LAST_MEMBER
    }

    /**
     * Result of a {@link #leaveInstanceForPlayer(UUID)} call.
     *
     * @param status   the leave outcome classification
     * @param instance the instance the player left, or {@code null} when not in an instance
     */
    public record DungeonLeaveResult(@Nonnull LeaveStatus status, @Nullable DungeonInstance instance) {

        /** Canonical constructor validating the status. */
        public DungeonLeaveResult {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Thrown when a non-owner party member attempts to start a dungeon run. Only the current party
     * owner may start a run for the party.
     */
    public static class PartyStartPermissionException extends RuntimeException {

        private final UUID playerId;

        /**
         * Creates a new permission exception.
         *
         * @param playerId the non-owner player who attempted the start
         */
        public PartyStartPermissionException(@Nonnull UUID playerId) {
            super("Player " + playerId + " is not the party owner and cannot start a dungeon run");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        /**
         * Returns the non-owner player who attempted the start.
         *
         * @return the player UUID
         */
        @Nonnull
        public UUID getPlayerId() {
            return playerId;
        }
    }

    /**
     * Thrown when a roster member's prior instance is still {@code CREATING} or
     * {@code TRANSITIONING} and therefore cannot be migrated from during a new start.
     */
    public static class UnsafePriorInstanceException extends RuntimeException {

        private final UUID playerId;
        private final String priorInstanceId;
        private final transient DungeonInstanceState priorState;

        /**
         * Creates a new unsafe-prior-instance exception.
         *
         * @param playerId        the roster member with the unsafe prior instance
         * @param priorInstanceId the prior instance identifier
         * @param priorState      the prior instance state
         */
        public UnsafePriorInstanceException(
                @Nonnull UUID playerId,
                @Nonnull String priorInstanceId,
                @Nonnull DungeonInstanceState priorState
        ) {
            super("Player " + playerId + " has a prior instance " + priorInstanceId
                    + " in state " + priorState + " that cannot be migrated");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.priorInstanceId = Objects.requireNonNull(priorInstanceId, "priorInstanceId");
            this.priorState = Objects.requireNonNull(priorState, "priorState");
        }

        /**
         * Returns the roster member with the unsafe prior instance.
         *
         * @return the player UUID
         */
        @Nonnull
        public UUID getPlayerId() {
            return playerId;
        }

        /**
         * Returns the prior instance identifier.
         *
         * @return the prior instance id
         */
        @Nonnull
        public String getPriorInstanceId() {
            return priorInstanceId;
        }

        /**
         * Returns the prior instance state.
         *
         * @return the prior instance state
         */
        @Nonnull
        public DungeonInstanceState getPriorState() {
            return priorState;
        }
    }

    /**
     * Thrown when a Continue group expansion cannot proceed because bringing the owner's party into
     * the instance would exceed the active member cap. Members active in a different instance are
     * migrated rather than blocked, so this is now only raised for capacity.
     */
    public static class ContinueRosterExpansionException extends RuntimeException {

        private final Set<UUID> blockedPlayers;
        private final boolean capacityExceeded;

        /**
         * Creates a new expansion exception.
         *
         * @param blockedPlayers   the members locked into a different active instance
         * @param capacityExceeded {@code true} when the expansion would exceed the active cap
         */
        public ContinueRosterExpansionException(@Nonnull Set<UUID> blockedPlayers, boolean capacityExceeded) {
            super(capacityExceeded
                    ? "Continue expansion would exceed the active member cap"
                    : "Continue expansion blocked by members in another active instance: " + blockedPlayers);
            this.blockedPlayers = Set.copyOf(blockedPlayers);
            this.capacityExceeded = capacityExceeded;
        }

        /**
         * Returns the members locked into a different active instance.
         *
         * @return immutable set of blocked player UUIDs
         */
        @Nonnull
        public Set<UUID> getBlockedPlayers() {
            return blockedPlayers;
        }

        /**
         * Returns whether the failure was caused by exceeding the active member cap.
         *
         * @return {@code true} if the cap would have been exceeded
         */
        public boolean isCapacityExceeded() {
            return capacityExceeded;
        }
    }

    /**
     * Thrown when a Continue request cannot proceed because the target dungeon world is not
     * currently loaded. No membership changes are made when this is thrown.
     */
    public static class ContinueWorldUnavailableException extends RuntimeException {

        private final transient DungeonInstance instance;

        /**
         * Creates a new world-unavailable exception.
         *
         * @param instance the instance whose world is not loaded
         */
        public ContinueWorldUnavailableException(@Nonnull DungeonInstance instance) {
            super("Dungeon world " + instance.worldName() + " for instance "
                    + instance.instanceId() + " is not loaded");
            this.instance = Objects.requireNonNull(instance, "instance");
        }

        /**
         * Returns the instance whose world is not loaded.
         *
         * @return the instance
         */
        @Nonnull
        public DungeonInstance getInstance() {
            return instance;
        }
    }
}
