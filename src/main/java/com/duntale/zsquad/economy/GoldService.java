package com.duntale.zsquad.economy;

import com.duntale.zsquad.rpg.RpgConstants;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Public API for gold economy operations.
 *
 * <p>All mutating methods are logged with player UUID, amount, old balance, and new balance.
 * SQL exceptions are caught internally and never propagate out of public API methods.
 */
public class GoldService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final GoldRepository repository;

    /** Optional listener notified after every gold balance change. */
    private GoldChangeListener changeListener;

    /**
     * Creates a new gold service backed by the given repository.
     *
     * @param repository the gold repository for persistence
     */
    public GoldService(@Nonnull GoldRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the listener notified after every gold balance mutation.
     *
     * @param listener the listener, or {@code null} to remove
     */
    public void setChangeListener(@Nullable GoldChangeListener listener) {
        this.changeListener = listener;
    }

    /**
     * Returns the gold balance for the given player.
     *
     * @param playerId the player's UUID
     * @return the player's balance, or {@code 0} if unavailable
     */
    public long getBalance(@Nonnull UUID playerId) {
        try {
            return repository.getBalance(playerId);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to get balance for %s: %s", playerId, e.getMessage());
            return 0;
        }
    }

    /**
     * Adds gold to a player's balance, clamping at {@link RpgConstants#MAX_GOLD_BALANCE}.
     *
     * @param playerId the player's UUID
     * @param amount   the amount of gold to add (must be positive)
     * @return {@code true} if the gold was added successfully, {@code false} if amount is
     *         not positive or a database error occurred
     */
    public boolean addGold(@Nonnull UUID playerId, long amount) {
        if (amount <= 0) {
            return false;
        }

        try {
            long oldBalance = repository.getBalance(playerId);
            long newBalance = Math.min(oldBalance + amount, RpgConstants.MAX_GOLD_BALANCE);
            repository.setBalance(playerId, newBalance);

            LOGGER.at(Level.INFO).log("addGold player=%s amount=%d old=%d new=%d",
                    playerId, amount, oldBalance, newBalance);
            notifyChange(playerId, newBalance);
            return true;
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to add gold for %s: %s", playerId, e.getMessage());
            return false;
        }
    }

    /**
     * Removes gold from a player's balance.
     *
     * @param playerId the player's UUID
     * @param amount   the amount of gold to remove (must be positive)
     * @return {@code true} if the gold was removed successfully, {@code false} if amount is
     *         not positive, the player has insufficient balance, or a database error occurred
     */
    public boolean removeGold(@Nonnull UUID playerId, long amount) {
        if (amount <= 0) {
            return false;
        }

        try {
            long oldBalance = repository.getBalance(playerId);
            if (oldBalance < amount) {
                return false;
            }

            long newBalance = oldBalance - amount;
            repository.setBalance(playerId, newBalance);

            LOGGER.at(Level.INFO).log("removeGold player=%s amount=%d old=%d new=%d",
                    playerId, amount, oldBalance, newBalance);
            notifyChange(playerId, newBalance);
            return true;
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to remove gold for %s: %s", playerId, e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether a player has at least the given amount of gold.
     *
     * @param playerId the player's UUID
     * @param cost     the required gold amount
     * @return {@code true} if the player's balance is at least {@code cost}
     */
    public boolean hasEnough(@Nonnull UUID playerId, long cost) {
        return getBalance(playerId) >= cost;
    }

    /**
     * Atomically transfers gold from one player to another via a database transaction.
     *
     * <p>The sender must have sufficient balance. The receiver's balance is clamped at
     * {@link RpgConstants#MAX_GOLD_BALANCE}.
     *
     * @param from   the sender's UUID
     * @param to     the receiver's UUID
     * @param amount the amount of gold to transfer (must be positive)
     * @return {@code true} if the transfer succeeded, {@code false} if amount is not positive,
     *         the sender has insufficient balance, or a database error occurred
     */
    public boolean transfer(@Nonnull UUID from, @Nonnull UUID to, long amount) {
        if (amount <= 0) {
            return false;
        }

        try {
            GoldRepository.TransferResult result = repository.transfer(from, to, amount);
            if (!result.success()) {
                return false;
            }

            LOGGER.at(Level.INFO).log(
                    "transfer from=%s to=%s amount=%d fromOld=%d fromNew=%d toOld=%d toNew=%d",
                    from, to, amount,
                    result.fromOldBalance(), result.fromNewBalance(),
                    result.toOldBalance(), result.toNewBalance());
            notifyChange(from, result.fromNewBalance());
            notifyChange(to, result.toNewBalance());
            return true;
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Transfer failed: %s", e.getMessage());
            return false;
        }
    }

    // ── Listener ─────────────────────────────────────────────────────

    private void notifyChange(@Nonnull UUID playerId, long newBalance) {
        GoldChangeListener listener = this.changeListener;
        if (listener != null) {
            listener.onGoldChanged(playerId, newBalance);
        }
    }

    /**
     * Listener interface for gold balance changes.
     */
    @FunctionalInterface
    public interface GoldChangeListener {

        /**
         * Called after a player's gold balance has changed.
         *
         * @param playerId   the player's UUID
         * @param newBalance the new gold balance
         */
        void onGoldChanged(@Nonnull UUID playerId, long newBalance);
    }
}
