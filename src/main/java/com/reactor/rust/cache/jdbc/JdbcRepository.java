package com.reactor.rust.cache.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public abstract class JdbcRepository implements AutoCloseable {

    private final DataSource dataSource;
    private final boolean schemaInit;
    private volatile boolean initialized;

    protected JdbcRepository(DataSource dataSource, boolean schemaInit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemaInit = schemaInit;
    }

    protected final void ensureInitialized() {
        if (!schemaInit || initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try (Connection connection = dataSource.getConnection()) {
                initializeSchema(connection);
                initialized = true;
            } catch (Exception e) {
                throw new IllegalStateException("JDBC schema initialization failed", e);
            }
        }
    }

    protected void initializeSchema(Connection connection) throws Exception {
        // Default: no schema bootstrap. Samples can override for local/demo databases.
    }

    protected final <T> List<T> query(
            String operation,
            String sql,
            SqlBinder binder,
            RowMapper<T> mapper) {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return rows;
            }
        } catch (Exception e) {
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    protected final <T> T queryOne(
            String operation,
            String sql,
            SqlBinder binder,
            RowMapper<T> mapper,
            T emptyValue) {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapper.map(resultSet) : emptyValue;
            }
        } catch (Exception e) {
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    protected final int update(String operation, String sql, SqlBinder binder) {
        ensureInitialized();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    protected final List<String> queryStrings(String operation, String sql, String columnName) {
        return query(operation, sql, SqlBinder.none(), row -> row.getString(columnName)).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    protected final <T> void forEachIdPage(
            int pageSize,
            int maxPageSize,
            Function<Long, List<T>> pageFetcher,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> pageConsumer) {
        int boundedPageSize = Math.max(1, Math.min(pageSize, maxPageSize));
        long lastSeenId = 0;
        while (true) {
            List<T> page = pageFetcher.apply(lastSeenId);
            if (page.isEmpty()) {
                return;
            }
            pageConsumer.accept(page);
            if (page.size() < boundedPageSize) {
                return;
            }
            lastSeenId = idExtractor.applyAsLong(page.get(page.size() - 1));
        }
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("JDBC datasource close failed", e);
            }
        }
    }

    @FunctionalInterface
    public interface SqlBinder {
        void bind(PreparedStatement statement) throws Exception;

        static SqlBinder none() {
            return statement -> {};
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws Exception;
    }
}
