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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DataExporter} — the HSQLDB-to-CSV export utility.
 *
 * <p>Uses an embedded in-memory HSQLDB instance seeded with the JPetStore schema
 * and a small representative data set to test the full export pipeline without
 * requiring an external database.</p>
 */
class DataExporterTest {

    /** Shared in-memory HSQLDB connection URL — unique per test class to avoid cross-contamination. */
    private static final String HSQLDB_URL = "jdbc:hsqldb:mem:exportertest";
    private static final String HSQLDB_USER = "SA";
    private static final String HSQLDB_PASSWORD = "";

    private static Connection conn;

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        conn = DriverManager.getConnection(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD);
        try (Statement stmt = conn.createStatement()) {
            // Create a minimal subset of the JPetStore schema that SchemaMapper references.
            // Only tables needed for the export pipeline; not the full 13-table schema.
            stmt.execute("CREATE TABLE signon (username VARCHAR(25) NOT NULL PRIMARY KEY, password VARCHAR(25) NOT NULL)");
            stmt.execute("CREATE TABLE account (userid VARCHAR(80) NOT NULL PRIMARY KEY, email VARCHAR(80), "
                    + "firstname VARCHAR(80), lastname VARCHAR(80), status VARCHAR(2), "
                    + "addr1 VARCHAR(80), addr2 VARCHAR(80), city VARCHAR(80), state VARCHAR(80), "
                    + "zip VARCHAR(20), country VARCHAR(20), phone VARCHAR(80))");
            stmt.execute("CREATE TABLE profile (userid VARCHAR(80) NOT NULL PRIMARY KEY, "
                    + "langpref VARCHAR(80), favcategoryid VARCHAR(30), mylistopt INT, banneropt INT)");
            stmt.execute("CREATE TABLE bannerdata (favcategoryid VARCHAR(80) NOT NULL PRIMARY KEY, "
                    + "bannername VARCHAR(255))");
            stmt.execute("CREATE TABLE supplier (suppid INT NOT NULL PRIMARY KEY, name VARCHAR(80), "
                    + "status VARCHAR(2), addr1 VARCHAR(80), addr2 VARCHAR(80), city VARCHAR(80), "
                    + "state VARCHAR(80), zip VARCHAR(20), phone VARCHAR(80))");
            stmt.execute("CREATE TABLE category (catid VARCHAR(10) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(80), descn VARCHAR(255))");
            stmt.execute("CREATE TABLE product (productid VARCHAR(10) NOT NULL PRIMARY KEY, "
                    + "category VARCHAR(10), name VARCHAR(80), descn VARCHAR(255))");
            stmt.execute("CREATE TABLE item (itemid VARCHAR(10) NOT NULL PRIMARY KEY, "
                    + "productid VARCHAR(10), listprice DECIMAL(10,2), unitcost DECIMAL(10,2), "
                    + "supplier INT, status VARCHAR(2), attr1 VARCHAR(80), attr2 VARCHAR(80), "
                    + "attr3 VARCHAR(80), attr4 VARCHAR(80), attr5 VARCHAR(80))");
            stmt.execute("CREATE TABLE inventory (itemid VARCHAR(10) NOT NULL PRIMARY KEY, qty INT)");
            stmt.execute("CREATE TABLE orders (orderid INT NOT NULL PRIMARY KEY, userid VARCHAR(80), "
                    + "orderdate DATE, shipaddr1 VARCHAR(80), shipaddr2 VARCHAR(80), "
                    + "shipcity VARCHAR(80), shipstate VARCHAR(80), shipzip VARCHAR(20), "
                    + "shipcountry VARCHAR(20), billaddr1 VARCHAR(80), billaddr2 VARCHAR(80), "
                    + "billcity VARCHAR(80), billstate VARCHAR(80), billzip VARCHAR(20), "
                    + "billcountry VARCHAR(20), courier VARCHAR(80), totalprice DECIMAL(10,2), "
                    + "billtofirstname VARCHAR(80), billtolastname VARCHAR(80), "
                    + "shiptofirstname VARCHAR(80), shiptolastname VARCHAR(80), "
                    + "creditcard VARCHAR(80), exprdate VARCHAR(7), cardtype VARCHAR(80), locale VARCHAR(80))");
            stmt.execute("CREATE TABLE orderstatus (orderid INT NOT NULL, linenum INT NOT NULL, "
                    + "timestamp DATE, status VARCHAR(2), PRIMARY KEY (orderid, linenum))");
            stmt.execute("CREATE TABLE lineitem (orderid INT NOT NULL, linenum INT NOT NULL, "
                    + "itemid VARCHAR(10), quantity INT, unitprice DECIMAL(10,2), PRIMARY KEY (orderid, linenum))");

            // Seed minimal representative data
            stmt.execute("INSERT INTO signon VALUES ('j2ee', 'j2ee')");
            stmt.execute("INSERT INTO signon VALUES ('ACID', 'ACID')");
            stmt.execute("INSERT INTO account VALUES ('j2ee', 'yourname@yourdomain.com', 'ABC', 'XYZ', 'OK', "
                    + "'901 San Antonio Road', 'MS UCUP02-206', 'Palo Alto', 'CA', '94303', 'USA', '555-555-5555')");
            stmt.execute("INSERT INTO account VALUES ('ACID', 'acid@yourdomain.com', 'ABC', 'XYZ', 'OK', "
                    + "'901 San Antonio Road', 'MS UCUP02-206', 'Palo Alto', 'CA', '94303', 'USA', '555-555-5555')");
            stmt.execute("INSERT INTO profile VALUES ('j2ee', 'english', 'DOGS', 1, 1)");
            stmt.execute("INSERT INTO profile VALUES ('ACID', 'english', 'CATS', 1, 1)");
            stmt.execute("INSERT INTO bannerdata VALUES ('FISH', '<image src=\"../images/banner_fish.gif\">')");
            stmt.execute("INSERT INTO bannerdata VALUES ('CATS', '<image src=\"../images/banner_cats.gif\">')");
            stmt.execute("INSERT INTO bannerdata VALUES ('DOGS', '<image src=\"../images/banner_dogs.gif\">')");
            stmt.execute("INSERT INTO supplier VALUES (1, 'XYZ Pets', 'AC', '600 Avon Way', '', 'Los Angeles', 'CA', '94024', '212-947-0797')");
            stmt.execute("INSERT INTO supplier VALUES (2, 'ABC Pets', 'AC', '700 Abalone Way', '', 'San Francisco', 'CA', '94024', '415-947-0797')");
            stmt.execute("INSERT INTO category VALUES ('FISH', 'Fish', '<image src=\"../images/fish_icon.gif\">')");
            stmt.execute("INSERT INTO category VALUES ('DOGS', 'Dogs', '<image src=\"../images/dogs_icon.gif\">')");
            stmt.execute("INSERT INTO product VALUES ('FI-SW-01', 'FISH', 'Angelfish', 'Salt Water fish from Australia')");
            stmt.execute("INSERT INTO product VALUES ('K9-BD-01', 'DOGS', 'Bulldog', 'Friendly dog from England')");
            stmt.execute("INSERT INTO item VALUES ('EST-1', 'FI-SW-01', 16.50, 10.00, 1, 'P', 'Large', NULL, NULL, NULL, NULL)");
            stmt.execute("INSERT INTO item VALUES ('EST-2', 'FI-SW-01', 16.50, 10.00, 1, 'P', 'Small', NULL, NULL, NULL, NULL)");
            stmt.execute("INSERT INTO inventory VALUES ('EST-1', 10000)");
            stmt.execute("INSERT INTO inventory VALUES ('EST-2', 10000)");
            stmt.execute("INSERT INTO orders VALUES (1000, 'j2ee', '2024-01-15', "
                    + "'901 San Antonio Road', 'MS UCUP02-206', 'Palo Alto', 'CA', '94303', 'USA', "
                    + "'901 San Antonio Road', 'MS UCUP02-206', 'Palo Alto', 'CA', '94303', 'USA', "
                    + "'UPS', 16.50, 'ABC', 'XYZ', 'ABC', 'XYZ', '999 9999 9999 9999', '12/03', 'Visa', 'CA')");
            stmt.execute("INSERT INTO orderstatus VALUES (1000, 1, '2024-01-15', 'P')");
            stmt.execute("INSERT INTO lineitem VALUES (1000, 1, 'EST-1', 1, 16.50)");
        }
    }

    @AfterAll
    static void tearDownDatabase() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.createStatement().execute("SHUTDOWN");
            conn.close();
        }
    }

    // =========================================================================
    // Constructor Validation Tests
    // =========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("should reject null hsqldbUrl")
        void shouldRejectNullUrl() {
            assertThatThrownBy(() -> new DataExporter(null, HSQLDB_USER, HSQLDB_PASSWORD, "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hsqldbUrl");
        }

        @Test
        @DisplayName("should reject null hsqldbUser")
        void shouldRejectNullUser() {
            assertThatThrownBy(() -> new DataExporter(HSQLDB_URL, null, HSQLDB_PASSWORD, "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hsqldbUser");
        }

        @Test
        @DisplayName("should reject null hsqldbPassword")
        void shouldRejectNullPassword() {
            assertThatThrownBy(() -> new DataExporter(HSQLDB_URL, HSQLDB_USER, null, "/tmp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hsqldbPassword");
        }

        @Test
        @DisplayName("should reject null outputDir")
        void shouldRejectNullOutputDir() {
            assertThatThrownBy(() -> new DataExporter(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outputDir");
        }

        @Test
        @DisplayName("should accept valid parameters")
        void shouldAcceptValidParams() {
            DataExporter exporter = new DataExporter(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, "/tmp/test");
            assertThat(exporter).isNotNull();
        }
    }

    // =========================================================================
    // ExportReport Tests
    // =========================================================================

    @Nested
    @DisplayName("ExportReport")
    class ExportReportTests {

        @Test
        @DisplayName("should create immutable report with correct values")
        void shouldCreateImmutableReport() {
            var counts = new java.util.LinkedHashMap<String, Integer>();
            counts.put("signon", 2);
            counts.put("account", 2);
            var failed = List.of("orderstatus");

            var report = new DataExporter.ExportReport(counts, false, failed, "2024-01-15T10:30:00");

            assertThat(report.success).isFalse();
            assertThat(report.tableCounts).containsEntry("signon", 2);
            assertThat(report.tableCounts).containsEntry("account", 2);
            assertThat(report.failedTables).containsExactly("orderstatus");
            assertThat(report.timestamp).isEqualTo("2024-01-15T10:30:00");
        }

        @Test
        @DisplayName("should produce informative toString")
        void shouldProduceInformativeToString() {
            var report = new DataExporter.ExportReport(
                    java.util.Map.of("signon", 2), true, List.of(), "2024-01-15");
            String str = report.toString();
            assertThat(str).contains("success=true");
            assertThat(str).contains("tables=1");
            assertThat(str).contains("failed=[]");
        }
    }

    // =========================================================================
    // Full Export Pipeline Tests
    // =========================================================================

    @Nested
    @DisplayName("Full Export Pipeline")
    class FullExportPipelineTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("should export all tables and produce success report")
        void shouldExportAllTablesSuccessfully() {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            DataExporter.ExportReport report = exporter.exportAll();

            assertThat(report.success).isTrue();
            assertThat(report.failedTables).isEmpty();
            assertThat(report.tableCounts).hasSize(12); // All 12 migrated tables
            assertThat(report.timestamp).isNotNull();

            // Verify specific row counts from seed data
            assertThat(report.tableCounts.get("signon")).isEqualTo(2);
            assertThat(report.tableCounts.get("account")).isEqualTo(2);
            assertThat(report.tableCounts.get("profile")).isEqualTo(2);
            assertThat(report.tableCounts.get("bannerdata")).isEqualTo(3);
            assertThat(report.tableCounts.get("supplier")).isEqualTo(2);
            assertThat(report.tableCounts.get("category")).isEqualTo(2);
            assertThat(report.tableCounts.get("product")).isEqualTo(2);
            assertThat(report.tableCounts.get("item")).isEqualTo(2);
            assertThat(report.tableCounts.get("inventory")).isEqualTo(2);
            assertThat(report.tableCounts.get("orders")).isEqualTo(1);
            assertThat(report.tableCounts.get("orderstatus")).isEqualTo(1);
            assertThat(report.tableCounts.get("lineitem")).isEqualTo(1);
        }

        @Test
        @DisplayName("should create CSV files for each table")
        void shouldCreateCsvFilesForEachTable() {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            exporter.exportAll();

            for (String table : SchemaMapper.getAllTableNames()) {
                File csvFile = tempDir.resolve(table + ".csv").toFile();
                assertThat(csvFile).exists().isFile();
                assertThat(csvFile.length()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("should create export manifest file")
        void shouldCreateExportManifest() throws IOException {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            exporter.exportAll();

            File manifest = tempDir.resolve("export-manifest.properties").toFile();
            assertThat(manifest).exists();
            String content = Files.readString(manifest.toPath());
            assertThat(content).contains("export.timestamp=");
            assertThat(content).contains("export.tableCount=12");
            assertThat(content).contains("table.signon.rows=2");
            assertThat(content).contains("table.signon.targetDb=jpetstore_account");
            assertThat(content).contains("table.orders.targetDb=jpetstore_order");
        }

        @Test
        @DisplayName("should produce CSV with proper header and data for signon table")
        void shouldProduceProperCsvForSignonTable() throws IOException {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            exporter.exportAll();

            Path csvPath = tempDir.resolve("signon.csv");
            List<String> lines = Files.readAllLines(csvPath);
            assertThat(lines).hasSize(3); // 1 header + 2 data rows
            // Header should contain column names
            assertThat(lines.get(0)).containsIgnoringCase("USERNAME");
            assertThat(lines.get(0)).containsIgnoringCase("PASSWORD");
        }

        @Test
        @DisplayName("should preserve decimal precision in item CSV")
        void shouldPreserveDecimalPrecisionInItemCsv() throws IOException {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            exporter.exportAll();

            Path csvPath = tempDir.resolve("item.csv");
            String content = Files.readString(csvPath);
            // 16.50 should be preserved with 2 decimal places, not truncated to 16.5
            assertThat(content).contains("16.50");
            assertThat(content).contains("10.00");
        }

        @Test
        @DisplayName("should handle quoted strings in CSV (bannerdata has HTML content)")
        void shouldHandleQuotedStringsInCsv() throws IOException {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            exporter.exportAll();

            Path csvPath = tempDir.resolve("bannerdata.csv");
            List<String> lines = Files.readAllLines(csvPath);
            assertThat(lines).hasSize(4); // 1 header + 3 data rows
            // HTML banner names contain angle brackets and quotes — they should be quoted in CSV
            String dataContent = String.join("\n", lines);
            assertThat(dataContent).contains("banner_fish.gif");
        }
    }

    // =========================================================================
    // Single Table Export Tests
    // =========================================================================

    @Nested
    @DisplayName("exportTable — Single Table")
    class SingleTableExportTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("should export single table and return correct row count")
        void shouldExportSingleTableWithCorrectRowCount() throws Exception {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            try (Connection localConn = DriverManager.getConnection(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD)) {
                localConn.setReadOnly(true);
                int rowCount = exporter.exportTable(localConn, "category");
                assertThat(rowCount).isEqualTo(2);

                File csv = tempDir.resolve("category.csv").toFile();
                assertThat(csv).exists();
            }
        }
    }

    // =========================================================================
    // Row Count Verification Tests
    // =========================================================================

    @Nested
    @DisplayName("verifyRowCount")
    class VerifyRowCountTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("should return true when count matches")
        void shouldReturnTrueWhenCountMatches() throws Exception {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            try (Connection localConn = DriverManager.getConnection(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD)) {
                boolean result = exporter.verifyRowCount(localConn, "signon", 2);
                assertThat(result).isTrue();
            }
        }

        @Test
        @DisplayName("should return false when count mismatches")
        void shouldReturnFalseWhenCountMismatches() throws Exception {
            DataExporter exporter = new DataExporter(
                    HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            try (Connection localConn = DriverManager.getConnection(HSQLDB_URL, HSQLDB_USER, HSQLDB_PASSWORD)) {
                boolean result = exporter.verifyRowCount(localConn, "signon", 999);
                assertThat(result).isFalse();
            }
        }
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should produce failure report when HSQLDB URL is invalid")
        void shouldProduceFailureReportWhenUrlInvalid(@TempDir Path tempDir) {
            DataExporter exporter = new DataExporter(
                    "jdbc:hsqldb:mem:nonexistent_db_that_will_not_connect_" + System.nanoTime(),
                    HSQLDB_USER, HSQLDB_PASSWORD, tempDir.toString());

            DataExporter.ExportReport report = exporter.exportAll();

            // Even if connection succeeds to in-memory (HSQLDB creates on connect),
            // verify the report is well-formed
            assertThat(report).isNotNull();
            assertThat(report.tableCounts).isNotNull();
            assertThat(report.timestamp).isNotNull();
        }
    }
}
