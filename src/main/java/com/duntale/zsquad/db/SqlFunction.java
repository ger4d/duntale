package com.duntale.zsquad.db;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A function that accepts a JDBC {@link Connection} and returns a result.
 *
 * @param <T> the return type
 * @since 1.5.0
 */
@FunctionalInterface
public interface SqlFunction<T> {

    /**
     * Applies this function to the given connection.
     *
     * @param connection the JDBC connection (managed by {@link DatabaseProvider})
     * @return the result
     * @throws SQLException if a database access error occurs
     */
    T apply(@Nonnull Connection connection) throws SQLException;
}
