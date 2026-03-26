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
package com.jpetstore.migration;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DataLoader} — the PostgreSQL data loading utility.
 *
 * <p>Tests cover constructor validation, CSV line parsing, SQL generation,
 * type code mapping, decimal scale extraction, and batch result counting.
 * Private static methods are tested via reflection since they contain critical
 * business logic for data type conversion and CSV parsing.</p>
 *
 * <p>Integration tests against live PostgreSQL are in a separate {@code DataLoaderIT}
 * (if available) and require Testcontainers — intentionally separated from these
 * fast, pure-unit tests.</p>
 */
class DataLoaderTest {

    // Dummy connection params for constructor tests — not actually connected
    private static final String DUMMY_URL = "jdbc:postgresql://localhost:5432/test";
    private static final String DUMMY_USER = "postgres";
    private static final String DUMMY_PASS = "postgres";

    // =========================================================================
    // Constructor Validation Tests
    // =========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("should reject null accountDbUrl")
        void shouldRejectNullAccountDbUrl() {
            assertThatThrownBy(() -> new DataLoader(
                    null, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountDbUrl");
        }

        @Test
        @DisplayName("should reject null accountDbUser")
        void shouldRejectNullAccountDbUser() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, null, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountDbUser");
        }

        @Test
        @DisplayName("should reject null accountDbPassword")
        void shouldRejectNullAccountDbPassword() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, null,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountDbPassword");
        }

        @Test
        @DisplayName("should reject null catalogDbUrl")
        void shouldRejectNullCatalogDbUrl() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    null, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catalogDbUrl");
        }

        @Test
        @DisplayName("should reject null catalogDbUser")
        void shouldRejectNullCatalogDbUser() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, null, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catalogDbUser");
        }

        @Test
        @DisplayName("should reject null catalogDbPassword")
        void shouldRejectNullCatalogDbPassword() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, null,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catalogDbPassword");
        }

        @Test
        @DisplayName("should reject null orderDbUrl")
        void shouldRejectNullOrderDbUrl() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    null, DUMMY_USER, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orderDbUrl");
        }

        @Test
        @DisplayName("should reject null orderDbUser")
        void shouldRejectNullOrderDbUser() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, null, DUMMY_PASS,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orderDbUser");
        }

        @Test
        @DisplayName("should reject null orderDbPassword")
        void shouldRejectNullOrderDbPassword() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, null,
                    "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orderDbPassword");
        }

        @Test
        @DisplayName("should reject null exportDir")
        void shouldRejectNullExportDir() {
            assertThatThrownBy(() -> new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exportDir");
        }

        @Test
        @DisplayName("should accept valid parameters")
        void shouldAcceptValidParams() {
            DataLoader loader = new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    "/tmp");
            assertThat(loader).isNotNull();
        }
    }

    // =========================================================================
    // CSV Line Parsing Tests (via reflection on private static parseCsvLine)
    // =========================================================================

    @Nested
    @DisplayName("parseCsvLine — CSV Parsing")
    class ParseCsvLineTests {

        /**
         * Invokes the private static {@code parseCsvLine(String)} method via reflection.
         */
        @SuppressWarnings("unchecked")
        private List<String> parseCsvLine(String line) throws Exception {
            Method method = DataLoader.class.getDeclaredMethod("parseCsvLine", String.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(null, line);
        }

        @Test
        @DisplayName("should parse simple unquoted numeric fields")
        void shouldParseSimpleNumericFields() throws Exception {
            List<String> fields = parseCsvLine("1000,1,EST-1,1,16.50");
            assertThat(fields).containsExactly("1000", "1", "EST-1", "1", "16.50");
        }

        @Test
        @DisplayName("should parse quoted string fields")
        void shouldParseQuotedStringFields() throws Exception {
            List<String> fields = parseCsvLine("\"j2ee\",\"j2ee\"");
            assertThat(fields).containsExactly("j2ee", "j2ee");
        }

        @Test
        @DisplayName("should represent unquoted empty field as null (SQL NULL)")
        void shouldRepresentEmptyFieldAsNull() throws Exception {
            List<String> fields = parseCsvLine("\"EST-1\",\"FI-SW-01\",16.50,10.00,1,\"P\",\"Large\",,,,");
            assertThat(fields.get(0)).isEqualTo("EST-1");
            // attr2 through attr5 are NULL — unquoted empty fields between commas
            assertThat(fields.get(7)).isNull();
            assertThat(fields.get(8)).isNull();
            assertThat(fields.get(9)).isNull();
            assertThat(fields.get(10)).isNull();
        }

        @Test
        @DisplayName("should parse quoted field containing comma")
        void shouldParseQuotedFieldContainingComma() throws Exception {
            List<String> fields = parseCsvLine("\"hello, world\",42");
            assertThat(fields).containsExactly("hello, world", "42");
        }

        @Test
        @DisplayName("should parse escaped double-quotes inside quoted field")
        void shouldParseEscapedDoubleQuotes() throws Exception {
            List<String> fields = parseCsvLine("\"He said \"\"hello\"\"\",42");
            assertThat(fields.get(0)).isEqualTo("He said \"hello\"");
            assertThat(fields.get(1)).isEqualTo("42");
        }

        @Test
        @DisplayName("should return empty list for null input")
        void shouldReturnEmptyForNullInput() throws Exception {
            List<String> fields = parseCsvLine(null);
            assertThat(fields).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty string")
        void shouldReturnEmptyForEmptyString() throws Exception {
            List<String> fields = parseCsvLine("");
            assertThat(fields).isEmpty();
        }

        @Test
        @DisplayName("should parse mixed quoted and unquoted fields")
        void shouldParseMixedFields() throws Exception {
            // Simulates a signon row: quoted username, quoted password
            List<String> fields = parseCsvLine("\"j2ee\",\"j2ee\"");
            assertThat(fields).hasSize(2);
            assertThat(fields.get(0)).isEqualTo("j2ee");
            assertThat(fields.get(1)).isEqualTo("j2ee");
        }
    }

    // =========================================================================
    // buildInsertSql Tests (via reflection on private static method)
    // =========================================================================

    @Nested
    @DisplayName("buildInsertSql — SQL Generation")
    class BuildInsertSqlTests {

        /**
         * Invokes the private static {@code buildInsertSql} method via reflection.
         */
        private String buildInsertSql(String tableName, List<String> pgColumns,
                                      List<String> pkColumns) throws Exception {
            Method method = DataLoader.class.getDeclaredMethod("buildInsertSql",
                    String.class, List.class, List.class);
            method.setAccessible(true);
            return (String) method.invoke(null, tableName, pgColumns, pkColumns);
        }

        @Test
        @DisplayName("should generate INSERT ON CONFLICT DO NOTHING for signon table")
        void shouldGenerateInsertForSignonTable() throws Exception {
            String sql = buildInsertSql("signon",
                    List.of("username", "password"),
                    List.of("username"));

            assertThat(sql).isEqualTo(
                    "INSERT INTO signon (username, password) VALUES (?, ?) ON CONFLICT (username) DO NOTHING");
        }

        @Test
        @DisplayName("should generate INSERT with composite PK for lineitem table")
        void shouldGenerateInsertForLineitemTable() throws Exception {
            String sql = buildInsertSql("lineitem",
                    List.of("order_id", "line_num", "item_id", "quantity", "unit_price"),
                    List.of("order_id", "line_num"));

            assertThat(sql).contains("INSERT INTO lineitem");
            assertThat(sql).contains("order_id, line_num, item_id, quantity, unit_price");
            assertThat(sql).contains("VALUES (?, ?, ?, ?, ?)");
            assertThat(sql).contains("ON CONFLICT (order_id, line_num) DO NOTHING");
        }
    }

    // =========================================================================
    // getSqlTypeCode Tests (via reflection on private static method)
    // =========================================================================

    @Nested
    @DisplayName("getSqlTypeCode — JDBC Type Mapping")
    class GetSqlTypeCodeTests {

        private int getSqlTypeCode(String pgType) throws Exception {
            Method method = DataLoader.class.getDeclaredMethod("getSqlTypeCode", String.class);
            method.setAccessible(true);
            return (int) method.invoke(null, pgType);
        }

        @Test
        @DisplayName("should map varchar to Types.VARCHAR")
        void shouldMapVarchar() throws Exception {
            assertThat(getSqlTypeCode("varchar(80)")).isEqualTo(Types.VARCHAR);
        }

        @Test
        @DisplayName("should map integer to Types.INTEGER")
        void shouldMapInteger() throws Exception {
            assertThat(getSqlTypeCode("integer")).isEqualTo(Types.INTEGER);
        }

        @Test
        @DisplayName("should map numeric to Types.NUMERIC")
        void shouldMapNumeric() throws Exception {
            assertThat(getSqlTypeCode("numeric(10,2)")).isEqualTo(Types.NUMERIC);
        }

        @Test
        @DisplayName("should map timestamp to Types.TIMESTAMP")
        void shouldMapTimestamp() throws Exception {
            assertThat(getSqlTypeCode("timestamp with time zone")).isEqualTo(Types.TIMESTAMP);
        }

        @Test
        @DisplayName("should default to Types.VARCHAR for null")
        void shouldDefaultToVarcharForNull() throws Exception {
            assertThat(getSqlTypeCode(null)).isEqualTo(Types.VARCHAR);
        }

        @Test
        @DisplayName("should default to Types.VARCHAR for unknown type")
        void shouldDefaultToVarcharForUnknown() throws Exception {
            assertThat(getSqlTypeCode("jsonb")).isEqualTo(Types.VARCHAR);
        }
    }

    // =========================================================================
    // extractDecimalScale Tests (via reflection on private static method)
    // =========================================================================

    @Nested
    @DisplayName("extractDecimalScale — Precision Extraction")
    class ExtractDecimalScaleTests {

        private int extractDecimalScale(String pgType) throws Exception {
            Method method = DataLoader.class.getDeclaredMethod("extractDecimalScale", String.class);
            method.setAccessible(true);
            return (int) method.invoke(null, pgType);
        }

        @Test
        @DisplayName("should extract scale 2 from numeric(10,2)")
        void shouldExtractScale2() throws Exception {
            assertThat(extractDecimalScale("numeric(10,2)")).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 0 for numeric without precision")
        void shouldReturn0ForNoPrecision() throws Exception {
            assertThat(extractDecimalScale("numeric")).isEqualTo(0);
        }

        @Test
        @DisplayName("should return 0 for malformed type string")
        void shouldReturn0ForMalformedType() throws Exception {
            assertThat(extractDecimalScale("numeric(abc)")).isEqualTo(0);
        }
    }

    // =========================================================================
    // countBatchResults Tests (via reflection on private static method)
    // =========================================================================

    @Nested
    @DisplayName("countBatchResults — Batch Statistics")
    class CountBatchResultsTests {

        private int[] countBatchResults(int[] batchResults) throws Exception {
            Method method = DataLoader.class.getDeclaredMethod("countBatchResults", int[].class);
            method.setAccessible(true);
            return (int[]) method.invoke(null, batchResults);
        }

        @Test
        @DisplayName("should count all rows as inserted when affected rows > 0")
        void shouldCountInsertedRows() throws Exception {
            int[] results = countBatchResults(new int[]{1, 1, 1});
            assertThat(results[0]).isEqualTo(3); // inserted
            assertThat(results[1]).isEqualTo(0); // skipped
        }

        @Test
        @DisplayName("should count rows with result 0 as skipped (ON CONFLICT DO NOTHING)")
        void shouldCountSkippedRows() throws Exception {
            int[] results = countBatchResults(new int[]{1, 0, 1, 0});
            assertThat(results[0]).isEqualTo(2); // inserted
            assertThat(results[1]).isEqualTo(2); // skipped
        }

        @Test
        @DisplayName("should count SUCCESS_NO_INFO as inserted")
        void shouldCountSuccessNoInfoAsInserted() throws Exception {
            int[] results = countBatchResults(new int[]{Statement.SUCCESS_NO_INFO, 1});
            assertThat(results[0]).isEqualTo(2); // both counted as inserted
            assertThat(results[1]).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle empty batch results")
        void shouldHandleEmptyBatchResults() throws Exception {
            int[] results = countBatchResults(new int[]{});
            assertThat(results[0]).isEqualTo(0);
            assertThat(results[1]).isEqualTo(0);
        }
    }

    // =========================================================================
    // loadTable Error Handling Tests
    // =========================================================================

    @Nested
    @DisplayName("loadTable — Error Handling")
    class LoadTableErrorHandlingTests {

        @Test
        @DisplayName("should throw IOException when CSV file does not exist")
        void shouldThrowWhenCsvFileMissing(@TempDir Path tempDir) {
            DataLoader loader = new DataLoader(
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    DUMMY_URL, DUMMY_USER, DUMMY_PASS,
                    tempDir.toString());

            // We cannot actually call loadTable without a real connection to PostgreSQL,
            // but we can verify the file-not-found check by creating a mock scenario.
            // The method takes a Connection + tableName + filePath — passing a non-existent file path
            // should cause an IOException before any DB operations.
            assertThatThrownBy(() -> loader.loadTable(null, "signon", "/nonexistent/path/signon.csv"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("CSV file not found");
        }
    }
}
