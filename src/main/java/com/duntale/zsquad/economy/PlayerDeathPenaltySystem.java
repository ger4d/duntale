package com.duntale.zsquad.economy;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Deducts a percentage of the player's gold balance on death.
 *
 * <p>Extends {@link DeathSystems.OnDeathSystem} to intercept player death events.
 * Only applies to player entities (NPCs are filtered out by the query).
 *
 * <p>The penalty rate is configurable via {@link #DEATH_PENALTY_RATE}. A chat
 * message notifies the player of the gold lost.
 */
public class PlayerDeathPenaltySystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Fraction of gold lost on death (0.10 = 10%).
     */
    static final double DEATH_PENALTY_RATE = 0.10;

    /**
     * Minimum balance required to trigger a penalty.
     * Players with less than this amount lose nothing.
     */
    private static final long MIN_BALANCE_FOR_PENALTY = 10L;

    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_GOLD = "#FFD700";
    private static final String COLOR_GRAY = "#AAAAAA";

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            UUIDComponent.getComponentType()
    );

    private final GoldService goldService;

    /**
     * Creates a new player death penalty system.
     *
     * @param goldService the gold service for balance operations
     */
    public PlayerDeathPenaltySystem(@Nonnull GoldService goldService) {
        this.goldService = goldService;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }

        UUID playerId = uuidComponent.getUuid();
        long balance = goldService.getBalance(playerId);

        if (balance < MIN_BALANCE_FOR_PENALTY) {
            return;
        }

        long penalty = Math.max(1L, (long) (balance * DEATH_PENALTY_RATE));
        boolean removed = goldService.removeGold(playerId, penalty);

        if (!removed) {
            LOGGER.atWarning().log("Failed to apply death penalty for %s (balance=%d, penalty=%d)",
                    playerId, balance, penalty);
            return;
        }

        long newBalance = goldService.getBalance(playerId);
        LOGGER.atInfo().log("Death penalty: player=%s lost=%d old=%d new=%d",
                playerId, penalty, balance, newBalance);

        // Send death penalty chat notification
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            playerRef.sendMessage(
                    Message.raw("You lost ").color(COLOR_RED)
                            .insert(Message.raw(String.valueOf(penalty)).color(COLOR_GOLD).bold(true))
                            .insert(Message.raw(" gold on death. ").color(COLOR_RED))
                            .insert(Message.raw("(Remaining: " + newBalance + ")").color(COLOR_GRAY))
            );
        }
    }
}
