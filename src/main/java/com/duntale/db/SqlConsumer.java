package com.duntale.db;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A consumer that accepts a JDBC {@link Connection} and performs an operation.
 *
 * @since 1.5.0
 */
@FunctionalInterface
public interface SqlConsumer {

    /**
     * Performs this operation on the given connection.
     *
     * @param connection the JDBC connection (managed by {@link DatabaseProvider})
     * @throws SQLException if a database access error occurs
     */
    void accept(@Nonnull Connection connection) throws SQLException;
}
