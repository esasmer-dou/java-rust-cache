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
import java.util.function.BiConsumer;

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

    protected final com.reactor.rust.jdbc.JdbcTransactions transactions() {
        return new com.reactor.rust.jdbc.JdbcTransactions(dataSource, this::ensureInitialized);
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

    /** Streams ordered groups through a bounded PostgreSQL cursor, never retaining all groups. */
    protected final <T, K> void forEachQueryGroup(String operation, String sql, SqlBinder binder,
            RowMapper<T> mapper, Function<T, K> key, int maxGroups, int maxRowsPerGroup,
            BiConsumer<K, List<T>> consumer) {
        forEachQueryGroup(operation, sql, null, binder, mapper, key, maxGroups, maxRowsPerGroup, consumer);
    }

    /** Uses one autocommit read for a small complete result, otherwise the original cursor. */
    protected final <T, K> void forEachQueryGroup(String operation, GroupQuery query, SqlBinder binder,
            RowMapper<T> mapper, Function<T, K> key, int maxGroups, int maxRowsPerGroup,
            BiConsumer<K, List<T>> consumer) {
        Objects.requireNonNull(query, "query");
        forEachQueryGroup(operation, query.sql, query.probe, binder, mapper, key,
                maxGroups, maxRowsPerGroup, consumer);
    }

    private <T, K> void forEachQueryGroup(String operation, String sql, String probe, SqlBinder binder,
            RowMapper<T> mapper, Function<T, K> key, int maxGroups, int maxRowsPerGroup,
            BiConsumer<K, List<T>> consumer) {
        if (maxGroups < 1 || maxRowsPerGroup < 1) throw new IllegalArgumentException("Group bounds must be positive");
        ensureInitialized();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            if (probe != null && autoCommit) {
                try (PreparedStatement statement = connection.prepareStatement(probe)) {
                    binder.bind(statement);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return;
                        if (!result.getBoolean(result.getMetaData().getColumnCount())) {
                            consumeGroups(result, true, mapper, key, maxGroups, maxRowsPerGroup, consumer);
                            return;
                        }
                        // No callback has run. A larger result is reread as one
                        // cursor snapshot, never combined with the bounded probe.
                    }
                }
            }
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(Math.min(256, maxRowsPerGroup));
                binder.bind(statement);
                try (ResultSet result = statement.executeQuery()) {
                    consumeGroups(result, false, mapper, key, maxGroups, maxRowsPerGroup, consumer);
                }
            } finally {
                // This helper owns a read-only cursor transaction, including callback failure.
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    private static <T, K> void consumeGroups(ResultSet result, boolean firstReady,
            RowMapper<T> mapper, Function<T, K> key, int maxGroups, int maxRowsPerGroup,
            BiConsumer<K, List<T>> consumer) throws Exception {
        K current = null;
        List<T> group = new ArrayList<>(Math.min(16, maxRowsPerGroup));
        int groups = 0;
        boolean ready = firstReady || result.next();
        while (ready) {
            T value = mapper.map(result);
            K next = key.apply(value);
            if (!group.isEmpty() && !Objects.equals(current, next)) {
                if (++groups > maxGroups) throw new IllegalStateException("Query group bound exceeded");
                consumer.accept(current, group);
                group = new ArrayList<>(Math.min(16, maxRowsPerGroup));
            }
            current = next;
            if (group.size() == maxRowsPerGroup) throw new IllegalStateException("Query row bound exceeded");
            group.add(value);
            ready = result.next();
        }
        if (!group.isEmpty()) {
            if (++groups > maxGroups) throw new IllegalStateException("Query group bound exceeded");
            consumer.accept(current, group);
        }
    }

    /** Startup-only SQL plan. The binder must be repeatable; an overflow starts the cursor read. */
    protected static final class GroupQuery {
        private final String sql;
        private final String probe;

        private GroupQuery(String sql, String probe) {
            this.sql = sql;
            this.probe = probe;
        }

        public static GroupQuery bounded(String sql, int probeRows, String orderBy) {
            if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Group SQL must not be blank");
            if (probeRows < 1 || probeRows > 256) throw new IllegalArgumentException("Probe rows must be 1..256");
            if (orderBy == null || !orderBy.matches("[A-Za-z_][A-Za-z0-9_]*(\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*"))
                throw new IllegalArgumentException("Group order must be a literal list of column names");
            String original = sql.strip();
            if (original.endsWith(";")) original = original.substring(0, original.length() - 1);
            String probe = "with reactor_group_probe as (select * from (" + original
                    + ") reactor_group_source limit " + (probeRows + 1) + ") "
                    + "select reactor_group_probe.*, (select count(*) > " + probeRows
                    + " from reactor_group_probe) as reactor_group_overflow from reactor_group_probe order by " + orderBy;
            return new GroupQuery(original, probe);
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
        List<String> values = query(operation, sql, SqlBinder.none(), row -> row.getString(columnName));
        values.removeIf(value -> value == null || value.isBlank());
        return values;
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
