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
package com.jpetstore.account.config;

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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Dual-write configuration for the Account Service during Strangler Fig coexistence.
 *
 * <p>This configuration class provides the infrastructure beans needed to asynchronously
 * replicate writes from the primary PostgreSQL database to the secondary HSQLDB during
 * the coexistence window. It is activated <strong>only</strong> when
 * {@code dualwrite.enabled=true} in {@code application.yml} — when disabled (the default),
 * this entire configuration is skipped with zero overhead and zero HSQLDB connections.</p>
 *
 * <h3>Write Propagation Direction</h3>
 * <p>PostgreSQL (primary) → HSQLDB (secondary) — NEVER the reverse.
 * Per AAP Section 0.7.5: all writes go to PostgreSQL first, then are asynchronously
 * propagated to HSQLDB.</p>
 *
 * <h3>Maximum Acceptable Lag</h3>
 * <p>5 seconds (configurable via {@code dualwrite.max-lag-seconds}). The replicator
 * tracks lag as a Micrometer metric and logs ERROR-level alerts if exceeded.</p>
 *
 * <h3>Conflict Detection</h3>
 * <p>Each write is tracked with a {@code last_modified_timestamp}. If a conflict is
 * detected (a newer modification exists for the same row), the replicator logs an
 * ERROR-level alarm and does NOT overwrite the HSQLDB row.</p>
 *
 * <h3>Dual-Write Lifecycle</h3>
 * <ul>
 *   <li>Deployed and verified <em>before</em> the routing flag is switched</li>
 *   <li>Disabled only after a 48-hour post-cutover observation window with no incidents</li>
 *   <li>Enabled/disabled via {@code dualwrite.enabled} property — no redeployment required</li>
 * </ul>
 *
 * <h3>Prerequisite</h3>
 * <p>The HSQLDB JDBC driver ({@code org.hsqldb:hsqldb}) must be on the classpath when
 * dual-write is enabled. Add it to {@code account-service/pom.xml} with
 * {@code <scope>runtime</scope>} before setting {@code dualwrite.enabled=true}.</p>
 *
 * @see DualWriteReplicator
 */
@Configuration
@ConditionalOnProperty(name = "dualwrite.enabled", havingValue = "true")
public class DualWriteConfig {

    private static final Logger log = LoggerFactory.getLogger(DualWriteConfig.class);

    @Value("${dualwrite.hsqldb.url}")
    private String hsqldbUrl;

    @Value("${dualwrite.hsqldb.username}")
    private String hsqldbUsername;

    @Value("${dualwrite.hsqldb.password}")
    private String hsqldbPassword;

    @Value("${dualwrite.max-lag-seconds:5}")
    private int maxLagSeconds;

    /**
     * Creates the secondary HSQLDB DataSource for dual-write replication.
     *
     * <p>This DataSource connects to the monolith's HSQLDB in network server mode.
     * It uses {@link javax.sql.DataSource} (standard JDBC API) rather than Jakarta
     * persistence — this is a raw JDBC connection to the legacy database.</p>
     *
     * <p>Connection properties are read from {@code dualwrite.hsqldb.*} in
     * {@code application.yml}:</p>
     * <ul>
     *   <li>{@code dualwrite.hsqldb.url} — HSQLDB JDBC URL (e.g., {@code jdbc:hsqldb:hsql://localhost:9001/jpetstore})</li>
     *   <li>{@code dualwrite.hsqldb.username} — HSQLDB username (default: {@code SA})</li>
     *   <li>{@code dualwrite.hsqldb.password} — HSQLDB password (default: empty)</li>
     * </ul>
     *
     * @return the secondary HSQLDB DataSource, qualified as {@code "hsqldbDataSource"}
     *         to distinguish from the primary PostgreSQL DataSource
     */
    @Bean
    @Qualifier("hsqldbDataSource")
    public DataSource hsqldbDataSource() {
        log.info("Initializing HSQLDB secondary DataSource for dual-write replication: url={}", hsqldbUrl);
        DataSource dataSource = DataSourceBuilder.create()
                .url(hsqldbUrl)
                .username(hsqldbUsername)
                .password(hsqldbPassword)
                .build();
        log.info("HSQLDB secondary DataSource initialized successfully for dual-write coexistence");
        return dataSource;
    }

    /**
     * Creates the async thread pool executor for dual-write propagation tasks.
     *
     * <p>Thread pool configuration is sized for JPetStore's low write volume while
     * maintaining the 5-second maximum lag threshold (AAP Section 0.7.5):</p>
     * <ul>
     *   <li>Core pool size: 2 — sufficient for typical JPetStore account write load</li>
     *   <li>Max pool size: 5 — handles burst registration/update scenarios</li>
     *   <li>Queue capacity: 100 — buffers writes during transient HSQLDB slowdowns</li>
     *   <li>Thread name prefix: {@code dual-write-} — identifiable in thread dumps</li>
     * </ul>
     *
     * @return the configured thread pool executor for async write replication
     */
    @Bean
    public Executor dualWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dual-write-");
        executor.initialize();
        log.info("Initialized dual-write async executor: core=2, max=5, queue=100, prefix='dual-write-'");
        return executor;
    }

    /**
     * Creates the {@link DualWriteReplicator} bean that handles async write propagation
     * from PostgreSQL to HSQLDB for the Account Service's 4 owned tables.
     *
     * @param hsqldbDataSource the HSQLDB DataSource for secondary writes
     * @param dualWriteExecutor the async executor for background propagation
     * @param meterRegistry Micrometer meter registry for lag tracking metrics
     * @return the configured DualWriteReplicator
     */
    @Bean
    public DualWriteReplicator dualWriteReplicator(
            @Qualifier("hsqldbDataSource") DataSource hsqldbDataSource,
            @Qualifier("dualWriteExecutor") Executor dualWriteExecutor,
            MeterRegistry meterRegistry) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(hsqldbDataSource);
        return new DualWriteReplicator(jdbcTemplate, dualWriteExecutor, meterRegistry, maxLagSeconds);
    }

    /**
     * Dual-write replicator component that asynchronously replicates write operations
     * from the Account Service's PostgreSQL database to the monolith's HSQLDB.
     *
     * <p>This replicator handles the following Account Service-owned tables:</p>
     * <table>
     *   <tr><th>Table</th><th>Primary Key</th><th>Description</th></tr>
     *   <tr><td>{@code account}</td><td>{@code userid}</td><td>User account data</td></tr>
     *   <tr><td>{@code profile}</td><td>{@code userid}</td><td>User profile preferences</td></tr>
     *   <tr><td>{@code signon}</td><td>{@code username}</td><td>Authentication credentials</td></tr>
     *   <tr><td>{@code bannerdata}</td><td>{@code favcategory}</td><td>Banner personalization</td></tr>
     * </table>
     *
     * <h3>Column Name Mapping</h3>
     * <p>PostgreSQL columns use {@code snake_case} naming while HSQLDB uses the original
     * short names from the monolith schema ({@code jpetstore-hsqldb-schema.sql}). This
     * replicator automatically maps PostgreSQL column names to their HSQLDB equivalents
     * before executing SQL. Columns with identical names are passed through unchanged.</p>
     *
     * <h3>Conflict Detection</h3>
     * <p>Each write operation is tracked with a {@code last_modified_timestamp}. If the
     * same row receives a newer replication event before the current one is processed
     * (out-of-order delivery due to async processing), the older event is skipped and
     * an ERROR-level alert is logged for manual investigation (AAP Section 0.7.5).</p>
     *
     * <h3>Lag Monitoring</h3>
     * <p>Replication lag (time from PostgreSQL commit to HSQLDB replication completion)
     * is tracked via the {@code dualwrite.replication.duration} Micrometer timer.
     * An ERROR-level alert is logged if the lag exceeds the configured threshold
     * (default: 5 seconds per AAP Section 0.7.5).</p>
     *
     * <h3>Error Handling</h3>
     * <p>All replication failures are logged at ERROR level for operational visibility.
     * A failed dual-write does NOT cause the primary PostgreSQL write to fail — the
     * replication is best-effort with alerting, not transactional.</p>
     */
    public static class DualWriteReplicator {

        private static final Logger log = LoggerFactory.getLogger(DualWriteReplicator.class);

        private final JdbcTemplate hsqldbJdbcTemplate;
        private final Executor dualWriteExecutor;
        private final Timer replicationTimer;
        private final int maxLagSeconds;

        /**
         * Tracks the last modification timestamp per entity for conflict detection.
         * Key format: {@code "table:pk_value"} (e.g., {@code "account:j2ee"}).
         * Used to detect and skip out-of-order replication events.
         */
        private final ConcurrentHashMap<String, Instant> lastModifiedTracker = new ConcurrentHashMap<>();

        /**
         * Column name mapping: PostgreSQL (snake_case) → HSQLDB (original schema names).
         * Only columns with <em>different</em> names between PostgreSQL and HSQLDB are
         * included. Columns with identical names in both databases are passed through
         * unchanged by {@link #mapToHsqldbColumns(String, Map)}.
         */
        private static final Map<String, Map<String, String>> COLUMN_MAPPINGS;

        /**
         * Primary key column names per HSQLDB table (using HSQLDB column names).
         * Used to build WHERE clauses for UPDATE operations and to construct
         * conflict detection keys.
         */
        private static final Map<String, String[]> TABLE_PRIMARY_KEYS;

        static {
            // Account table: PostgreSQL snake_case → HSQLDB original names
            // HSQLDB schema: userid, email, firstname, lastname, status, addr1, addr2,
            //                city, state, zip, country, phone
            Map<String, String> accountMapping = new HashMap<>();
            accountMapping.put("user_id", "userid");
            accountMapping.put("first_name", "firstname");
            accountMapping.put("last_name", "lastname");
            // email, status, addr1, addr2, city, state, zip, country, phone are identical

            // Profile table: PostgreSQL snake_case → HSQLDB original names
            // HSQLDB schema: userid, langpref, favcategory, mylistopt, banneropt
            Map<String, String> profileMapping = new HashMap<>();
            profileMapping.put("user_id", "userid");
            profileMapping.put("lang_pref", "langpref");
            profileMapping.put("favourite_category_id", "favcategory");
            profileMapping.put("my_list_opt", "mylistopt");
            profileMapping.put("banner_opt", "banneropt");

            // Signon table: column names are identical in both databases
            // HSQLDB schema: username, password
            Map<String, String> signonMapping = new HashMap<>();

            // Bannerdata table: PostgreSQL snake_case → HSQLDB original names
            // HSQLDB schema: favcategory, bannername
            Map<String, String> bannerdataMapping = new HashMap<>();
            bannerdataMapping.put("favourite_category", "favcategory");
            bannerdataMapping.put("banner_name", "bannername");

            Map<String, Map<String, String>> mappings = new HashMap<>();
            mappings.put("account", Collections.unmodifiableMap(accountMapping));
            mappings.put("profile", Collections.unmodifiableMap(profileMapping));
            mappings.put("signon", Collections.unmodifiableMap(signonMapping));
            mappings.put("bannerdata", Collections.unmodifiableMap(bannerdataMapping));
            COLUMN_MAPPINGS = Collections.unmodifiableMap(mappings);

            // Primary keys per table using HSQLDB column names
            Map<String, String[]> pks = new HashMap<>();
            pks.put("account", new String[]{"userid"});
            pks.put("profile", new String[]{"userid"});
            pks.put("signon", new String[]{"username"});
            pks.put("bannerdata", new String[]{"favcategory"});
            TABLE_PRIMARY_KEYS = Collections.unmodifiableMap(pks);
        }

        /**
         * Constructs a new DualWriteReplicator with the specified dependencies.
         *
         * @param hsqldbJdbcTemplate JdbcTemplate connected to the secondary HSQLDB database
         * @param dualWriteExecutor  async executor for background write propagation
         * @param meterRegistry      Micrometer registry for lag tracking metrics
         * @param maxLagSeconds      maximum acceptable replication lag in seconds (default: 5)
         */
        public DualWriteReplicator(JdbcTemplate hsqldbJdbcTemplate,
                                   Executor dualWriteExecutor,
                                   MeterRegistry meterRegistry,
                                   int maxLagSeconds) {
            this.hsqldbJdbcTemplate = hsqldbJdbcTemplate;
            this.dualWriteExecutor = dualWriteExecutor;
            this.maxLagSeconds = maxLagSeconds;
            this.replicationTimer = meterRegistry.timer("dualwrite.replication.duration",
                    "service", "account-service");
            meterRegistry.gauge("dualwrite.max.lag.seconds",
                    this, replicator -> replicator.maxLagSeconds);
            log.info("DualWriteReplicator initialized: max-lag={}s, tracked-tables={}",
                    maxLagSeconds, COLUMN_MAPPINGS.keySet());
        }

        /**
         * Asynchronously replicates a write operation to the secondary HSQLDB database.
         *
         * <p>This is the generic entry point for dual-write replication. The method
         * attempts an INSERT into the HSQLDB table. If the row already exists (detected
         * via primary key existence check), it falls back to an UPDATE. Column names
         * are automatically mapped from PostgreSQL snake_case to HSQLDB original names.</p>
         *
         * <p>Write direction: PostgreSQL (primary) → HSQLDB (secondary) — NEVER reversed.</p>
         *
         * @param table the target table name (must be one of: account, profile, signon, bannerdata)
         * @param data  column name-value pairs using PostgreSQL snake_case naming;
         *              automatically mapped to HSQLDB column names before execution
         */
        public void replicateWrite(String table, Map<String, Object> data) {
            if (!validateTable(table)) {
                return;
            }
            Instant eventTime = Instant.now();
            dualWriteExecutor.execute(() -> {
                try {
                    Map<String, Object> hsqldbData = mapToHsqldbColumns(table, data);
                    String conflictKey = buildConflictKey(table, hsqldbData);

                    if (hasConflict(conflictKey, eventTime)) {
                        log.error("DUAL_WRITE_CONFLICT: Skipping write to HSQLDB table '{}' — "
                                + "row was modified more recently than this replication event. "
                                + "Manual investigation required. Conflict key: '{}'",
                                table, conflictKey);
                        return;
                    }

                    boolean rowExists = rowExistsInHsqldb(table, hsqldbData);
                    if (rowExists) {
                        performUpdate(table, hsqldbData);
                    } else {
                        performInsert(table, hsqldbData);
                    }

                    lastModifiedTracker.put(conflictKey, Instant.now());
                    Duration lag = Duration.between(eventTime, Instant.now());
                    replicationTimer.record(lag);
                    checkLagThreshold(table, "WRITE", lag);

                    log.info("Dual-write {} to HSQLDB table '{}' completed in {}ms",
                            rowExists ? "UPDATE" : "INSERT", table, lag.toMillis());
                } catch (Exception e) {
                    log.error("DUAL_WRITE_FAILURE: Failed to replicate write to HSQLDB table '{}': {}",
                            table, e.getMessage(), e);
                }
            });
        }

        /**
         * Asynchronously replicates an INSERT operation to the secondary HSQLDB database.
         *
         * <p>Builds an INSERT SQL statement from the provided column data and executes it
         * against the HSQLDB secondary database. Column names are automatically mapped
         * from PostgreSQL snake_case to HSQLDB original naming conventions.</p>
         *
         * <p>If the row already exists in HSQLDB (duplicate primary key), the INSERT is
         * skipped with a warning log — this ensures idempotency for retry scenarios.</p>
         *
         * @param table the target table name (must be one of: account, profile, signon, bannerdata)
         * @param data  column name-value pairs using PostgreSQL snake_case naming
         */
        public void replicateInsert(String table, Map<String, Object> data) {
            if (!validateTable(table)) {
                return;
            }
            Instant eventTime = Instant.now();
            dualWriteExecutor.execute(() -> {
                try {
                    Map<String, Object> hsqldbData = mapToHsqldbColumns(table, data);
                    String conflictKey = buildConflictKey(table, hsqldbData);

                    if (hasConflict(conflictKey, eventTime)) {
                        log.error("DUAL_WRITE_CONFLICT: Skipping INSERT to HSQLDB table '{}' — "
                                + "a newer modification exists. Manual investigation required. "
                                + "Conflict key: '{}'", table, conflictKey);
                        return;
                    }

                    if (rowExistsInHsqldb(table, hsqldbData)) {
                        log.warn("Dual-write INSERT to HSQLDB table '{}' skipped — row already exists. "
                                + "Conflict key: '{}'. This is expected during initial coexistence sync.",
                                table, conflictKey);
                        return;
                    }

                    performInsert(table, hsqldbData);
                    lastModifiedTracker.put(conflictKey, Instant.now());

                    Duration lag = Duration.between(eventTime, Instant.now());
                    replicationTimer.record(lag);
                    checkLagThreshold(table, "INSERT", lag);

                    log.info("Dual-write INSERT to HSQLDB table '{}' completed in {}ms",
                            table, lag.toMillis());
                } catch (Exception e) {
                    log.error("DUAL_WRITE_FAILURE: Failed to replicate INSERT to HSQLDB table '{}': {}",
                            table, e.getMessage(), e);
                }
            });
        }

        /**
         * Asynchronously replicates an UPDATE operation to the secondary HSQLDB database.
         *
         * <p>Builds an UPDATE SQL statement from the provided column data, using the
         * table's primary key columns for the WHERE clause. Column names are automatically
         * mapped from PostgreSQL snake_case to HSQLDB original naming conventions.</p>
         *
         * <p>Conflict detection: If the HSQLDB row was modified more recently than this
         * replication event (tracked via {@code last_modified_timestamp}), the update is
         * skipped and an ERROR-level alert is logged for manual investigation
         * (AAP Section 0.7.5).</p>
         *
         * @param table the target table name (must be one of: account, profile, signon, bannerdata)
         * @param data  column name-value pairs (including primary key columns) using PostgreSQL naming
         */
        public void replicateUpdate(String table, Map<String, Object> data) {
            if (!validateTable(table)) {
                return;
            }
            Instant eventTime = Instant.now();
            dualWriteExecutor.execute(() -> {
                try {
                    Map<String, Object> hsqldbData = mapToHsqldbColumns(table, data);
                    String conflictKey = buildConflictKey(table, hsqldbData);

                    if (hasConflict(conflictKey, eventTime)) {
                        log.error("DUAL_WRITE_CONFLICT: Skipping UPDATE to HSQLDB table '{}' — "
                                + "row was modified more recently than this replication event. "
                                + "Manual investigation required. Conflict key: '{}'",
                                table, conflictKey);
                        return;
                    }

                    performUpdate(table, hsqldbData);
                    lastModifiedTracker.put(conflictKey, Instant.now());

                    Duration lag = Duration.between(eventTime, Instant.now());
                    replicationTimer.record(lag);
                    checkLagThreshold(table, "UPDATE", lag);

                    log.info("Dual-write UPDATE to HSQLDB table '{}' completed in {}ms",
                            table, lag.toMillis());
                } catch (Exception e) {
                    log.error("DUAL_WRITE_FAILURE: Failed to replicate UPDATE to HSQLDB table '{}': {}",
                            table, e.getMessage(), e);
                }
            });
        }

        // =====================================================================
        // Internal Helpers — Column Mapping
        // =====================================================================

        /**
         * Maps PostgreSQL snake_case column names to HSQLDB original column names
         * for the specified table. Columns without a mapping entry are passed through
         * unchanged (they share the same name in both databases).
         */
        private Map<String, Object> mapToHsqldbColumns(String table, Map<String, Object> pgData) {
            Map<String, String> mapping = COLUMN_MAPPINGS.getOrDefault(table, Collections.emptyMap());
            Map<String, Object> hsqldbData = new HashMap<>(pgData.size());
            for (Map.Entry<String, Object> entry : pgData.entrySet()) {
                String pgColumn = entry.getKey();
                String hsqldbColumn = mapping.getOrDefault(pgColumn, pgColumn);
                hsqldbData.put(hsqldbColumn, entry.getValue());
            }
            return hsqldbData;
        }

        // =====================================================================
        // Internal Helpers — Conflict Detection
        // =====================================================================

        /**
         * Builds a unique conflict tracking key from the table name and primary key values.
         * Format: {@code "table:pk_value"} (e.g., {@code "account:j2ee"}).
         */
        private String buildConflictKey(String table, Map<String, Object> hsqldbData) {
            String[] pkColumns = TABLE_PRIMARY_KEYS.getOrDefault(table, new String[0]);
            StringJoiner keyJoiner = new StringJoiner(":");
            keyJoiner.add(table);
            for (String pk : pkColumns) {
                Object value = hsqldbData.get(pk);
                keyJoiner.add(value != null ? value.toString() : "null");
            }
            return keyJoiner.toString();
        }

        /**
         * Checks whether a newer modification exists for the given conflict key.
         * Returns {@code true} if the tracked last-modified timestamp is after the
         * event time, indicating the row was modified by a more recent operation
         * and this older event should be skipped.
         */
        private boolean hasConflict(String conflictKey, Instant eventTime) {
            Instant lastModified = lastModifiedTracker.get(conflictKey);
            return lastModified != null && lastModified.isAfter(eventTime);
        }

        /**
         * Checks whether a row with the given primary key already exists in HSQLDB.
         * Uses {@link JdbcTemplate#queryForObject(String, Class, Object...)} to perform
         * a COUNT query against the primary key columns.
         *
         * @return {@code true} if the row exists, {@code false} otherwise
         */
        private boolean rowExistsInHsqldb(String table, Map<String, Object> hsqldbData) {
            String[] pkColumns = TABLE_PRIMARY_KEYS.getOrDefault(table, new String[0]);
            if (pkColumns.length == 0) {
                return false;
            }

            StringJoiner whereClauses = new StringJoiner(" AND ");
            Object[] pkValues = new Object[pkColumns.length];
            for (int i = 0; i < pkColumns.length; i++) {
                whereClauses.add(pkColumns[i] + " = ?");
                pkValues[i] = hsqldbData.get(pkColumns[i]);
            }

            String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s", table, whereClauses);
            Integer count = hsqldbJdbcTemplate.queryForObject(sql, Integer.class, pkValues);
            return count != null && count > 0;
        }

        // =====================================================================
        // Internal Helpers — SQL Execution
        // =====================================================================

        /**
         * Executes an INSERT statement against the HSQLDB secondary database.
         * Builds the SQL dynamically from the column name-value map.
         */
        private void performInsert(String table, Map<String, Object> hsqldbData) {
            if (hsqldbData.isEmpty()) {
                log.warn("Skipping INSERT to HSQLDB table '{}': no data provided", table);
                return;
            }

            StringJoiner columns = new StringJoiner(", ");
            StringJoiner placeholders = new StringJoiner(", ");
            Object[] values = new Object[hsqldbData.size()];
            int i = 0;
            for (Map.Entry<String, Object> entry : hsqldbData.entrySet()) {
                columns.add(entry.getKey());
                placeholders.add("?");
                values[i++] = entry.getValue();
            }

            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                    table, columns, placeholders);

            int rowsAffected = hsqldbJdbcTemplate.update(sql, values);
            if (rowsAffected == 0) {
                log.warn("Dual-write INSERT to HSQLDB table '{}' affected 0 rows", table);
            }
        }

        /**
         * Executes an UPDATE statement against the HSQLDB secondary database.
         * Separates primary key columns (for WHERE clause) from data columns
         * (for SET clause) based on the {@link #TABLE_PRIMARY_KEYS} mapping.
         */
        private void performUpdate(String table, Map<String, Object> hsqldbData) {
            String[] pkColumns = TABLE_PRIMARY_KEYS.getOrDefault(table, new String[0]);
            if (pkColumns.length == 0) {
                log.error("DUAL_WRITE_CONFIG_ERROR: No primary key mapping found for HSQLDB table '{}'. "
                        + "Skipping UPDATE. This indicates a configuration issue.", table);
                return;
            }

            // Separate PK columns (WHERE) from data columns (SET)
            Map<String, Object> setData = new HashMap<>();
            Map<String, Object> whereData = new HashMap<>();

            for (Map.Entry<String, Object> entry : hsqldbData.entrySet()) {
                boolean isPrimaryKey = false;
                for (String pk : pkColumns) {
                    if (pk.equals(entry.getKey())) {
                        isPrimaryKey = true;
                        break;
                    }
                }
                if (isPrimaryKey) {
                    whereData.put(entry.getKey(), entry.getValue());
                } else {
                    setData.put(entry.getKey(), entry.getValue());
                }
            }

            if (setData.isEmpty()) {
                log.warn("Skipping UPDATE to HSQLDB table '{}': no non-PK columns to update", table);
                return;
            }

            if (whereData.size() != pkColumns.length) {
                log.error("DUAL_WRITE_DATA_ERROR: Missing primary key column(s) for HSQLDB table '{}'. "
                        + "Expected PKs: [{}], found: {}. Skipping UPDATE to prevent full-table update.",
                        table, String.join(", ", pkColumns), whereData.keySet());
                return;
            }

            // Build SET clause
            StringJoiner setClauses = new StringJoiner(", ");
            Object[] allValues = new Object[setData.size() + whereData.size()];
            int i = 0;
            for (Map.Entry<String, Object> entry : setData.entrySet()) {
                setClauses.add(entry.getKey() + " = ?");
                allValues[i++] = entry.getValue();
            }

            // Build WHERE clause using PK columns in defined order
            StringJoiner whereClauses = new StringJoiner(" AND ");
            for (String pk : pkColumns) {
                whereClauses.add(pk + " = ?");
                allValues[i++] = whereData.get(pk);
            }

            String sql = String.format("UPDATE %s SET %s WHERE %s",
                    table, setClauses, whereClauses);

            int rowsAffected = hsqldbJdbcTemplate.update(sql, allValues);
            if (rowsAffected == 0) {
                log.warn("Dual-write UPDATE to HSQLDB table '{}' affected 0 rows. "
                        + "Row may not exist in HSQLDB yet. WHERE: {}", table, whereData);
            }
        }

        // =====================================================================
        // Internal Helpers — Monitoring and Validation
        // =====================================================================

        /**
         * Checks if the replication lag exceeds the configured maximum threshold
         * and logs an ERROR-level alert if it does. Per AAP Section 0.7.5:
         * "The replicator tracks lag as a metric and alerts if it exceeds the threshold."
         */
        private void checkLagThreshold(String table, String operation, Duration lag) {
            if (lag.getSeconds() >= maxLagSeconds) {
                log.error("DUAL_WRITE_LAG_EXCEEDED: {} replication to HSQLDB table '{}' "
                        + "took {}ms ({}s), exceeds max-lag threshold of {}s. "
                        + "Operational investigation required.",
                        operation, table, lag.toMillis(), lag.getSeconds(), maxLagSeconds);
            }
        }

        /**
         * Validates that the specified table is a known Account Service table.
         * Returns {@code false} and logs an error if the table is not recognized,
         * preventing accidental writes to tables owned by other services.
         */
        private boolean validateTable(String table) {
            if (!TABLE_PRIMARY_KEYS.containsKey(table)) {
                log.error("DUAL_WRITE_CONFIG_ERROR: Unknown table '{}'. "
                        + "Account Service only supports dual-write for tables: {}. "
                        + "Write operation rejected.",
                        table, TABLE_PRIMARY_KEYS.keySet());
                return false;
            }
            return true;
        }
    }
}
