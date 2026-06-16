package com.duntale.death;

import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceService;
import com.duntale.dungeon.DungeonInstanceState;
import com.duntale.economy.GoldService;
import com.duntale.progression.PricingRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Resolves dungeon death context and owns gold-cost calculations for dungeon respawn choices.
 */
public class DungeonRespawnService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Per-floor current-floor respawn cost used when no reconciled tuning asset is loaded. */
    public static final long CURRENT_FLOOR_COST_MULTIPLIER = 500L;

    /** Per-floor restart-lower cost used when no reconciled tuning asset is loaded. */
    public static final long LOWER_FLOOR_COST_MULTIPLIER = 300L;

    private final DungeonInstanceService dungeonInstanceService;
    private final GoldService goldService;

    /** Optional source of the income-derived respawn curve; {@code null}/unloaded uses the multipliers above. */
    @Nullable
    private final PricingRegistry pricingRegistry;

    /**
     * Creates a dungeon respawn service using the historic flat per-floor cost multipliers.
     *
     * @param dungeonInstanceService the dungeon lifecycle service
     * @param goldService the gold service used for balance checks and charges
     */
    public DungeonRespawnService(
            @Nonnull DungeonInstanceService dungeonInstanceService,
            @Nonnull GoldService goldService
    ) {
        this(dungeonInstanceService, goldService, null);
    }

    /**
     * Creates a dungeon respawn service whose costs can be driven by the reconciled tuning asset.
     *
     * @param dungeonInstanceService the dungeon lifecycle service
     * @param goldService the gold service used for balance checks and charges
     * @param pricingRegistry the pricing registry supplying the income-derived respawn curve, or {@code null}
     */
    public DungeonRespawnService(
            @Nonnull DungeonInstanceService dungeonInstanceService,
            @Nonnull GoldService goldService,
            @Nullable PricingRegistry pricingRegistry
    ) {
        this.dungeonInstanceService = Objects.requireNonNull(dungeonInstanceService, "dungeonInstanceService");
        this.goldService = Objects.requireNonNull(goldService, "goldService");
        this.pricingRegistry = pricingRegistry;
    }

    /**
     * Resolves a dungeon death context for a player in the given world.
     *
     * <p>Returns empty when the player has no active dungeon, the active instance is not
     * {@link DungeonInstanceState#ACTIVE}, the active instance world differs from the death
     * world, or the persistence lookup fails.
     *
     * @param playerId the dead player's UUID
     * @param currentWorldName the world where the death occurred
     * @param deathReason the formatted death reason, or {@code null} when unavailable
     * @return the dungeon death context, or empty when the built-in death flow should handle it
     */
    @Nonnull
    public Optional<DungeonDeathContext> resolveContext(
            @Nonnull UUID playerId,
            @Nullable String currentWorldName,
            @Nullable Message deathReason
    ) {
        Objects.requireNonNull(playerId, "playerId");
        if (currentWorldName == null || currentWorldName.isBlank()) {
            return Optional.empty();
        }

        DungeonInstance instance;
        try {
            instance = dungeonInstanceService.getActiveInstance(playerId);
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING)
                    .withCause(e)
                    .log("Failed to resolve active dungeon death context for player %s", playerId);
            return Optional.empty();
        }

        if (instance == null
                || instance.state() != DungeonInstanceState.ACTIVE
                || !instance.worldName().equals(currentWorldName)) {
            return Optional.empty();
        }

        long balance = Math.max(0L, goldService.getBalance(playerId));
        return Optional.of(createContext(instance, deathReason, balance));
    }

    /**
     * Calculates the cost to respawn on the current floor.
     *
     * @param floorLevel the current floor level
     * @return the current-floor respawn cost
     */
    public long currentFloorCost(int floorLevel) {
        validateFloorLevel(floorLevel);
        Long scheduled = resolveScheduledCost(floorLevel);
        // The income-derived schedule is an absolute per-floor-band cost; the flat per-floor
        // multiplier is the safe fallback when no pricing asset (or schedule) is loaded.
        return scheduled != null
                ? scheduled : Math.multiplyExact((long) floorLevel, CURRENT_FLOOR_COST_MULTIPLIER);
    }

    /**
     * Calculates the cost to restart one floor lower.
     *
     * @param floorLevel the current floor level
     * @return the lower-floor restart cost
     */
    public long lowerFloorCost(int floorLevel) {
        validateFloorLevel(floorLevel);
        Long scheduled = resolveScheduledCost(floorLevel);
        if (scheduled != null) {
            // Restart-lower is a fraction of the income-derived current-floor cost.
            return Math.round(scheduled * pricingRegistry.respawnRestartFraction());
        }
        return Math.multiplyExact((long) floorLevel, LOWER_FLOOR_COST_MULTIPLIER);
    }

    /**
     * Resolves the income-derived current-floor respawn cost from the pricing schedule, or
     * {@code null} when no pricing asset (or schedule) is loaded and the flat fallback applies.
     */
    @Nullable
    private Long resolveScheduledCost(int floorLevel) {
        return pricingRegistry != null && pricingRegistry.isLoaded()
                ? pricingRegistry.resolveRespawnCost(floorLevel) : null;
    }

    /**
     * Returns whether a lower-floor restart is available for the instance.
     *
     * @param instance the dungeon instance
     * @return {@code true} when the current floor is greater than one
     */
    public boolean lowerFloorAvailable(@Nonnull DungeonInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return instance.floorLevel() > 1;
    }

    /**
     * Charges the player for a paid dungeon death option.
     *
     * @param playerId the player UUID
     * @param cost the gold cost to remove
     * @return {@code true} when the charge succeeded
     */
    public boolean chargeGold(@Nonnull UUID playerId, long cost) {
        Objects.requireNonNull(playerId, "playerId");
        validateCost(cost);
        return cost == 0L || goldService.removeGold(playerId, cost);
    }

    /**
     * Refunds gold after a paid dungeon death option fails before delivery.
     *
     * @param playerId the player UUID
     * @param cost the gold cost to refund
     * @return {@code true} when no refund was needed or the refund succeeded
     */
    public boolean refundGold(@Nonnull UUID playerId, long cost) {
        Objects.requireNonNull(playerId, "playerId");
        validateCost(cost);
        if (cost == 0L) {
            return true;
        }
        boolean refunded = goldService.addGold(playerId, cost);
        if (!refunded) {
            LOGGER.at(Level.WARNING).log("Failed to refund %d gold to player %s", cost, playerId);
        }
        return refunded;
    }

    /**
     * Restarts an existing dungeon instance on the requested floor for its captured roster.
     *
     * @param instanceId the current dungeon instance ID
     * @param floorLevel the target floor level
     * @return a future that completes with the new active dungeon instance
     * @throws SQLException if the current roster or force-end preparation cannot be loaded
     */
    @Nonnull
    public CompletableFuture<DungeonInstance> restartInstanceAtFloor(
            @Nonnull String instanceId,
            int floorLevel
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return dungeonInstanceService.restartInstanceAtFloor(instanceId, floorLevel);
    }

    @Nonnull
    private DungeonDeathContext createContext(
            @Nonnull DungeonInstance instance,
            @Nullable Message deathReason,
            long balance
    ) {
        Objects.requireNonNull(instance, "instance");
        return new DungeonDeathContext(
                instance,
                deathReason,
                balance,
                currentFloorCost(instance.floorLevel()),
                lowerFloorCost(instance.floorLevel()),
                lowerFloorAvailable(instance)
        );
    }

    private static void validateFloorLevel(int floorLevel) {
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }
    }

    private static void validateCost(long cost) {
        if (cost < 0L) {
            throw new IllegalArgumentException("cost must not be negative");
        }
    }
}