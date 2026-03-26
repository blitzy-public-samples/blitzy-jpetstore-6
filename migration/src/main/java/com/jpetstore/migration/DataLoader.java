package com.jpetstore.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC-based PostgreSQL data loader for the JPetStore migration pipeline.
 *
 * <p>Loads exported HSQLDB data (CSV files produced by {@link DataExporter}) into three
 * separate PostgreSQL databases: {@code jpetstore_account}, {@code jpetstore_catalog},
 * and {@code jpetstore_order}. Uses {@code INSERT ... ON CONFLICT DO NOTHING} for
 * idempotency — re-running the loader against already-populated databases does not
 * create duplicates.</p>
 *
 * <p>Key guarantees:</p>
 * <ul>
 *   <li>Idempotent loading via {@code ON CONFLICT DO NOTHING} for every table</li>
 *   <li>FK-safe table load order via {@link SchemaMapper#getTablesForDatabase(String)}</li>
 *   <li>Column name conversion from HSQLDB UPPERCASE to PostgreSQL snake_case via
 *       {@link SchemaMapper#toSnakeCase(String)} and {@link SchemaMapper#getColumnMapping(String)}</li>
 *   <li>Exact decimal precision preserved via {@link BigDecimal} for DECIMAL(10,2) columns</li>
 *   <li>HSQLDB DATE converted to PostgreSQL {@code timestamp with time zone} at midnight UTC</li>
 *   <li>NULL values preserved as SQL NULL — not converted to empty strings or zeros</li>
 *   <li>JDBC batch processing with configurable batch size for performance</li>
 *   <li>Per-table transaction boundaries with rollback on failure, continue with next tables</li>
 *   <li>PostgreSQL sequence configuration (order_id_seq) set to MAX + 1000 after data load</li>
 * </ul>
 *
 * <p>Has its own {@code main()} method for standalone execution via:</p>
 * <pre>
 *   java -Daccount.db.url=jdbc:postgresql://localhost:5432/jpetstore_account \
 *        -Daccount.db.user=postgres -Daccount.db.password=postgres \
 *        -Dcatalog.db.url=jdbc:postgresql://localhost:5432/jpetstore_catalog \
 *        -Dcatalog.db.user=postgres -Dcatalog.db.password=postgres \
 *        -Dorder.db.url=jdbc:postgresql://localhost:5432/jpetstore_order \
 *        -Dorder.db.user=postgres -Dorder.db.password=postgres \
 *        -Dexport.dir=./export \
 *        -cp migration.jar com.jpetstore.migration.DataLoader
 * </pre>
 *
 * <p>This class uses plain JDBC only — no Spring, no Hibernate, no MyBatis.</p>
 */
public class DataLoader {

    // ==========================================
    // Constants
    // ==========================================

    /** Number of rows accumulated in each JDBC batch before flushing to the database. */
    private static final int BATCH_SIZE = 500;

    /**
     * Maps PostgreSQL type keywords to their corresponding {@link Types} constants.
     * Used for {@link PreparedStatement#setNull(int, int)} to provide the correct SQL type code.
     */
    private static final Map<String, Integer> PG_TYPE_TO_SQL_TYPE = Map.of(
            "varchar", Types.VARCHAR,
            "integer", Types.INTEGER,
            "numeric", Types.NUMERIC,
            "decimal", Types.DECIMAL,
            "date", Types.DATE,
            "timestamp", Types.TIMESTAMP
    );

    // ==========================================
    // Instance Fields
    // ==========================================

    /** JDBC URL for the Account bounded context PostgreSQL database. */
    private final String accountDbUrl;

    /** JDBC user for the Account bounded context PostgreSQL database. */
    private final String accountDbUser;

    /** JDBC password for the Account bounded context PostgreSQL database. */
    private final String accountDbPassword;

    /** JDBC URL for the Catalog bounded context PostgreSQL database. */
    private final String catalogDbUrl;

    /** JDBC user for the Catalog bounded context PostgreSQL database. */
    private final String catalogDbUser;

    /** JDBC password for the Catalog bounded context PostgreSQL database. */
    private final String catalogDbPassword;

    /** JDBC URL for the Order bounded context PostgreSQL database. */
    private final String orderDbUrl;

    /** JDBC user for the Order bounded context PostgreSQL database. */
    private final String orderDbUser;

    /** JDBC password for the Order bounded context PostgreSQL database. */
    private final String orderDbPassword;

    /** Directory containing CSV files exported by {@link DataExporter}. */
    private final String exportDir;

    // ==========================================
    // Constructor
    // ==========================================

    /**
     * Creates a new DataLoader configured with connection details for three PostgreSQL
     * databases and the directory containing exported CSV files.
     *
     * @param accountDbUrl      JDBC URL for the Account database
     *                          (e.g., {@code "jdbc:postgresql://localhost:5432/jpetstore_account"})
     * @param accountDbUser     database user for Account database
     * @param accountDbPassword database password for Account database
     * @param catalogDbUrl      JDBC URL for the Catalog database
     * @param catalogDbUser     database user for Catalog database
     * @param catalogDbPassword database password for Catalog database
     * @param orderDbUrl        JDBC URL for the Order database
     * @param orderDbUser       database user for Order database
     * @param orderDbPassword   database password for Order database
     * @param exportDir         path to the directory containing CSV files from DataExporter
     * @throws IllegalArgumentException if any parameter is null
     */
    public DataLoader(String accountDbUrl, String accountDbUser, String accountDbPassword,
                      String catalogDbUrl, String catalogDbUser, String catalogDbPassword,
                      String orderDbUrl, String orderDbUser, String orderDbPassword,
                      String exportDir) {
        if (accountDbUrl == null) throw new IllegalArgumentException("accountDbUrl must not be null");
        if (accountDbUser == null) throw new IllegalArgumentException("accountDbUser must not be null");
        if (accountDbPassword == null) throw new IllegalArgumentException("accountDbPassword must not be null");
        if (catalogDbUrl == null) throw new IllegalArgumentException("catalogDbUrl must not be null");
        if (catalogDbUser == null) throw new IllegalArgumentException("catalogDbUser must not be null");
        if (catalogDbPassword == null) throw new IllegalArgumentException("catalogDbPassword must not be null");
        if (orderDbUrl == null) throw new IllegalArgumentException("orderDbUrl must not be null");
        if (orderDbUser == null) throw new IllegalArgumentException("orderDbUser must not be null");
        if (orderDbPassword == null) throw new IllegalArgumentException("orderDbPassword must not be null");
        if (exportDir == null) throw new IllegalArgumentException("exportDir must not be null");

        this.accountDbUrl = accountDbUrl;
        this.accountDbUser = accountDbUser;
        this.accountDbPassword = accountDbPassword;
        this.catalogDbUrl = catalogDbUrl;
        this.catalogDbUser = catalogDbUser;
        this.catalogDbPassword = catalogDbPassword;
        this.orderDbUrl = orderDbUrl;
        this.orderDbUser = orderDbUser;
        this.orderDbPassword = orderDbPassword;
        this.exportDir = exportDir;
    }

    // ==========================================
    // Core Loading Orchestration
    // ==========================================

    /**
     * Loads all 13 tables from the export directory into the three PostgreSQL databases,
     * then configures PostgreSQL sequences.
     *
     * <p>Tables are loaded in FK-safe order within each database using
     * {@link SchemaMapper#getTablesForDatabase(String)}:</p>
     * <ol>
     *   <li><strong>Account DB</strong>: signon → account → profile → bannerdata</li>
     *   <li><strong>Catalog DB</strong>: supplier → category → product → item → inventory</li>
     *   <li><strong>Order DB</strong>: sequence → orders → orderstatus → lineitem</li>
     *   <li>Configure PostgreSQL sequences (order_id_seq)</li>
     * </ol>
     *
     * <p>Each table is loaded within its own transaction. If a table fails, it is rolled
     * back and the remaining tables continue loading. A full summary report is printed
     * at the end.</p>
     */
    public void loadAll() {
        System.out.println("=== JPetStore PostgreSQL Data Loader ===");
        System.out.println("Export directory: " + exportDir);
        System.out.println();

        // Per-table statistics: [rowsAttempted, rowsInserted, rowsSkipped]
        Map<String, int[]> stats = new LinkedHashMap<>();
        List<String> failedTables = new ArrayList<>();
        boolean overallSuccess = true;

        // Map each database constant to its connection details for ordered iteration
        Map<String, String[]> dbConnectionMap = new HashMap<>();
        dbConnectionMap.put(SchemaMapper.ACCOUNT_DB,
                new String[]{accountDbUrl, accountDbUser, accountDbPassword});
        dbConnectionMap.put(SchemaMapper.CATALOG_DB,
                new String[]{catalogDbUrl, catalogDbUser, catalogDbPassword});
        dbConnectionMap.put(SchemaMapper.ORDER_DB,
                new String[]{orderDbUrl, orderDbUser, orderDbPassword});

        // Process databases in the recommended cutover order: Account → Catalog → Order
        List<String> dbOrder = Arrays.asList(SchemaMapper.ACCOUNT_DB, SchemaMapper.CATALOG_DB, SchemaMapper.ORDER_DB);

        for (String dbName : dbOrder) {
            System.out.println("--- Loading into " + dbName + " ---");
            String[] connDetails = dbConnectionMap.get(dbName);
            String url = connDetails[0];
            String user = connDetails[1];
            String password = connDetails[2];

            List<String> tables = SchemaMapper.getTablesForDatabase(dbName);

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                conn.setAutoCommit(false);

                for (String tableName : tables) {
                    String csvPath = java.nio.file.Path.of(exportDir, tableName + ".csv").toString();
                    System.out.println("Loading table " + tableName + " into " + dbName + "...");

                    try {
                        int[] result = loadTable(conn, tableName, csvPath);
                        conn.commit();
                        stats.put(tableName, result);
                        System.out.println("  Loaded " + result[1] + " rows into " + tableName
                                + " (" + result[2] + " skipped as duplicates)");
                    } catch (Exception e) {
                        try {
                            conn.rollback();
                        } catch (SQLException rollbackEx) {
                            System.err.println("  ERROR: Rollback failed for " + tableName
                                    + ": " + rollbackEx.getMessage());
                        }
                        System.err.println("  ERROR: Failed to load table " + tableName
                                + ": " + e.getMessage());
                        failedTables.add(tableName);
                        stats.put(tableName, new int[]{0, 0, 0});
                        overallSuccess = false;
                    }
                }
            } catch (SQLException e) {
                System.err.println("ERROR: Failed to connect to " + dbName + " at " + url
                        + ": " + e.getMessage());
                for (String table : tables) {
                    if (!stats.containsKey(table)) {
                        failedTables.add(table);
                        stats.put(table, new int[]{0, 0, 0});
                    }
                }
                overallSuccess = false;
            }

            System.out.println();
        }

        // Configure PostgreSQL sequences after all data is loaded
        System.out.println("--- Configuring PostgreSQL sequences ---");
        try (Connection orderConn = DriverManager.getConnection(orderDbUrl, orderDbUser, orderDbPassword)) {
            configureSequences(orderConn);
        } catch (SQLException e) {
            System.err.println("ERROR: Failed to configure sequences: " + e.getMessage());
            overallSuccess = false;
        }

        // Print summary report
        printSummary(stats, failedTables, overallSuccess);
    }

    // ==========================================
    // Single Table Loading
    // ==========================================

    /**
     * Loads a single table from a CSV file into the target PostgreSQL database.
     *
     * <p>The CSV file must have been produced by {@link DataExporter}, with a header row
     * containing HSQLDB column names (UPPERCASE) followed by data rows in RFC 4180 format.
     * Column names are converted to PostgreSQL convention via {@link SchemaMapper}.</p>
     *
     * <p>The generated INSERT statement uses {@code ON CONFLICT (<pk_columns>) DO NOTHING}
     * for idempotency. Primary key columns are obtained from
     * {@link SchemaMapper#getPrimaryKeyColumns(String)}.</p>
     *
     * <p>JDBC batch processing is used with a batch size of {@value #BATCH_SIZE}. The caller
     * is responsible for managing the transaction (auto-commit should be disabled).</p>
     *
     * @param conn           an active JDBC connection to the target PostgreSQL database
     *                       (auto-commit should be disabled by the caller)
     * @param tableName      the lowercase table name (e.g., "supplier", "orders")
     * @param exportFilePath file system path to the CSV file for this table
     * @return int array: [rowsAttempted, rowsInserted, rowsSkipped]
     * @throws SQLException if a database access error occurs
     * @throws IOException  if the CSV file cannot be read
     */
    public int[] loadTable(Connection conn, String tableName, String exportFilePath)
            throws SQLException, IOException {

        java.nio.file.Path csvPath = java.nio.file.Path.of(exportFilePath);
        if (!java.nio.file.Files.exists(csvPath)) {
            throw new IOException("CSV file not found: " + exportFilePath);
        }

        // Pre-flight check: verify the target table exists in PostgreSQL and inspect its schema
        try (Statement verifyStmt = conn.createStatement();
             ResultSet verifyRs = verifyStmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1=0")) {
            ResultSetMetaData meta = verifyRs.getMetaData();
            System.out.println("  Target table '" + tableName + "' verified ("
                    + meta.getColumnCount() + " columns in PostgreSQL)");
        }

        // Retrieve column mapping and primary key definitions from SchemaMapper
        Map<String, String> columnMapping = SchemaMapper.getColumnMapping(tableName);
        List<String> pkColumns = SchemaMapper.getPrimaryKeyColumns(tableName);

        int rowsAttempted = 0;
        int rowsInserted = 0;
        int rowsSkipped = 0;

        try (BufferedReader reader = java.nio.file.Files.newBufferedReader(csvPath)) {
            // Read the header row containing HSQLDB UPPERCASE column names
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                System.out.println("  Empty CSV file for table " + tableName);
                return new int[]{0, 0, 0};
            }

            // Parse header columns: HSQLDB UPPERCASE → lowercase via toSnakeCase → PG name via mapping
            List<String> rawHeaders = Arrays.asList(headerLine.split(","));
            List<String> hsqldbNames = new ArrayList<>(rawHeaders.size());
            List<String> pgNames = new ArrayList<>(rawHeaders.size());
            List<String> pgTypes = new ArrayList<>(rawHeaders.size());

            for (String rawHeader : rawHeaders) {
                String trimmed = rawHeader.trim();
                // Convert HSQLDB UPPERCASE column name to lowercase
                String hsqldbName = SchemaMapper.toSnakeCase(trimmed);
                hsqldbNames.add(hsqldbName);

                // Look up the PostgreSQL column name from SchemaMapper's column mapping
                String pgName = columnMapping.get(hsqldbName);
                if (pgName == null) {
                    // Fallback: use the lowercase name directly (should not occur for known tables)
                    pgName = hsqldbName;
                    System.err.println("  WARNING: No column mapping found for '" + hsqldbName
                            + "' in table '" + tableName + "', using as-is");
                }
                pgNames.add(pgName);

                // Look up the PostgreSQL data type for proper PreparedStatement parameter setting
                String pgType = SchemaMapper.getPostgresType(tableName, hsqldbName);
                pgTypes.add(pgType);
            }

            // Build the INSERT ... ON CONFLICT DO NOTHING SQL statement
            String insertSql = buildInsertSql(tableName, pgNames, pkColumns);
            System.out.println("  SQL: " + insertSql);

            // Process data rows using JDBC batch processing
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                String dataLine;
                int batchCount = 0;

                while ((dataLine = reader.readLine()) != null) {
                    if (dataLine.isBlank()) {
                        continue; // Skip empty lines between data rows
                    }

                    // Parse the CSV line into individual field values
                    List<String> values = parseCsvLine(dataLine);
                    rowsAttempted++;

                    // Set PreparedStatement parameters for each column with proper type conversion
                    for (int i = 0; i < pgNames.size(); i++) {
                        String value = (i < values.size()) ? values.get(i) : null;
                        String pgType = pgTypes.get(i);
                        setParameter(pstmt, i + 1, value, pgType);
                    }

                    pstmt.addBatch();
                    batchCount++;

                    // Flush the batch when the batch size threshold is reached
                    if (batchCount % BATCH_SIZE == 0) {
                        int[] batchResults = pstmt.executeBatch();
                        int[] counts = countBatchResults(batchResults);
                        rowsInserted += counts[0];
                        rowsSkipped += counts[1];
                    }
                }

                // Flush any remaining rows in the final partial batch
                if (batchCount % BATCH_SIZE != 0) {
                    int[] batchResults = pstmt.executeBatch();
                    int[] counts = countBatchResults(batchResults);
                    rowsInserted += counts[0];
                    rowsSkipped += counts[1];
                }
            }
        }

        return new int[]{rowsAttempted, rowsInserted, rowsSkipped};
    }

    // ==========================================
    // PostgreSQL Sequence Configuration
    // ==========================================

    /**
     * Configures PostgreSQL sequences after all data has been loaded into the Order database.
     *
     * <p>Sets the {@code order_id_seq} sequence to start at {@code MAX(order_id) + 1000},
     * ensuring a generous buffer of at least 1000 above the maximum migrated order ID.
     * If no orders have been migrated, the sequence starts at 1000 (matching the monolith's
     * seed data where {@code sequence('ordernum', 1000)}).</p>
     *
     * <p>This replaces the monolith's non-thread-safe {@code sequence} table read-then-update
     * pattern with PostgreSQL's native atomic sequence generator.</p>
     *
     * @param orderDbConn an active JDBC connection to the Order PostgreSQL database
     * @throws SQLException if a database access error occurs
     */
    public void configureSequences(Connection orderDbConn) throws SQLException {
        // Query the maximum order_id from the migrated orders table
        int maxOrderId = 0;
        try (Statement stmt = orderDbConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(order_id) FROM orders")) {
            if (rs.next()) {
                maxOrderId = rs.getInt(1);
                if (rs.wasNull()) {
                    maxOrderId = 0; // No orders migrated yet
                }
            }
        }

        // Calculate the sequence starting value: MAX + 1000 buffer (minimum 1000)
        // Per AAP Section 0.7.3: "starting value is set to the maximum migrated order ID
        // plus a generous buffer (1000) to guarantee no collisions with legacy data"
        int sequenceStart = Math.max(maxOrderId + 1000, 1000);

        try (Statement stmt = orderDbConn.createStatement()) {
            String alterSql = "ALTER SEQUENCE order_id_seq RESTART WITH " + sequenceStart;
            stmt.executeUpdate(alterSql);
            System.out.println("Configured order_id_seq to start at " + sequenceStart
                    + " (max existing order_id: " + (maxOrderId == 0 ? "none" : maxOrderId) + ")");
        }
    }

    // ==========================================
    // Standalone Entry Point
    // ==========================================

    /**
     * Standalone entry point for running the PostgreSQL data loader from the command line.
     *
     * <p>Accepts configuration via system properties:</p>
     * <ul>
     *   <li>{@code -Daccount.db.url} — Account DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_account})</li>
     *   <li>{@code -Daccount.db.user} — Account DB user (default: {@code postgres})</li>
     *   <li>{@code -Daccount.db.password} — Account DB password (default: {@code postgres})</li>
     *   <li>{@code -Dcatalog.db.url} — Catalog DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_catalog})</li>
     *   <li>{@code -Dcatalog.db.user} — Catalog DB user (default: {@code postgres})</li>
     *   <li>{@code -Dcatalog.db.password} — Catalog DB password (default: {@code postgres})</li>
     *   <li>{@code -Dorder.db.url} — Order DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_order})</li>
     *   <li>{@code -Dorder.db.user} — Order DB user (default: {@code postgres})</li>
     *   <li>{@code -Dorder.db.password} — Order DB password (default: {@code postgres})</li>
     *   <li>{@code -Dexport.dir} — Directory containing CSV files from DataExporter
     *       (default: {@code ./export})</li>
     * </ul>
     *
     * <p>Exits with code 0 on success, 1 on any error.</p>
     *
     * @param args command-line arguments (not used; configuration via system properties)
     */
    public static void main(String[] args) {
        String accountUrl = System.getProperty("account.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_account");
        String accountUser = System.getProperty("account.db.user", "postgres");
        String accountPassword = System.getProperty("account.db.password", "postgres");

        String catalogUrl = System.getProperty("catalog.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_catalog");
        String catalogUser = System.getProperty("catalog.db.user", "postgres");
        String catalogPassword = System.getProperty("catalog.db.password", "postgres");

        String orderUrl = System.getProperty("order.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_order");
        String orderUser = System.getProperty("order.db.user", "postgres");
        String orderPassword = System.getProperty("order.db.password", "postgres");

        String exportDirectory = System.getProperty("export.dir", "./export");

        System.out.println("=== JPetStore PostgreSQL Data Loader (Standalone) ===");
        System.out.println("Account DB: " + accountUrl);
        System.out.println("Catalog DB: " + catalogUrl);
        System.out.println("Order DB:   " + orderUrl);
        System.out.println("Export dir: " + exportDirectory);
        System.out.println();

        try {
            DataLoader loader = new DataLoader(
                    accountUrl, accountUser, accountPassword,
                    catalogUrl, catalogUser, catalogPassword,
                    orderUrl, orderUser, orderPassword,
                    exportDirectory
            );

            loader.loadAll();

            System.out.println();
            System.out.println("Data loading completed successfully.");
            System.exit(0);
        } catch (Exception e) {
            System.err.println();
            System.err.println("FATAL: Data loading failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ==========================================
    // Private Helper Methods
    // ==========================================

    /**
     * Builds an {@code INSERT ... ON CONFLICT DO NOTHING} SQL statement for the given table.
     *
     * <p>The generated SQL has the form:</p>
     * <pre>
     * INSERT INTO tableName (col1, col2, ...)
     * VALUES (?, ?, ...)
     * ON CONFLICT (pk1, pk2) DO NOTHING
     * </pre>
     *
     * @param tableName the PostgreSQL table name
     * @param pgColumns list of PostgreSQL column names in insertion order
     * @param pkColumns list of primary key column names for the ON CONFLICT clause
     * @return the complete INSERT SQL string with parameter placeholders
     */
    private static String buildInsertSql(String tableName, List<String> pgColumns,
                                         List<String> pkColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (");

        for (int i = 0; i < pgColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(pgColumns.get(i));
        }

        sql.append(") VALUES (");

        for (int i = 0; i < pgColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }

        sql.append(") ON CONFLICT (");

        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(pkColumns.get(i));
        }

        sql.append(") DO NOTHING");

        return sql.toString();
    }

    /**
     * Parses a single CSV data line respecting RFC 4180 quoting conventions.
     *
     * <p>Parsing rules matching the output format of {@link DataExporter}:</p>
     * <ul>
     *   <li>Fields are separated by commas</li>
     *   <li>Quoted fields are enclosed in double quotes; internal quotes are escaped by doubling</li>
     *   <li>An unquoted empty field (nothing between commas) represents SQL NULL → returned as {@code null}</li>
     *   <li>A quoted empty field ({@code ""}) represents an empty string → returned as {@code ""}</li>
     *   <li>Unquoted non-empty fields (numbers, dates) are returned as their string representation</li>
     * </ul>
     *
     * @param line the CSV data line to parse
     * @return list of field values where {@code null} entries represent SQL NULL
     */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return fields;
        }

        int pos = 0;
        int len = line.length();

        while (pos <= len) {
            if (pos == len) {
                // Reached end of line — no more fields to parse
                break;
            }

            if (line.charAt(pos) == '"') {
                // Quoted field: consume characters until the matching closing quote
                pos++; // Skip the opening double-quote
                StringBuilder fieldValue = new StringBuilder();

                while (pos < len) {
                    char ch = line.charAt(pos);
                    if (ch == '"') {
                        // Check for escaped quote (doubled: "")
                        if (pos + 1 < len && line.charAt(pos + 1) == '"') {
                            fieldValue.append('"');
                            pos += 2;
                        } else {
                            // Closing quote found
                            pos++;
                            break;
                        }
                    } else {
                        fieldValue.append(ch);
                        pos++;
                    }
                }

                // Quoted fields are never null — even "" is an empty string (not NULL)
                fields.add(fieldValue.toString());

                // Advance past the comma delimiter after the quoted field
                if (pos < len && line.charAt(pos) == ',') {
                    pos++;
                    // If comma is the final character, there is a trailing empty (null) field
                    if (pos == len) {
                        fields.add(null);
                    }
                }
            } else {
                // Unquoted field: read until the next comma or end of line
                int nextComma = line.indexOf(',', pos);
                if (nextComma < 0) {
                    // This is the last field on the line
                    String value = line.substring(pos);
                    fields.add(value.isEmpty() ? null : value);
                    pos = len;
                } else {
                    // Extract value between current position and the comma
                    String value = line.substring(pos, nextComma);
                    fields.add(value.isEmpty() ? null : value);
                    pos = nextComma + 1;
                    // If comma is the final character, there is a trailing empty (null) field
                    if (pos == len) {
                        fields.add(null);
                    }
                }
            }
        }

        return fields;
    }

    /**
     * Sets a {@link PreparedStatement} parameter with proper type conversion based on
     * the PostgreSQL column type returned by {@link SchemaMapper#getPostgresType(String, String)}.
     *
     * <p>Type conversion rules:</p>
     * <ul>
     *   <li>{@code varchar(N)} → {@link PreparedStatement#setString(int, String)}</li>
     *   <li>{@code integer} → {@link PreparedStatement#setInt(int, int)}</li>
     *   <li>{@code numeric(P,S)} → {@link PreparedStatement#setBigDecimal(int, BigDecimal)}
     *       with exact scale preservation</li>
     *   <li>{@code timestamp with time zone} → {@link PreparedStatement#setTimestamp(int, Timestamp)}
     *       converting ISO 8601 date (YYYY-MM-DD) to timestamp at midnight UTC</li>
     *   <li>{@code null} values → {@link PreparedStatement#setNull(int, int)} with
     *       appropriate JDBC {@link Types} constant</li>
     * </ul>
     *
     * @param pstmt      the PreparedStatement to set the parameter on
     * @param paramIndex the 1-based parameter index
     * @param value      the string value from the CSV file ({@code null} for SQL NULL)
     * @param pgType     the PostgreSQL column type string (e.g., "varchar(80)", "integer", "numeric(10,2)")
     * @throws SQLException if a database access error occurs or type conversion fails
     */
    private static void setParameter(PreparedStatement pstmt, int paramIndex,
                                     String value, String pgType) throws SQLException {
        if (value == null) {
            // Set SQL NULL with the appropriate JDBC type code for the column
            pstmt.setNull(paramIndex, getSqlTypeCode(pgType));
            return;
        }

        if (pgType.startsWith("varchar")) {
            pstmt.setString(paramIndex, value);

        } else if ("integer".equals(pgType)) {
            try {
                pstmt.setInt(paramIndex, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                throw new SQLException("Cannot parse integer value '" + value
                        + "' for parameter index " + paramIndex + ": " + e.getMessage());
            }

        } else if (pgType.startsWith("numeric")) {
            try {
                BigDecimal bd = new BigDecimal(value.trim());
                // Preserve exact scale for DECIMAL(P,S) columns (e.g., 16.50 not 16.5)
                int scale = extractDecimalScale(pgType);
                if (scale > 0) {
                    bd = bd.setScale(scale, RoundingMode.HALF_UP);
                }
                pstmt.setBigDecimal(paramIndex, bd);
            } catch (NumberFormatException | ArithmeticException e) {
                throw new SQLException("Cannot parse numeric value '" + value
                        + "' for parameter index " + paramIndex + ": " + e.getMessage());
            }

        } else if (pgType.startsWith("timestamp")) {
            try {
                // Parse ISO 8601 date string (YYYY-MM-DD) and convert to timestamp at midnight UTC
                // HSQLDB DATE → PostgreSQL timestamp with time zone
                LocalDate date = LocalDate.parse(value.trim());
                Timestamp ts = Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
                pstmt.setTimestamp(paramIndex, ts);
            } catch (Exception e) {
                throw new SQLException("Cannot parse date/timestamp value '" + value
                        + "' for parameter index " + paramIndex + ": " + e.getMessage());
            }

        } else {
            // Fallback for any unrecognized type: treat as varchar
            pstmt.setString(paramIndex, value);
        }
    }

    /**
     * Maps a PostgreSQL type string to the corresponding JDBC {@link Types} constant.
     *
     * <p>Used with {@link PreparedStatement#setNull(int, int)} to provide the correct
     * SQL type code when setting a NULL value for a column.</p>
     *
     * @param pgType the PostgreSQL type string (e.g., "varchar(80)", "integer", "numeric(10,2)",
     *               "timestamp with time zone")
     * @return the corresponding {@link Types} constant
     */
    private static int getSqlTypeCode(String pgType) {
        if (pgType == null) {
            return Types.VARCHAR;
        }
        String lower = pgType.toLowerCase();

        // Match against known PostgreSQL type keywords
        for (Map.Entry<String, Integer> entry : PG_TYPE_TO_SQL_TYPE.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Default to VARCHAR for any unrecognized types
        return Types.VARCHAR;
    }

    /**
     * Extracts the scale (number of decimal places) from a PostgreSQL numeric type declaration.
     *
     * <p>Parses type strings like {@code "numeric(10,2)"} to extract the scale value (2).
     * Returns 0 if no scale is specified (e.g., {@code "numeric"} without precision).</p>
     *
     * @param pgType the PostgreSQL numeric type string (e.g., "numeric(10,2)")
     * @return the decimal scale, or 0 if not specified
     */
    private static int extractDecimalScale(String pgType) {
        int commaIdx = pgType.indexOf(',');
        int closeIdx = pgType.indexOf(')');
        if (commaIdx >= 0 && closeIdx > commaIdx) {
            try {
                return Integer.parseInt(pgType.substring(commaIdx + 1, closeIdx).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Counts inserted and skipped rows from a JDBC batch execution result array.
     *
     * <p>With PostgreSQL's {@code ON CONFLICT DO NOTHING}, the batch result codes indicate:</p>
     * <ul>
     *   <li>Affected rows &gt; 0 → row was successfully inserted</li>
     *   <li>Affected rows = 0 → row was skipped due to primary key conflict</li>
     *   <li>{@link Statement#SUCCESS_NO_INFO} → batch executed but individual count unknown
     *       (counted as inserted optimistically)</li>
     * </ul>
     *
     * @param batchResults the array returned by {@link PreparedStatement#executeBatch()}
     * @return int array: [insertedCount, skippedCount]
     */
    private static int[] countBatchResults(int[] batchResults) {
        int inserted = 0;
        int skipped = 0;
        for (int result : batchResults) {
            if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                inserted++;
            } else if (result == 0) {
                skipped++;
            }
            // Statement.EXECUTE_FAILED (-3) should not occur with ON CONFLICT DO NOTHING
            // since conflicts are handled gracefully by the DO NOTHING clause
        }
        return new int[]{inserted, skipped};
    }

    /**
     * Prints a formatted summary report of the loading operation to stdout.
     *
     * <p>The report includes per-table row counts (attempted, inserted, skipped)
     * and overall totals.</p>
     *
     * @param stats        per-table statistics map: tableName → [attempted, inserted, skipped]
     * @param failedTables list of tables that failed during loading
     * @param success      whether the overall loading operation succeeded
     */
    private static void printSummary(Map<String, int[]> stats, List<String> failedTables,
                                     boolean success) {
        System.out.println();
        System.out.println("=== Load Summary ===");

        int totalAttempted = 0;
        int totalInserted = 0;
        int totalSkipped = 0;

        for (Map.Entry<String, int[]> entry : stats.entrySet()) {
            int[] s = entry.getValue();
            totalAttempted += s[0];
            totalInserted += s[1];
            totalSkipped += s[2];
            System.out.printf("  %-15s: %d attempted, %d inserted, %d skipped%n",
                    entry.getKey(), s[0], s[1], s[2]);
        }

        System.out.println();
        System.out.printf("Total: %d attempted, %d inserted, %d skipped%n",
                totalAttempted, totalInserted, totalSkipped);
        System.out.println("Overall success: " + success);

        if (!failedTables.isEmpty()) {
            System.out.println("Failed tables: " + failedTables);
        }
    }
}
