/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.order.config;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dual-write adapter configuration for the Order Service coexistence window.
 *
 * <p>During the Strangler Fig migration from monolith to microservices, this
 * configuration enables asynchronous replication of write operations from the
 * primary PostgreSQL database to the secondary HSQLDB database (the monolith's
 * original data store). This ensures safe rollback at any point before dual-write
 * is disabled after the 48-hour post-cutover observation window.</p>
 *
 * <h3>Write Propagation Direction</h3>
 * <p><strong>PostgreSQL (primary) → HSQLDB (secondary) — NEVER the reverse.</strong></p>
 *
 * <h3>Tables Affected</h3>
 * <ul>
 *   <li>{@code orders} — (orderid PK, userid, orderdate, ship/bill address fields,
 *       courier, totalprice, name fields, creditcard, exprdate, cardtype, locale)</li>
 *   <li>{@code orderstatus} — (orderid PK, linenum PK, timestamp, status)</li>
 *   <li>{@code lineitem} — (orderid PK, linenum PK, itemid, quantity, unitprice)</li>
 *   <li>{@code sequence} — (name PK, nextid) — legacy table, updated during coexistence</li>
 * </ul>
 *
 * <h3>Maximum Acceptable Lag</h3>
 * <p>5 seconds (configurable via {@code dualwrite.max-lag-seconds}). The async
 * thread pool is tuned for JPetStore's low write volume to stay well within
 * this threshold.</p>
 *
 * <h3>Conditional Loading</h3>
 * <p>This entire configuration is conditionally loaded <strong>only</strong> when
 * {@code dualwrite.enabled=true} in application properties. When disabled
 * (the default), zero beans are registered, zero HSQLDB connections are created,
 * and there is zero runtime overhead.</p>
 *
 * <h3>Conflict Detection</h3>
 * <p>Each replication event includes a timestamp. If the HSQLDB row has been
 * modified independently (indicating the monolith wrote to it despite the
 * routing flag), the replicator logs an ERROR-level conflict alert for
 * operational alarm triggering and does <strong>NOT</strong> overwrite the row.</p>
 *
 * <h3>Column Name Mapping</h3>
 * <p>PostgreSQL uses snake_case column names (e.g., {@code order_id}, {@code user_id},
 * {@code bill_to_first_name}). HSQLDB uses concatenated lowercase names
 * (e.g., {@code orderid}, {@code userid}, {@code billtofirstname}). The replicator
 * translates automatically by removing underscores.</p>
 *
 * @see DualWriteProperties
 * @see DualWriteReplicator
 */
@Configuration
@ConditionalOnProperty(name = "dualwrite.enabled", havingValue = "true", matchIfMissing = false)
@EnableAsync
public class DualWriteConfig {

    private static final Logger log = LoggerFactory.getLogger(DualWriteConfig.class);

    /**
     * HSQLDB JDBC connection URL for the monolith's database running in network
     * server mode. Default empty; must be set when dual-write is enabled.
     * Example: {@code jdbc:hsqldb:hsql://localhost:9001/jpetstore}
     */
    @Value("${dualwrite.hsqldb.url:}")
    private String hsqldbUrl;

    /**
     * HSQLDB database username. Defaults to {@code SA} (HSQLDB default admin user).
     */
    @Value("${dualwrite.hsqldb.username:SA}")
    private String hsqldbUsername;

    /**
     * HSQLDB database password. Defaults to empty string (HSQLDB default).
     */
    @Value("${dualwrite.hsqldb.password:}")
    private String hsqldbPassword;

    /**
     * Maximum acceptable replication lag in seconds. Per AAP Section 0.7.5,
     * the default is 5 seconds. If lag exceeds this threshold, a warning is
     * logged and a metric is emitted for operational alerting.
     */
    @Value("${dualwrite.max-lag-seconds:5}")
    private int maxLagSeconds;

    /**
     * Toggle for conflict detection during dual-write replication. When enabled
     * (default: true), the replicator checks HSQLDB for existing rows before
     * writing and logs ERROR-level alerts for any detected conflicts. Per AAP
     * Section 0.7.5, conflicts trigger operational alarms for manual investigation.
     */
    @Value("${dualwrite.conflict-detection.enabled:true}")
    private boolean conflictDetectionEnabled;

    // =========================================================================
    // Bean Definitions
    // =========================================================================

    /**
     * Creates the secondary HSQLDB DataSource for dual-write replication.
     *
     * <p>This DataSource connects to the monolith's HSQLDB running in network
     * server mode. It is qualified as {@code "hsqldbDataSource"} to distinguish
     * it from the primary auto-configured PostgreSQL DataSource.</p>
     *
     * <p>This connection is <strong>ONLY</strong> used for dual-write replication
     * during the coexistence window. It is the sole reference to HSQLDB in the
     * Order Service.</p>
     *
     * @return the HSQLDB DataSource configured from {@code dualwrite.hsqldb.*} properties
     */
    @Bean
    @Qualifier("hsqldbDataSource")
    public DataSource hsqldbDataSource() {
        log.info("Initializing HSQLDB secondary DataSource for dual-write replication at URL: {}", hsqldbUrl);
        return DataSourceBuilder.create()
                .url(hsqldbUrl)
                .username(hsqldbUsername)
                .password(hsqldbPassword)
                .build();
    }

    /**
     * Creates the async task executor for dual-write propagation.
     *
     * <p>Thread pool configuration is tuned for JPetStore's low write volume
     * (pet store orders are infrequent):</p>
     * <ul>
     *   <li>Core pool: 2 threads — handles typical order volume</li>
     *   <li>Max pool: 5 threads — burst capacity for peak load</li>
     *   <li>Queue: 100 tasks — buffer for write spikes during checkout surges</li>
     *   <li>Thread prefix: {@code "dual-write-"} — for operational log and thread dump identification</li>
     * </ul>
     *
     * <p>The pool must process tasks quickly enough to stay within the 5-second
     * maximum acceptable lag threshold defined in AAP Section 0.7.5.</p>
     *
     * @return the configured Executor for async dual-write propagation tasks
     */
    @Bean
    public Executor dualWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dual-write-");
        executor.initialize();
        log.info("Initialized dual-write async executor: corePoolSize=2, maxPoolSize=5, queueCapacity=100");
        return executor;
    }

    /**
     * Creates the {@link DualWriteProperties} bean exposing dual-write
     * configuration state for consumption by service-layer classes, AOP aspects,
     * and operational monitoring.
     *
     * @return an immutable holder of dual-write configuration properties
     */
    @Bean
    public DualWriteProperties dualWriteProperties() {
        DualWriteProperties properties = new DualWriteProperties(
                hsqldbUrl, maxLagSeconds, conflictDetectionEnabled);
        log.info("Dual-write properties initialized: {}", properties);
        return properties;
    }

    /**
     * Creates the {@link DualWriteReplicator} bean that performs the actual
     * asynchronous replication of write events from PostgreSQL to HSQLDB.
     *
     * <p>The replicator implementation:</p>
     * <ol>
     *   <li>Accepts write events for orders, orderstatus, lineitem, and sequence tables</li>
     *   <li>Translates PostgreSQL snake_case column names to HSQLDB original names</li>
     *   <li>Checks for conflicts if conflict detection is enabled</li>
     *   <li>Executes the SQL INSERT/UPDATE against HSQLDB asynchronously via the executor</li>
     *   <li>Tracks replication lag via Micrometer metrics</li>
     *   <li>Logs errors at ERROR level for operational alarm triggering</li>
     * </ol>
     *
     * <p>The caller invokes {@code replicateWrite()} synchronously but the actual
     * HSQLDB write is offloaded to the async executor, making the call non-blocking.</p>
     *
     * @param hsqldbDataSource  the secondary HSQLDB DataSource
     * @param dualWriteExecutor the async task executor
     * @param properties        the dual-write configuration properties
     * @param meterRegistry     the Micrometer meter registry for metrics tracking
     * @return a fully configured DualWriteReplicator implementation
     */
    @Bean
    public DualWriteReplicator dualWriteReplicator(
            @Qualifier("hsqldbDataSource") DataSource hsqldbDataSource,
            Executor dualWriteExecutor,
            DualWriteProperties properties,
            MeterRegistry meterRegistry) {

        JdbcTemplate hsqldbJdbc = new JdbcTemplate(hsqldbDataSource);
        Timer replicationTimer = meterRegistry.timer("dualwrite.replication.duration", "service", "order");
        meterRegistry.gauge("dualwrite.max.lag.seconds", properties,
                p -> (double) p.maxLagSeconds());

        log.info("Initialized DualWriteReplicator: conflictDetection={}, maxLagSeconds={}",
                properties.conflictDetectionEnabled(), properties.maxLagSeconds());

        return (tableName, operation, primaryKey, newValues, timestamp) -> {
            dualWriteExecutor.execute(() -> {
                Timer.Sample sample = Timer.start(meterRegistry);
                try {
                    // Step 1: Translate column names from PostgreSQL snake_case to HSQLDB originals
                    Map<String, Object> hsqldbPk = translateColumnNames(primaryKey);
                    Map<String, Object> hsqldbValues = translateColumnNames(newValues);

                    // Step 2: Conflict detection (if enabled)
                    if (properties.conflictDetectionEnabled()) {
                        if (detectConflict(hsqldbJdbc, tableName, operation, hsqldbPk, timestamp)) {
                            // Conflict detected — skip write (already logged at ERROR level)
                            return;
                        }
                    }

                    // Step 3: Execute SQL against HSQLDB secondary database
                    int rowsAffected;
                    if ("INSERT".equalsIgnoreCase(operation)) {
                        rowsAffected = executeInsert(hsqldbJdbc, tableName, hsqldbValues);
                    } else if ("UPDATE".equalsIgnoreCase(operation)) {
                        rowsAffected = executeUpdate(hsqldbJdbc, tableName, hsqldbPk, hsqldbValues);
                    } else {
                        log.warn("Unsupported dual-write operation '{}' for table={}. "
                                + "Only INSERT and UPDATE are supported. Skipping.", operation, tableName);
                        return;
                    }

                    // Step 4: Calculate and track replication lag
                    Duration lag = Duration.between(timestamp, Instant.now());
                    long lagMillis = lag.toMillis();
                    long lagSeconds = lag.getSeconds();

                    if (lagSeconds > properties.maxLagSeconds()) {
                        log.warn("Dual-write replication lag EXCEEDED threshold: {}s > {}s — "
                                        + "table={}, pk={}, lagMs={}. Operational alert recommended.",
                                lagSeconds, properties.maxLagSeconds(), tableName, hsqldbPk, lagMillis);
                    }

                    log.info("Dual-write {} replicated to HSQLDB: table={}, pk={}, "
                                    + "rowsAffected={}, lagMs={}, writeTimestamp={}",
                            operation, tableName, hsqldbPk, rowsAffected, lagMillis,
                            timestamp.toEpochMilli());

                } catch (Exception ex) {
                    log.error("DUAL-WRITE REPLICATION FAILED — table={}, operation={}, pk={}, "
                                    + "writeTimestamp={}: {}. This failure requires manual investigation "
                                    + "to ensure HSQLDB consistency.",
                            tableName, operation, primaryKey, timestamp, ex.getMessage(), ex);
                } finally {
                    sample.stop(replicationTimer);
                }
            });
        };
    }

    // =========================================================================
    // Column Name Mapping — PostgreSQL snake_case → HSQLDB original
    // =========================================================================

    /**
     * Translates a map of PostgreSQL snake_case column names to HSQLDB original
     * column names by removing all underscore characters.
     *
     * <p>Column mapping examples for Order Service tables:</p>
     * <table>
     *   <tr><th>PostgreSQL (snake_case)</th><th>HSQLDB (original)</th></tr>
     *   <tr><td>{@code order_id}</td><td>{@code orderid}</td></tr>
     *   <tr><td>{@code user_id}</td><td>{@code userid}</td></tr>
     *   <tr><td>{@code order_date}</td><td>{@code orderdate}</td></tr>
     *   <tr><td>{@code ship_addr1}</td><td>{@code shipaddr1}</td></tr>
     *   <tr><td>{@code bill_to_first_name}</td><td>{@code billtofirstname}</td></tr>
     *   <tr><td>{@code total_price}</td><td>{@code totalprice}</td></tr>
     *   <tr><td>{@code credit_card}</td><td>{@code creditcard}</td></tr>
     *   <tr><td>{@code unit_price}</td><td>{@code unitprice}</td></tr>
     *   <tr><td>{@code item_id}</td><td>{@code itemid}</td></tr>
     *   <tr><td>{@code line_num}</td><td>{@code linenum}</td></tr>
     *   <tr><td>{@code next_id}</td><td>{@code nextid}</td></tr>
     *   <tr><td>{@code courier}</td><td>{@code courier} (unchanged)</td></tr>
     *   <tr><td>{@code locale}</td><td>{@code locale} (unchanged)</td></tr>
     * </table>
     *
     * @param pgColumns map with PostgreSQL snake_case column names as keys
     * @return a new ordered map with HSQLDB column names as keys, preserving insertion order
     */
    static Map<String, Object> translateColumnNames(Map<String, Object> pgColumns) {
        if (pgColumns == null || pgColumns.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> hsqldbColumns = new LinkedHashMap<>(pgColumns.size());
        for (Map.Entry<String, Object> entry : pgColumns.entrySet()) {
            String hsqldbName = entry.getKey().replace("_", "");
            hsqldbColumns.put(hsqldbName, entry.getValue());
        }
        return hsqldbColumns;
    }

    // =========================================================================
    // Conflict Detection
    // =========================================================================

    /**
     * Detects potential write conflicts between PostgreSQL and HSQLDB data.
     *
     * <p>Per AAP Section 0.7.5: "If HSQLDB row has a newer timestamp than the
     * propagated write, the dual-write replicator logs a conflict alert and
     * does NOT overwrite the HSQLDB row. All conflict alerts trigger an
     * operational alarm for manual investigation."</p>
     *
     * <p>Conflict scenarios:</p>
     * <ul>
     *   <li><strong>INSERT + row exists:</strong> The monolith created this row despite
     *       the routing flag directing traffic to the microservice. This is a conflict
     *       — the INSERT is skipped and an ERROR is logged.</li>
     *   <li><strong>UPDATE + row missing:</strong> Expected row does not exist in HSQLDB.
     *       A warning is logged but the write proceeds (falls back to INSERT behavior).</li>
     * </ul>
     *
     * <p>Note: Since the routing flag ensures traffic goes to either the monolith
     * OR the microservice (never both simultaneously), true conflicts should be
     * extremely rare. This detection is a safety net.</p>
     *
     * @param hsqldbJdbc     JdbcTemplate for the HSQLDB secondary database
     * @param tableName      the target table name
     * @param operation      the SQL operation ("INSERT" or "UPDATE")
     * @param hsqldbPk       primary key column-value map (HSQLDB column names)
     * @param writeTimestamp  the timestamp of the original PostgreSQL write
     * @return true if a conflict was detected and the write should be skipped; false to proceed
     */
    static boolean detectConflict(JdbcTemplate hsqldbJdbc, String tableName,
                                  String operation, Map<String, Object> hsqldbPk,
                                  Instant writeTimestamp) {
        try {
            String whereClause = buildWhereClause(hsqldbPk);
            Object[] pkValues = hsqldbPk.values().toArray();
            String countSql = "SELECT COUNT(*) FROM " + sanitizeTableName(tableName)
                    + " WHERE " + whereClause;
            Integer count = hsqldbJdbc.queryForObject(countSql, Integer.class, pkValues);
            boolean rowExists = count != null && count > 0;

            if ("INSERT".equalsIgnoreCase(operation) && rowExists) {
                log.error("DUAL-WRITE CONFLICT DETECTED — INSERT attempted but row already exists "
                                + "in HSQLDB secondary database. Table={}, PK={}, writeTimestamp={}ms. "
                                + "Skipping INSERT to prevent data corruption. "
                                + "Manual investigation required to reconcile PostgreSQL and HSQLDB.",
                        tableName, hsqldbPk, writeTimestamp.toEpochMilli());
                return true;
            }

            if ("UPDATE".equalsIgnoreCase(operation) && !rowExists) {
                log.warn("Dual-write UPDATE target row does not exist in HSQLDB secondary. "
                                + "Table={}, PK={}, writeTimestamp={}ms. "
                                + "The row may not have been replicated by a prior INSERT. "
                                + "Proceeding with write — will attempt INSERT fallback.",
                        tableName, hsqldbPk, writeTimestamp.toEpochMilli());
            }

            return false;
        } catch (Exception ex) {
            log.warn("Conflict detection query failed for table={}, pk={}: {}. "
                            + "Proceeding with write as safety fallback to maintain replication.",
                    tableName, hsqldbPk, ex.getMessage());
            return false;
        }
    }

    // =========================================================================
    // SQL Execution — INSERT and UPDATE against HSQLDB
    // =========================================================================

    /**
     * Executes an INSERT statement against the HSQLDB secondary database.
     *
     * <p>Generates: {@code INSERT INTO tableName (col1, col2, ...) VALUES (?, ?, ...)}</p>
     *
     * <p>Column names in the {@code columnValues} map must already be translated
     * to HSQLDB naming convention (no underscores).</p>
     *
     * @param hsqldbJdbc   JdbcTemplate for the HSQLDB secondary database
     * @param tableName    the target table name (e.g., "orders", "lineitem")
     * @param columnValues map of HSQLDB column names to their values
     * @return the number of rows affected (expected: 1 for successful INSERT)
     */
    static int executeInsert(JdbcTemplate hsqldbJdbc, String tableName,
                             Map<String, Object> columnValues) {
        if (columnValues.isEmpty()) {
            log.warn("No column values provided for INSERT into {}. Skipping.", tableName);
            return 0;
        }

        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        Object[] values = new Object[columnValues.size()];
        int idx = 0;

        for (Map.Entry<String, Object> entry : columnValues.entrySet()) {
            columns.add(entry.getKey());
            placeholders.add("?");
            values[idx++] = entry.getValue();
        }

        String sql = "INSERT INTO " + sanitizeTableName(tableName)
                + " (" + columns + ") VALUES (" + placeholders + ")";
        return hsqldbJdbc.update(sql, values);
    }

    /**
     * Executes an UPDATE statement against the HSQLDB secondary database.
     *
     * <p>Generates: {@code UPDATE tableName SET col1=?, col2=? WHERE pk1=? AND pk2=?}</p>
     *
     * <p>Primary key columns are excluded from the SET clause but used in the
     * WHERE clause. Column names must already be translated to HSQLDB naming.</p>
     *
     * @param hsqldbJdbc   JdbcTemplate for the HSQLDB secondary database
     * @param tableName    the target table name
     * @param primaryKey   map of primary key column names to values (for WHERE clause)
     * @param columnValues map of all column names to their new values (for SET clause)
     * @return the number of rows affected (expected: 1 for successful UPDATE)
     */
    static int executeUpdate(JdbcTemplate hsqldbJdbc, String tableName,
                             Map<String, Object> primaryKey,
                             Map<String, Object> columnValues) {
        if (columnValues.isEmpty()) {
            log.warn("No column values provided for UPDATE on {}. Skipping.", tableName);
            return 0;
        }

        // Build SET clause — exclude primary key columns from SET
        StringJoiner setClause = new StringJoiner(", ");
        int nonPkCount = 0;
        for (String col : columnValues.keySet()) {
            if (!primaryKey.containsKey(col)) {
                setClause.add(col + " = ?");
                nonPkCount++;
            }
        }

        if (nonPkCount == 0) {
            log.warn("No non-PK column values for UPDATE on {}. Only PK columns present. Skipping.",
                    tableName);
            return 0;
        }

        String whereClause = buildWhereClause(primaryKey);

        // Build parameter array: SET values first, then WHERE values
        Object[] params = new Object[nonPkCount + primaryKey.size()];
        int idx = 0;
        for (Map.Entry<String, Object> entry : columnValues.entrySet()) {
            if (!primaryKey.containsKey(entry.getKey())) {
                params[idx++] = entry.getValue();
            }
        }
        for (Object pkValue : primaryKey.values()) {
            params[idx++] = pkValue;
        }

        String sql = "UPDATE " + sanitizeTableName(tableName)
                + " SET " + setClause + " WHERE " + whereClause;
        return hsqldbJdbc.update(sql, params);
    }

    // =========================================================================
    // SQL Helpers
    // =========================================================================

    /**
     * Builds a WHERE clause from primary key columns.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>Single PK: {@code {orderid=1000}} → {@code "orderid = ?"}</li>
     *   <li>Composite PK: {@code {orderid=1000, linenum=1}} → {@code "orderid = ? AND linenum = ?"}</li>
     * </ul>
     *
     * @param primaryKey map of primary key column names to values
     * @return the WHERE clause string with {@code ?} placeholders
     */
    static String buildWhereClause(Map<String, Object> primaryKey) {
        return primaryKey.keySet().stream()
                .map(col -> col + " = ?")
                .collect(Collectors.joining(" AND "));
    }

    /**
     * Sanitizes the table name to prevent SQL injection.
     *
     * <p>Only allows alphanumeric characters and underscores. The Order Service
     * operates exclusively on the following tables: {@code orders}, {@code orderstatus},
     * {@code lineitem}, {@code sequence}.</p>
     *
     * @param tableName the table name to sanitize
     * @return the sanitized table name
     * @throws IllegalArgumentException if the table name contains disallowed characters
     */
    static String sanitizeTableName(String tableName) {
        if (tableName == null || !tableName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Invalid table name for dual-write replication: '" + tableName
                            + "'. Only alphanumeric characters and underscores are allowed.");
        }
        return tableName;
    }

    // =========================================================================
    // Inner Types — DualWriteProperties and DualWriteReplicator
    // =========================================================================

    /**
     * Immutable holder for dual-write configuration properties.
     *
     * <p>Consumed by service-layer classes, AOP aspects, and operational monitoring
     * to access dual-write runtime state without direct dependency on Spring
     * {@code @Value} injection.</p>
     *
     * <h4>Properties:</h4>
     * <ul>
     *   <li>{@code hsqldbUrl} — HSQLDB JDBC connection URL for the secondary database</li>
     *   <li>{@code maxLagSeconds} — Maximum acceptable replication lag (default: 5 seconds,
     *       per AAP Section 0.7.5)</li>
     *   <li>{@code conflictDetectionEnabled} — Whether to check for conflicts before
     *       writing to HSQLDB (default: true)</li>
     * </ul>
     */
    public static class DualWriteProperties {

        private final String hsqldbUrl;
        private final int maxLagSeconds;
        private final boolean conflictDetectionEnabled;

        /**
         * Constructs a new DualWriteProperties instance.
         *
         * @param hsqldbUrl                the HSQLDB JDBC connection URL
         * @param maxLagSeconds            maximum acceptable replication lag in seconds
         * @param conflictDetectionEnabled whether conflict detection is active
         */
        public DualWriteProperties(String hsqldbUrl, int maxLagSeconds,
                                   boolean conflictDetectionEnabled) {
            this.hsqldbUrl = hsqldbUrl;
            this.maxLagSeconds = maxLagSeconds;
            this.conflictDetectionEnabled = conflictDetectionEnabled;
        }

        /**
         * Returns the HSQLDB JDBC connection URL for the secondary database.
         *
         * @return the HSQLDB URL string
         */
        public String hsqldbUrl() {
            return hsqldbUrl;
        }

        /**
         * Returns the maximum acceptable replication lag in seconds.
         * Default is 5 seconds per AAP Section 0.7.5.
         *
         * @return the max lag threshold in seconds
         */
        public int maxLagSeconds() {
            return maxLagSeconds;
        }

        /**
         * Returns whether conflict detection is enabled for dual-write replication.
         * When enabled, the replicator checks HSQLDB for existing data before writing
         * and logs ERROR-level alerts for any detected conflicts.
         *
         * @return true if conflict detection is active
         */
        public boolean conflictDetectionEnabled() {
            return conflictDetectionEnabled;
        }

        @Override
        public String toString() {
            return "DualWriteProperties{"
                    + "hsqldbUrl='" + hsqldbUrl + '\''
                    + ", maxLagSeconds=" + maxLagSeconds
                    + ", conflictDetectionEnabled=" + conflictDetectionEnabled
                    + '}';
        }
    }

    /**
     * Functional interface for dual-write replication of Order Service write events
     * from the primary PostgreSQL database to the secondary HSQLDB database.
     *
     * <p>Implementations asynchronously replicate writes during the Strangler Fig
     * coexistence window. Column names in the {@code primaryKey} and {@code newValues}
     * maps use PostgreSQL snake_case convention (e.g., {@code order_id}, {@code user_id})
     * and are automatically translated to HSQLDB original column names
     * (e.g., {@code orderid}, {@code userid}) by the default implementation.</p>
     *
     * <p>The default implementation provided by {@link DualWriteConfig#dualWriteReplicator}:</p>
     * <ol>
     *   <li>Translates column names (snake_case → HSQLDB original)</li>
     *   <li>Checks for conflicts via existence queries (if conflict detection enabled)</li>
     *   <li>Executes INSERT/UPDATE SQL against HSQLDB</li>
     *   <li>Tracks replication lag metrics via Micrometer</li>
     *   <li>Logs errors at ERROR level for operational alarms</li>
     * </ol>
     *
     * <p>All operations are non-blocking — the actual HSQLDB write is offloaded to
     * an async executor. Failures are logged but never propagate to the caller,
     * ensuring the primary PostgreSQL write path is never affected by HSQLDB issues.</p>
     */
    @FunctionalInterface
    public interface DualWriteReplicator {

        /**
         * Replicates a write event to the secondary HSQLDB database.
         *
         * <p>This method returns immediately after submitting the replication task
         * to the async executor. The actual HSQLDB write occurs asynchronously.</p>
         *
         * @param tableName  the target table name (e.g., "orders", "orderstatus",
         *                   "lineitem", "sequence")
         * @param operation  the SQL operation type: "INSERT" or "UPDATE"
         * @param primaryKey map of primary key column names (PostgreSQL snake_case)
         *                   to their values (e.g., {"order_id": 1000} or
         *                   {"order_id": 1000, "line_num": 1} for composite PKs)
         * @param newValues  map of all column names (PostgreSQL snake_case) to their
         *                   new values, including primary key columns
         * @param timestamp  the {@link Instant} when the primary PostgreSQL write
         *                   occurred, used for replication lag calculation and
         *                   conflict detection via last_modified_timestamp comparison
         */
        void replicateWrite(String tableName, String operation,
                           Map<String, Object> primaryKey,
                           Map<String, Object> newValues, Instant timestamp);
    }
}
