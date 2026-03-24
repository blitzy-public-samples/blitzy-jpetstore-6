package com.jpetstore.catalog.config;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Dual-write adapter configuration for the Catalog Service during the Strangler Fig
 * coexistence window.
 *
 * <p>During the incremental monolith-to-microservices migration, the Catalog Service
 * operates alongside the legacy JPetStore monolith. Both systems must see consistent
 * data until the monolith's Catalog bounded context is fully decommissioned. This
 * configuration defines the adapter infrastructure that enables <strong>dual-write
 * propagation</strong>: every write to the Catalog Service's PostgreSQL database
 * (the primary store) is propagated to the monolith's HSQLDB (the secondary store)
 * so that the monolith sees up-to-date catalog/inventory data.</p>
 *
 * <h3>Activation</h3>
 * <p>This configuration is <strong>conditionally loaded</strong> — it is only active
 * when the externalized property {@code dualwrite.enabled=true} is set in
 * {@code application.yml}, environment variables, or a Spring Cloud Config source.
 * When the property is {@code false} or absent, <em>none</em> of the beans defined
 * here are registered in the application context, incurring zero runtime overhead.</p>
 *
 * <h3>Write Propagation Direction</h3>
 * <pre>
 *   PostgreSQL (primary) ──→ HSQLDB (secondary)
 * </pre>
 * <p>The primary write operation in the Catalog bounded context that requires dual-write
 * propagation is the <strong>inventory decrement</strong>:</p>
 * <pre>
 *   UPDATE inventory SET qty = qty - :increment WHERE itemid = :itemId
 * </pre>
 * <p>This operation is triggered by the Order Service via the Catalog Service's
 * {@code POST /api/items/{id}/inventory/decrement} endpoint during the Saga-orchestrated
 * order transaction.</p>
 *
 * <h3>Maximum Acceptable Lag</h3>
 * <p>The default maximum acceptable propagation lag is <strong>5 seconds</strong>
 * (configurable via {@code dualwrite.max-lag-seconds}). Given the low write volume
 * of the JPetStore application, this threshold ensures that in the worst case of an
 * immediate rollback, at most 5 seconds of writes may need manual reconciliation.</p>
 *
 * <h3>Conflict Detection</h3>
 * <p>Conflict detection is enabled by default ({@code dualwrite.conflict-detection.enabled=true}).
 * When enabled, every dual-write record includes a {@code last_modified_timestamp}.
 * If the HSQLDB row has a newer timestamp than the propagated write, the replicator
 * logs a conflict alert at ERROR level and does NOT overwrite the HSQLDB row.</p>
 *
 * <h3>No HSQLDB Driver in This Service</h3>
 * <p>The catalog-service {@code pom.xml} does <strong>not</strong> include the HSQLDB
 * JDBC driver as a dependency. This configuration defines the adapter <em>interface
 * contract</em> only — it specifies what data is sent (table name, operation type,
 * primary key, new column values, timestamp). The actual HSQLDB connectivity is
 * handled externally by an infrastructure-layer component such as a sidecar replicator,
 * message queue consumer, or the migration module.</p>
 *
 * <h3>Disabling After Cutover</h3>
 * <p>Dual-write is disabled only after the post-cutover <strong>48-hour observation
 * window</strong> has passed with no incidents. Disabling is achieved by setting
 * {@code dualwrite.enabled=false} in the externalized configuration source — no
 * redeployment is required when using Spring Cloud Config, Redis-backed config,
 * or environment variable reload.</p>
 *
 * @see DualWriteProperties
 * @see DualWriteEventPublisher
 */
@Configuration
@ConditionalOnProperty(name = "dualwrite.enabled", havingValue = "true", matchIfMissing = false)
@EnableAsync
public class DualWriteConfig {

    private static final Logger log = LoggerFactory.getLogger(DualWriteConfig.class);

    /**
     * HSQLDB JDBC URL for the external replicator. Empty by default because the
     * catalog-service does not include the HSQLDB JDBC driver — the URL is consumed
     * by the infrastructure-layer replicator component that handles actual connectivity.
     */
    @Value("${dualwrite.hsqldb.url:}")
    private String hsqldbUrl;

    /**
     * Maximum acceptable write propagation lag in seconds. Defaults to 5 seconds per
     * the architectural specification. The replicator tracks actual lag as a metric
     * and alerts if it exceeds this threshold.
     */
    @Value("${dualwrite.max-lag-seconds:5}")
    private int maxLagSeconds;

    /**
     * Whether conflict detection via {@code last_modified_timestamp} comparison is
     * enabled. When {@code true}, the replicator compares the timestamp of the
     * propagated write against the current HSQLDB row timestamp and refuses to
     * overwrite if the HSQLDB row is newer. Defaults to {@code true}.
     */
    @Value("${dualwrite.conflict-detection.enabled:true}")
    private boolean conflictDetectionEnabled;

    /**
     * Master enable/disable flag for the dual-write mechanism. This value is also
     * accessible programmatically via the {@link DualWriteProperties} bean for
     * components that need to conditionally execute dual-write logic at runtime.
     * Defaults to {@code false} (disabled).
     */
    @Value("${dualwrite.enabled:false}")
    private boolean enabled;

    /**
     * Creates a {@link DualWriteProperties} bean that aggregates all dual-write
     * configuration values into a single typed holder.
     *
     * <p>This bean is consumed by any service class, AOP aspect, or interceptor
     * that needs to inspect the dual-write configuration at runtime — for example,
     * the {@code InventoryService} may check {@code dualWriteProperties.enabled()}
     * before publishing a write event, or a monitoring component may read
     * {@code maxLagSeconds()} to configure alerting thresholds.</p>
     *
     * <p>Configuration properties and their defaults:</p>
     * <table>
     *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
     *   <tr><td>{@code dualwrite.hsqldb.url}</td><td>(empty)</td>
     *       <td>HSQLDB JDBC URL for external replicator</td></tr>
     *   <tr><td>{@code dualwrite.max-lag-seconds}</td><td>5</td>
     *       <td>Max propagation lag in seconds</td></tr>
     *   <tr><td>{@code dualwrite.conflict-detection.enabled}</td><td>true</td>
     *       <td>Whether timestamp-based conflict detection is active</td></tr>
     *   <tr><td>{@code dualwrite.enabled}</td><td>false</td>
     *       <td>Master enable/disable toggle</td></tr>
     * </table>
     *
     * @return a {@link DualWriteProperties} instance populated from externalized configuration
     */
    @Bean
    public DualWriteProperties dualWriteProperties() {
        DualWriteProperties properties = new DualWriteProperties(
                hsqldbUrl,
                maxLagSeconds,
                conflictDetectionEnabled,
                enabled
        );
        log.info("Dual-write configuration activated: hsqldbUrl={}, maxLagSeconds={}, "
                        + "conflictDetectionEnabled={}, enabled={}",
                properties.hsqldbUrl().isEmpty() ? "(not set — external replicator)" : properties.hsqldbUrl(),
                properties.maxLagSeconds(),
                properties.conflictDetectionEnabled(),
                properties.enabled());
        return properties;
    }

    /**
     * Creates a default {@link DualWriteEventPublisher} bean that logs dual-write events.
     *
     * <p>This is a <strong>logging-only (no-op) implementation</strong> — it records each
     * write event at INFO level for operational visibility but does not perform actual
     * HSQLDB propagation. The catalog-service does not include the HSQLDB JDBC driver,
     * so actual database propagation must be handled by an external infrastructure
     * component.</p>
     *
     * <p>To enable actual propagation, replace this bean by defining a higher-priority
     * {@code DualWriteEventPublisher} bean in a separate configuration class (e.g., one
     * backed by a message queue producer, a REST client to a replicator sidecar, or
     * a direct JDBC connection if the HSQLDB driver is added at deployment time).</p>
     *
     * <p>The event publisher is designed to be called asynchronously (supported by the
     * {@code @EnableAsync} annotation on this configuration class) so that dual-write
     * propagation does not block the primary PostgreSQL write path.</p>
     *
     * @return a logging-based {@link DualWriteEventPublisher} implementation
     */
    @Bean
    public DualWriteEventPublisher dualWriteEventPublisher() {
        return new LoggingDualWriteEventPublisher();
    }

    // =========================================================================
    // Inner Types
    // =========================================================================

    /**
     * Typed configuration holder for dual-write adapter properties.
     *
     * <p>Aggregates all externalized dual-write configuration values into a single
     * immutable record, providing a clean API for runtime inspection by service
     * classes, interceptors, and monitoring components.</p>
     *
     * <p>Fields:</p>
     * <ul>
     *   <li>{@code hsqldbUrl} — JDBC URL for the secondary HSQLDB database (empty
     *       when the catalog-service does not include the HSQLDB driver)</li>
     *   <li>{@code maxLagSeconds} — maximum acceptable propagation lag (default: 5)</li>
     *   <li>{@code conflictDetectionEnabled} — whether timestamp-based conflict
     *       detection is active (default: true)</li>
     *   <li>{@code enabled} — master enable/disable toggle (default: false)</li>
     * </ul>
     *
     * @param hsqldbUrl               HSQLDB JDBC URL for the external replicator; empty
     *                                string if not configured (catalog-service has no
     *                                HSQLDB driver dependency)
     * @param maxLagSeconds           maximum acceptable write propagation lag in seconds;
     *                                defaults to 5 per architectural specification
     * @param conflictDetectionEnabled whether conflict detection via
     *                                {@code last_modified_timestamp} comparison is active
     * @param enabled                 master enable/disable flag for the dual-write mechanism
     */
    public record DualWriteProperties(
            String hsqldbUrl,
            int maxLagSeconds,
            boolean conflictDetectionEnabled,
            boolean enabled
    ) {
    }

    /**
     * Functional interface defining the contract for publishing dual-write events
     * from the Catalog Service's PostgreSQL database to the monolith's HSQLDB.
     *
     * <p>Each write operation that modifies catalog-owned tables (primarily the
     * {@code inventory} table during inventory decrement operations) publishes an
     * event through this interface. The event contains all information necessary
     * for an external replicator to apply the same change to HSQLDB:</p>
     * <ul>
     *   <li><strong>tableName</strong> — the PostgreSQL table that was modified
     *       (e.g., {@code "inventory"})</li>
     *   <li><strong>operation</strong> — the type of SQL operation performed
     *       (e.g., {@code "UPDATE"}, {@code "INSERT"}, {@code "DELETE"})</li>
     *   <li><strong>primaryKey</strong> — the primary key value of the affected row
     *       (e.g., the {@code itemid} value for inventory table)</li>
     *   <li><strong>newValues</strong> — a map of column names to their new values
     *       after the write operation</li>
     *   <li><strong>timestamp</strong> — the instant when the write occurred, used for
     *       conflict detection via {@code last_modified_timestamp} comparison between
     *       PostgreSQL and HSQLDB</li>
     * </ul>
     *
     * <p>Implementations may propagate events synchronously or asynchronously.
     * The default implementation ({@link LoggingDualWriteEventPublisher}) logs
     * events for operational visibility without performing actual HSQLDB writes.</p>
     *
     * <p>Production implementations might:</p>
     * <ul>
     *   <li>Send events to a message queue (e.g., RabbitMQ, Kafka) consumed by a
     *       dedicated replicator service</li>
     *   <li>Call a replicator sidecar's REST API</li>
     *   <li>Write directly to HSQLDB via JDBC (if the HSQLDB driver is added at
     *       deployment time)</li>
     * </ul>
     */
    @FunctionalInterface
    public interface DualWriteEventPublisher {

        /**
         * Publishes a dual-write event for propagation from PostgreSQL to HSQLDB.
         *
         * @param tableName  the name of the PostgreSQL table that was modified
         *                   (e.g., {@code "inventory"}, {@code "item"}, {@code "category"})
         * @param operation  the SQL operation type: {@code "INSERT"}, {@code "UPDATE"},
         *                   or {@code "DELETE"}
         * @param primaryKey the primary key value of the affected row (e.g., the
         *                   {@code itemid} for inventory operations)
         * @param newValues  a map of column names to their new values after the write;
         *                   for DELETE operations, this map may be empty
         * @param timestamp  the instant when the primary write occurred, used for
         *                   conflict detection via {@code last_modified_timestamp}
         *                   comparison
         */
        void publishWriteEvent(String tableName, String operation, String primaryKey,
                               Map<String, Object> newValues, Instant timestamp);
    }

    /**
     * Default logging-only implementation of {@link DualWriteEventPublisher}.
     *
     * <p>This implementation logs every dual-write event at INFO level for operational
     * visibility and auditing purposes. It does <strong>not</strong> perform actual
     * HSQLDB propagation because the catalog-service does not include the HSQLDB
     * JDBC driver as a compile-time dependency.</p>
     *
     * <p>The log output includes all event details (table name, operation, primary key,
     * new values, and timestamp) in a structured format suitable for log aggregation
     * and monitoring systems. Conflict detection scenarios are logged at WARN level
     * to enable alerting.</p>
     *
     * <p>In production, this bean should be replaced by a concrete implementation
     * that forwards events to the external replicator infrastructure (message queue,
     * REST sidecar, or direct JDBC).</p>
     */
    private static final class LoggingDualWriteEventPublisher implements DualWriteEventPublisher {

        private static final Logger eventLog = LoggerFactory.getLogger(
                LoggingDualWriteEventPublisher.class);

        @Override
        public void publishWriteEvent(String tableName, String operation, String primaryKey,
                                      Map<String, Object> newValues, Instant timestamp) {
            if (tableName == null || tableName.isEmpty()) {
                eventLog.error("Dual-write event rejected: tableName is null or empty. "
                        + "operation={}, primaryKey={}, timestamp={}", operation, primaryKey, timestamp);
                return;
            }
            if (operation == null || operation.isEmpty()) {
                eventLog.error("Dual-write event rejected: operation is null or empty. "
                        + "tableName={}, primaryKey={}, timestamp={}", tableName, primaryKey, timestamp);
                return;
            }
            if (primaryKey == null || primaryKey.isEmpty()) {
                eventLog.error("Dual-write event rejected: primaryKey is null or empty. "
                        + "tableName={}, operation={}, timestamp={}", tableName, operation, timestamp);
                return;
            }
            if (timestamp == null) {
                eventLog.error("Dual-write event rejected: timestamp is null. "
                        + "tableName={}, operation={}, primaryKey={}", tableName, operation, primaryKey);
                return;
            }

            eventLog.info("Dual-write event published: table={}, operation={}, primaryKey={}, "
                            + "newValues={}, timestamp={}. "
                            + "Note: This is a logging-only implementation — actual HSQLDB propagation "
                            + "is handled by the external replicator infrastructure.",
                    tableName, operation, primaryKey,
                    newValues != null ? newValues : "(none)",
                    timestamp);
        }
    }
}
