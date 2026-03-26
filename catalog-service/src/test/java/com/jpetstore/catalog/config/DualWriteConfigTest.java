/*
 * DualWriteConfigTest.java — Unit tests for the Catalog Service's dual-write
 * adapter configuration.
 *
 * Verifies:
 *   - DualWriteProperties record construction and accessors
 *   - DualWriteEventPublisher contract via LoggingDualWriteEventPublisher
 *   - Input validation (null/empty rejection for tableName, operation, primaryKey, timestamp)
 *   - Config bean creation via Spring context with dualwrite.enabled=true
 *
 * Per AAP Section 0.7.5, dual-write is a critical coexistence mechanism that
 * propagates PostgreSQL writes to the monolith's HSQLDB during the Strangler Fig
 * migration window.
 */
package com.jpetstore.catalog.config;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit and integration tests for {@link DualWriteConfig} and its inner types.
 *
 * <p>The catalog-service's dual-write implementation uses an event-based approach:
 * write events are published through {@link DualWriteConfig.DualWriteEventPublisher}
 * and consumed by an external replicator. The default
 * {@code LoggingDualWriteEventPublisher} logs events without performing actual
 * HSQLDB propagation (the catalog-service has no HSQLDB driver dependency).
 */
class DualWriteConfigTest {

    // =========================================================================
    // DualWriteProperties Record Tests
    // =========================================================================

    @Nested
    @DisplayName("DualWriteProperties")
    class DualWritePropertiesTests {

        @Test
        @DisplayName("should create properties with all values and provide correct accessors")
        void shouldCreatePropertiesWithAllValues() {
            // given
            DualWriteConfig.DualWriteProperties props = new DualWriteConfig.DualWriteProperties(
                    "jdbc:hsqldb:hsql://localhost/jpetstore", 5, true, true
            );

            // then
            assertThat(props.hsqldbUrl()).isEqualTo("jdbc:hsqldb:hsql://localhost/jpetstore");
            assertThat(props.maxLagSeconds()).isEqualTo(5);
            assertThat(props.conflictDetectionEnabled()).isTrue();
            assertThat(props.enabled()).isTrue();
        }

        @Test
        @DisplayName("should support empty URL for external replicator mode")
        void shouldSupportEmptyUrlForExternalReplicator() {
            // given — empty URL indicates catalog-service has no HSQLDB driver
            DualWriteConfig.DualWriteProperties props = new DualWriteConfig.DualWriteProperties(
                    "", 10, false, true
            );

            // then
            assertThat(props.hsqldbUrl()).isEmpty();
            assertThat(props.maxLagSeconds()).isEqualTo(10);
            assertThat(props.conflictDetectionEnabled()).isFalse();
            assertThat(props.enabled()).isTrue();
        }

        @Test
        @DisplayName("should support disabled state for post-cutover configuration")
        void shouldSupportDisabledState() {
            // given — after 48-hour observation window, dual-write is disabled
            DualWriteConfig.DualWriteProperties props = new DualWriteConfig.DualWriteProperties(
                    "", 5, true, false
            );

            // then
            assertThat(props.enabled()).isFalse();
        }
    }

    // =========================================================================
    // LoggingDualWriteEventPublisher Tests (via DualWriteEventPublisher interface)
    // =========================================================================

    @Nested
    @DisplayName("LoggingDualWriteEventPublisher — valid events")
    class ValidEventTests {

        @Test
        @DisplayName("should accept valid inventory UPDATE event without throwing")
        void shouldAcceptValidInventoryUpdateEvent() {
            // given — create a publisher via the config bean method
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            Map<String, Object> newValues = Map.of("qty", 8);
            Instant now = Instant.now();

            // when / then — no exception should be thrown
            publisher.publishWriteEvent("inventory", "UPDATE", "EST-1", newValues, now);
        }

        @Test
        @DisplayName("should accept valid INSERT event with multiple column values")
        void shouldAcceptValidInsertEvent() {
            // given
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            Map<String, Object> newValues = Map.of(
                    "itemid", "EST-99",
                    "qty", 100
            );
            Instant now = Instant.now();

            // when / then — no exception
            publisher.publishWriteEvent("inventory", "INSERT", "EST-99", newValues, now);
        }

        @Test
        @DisplayName("should accept DELETE event with empty newValues map")
        void shouldAcceptDeleteEventWithEmptyValues() {
            // given
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            // when / then — DELETE operations may have empty newValues per interface contract
            publisher.publishWriteEvent("inventory", "DELETE", "EST-99", Map.of(), Instant.now());
        }

        @Test
        @DisplayName("should accept event with null newValues (per interface contract for DELETEs)")
        void shouldAcceptEventWithNullNewValues() {
            // given
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            // when / then — null newValues is acceptable per the interface Javadoc
            publisher.publishWriteEvent("inventory", "DELETE", "EST-99", null, Instant.now());
        }
    }

    @Nested
    @DisplayName("LoggingDualWriteEventPublisher — input validation (rejection)")
    class InputValidationTests {

        @Test
        @DisplayName("should reject event with null tableName without throwing")
        void shouldRejectNullTableName() {
            // given
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            // when / then — silently logs error and returns, does not throw
            publisher.publishWriteEvent(null, "UPDATE", "EST-1", Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with empty tableName without throwing")
        void shouldRejectEmptyTableName() {
            // given
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            // when / then — logs error, returns early
            publisher.publishWriteEvent("", "UPDATE", "EST-1", Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with null operation without throwing")
        void shouldRejectNullOperation() {
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            publisher.publishWriteEvent("inventory", null, "EST-1", Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with empty operation without throwing")
        void shouldRejectEmptyOperation() {
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            publisher.publishWriteEvent("inventory", "", "EST-1", Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with null primaryKey without throwing")
        void shouldRejectNullPrimaryKey() {
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            publisher.publishWriteEvent("inventory", "UPDATE", null, Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with empty primaryKey without throwing")
        void shouldRejectEmptyPrimaryKey() {
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            publisher.publishWriteEvent("inventory", "UPDATE", "", Map.of("qty", 5), Instant.now());
        }

        @Test
        @DisplayName("should reject event with null timestamp without throwing")
        void shouldRejectNullTimestamp() {
            DualWriteConfig config = new DualWriteConfig();
            DualWriteConfig.DualWriteEventPublisher publisher = config.dualWriteEventPublisher();

            publisher.publishWriteEvent("inventory", "UPDATE", "EST-1", Map.of("qty", 5), null);
        }
    }

    // =========================================================================
    // Spring Context Integration — conditional activation
    // =========================================================================

    @Nested
    @DisplayName("DualWriteConfig conditional activation")
    @SpringBootTest(
            classes = DualWriteConfig.class,
            properties = {
                    "dualwrite.enabled=true",
                    "dualwrite.hsqldb.url=jdbc:hsqldb:mem:testdb",
                    "dualwrite.max-lag-seconds=3",
                    "dualwrite.conflict-detection.enabled=false"
            }
    )
    class ConditionalActivationTest {

        @Autowired(required = false)
        private DualWriteConfig.DualWriteProperties properties;

        @Autowired(required = false)
        private DualWriteConfig.DualWriteEventPublisher eventPublisher;

        @Test
        @DisplayName("should create DualWriteProperties bean when dualwrite.enabled=true")
        void shouldCreatePropertiesBean() {
            assertThat(properties).isNotNull();
            assertThat(properties.hsqldbUrl()).isEqualTo("jdbc:hsqldb:mem:testdb");
            assertThat(properties.maxLagSeconds()).isEqualTo(3);
            assertThat(properties.conflictDetectionEnabled()).isFalse();
            assertThat(properties.enabled()).isTrue();
        }

        @Test
        @DisplayName("should create DualWriteEventPublisher bean when dualwrite.enabled=true")
        void shouldCreateEventPublisherBean() {
            assertThat(eventPublisher).isNotNull();
        }
    }
}
