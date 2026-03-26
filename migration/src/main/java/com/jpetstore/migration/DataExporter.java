package com.jpetstore.migration;

import java.io.BufferedWriter;
import java.io.File;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC-based read-only HSQLDB export utility for the JPetStore migration pipeline.
 *
 * <p>Connects to the live HSQLDB database via JDBC and exports all 13 tables to
 * individual CSV files (one per table) without modifying the source database. Uses
 * {@link SchemaMapper} for table-to-target-database mapping and to iterate all tables
 * in the correct export order.</p>
 *
 * <p>Key guarantees:</p>
 * <ul>
 *   <li>Read-only access to HSQLDB — no DML or DDL is ever executed</li>
 *   <li>Dynamic column discovery via {@link ResultSetMetaData} — no hardcoded column lists</li>
 *   <li>RFC 4180-compliant CSV output with proper quoting and escaping</li>
 *   <li>Exact decimal precision preserved (e.g., 16.50 not 16.5)</li>
 *   <li>Row count verification after each table export</li>
 *   <li>Export manifest file with table-to-database mapping for downstream consumption</li>
 *   <li>Per-table error isolation — a failure on one table does not block others</li>
 * </ul>
 *
 * <p>Has its own {@code main()} method for standalone execution via:</p>
 * <pre>
 *   java -Dhsqldb.url=jdbc:hsqldb:hsql://localhost/jpetstore \
 *        -Dhsqldb.user=SA -Dhsqldb.password= \
 *        -Dexport.dir=./export \
 *        -cp migration.jar com.jpetstore.migration.DataExporter
 * </pre>
 */
public class DataExporter {

    // ==========================================
    // Instance Fields
    // ==========================================

    /** JDBC connection URL for the source HSQLDB database. */
    private final String hsqldbUrl;

    /** HSQLDB database user name. */
    private final String hsqldbUser;

    /** HSQLDB database password. */
    private final String hsqldbPassword;

    /** Directory path where exported CSV files and the manifest are written. */
    private final String outputDir;

    // ==========================================
    // Inner Type: ExportReport
    // ==========================================

    /**
     * Immutable report produced by {@link #exportAll()} summarising the outcome
     * of a full database export run.
     *
     * <p>Consumers can inspect {@code success} to determine whether every table
     * exported and verified successfully, and drill into {@code failedTables}
     * for details on any failures.</p>
     */
    public static class ExportReport {

        /** Per-table row counts keyed by lowercase table name. Insertion order is preserved. */
        public final Map<String, Integer> tableCounts;

        /** {@code true} if and only if every table was exported and verified without error. */
        public final boolean success;

        /** List of table names that failed during export or verification. Empty on full success. */
        public final List<String> failedTables;

        /** ISO 8601 timestamp recording when the export was performed. */
        public final String timestamp;

        /**
         * Constructs an export report.
         *
         * @param tableCounts per-table row counts (insertion-ordered)
         * @param success     overall success flag
         * @param failedTables list of tables that encountered errors
         * @param timestamp   ISO 8601 export timestamp
         */
        public ExportReport(Map<String, Integer> tableCounts, boolean success,
                            List<String> failedTables, String timestamp) {
            this.tableCounts = Map.copyOf(tableCounts);
            this.success = success;
            this.failedTables = List.copyOf(failedTables);
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "ExportReport{success=" + success
                    + ", tables=" + tableCounts.size()
                    + ", failed=" + failedTables
                    + ", timestamp=" + timestamp + "}";
        }
    }

    // ==========================================
    // Constructor
    // ==========================================

    /**
     * Creates a new DataExporter configured with HSQLDB connection details and
     * an output directory for the exported CSV files.
     *
     * @param hsqldbUrl      JDBC URL for the HSQLDB database
     *                       (e.g., {@code "jdbc:hsqldb:hsql://localhost/jpetstore"})
     * @param hsqldbUser     database user name (typically {@code "SA"})
     * @param hsqldbPassword database password (typically empty string)
     * @param outputDir      file system path for the export output directory
     * @throws IllegalArgumentException if any parameter is null
     */
    public DataExporter(String hsqldbUrl, String hsqldbUser, String hsqldbPassword, String outputDir) {
        if (hsqldbUrl == null) {
            throw new IllegalArgumentException("hsqldbUrl must not be null");
        }
        if (hsqldbUser == null) {
            throw new IllegalArgumentException("hsqldbUser must not be null");
        }
        if (hsqldbPassword == null) {
            throw new IllegalArgumentException("hsqldbPassword must not be null");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        this.hsqldbUrl = hsqldbUrl;
        this.hsqldbUser = hsqldbUser;
        this.hsqldbPassword = hsqldbPassword;
        this.outputDir = outputDir;
    }

    // ==========================================
    // Core Export Orchestration
    // ==========================================

    /**
     * Exports all 13 HSQLDB tables to individual CSV files in the output directory
     * and writes an export manifest properties file.
     *
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Create output directory if it does not exist</li>
     *   <li>Open a read-only JDBC connection to HSQLDB</li>
     *   <li>Record baseline row counts for all 13 tables</li>
     *   <li>For each table: export to CSV and verify the row count matches the baseline</li>
     *   <li>Write the export manifest with table metadata</li>
     * </ol>
     *
     * <p>A failure on an individual table does not prevent the remaining tables from
     * being exported. Failed tables are tracked in the returned {@link ExportReport}.</p>
     *
     * @return an {@link ExportReport} describing per-table row counts and overall success
     */
    public ExportReport exportAll() {
        String exportTimestamp = LocalDateTime.now().toString();
        Map<String, Integer> tableCounts = new LinkedHashMap<>();
        List<String> failedTables = new ArrayList<>();

        // Ensure the output directory exists
        try {
            Files.createDirectories(Path.of(outputDir));
        } catch (IOException e) {
            System.err.println("ERROR: Failed to create output directory: " + outputDir + " — " + e.getMessage());
            // If we cannot create the output directory, every table will fail
            for (String table : SchemaMapper.getAllTableNames()) {
                failedTables.add(table);
                tableCounts.put(table, 0);
            }
            return new ExportReport(tableCounts, false, failedTables, exportTimestamp);
        }

        try (Connection conn = DriverManager.getConnection(hsqldbUrl, hsqldbUser, hsqldbPassword)) {
            // Enforce read-only access — hard requirement from AAP
            conn.setReadOnly(true);

            // Phase 1: Record baseline row counts for all 13 tables before export
            Map<String, Integer> baselineCounts = new LinkedHashMap<>();
            for (String table : SchemaMapper.getAllTableNames()) {
                try {
                    int baseline = getRowCount(conn, table);
                    baselineCounts.put(table, baseline);
                    System.out.println("Baseline row count for " + table + ": " + baseline);
                } catch (SQLException e) {
                    System.err.println("ERROR: Failed to get baseline count for " + table + " — " + e.getMessage());
                    baselineCounts.put(table, -1);
                }
            }

            // Phase 2: Export each table and verify row counts
            for (String table : SchemaMapper.getAllTableNames()) {
                try {
                    int exportedRows = exportTable(conn, table);
                    tableCounts.put(table, exportedRows);

                    // Verify the exported count matches the baseline
                    boolean verified = verifyRowCount(conn, table, exportedRows);
                    int baseline = baselineCounts.getOrDefault(table, -1);

                    if (!verified) {
                        System.err.println("WARNING: Row count mismatch for " + table
                                + ": baseline=" + baseline + ", exported=" + exportedRows);
                        failedTables.add(table);
                    } else {
                        System.out.println("Row count verified: " + table + " = " + exportedRows + " rows");
                    }
                } catch (SQLException | IOException e) {
                    System.err.println("ERROR: Failed to export table " + table + " — " + e.getMessage());
                    failedTables.add(table);
                    tableCounts.put(table, 0);
                }
            }

            // Phase 3: Write export manifest
            try {
                writeExportManifest(tableCounts, exportTimestamp);
                System.out.println("Export manifest written to " + outputDir + "/export-manifest.properties");
            } catch (IOException e) {
                System.err.println("ERROR: Failed to write export manifest — " + e.getMessage());
            }

        } catch (SQLException e) {
            System.err.println("ERROR: Failed to connect to HSQLDB at " + hsqldbUrl + " — " + e.getMessage());
            for (String table : SchemaMapper.getAllTableNames()) {
                if (!tableCounts.containsKey(table)) {
                    failedTables.add(table);
                    tableCounts.put(table, 0);
                }
            }
        }

        boolean allSuccess = failedTables.isEmpty();
        ExportReport report = new ExportReport(tableCounts, allSuccess, failedTables, exportTimestamp);

        System.out.println();
        System.out.println("=== Export Summary ===");
        System.out.println("Timestamp: " + exportTimestamp);
        System.out.println("Output directory: " + outputDir);
        System.out.println("Tables exported: " + tableCounts.size());
        System.out.println("Overall success: " + allSuccess);
        if (!failedTables.isEmpty()) {
            System.out.println("Failed tables: " + failedTables);
        }
        for (Map.Entry<String, Integer> entry : tableCounts.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue() + " rows");
        }

        return report;
    }

    // ==========================================
    // Single Table Export
    // ==========================================

    /**
     * Exports a single HSQLDB table to a CSV file in the output directory.
     *
     * <p>The CSV file is named {@code <tableName>.csv} and follows RFC 4180:</p>
     * <ul>
     *   <li>First line is a header row with HSQLDB column names (UPPERCASE as returned by driver)</li>
     *   <li>String values are enclosed in double quotes; internal quotes are doubled</li>
     *   <li>NULL values produce empty fields (no quotes)</li>
     *   <li>Numeric values are unquoted with exact decimal precision preserved</li>
     *   <li>Date values are written in ISO 8601 format (YYYY-MM-DD)</li>
     * </ul>
     *
     * <p>Only SELECT statements are executed — no DML or DDL.</p>
     *
     * @param conn      an active JDBC connection to the HSQLDB database
     * @param tableName the lowercase table name to export
     * @return the number of data rows written (excludes the header row)
     * @throws SQLException if a database access error occurs
     * @throws IOException  if the CSV file cannot be written
     */
    public int exportTable(Connection conn, String tableName) throws SQLException, IOException {
        String csvFilePath = Path.of(outputDir, tableName + ".csv").toString();
        System.out.println("Exporting table " + tableName + " to " + csvFilePath + "...");

        int rowCount = 0;

        try (Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName);
             BufferedWriter bw = Files.newBufferedWriter(Path.of(csvFilePath));
             PrintWriter writer = new PrintWriter(bw)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            // Write header row with column names as they appear in HSQLDB
            // (the DataLoader will convert to snake_case using SchemaMapper)
            StringBuilder headerLine = new StringBuilder();
            for (int col = 1; col <= columnCount; col++) {
                if (col > 1) {
                    headerLine.append(',');
                }
                headerLine.append(meta.getColumnName(col));
            }
            writer.println(headerLine.toString());

            // Cache column types for efficient per-row formatting
            int[] columnTypes = new int[columnCount + 1];
            int[] columnScales = new int[columnCount + 1];
            for (int col = 1; col <= columnCount; col++) {
                columnTypes[col] = meta.getColumnType(col);
                columnScales[col] = meta.getScale(col);
            }

            // Write data rows
            while (rs.next()) {
                StringBuilder dataLine = new StringBuilder();
                for (int col = 1; col <= columnCount; col++) {
                    if (col > 1) {
                        dataLine.append(',');
                    }
                    appendCsvValue(dataLine, rs, col, columnTypes[col], columnScales[col]);
                }
                writer.println(dataLine.toString());
                rowCount++;
            }
        }

        System.out.println("Exported " + rowCount + " rows from " + tableName);
        return rowCount;
    }

    // ==========================================
    // Row Count Verification
    // ==========================================

    /**
     * Verifies that the number of rows exported for a table matches the current
     * row count in the HSQLDB database.
     *
     * <p>This safety check detects partial exports caused by connection issues,
     * timeouts, or concurrent modifications. The method queries
     * {@code SELECT COUNT(*) FROM <tableName>} and compares against the number
     * of rows that were written to the CSV file.</p>
     *
     * @param conn          an active JDBC connection to the HSQLDB database
     * @param tableName     the table name to verify
     * @param exportedCount the number of rows written to the CSV file
     * @return {@code true} if the counts match; {@code false} otherwise
     * @throws SQLException if a database access error occurs during verification
     */
    public boolean verifyRowCount(Connection conn, String tableName, int exportedCount) throws SQLException {
        int actualCount = getRowCount(conn, tableName);
        if (actualCount != exportedCount) {
            System.err.println("WARNING: Row count mismatch for " + tableName
                    + ": expected " + actualCount + ", got " + exportedCount);
            return false;
        }
        return true;
    }

    // ==========================================
    // Standalone Entry Point
    // ==========================================

    /**
     * Standalone entry point for running the HSQLDB export from the command line.
     *
     * <p>Accepts configuration via system properties:</p>
     * <ul>
     *   <li>{@code -Dhsqldb.url} — JDBC URL (default: {@code jdbc:hsqldb:hsql://localhost/jpetstore})</li>
     *   <li>{@code -Dhsqldb.user} — database user (default: {@code SA})</li>
     *   <li>{@code -Dhsqldb.password} — database password (default: empty)</li>
     *   <li>{@code -Dexport.dir} — output directory (default: {@code ./export})</li>
     * </ul>
     *
     * <p>Exits with code 0 on success, 1 on any failure.</p>
     *
     * @param args command-line arguments (not used; configuration via system properties)
     */
    public static void main(String[] args) {
        String url = System.getProperty("hsqldb.url", "jdbc:hsqldb:hsql://localhost/jpetstore");
        String user = System.getProperty("hsqldb.user", "SA");
        String password = System.getProperty("hsqldb.password", "");
        String exportDir = System.getProperty("export.dir", "./export");

        System.out.println("=== JPetStore HSQLDB Data Exporter ===");
        System.out.println("Source URL:  " + url);
        System.out.println("User:        " + user);
        System.out.println("Output dir:  " + exportDir);
        System.out.println();

        DataExporter exporter = new DataExporter(url, user, password, exportDir);
        ExportReport report = exporter.exportAll();

        if (report.success) {
            System.out.println();
            System.out.println("Export completed successfully. All " + report.tableCounts.size() + " tables exported.");
            System.exit(0);
        } else {
            System.err.println();
            System.err.println("Export completed with failures. Failed tables: " + report.failedTables);
            System.exit(1);
        }
    }

    // ==========================================
    // Private Helper Methods
    // ==========================================

    /**
     * Queries the current row count for a table using {@code SELECT COUNT(*)}.
     *
     * @param conn      an active JDBC connection
     * @param tableName the table name
     * @return the row count
     * @throws SQLException if a database access error occurs
     */
    private int getRowCount(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    /**
     * Appends a single CSV-formatted value for a column to the given {@link StringBuilder}.
     *
     * <p>Formatting rules per RFC 4180:</p>
     * <ul>
     *   <li>NULL values: empty field (no output)</li>
     *   <li>VARCHAR/CHAR: double-quoted, internal double-quotes escaped by doubling</li>
     *   <li>INTEGER: unquoted number</li>
     *   <li>DECIMAL/NUMERIC: unquoted with exact scale (e.g., {@code 16.50})</li>
     *   <li>DATE/TIMESTAMP: ISO 8601 date format (YYYY-MM-DD)</li>
     * </ul>
     *
     * @param sb         the StringBuilder to append to
     * @param rs         the current ResultSet row
     * @param colIndex   1-based column index
     * @param sqlType    the JDBC SQL type constant from {@link Types}
     * @param scale      the column's decimal scale (for DECIMAL/NUMERIC columns)
     * @throws SQLException if a database access error occurs
     */
    private void appendCsvValue(StringBuilder sb, ResultSet rs, int colIndex,
                                int sqlType, int scale) throws SQLException {
        // Check for NULL first — applicable to any type
        Object rawValue = rs.getObject(colIndex);
        if (rawValue == null) {
            // NULL → empty field (no quotes, no content)
            return;
        }

        switch (sqlType) {
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR -> {
                String value = rs.getString(colIndex);
                sb.append('"');
                escapeCsvString(sb, value);
                sb.append('"');
            }

            case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT -> {
                long intValue = rs.getLong(colIndex);
                sb.append(intValue);
            }

            case Types.DECIMAL, Types.NUMERIC -> {
                BigDecimal decimalValue = rs.getBigDecimal(colIndex);
                if (decimalValue != null) {
                    // Preserve exact scale (e.g., 16.50 not 16.5)
                    if (scale > 0) {
                        decimalValue = decimalValue.setScale(scale, java.math.RoundingMode.HALF_UP);
                    }
                    sb.append(decimalValue.toPlainString());
                }
            }

            case Types.DATE -> {
                java.sql.Date dateValue = rs.getDate(colIndex);
                if (dateValue != null) {
                    // ISO 8601 format: YYYY-MM-DD
                    sb.append(dateValue.toLocalDate().toString());
                }
            }

            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                java.sql.Timestamp tsValue = rs.getTimestamp(colIndex);
                if (tsValue != null) {
                    // ISO 8601 date portion: YYYY-MM-DD
                    sb.append(tsValue.toLocalDateTime().toLocalDate().toString());
                }
            }

            case Types.BOOLEAN, Types.BIT -> {
                boolean boolValue = rs.getBoolean(colIndex);
                sb.append(boolValue);
            }

            case Types.DOUBLE, Types.FLOAT, Types.REAL -> {
                double dblValue = rs.getDouble(colIndex);
                sb.append(dblValue);
            }

            default -> {
                // Fallback: treat as a quoted string
                String fallback = rs.getString(colIndex);
                if (fallback != null) {
                    sb.append('"');
                    escapeCsvString(sb, fallback);
                    sb.append('"');
                }
            }
        }
    }

    /**
     * Escapes a string value for RFC 4180 CSV output by doubling any embedded
     * double-quote characters.
     *
     * <p>Per RFC 4180, fields containing commas, double-quotes, or line breaks
     * must be enclosed in double-quotes. Within such a field, each embedded
     * double-quote is represented by two double-quote characters.</p>
     *
     * @param sb    the StringBuilder to append the escaped characters to
     * @param value the raw string value to escape
     */
    private void escapeCsvString(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                sb.append('"'); // double the quote character
            }
            sb.append(ch);
        }
    }

    /**
     * Writes the export manifest properties file to the output directory.
     *
     * <p>The manifest records per-table metadata for downstream consumption by
     * {@code DataLoader}:</p>
     * <ul>
     *   <li>Export timestamp and source URL</li>
     *   <li>For each table: row count, target PostgreSQL database, CSV file name</li>
     * </ul>
     *
     * @param tableCounts per-table exported row counts
     * @param timestamp   ISO 8601 timestamp of the export
     * @throws IOException if the manifest file cannot be written
     */
    private void writeExportManifest(Map<String, Integer> tableCounts, String timestamp) throws IOException {
        Path manifestPath = Path.of(outputDir, "export-manifest.properties");

        try (BufferedWriter bw = Files.newBufferedWriter(manifestPath);
             PrintWriter writer = new PrintWriter(bw)) {

            writer.println("# Export Manifest — JPetStore HSQLDB Data Export");
            writer.println("# Generated by com.jpetstore.migration.DataExporter");
            writer.println("# This file is consumed by DataLoader for targeted data loading.");
            writer.println();
            writer.println("export.timestamp=" + timestamp);
            writer.println("export.source=" + hsqldbUrl);
            writer.println("export.tableCount=" + tableCounts.size());
            writer.println();

            for (Map.Entry<String, Integer> entry : tableCounts.entrySet()) {
                String table = entry.getKey();
                int rows = entry.getValue();
                String targetDb = SchemaMapper.getTargetDatabase(table);

                writer.println("# Table: " + table + " -> " + targetDb);
                writer.println("table." + table + ".rows=" + rows);
                writer.println("table." + table + ".targetDb=" + targetDb);
                writer.println("table." + table + ".file=" + table + ".csv");
                writer.println();
            }
        }
    }
}
