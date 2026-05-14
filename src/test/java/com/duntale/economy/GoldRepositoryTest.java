package com.duntale.economy;

import com.duntale.db.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GoldRepository")
class GoldRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private GoldRepository repository;

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("Should return starting balance for players without a wallet row")
    void shouldReturnStartingBalanceForPlayersWithoutWalletRow() throws SQLException {
        openRepository();

        assertEquals(GoldRepository.STARTING_BALANCE, repository.getBalance(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Should add deltas on top of the starting balance")
    void shouldAddDeltasOnTopOfStartingBalance() throws SQLException {
        openRepository();
        UUID playerId = UUID.randomUUID();

        repository.addBalance(playerId, 250L);

        assertEquals(GoldRepository.STARTING_BALANCE + 250L, repository.getBalance(playerId));
    }

    @Test
    @DisplayName("Should let explicit balances override the starting balance")
    void shouldLetExplicitBalancesOverrideStartingBalance() throws SQLException {
        openRepository();
        UUID playerId = UUID.randomUUID();

        repository.setBalance(playerId, 100L);

        assertEquals(100L, repository.getBalance(playerId));
    }

    @Test
    @DisplayName("Should transfer from and to starting balances")
    void shouldTransferFromAndToStartingBalances() throws SQLException {
        openRepository();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        GoldRepository.TransferResult result = repository.transfer(senderId, receiverId, 400L);

        assertTrue(result.success());
        assertEquals(GoldRepository.STARTING_BALANCE, result.fromOldBalance());
        assertEquals(GoldRepository.STARTING_BALANCE - 400L, result.fromNewBalance());
        assertEquals(GoldRepository.STARTING_BALANCE, result.toOldBalance());
        assertEquals(GoldRepository.STARTING_BALANCE + 400L, result.toNewBalance());
        assertEquals(GoldRepository.STARTING_BALANCE - 400L, repository.getBalance(senderId));
        assertEquals(GoldRepository.STARTING_BALANCE + 400L, repository.getBalance(receiverId));
    }

    @Test
    @DisplayName("Should reject transfers above the starting balance")
    void shouldRejectTransfersAboveStartingBalance() throws SQLException {
        openRepository();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        GoldRepository.TransferResult result = repository.transfer(
                senderId,
                receiverId,
                GoldRepository.STARTING_BALANCE + 1L
        );

        assertFalse(result.success());
        assertEquals(GoldRepository.STARTING_BALANCE, repository.getBalance(senderId));
        assertEquals(GoldRepository.STARTING_BALANCE, repository.getBalance(receiverId));
    }

    private void openRepository() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));
        repository = new GoldRepository(database);
        repository.initialize();
    }
}