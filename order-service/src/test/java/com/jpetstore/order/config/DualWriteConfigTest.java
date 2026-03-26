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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jpetstore.order.config.DualWriteConfig.DualWriteProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DualWriteConfig} — the Order Service dual-write
 * configuration that replicates PostgreSQL writes to HSQLDB during the
 * Strangler Fig coexistence window.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>{@link DualWriteProperties} — constructor, accessors, toString</li>
 *   <li>{@link DualWriteConfig#translateColumnNames} — PostgreSQL snake_case to HSQLDB mapping</li>
 *   <li>{@link DualWriteConfig#buildWhereClause} — single and composite PK WHERE generation</li>
 *   <li>{@link DualWriteConfig#sanitizeTableName} — SQL injection prevention</li>
 *   <li>{@link DualWriteConfig#executeInsert} — INSERT SQL generation and execution</li>
 *   <li>{@link DualWriteConfig#executeUpdate} — UPDATE SQL generation and execution</li>
 *   <li>{@link DualWriteConfig#detectConflict} — INSERT conflict, UPDATE missing row, no conflict</li>
 * </ul>
 */
class DualWriteConfigTest {

    // =========================================================================
    // DualWriteProperties Tests
    // =========================================================================

    @Nested
    @DisplayName("DualWriteProperties")
    class DualWritePropertiesTests {

        @Test
        @DisplayName("should return constructor values via accessor methods")
        void shouldReturnConstructorValuesViaAccessors() {
            // given
            String url = "jdbc:hsqldb:hsql://localhost:9001/jpetstore";
            int maxLag = 10;
            boolean conflictDetection = false;

            // when
            DualWriteProperties props = new DualWriteProperties(url, maxLag, conflictDetection);

            // then
            assertThat(props.hsqldbUrl()).isEqualTo(url);
            assertThat(props.maxLagSeconds()).isEqualTo(maxLag);
            assertThat(props.conflictDetectionEnabled()).isFalse();
        }

        @Test
        @DisplayName("should use default-like values matching AAP Section 0.7.5")
        void shouldSupportDefaultValues() {
            // given — defaults: 5 second max lag, conflict detection enabled
            DualWriteProperties props = new DualWriteProperties(
                    "jdbc:hsqldb:hsql://localhost:9001/jpetstore", 5, true);

            // then
            assertThat(props.maxLagSeconds()).isEqualTo(5);
            assertThat(props.conflictDetectionEnabled()).isTrue();
        }

        @Test
        @DisplayName("toString should include all property values for operational logging")
        void toStringShouldIncludeAllProperties() {
            // given
            DualWriteProperties props = new DualWriteProperties(
                    "jdbc:hsqldb:hsql://localhost:9001/jpetstore", 5, true);

            // when
            String result = props.toString();

            // then
            assertThat(result).contains("hsqldbUrl='jdbc:hsqldb:hsql://localhost:9001/jpetstore'");
            assertThat(result).contains("maxLagSeconds=5");
            assertThat(result).contains("conflictDetectionEnabled=true");
        }
    }

    // =========================================================================
    // Column Name Translation Tests
    // =========================================================================

    @Nested
    @DisplayName("translateColumnNames — PostgreSQL snake_case → HSQLDB originals")
    class TranslateColumnNamesTests {

        @Test
        @DisplayName("should remove underscores from snake_case column names")
        void shouldRemoveUnderscoresFromColumnNames() {
            // given — PostgreSQL snake_case columns for the orders table
            Map<String, Object> pgColumns = new LinkedHashMap<>();
            pgColumns.put("order_id", 1000);
            pgColumns.put("user_id", "j2ee");
            pgColumns.put("order_date", "2026-01-15");
            pgColumns.put("bill_to_first_name", "ABC");
            pgColumns.put("total_price", 55.50);
            pgColumns.put("credit_card", "999 9999 9999 9999");

            // when
            Map<String, Object> hsqldbColumns = DualWriteConfig.translateColumnNames(pgColumns);

            // then
            assertThat(hsqldbColumns).containsEntry("orderid", 1000);
            assertThat(hsqldbColumns).containsEntry("userid", "j2ee");
            assertThat(hsqldbColumns).containsEntry("orderdate", "2026-01-15");
            assertThat(hsqldbColumns).containsEntry("billtofirstname", "ABC");
            assertThat(hsqldbColumns).containsEntry("totalprice", 55.50);
            assertThat(hsqldbColumns).containsEntry("creditcard", "999 9999 9999 9999");
        }

        @Test
        @DisplayName("should preserve columns without underscores (e.g., courier, locale)")
        void shouldPreserveColumnsWithoutUnderscores() {
            // given — columns that are the same in both databases
            Map<String, Object> pgColumns = new LinkedHashMap<>();
            pgColumns.put("courier", "UPS");
            pgColumns.put("locale", "CA");
            pgColumns.put("status", "P");

            // when
            Map<String, Object> hsqldbColumns = DualWriteConfig.translateColumnNames(pgColumns);

            // then
            assertThat(hsqldbColumns).containsEntry("courier", "UPS");
            assertThat(hsqldbColumns).containsEntry("locale", "CA");
            assertThat(hsqldbColumns).containsEntry("status", "P");
        }

        @Test
        @DisplayName("should return empty map for null input")
        void shouldReturnEmptyMapForNullInput() {
            assertThat(DualWriteConfig.translateColumnNames(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty map for empty input")
        void shouldReturnEmptyMapForEmptyInput() {
            assertThat(DualWriteConfig.translateColumnNames(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("should translate lineitem composite PK columns")
        void shouldTranslateLineitemColumns() {
            // given — lineitem table columns
            Map<String, Object> pgColumns = new LinkedHashMap<>();
            pgColumns.put("order_id", 1001);
            pgColumns.put("line_num", 1);
            pgColumns.put("item_id", "EST-1");
            pgColumns.put("unit_price", 16.50);
            pgColumns.put("quantity", 3);

            // when
            Map<String, Object> hsqldbColumns = DualWriteConfig.translateColumnNames(pgColumns);

            // then
            assertThat(hsqldbColumns).containsEntry("orderid", 1001);
            assertThat(hsqldbColumns).containsEntry("linenum", 1);
            assertThat(hsqldbColumns).containsEntry("itemid", "EST-1");
            assertThat(hsqldbColumns).containsEntry("unitprice", 16.50);
            assertThat(hsqldbColumns).containsEntry("quantity", 3);
        }
    }

    // =========================================================================
    // SQL Helper Tests
    // =========================================================================

    @Nested
    @DisplayName("buildWhereClause")
    class BuildWhereClauseTests {

        @Test
        @DisplayName("should build single-column WHERE clause for orders table")
        void shouldBuildSingleColumnWhereClause() {
            // given
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1000);

            // when
            String where = DualWriteConfig.buildWhereClause(pk);

            // then
            assertThat(where).isEqualTo("orderid = ?");
        }

        @Test
        @DisplayName("should build composite WHERE clause for lineitem table")
        void shouldBuildCompositeWhereClause() {
            // given — lineitem has composite PK (orderid, linenum)
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1000);
            pk.put("linenum", 1);

            // when
            String where = DualWriteConfig.buildWhereClause(pk);

            // then
            assertThat(where).isEqualTo("orderid = ? AND linenum = ?");
        }
    }

    @Nested
    @DisplayName("sanitizeTableName — SQL injection prevention")
    class SanitizeTableNameTests {

        @Test
        @DisplayName("should allow valid Order Service table names")
        void shouldAllowValidTableNames() {
            assertThat(DualWriteConfig.sanitizeTableName("orders")).isEqualTo("orders");
            assertThat(DualWriteConfig.sanitizeTableName("orderstatus")).isEqualTo("orderstatus");
            assertThat(DualWriteConfig.sanitizeTableName("lineitem")).isEqualTo("lineitem");
            assertThat(DualWriteConfig.sanitizeTableName("sequence")).isEqualTo("sequence");
        }

        @Test
        @DisplayName("should reject null table name")
        void shouldRejectNullTableName() {
            assertThatThrownBy(() -> DualWriteConfig.sanitizeTableName(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid table name");
        }

        @Test
        @DisplayName("should reject SQL injection attempts")
        void shouldRejectSqlInjection() {
            assertThatThrownBy(() -> DualWriteConfig.sanitizeTableName("orders; DROP TABLE"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DualWriteConfig.sanitizeTableName("orders--"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject empty table name")
        void shouldRejectEmptyTableName() {
            assertThatThrownBy(() -> DualWriteConfig.sanitizeTableName(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // SQL Execution Tests (executeInsert, executeUpdate)
    // =========================================================================

    @Nested
    @DisplayName("executeInsert — INSERT SQL generation and execution")
    class ExecuteInsertTests {

        @Test
        @DisplayName("should generate and execute INSERT for orders table")
        void shouldExecuteInsertForOrdersTable() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderid", 1001);
            values.put("userid", "j2ee");
            values.put("totalprice", 55.50);

            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            // when
            int result = DualWriteConfig.executeInsert(jdbc, "orders", values);

            // then
            assertThat(result).isEqualTo(1);
            verify(jdbc).update(
                    eq("INSERT INTO orders (orderid, userid, totalprice) VALUES (?, ?, ?)"),
                    eq(new Object[]{1001, "j2ee", 55.50}));
        }

        @Test
        @DisplayName("should generate INSERT for lineitem with composite columns")
        void shouldExecuteInsertForLineitemTable() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderid", 1001);
            values.put("linenum", 1);
            values.put("itemid", "EST-1");
            values.put("quantity", 2);
            values.put("unitprice", 16.50);

            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            // when
            int result = DualWriteConfig.executeInsert(jdbc, "lineitem", values);

            // then
            assertThat(result).isEqualTo(1);
            verify(jdbc).update(
                    eq("INSERT INTO lineitem (orderid, linenum, itemid, quantity, unitprice) "
                            + "VALUES (?, ?, ?, ?, ?)"),
                    eq(new Object[]{1001, 1, "EST-1", 2, 16.50}));
        }

        @Test
        @DisplayName("should return 0 and skip when column values map is empty")
        void shouldReturnZeroForEmptyValues() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            // when
            int result = DualWriteConfig.executeInsert(jdbc, "orders", Map.of());

            // then
            assertThat(result).isZero();
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("executeUpdate — UPDATE SQL generation and execution")
    class ExecuteUpdateTests {

        @Test
        @DisplayName("should generate UPDATE for orders table excluding PK from SET")
        void shouldExecuteUpdateForOrdersTable() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderid", 1001);  // PK — should NOT appear in SET
            values.put("userid", "j2ee");
            values.put("totalprice", 99.99);

            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            // when
            int result = DualWriteConfig.executeUpdate(jdbc, "orders", pk, values);

            // then
            assertThat(result).isEqualTo(1);
            // SET clause should exclude orderid; WHERE should include it
            verify(jdbc).update(
                    eq("UPDATE orders SET userid = ?, totalprice = ? WHERE orderid = ?"),
                    eq(new Object[]{"j2ee", 99.99, 1001}));
        }

        @Test
        @DisplayName("should generate UPDATE for orderstatus with composite PK")
        void shouldExecuteUpdateForOrderstatusTable() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);
            pk.put("linenum", 1);

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderid", 1001);
            values.put("linenum", 1);
            values.put("status", "P");

            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            // when
            int result = DualWriteConfig.executeUpdate(jdbc, "orderstatus", pk, values);

            // then
            assertThat(result).isEqualTo(1);
            verify(jdbc).update(
                    eq("UPDATE orderstatus SET status = ? WHERE orderid = ? AND linenum = ?"),
                    eq(new Object[]{"P", 1001, 1}));
        }

        @Test
        @DisplayName("should return 0 when column values map is empty")
        void shouldReturnZeroForEmptyValues() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            int result = DualWriteConfig.executeUpdate(jdbc, "orders",
                    Map.of("orderid", 1001), Map.of());
            assertThat(result).isZero();
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should return 0 when only PK columns present (no non-PK to SET)")
        void shouldReturnZeroWhenOnlyPkColumnsPresent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderid", 1001); // Only PK column — nothing to SET

            int result = DualWriteConfig.executeUpdate(jdbc, "orders", pk, values);
            assertThat(result).isZero();
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    // =========================================================================
    // Conflict Detection Tests
    // =========================================================================

    @Nested
    @DisplayName("detectConflict — INSERT conflict, UPDATE missing row, no conflict")
    class DetectConflictTests {

        @Test
        @DisplayName("should detect INSERT conflict when row already exists in HSQLDB")
        void shouldDetectInsertConflictWhenRowExists() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1); // Row exists

            // when
            boolean conflict = DualWriteConfig.detectConflict(
                    jdbc, "orders", "INSERT", pk, Instant.now());

            // then — INSERT should be skipped (conflict = true)
            assertThat(conflict).isTrue();
        }

        @Test
        @DisplayName("should allow INSERT when row does not exist in HSQLDB")
        void shouldAllowInsertWhenRowDoesNotExist() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(0); // Row does not exist

            // when
            boolean conflict = DualWriteConfig.detectConflict(
                    jdbc, "orders", "INSERT", pk, Instant.now());

            // then — INSERT should proceed (conflict = false)
            assertThat(conflict).isFalse();
        }

        @Test
        @DisplayName("should allow UPDATE even when target row is missing (logged as warning)")
        void shouldAllowUpdateWhenRowMissing() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(0); // Row missing — warning logged

            // when
            boolean conflict = DualWriteConfig.detectConflict(
                    jdbc, "orders", "UPDATE", pk, Instant.now());

            // then — UPDATE should proceed (warning only, not a conflict)
            assertThat(conflict).isFalse();
        }

        @Test
        @DisplayName("should proceed with write when conflict detection query fails (safety fallback)")
        void shouldProceedWhenConflictDetectionQueryFails() {
            // given
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);

            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenThrow(new RuntimeException("HSQLDB connection lost"));

            // when
            boolean conflict = DualWriteConfig.detectConflict(
                    jdbc, "orders", "INSERT", pk, Instant.now());

            // then — should proceed (false) as safety fallback
            assertThat(conflict).isFalse();
        }

        @Test
        @DisplayName("should handle composite PK for lineitem conflict detection")
        void shouldHandleCompositePkConflictDetection() {
            // given — lineitem has (orderid, linenum) composite PK
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("orderid", 1001);
            pk.put("linenum", 1);

            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1); // Row exists

            // when
            boolean conflict = DualWriteConfig.detectConflict(
                    jdbc, "lineitem", "INSERT", pk, Instant.now());

            // then
            assertThat(conflict).isTrue();
            verify(jdbc).queryForObject(
                    eq("SELECT COUNT(*) FROM lineitem WHERE orderid = ? AND linenum = ?"),
                    eq(Integer.class), eq(1001), eq(1));
        }
    }
}
