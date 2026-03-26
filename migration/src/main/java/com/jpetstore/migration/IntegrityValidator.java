package com.jpetstore.migration;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jpetstore.migration.SchemaMapper.ForeignKey;

/**
 * Mandatory 7-check data integrity validation gate for the JPetStore
 * monolith-to-microservices data migration. Validates that data migrated from
 * the monolith's HSQLDB database to three separate PostgreSQL databases
 * (jpetstore_account, jpetstore_catalog, jpetstore_order) is complete,
 * consistent, and correctly transformed.
 *
 * <p>The 7 checks are:</p>
 * <ol>
 *   <li>Row Count Match — every table has same row count in HSQLDB and PostgreSQL</li>
 *   <li>Primary Key Uniqueness — no duplicate PKs in any PostgreSQL table</li>
 *   <li>Intra-Service FK Integrity — FKs within a single service database are satisfied</li>
 *   <li>Cross-Service Reference Integrity — FKs spanning database boundaries are satisfied</li>
 *   <li>Sequence Safety — PostgreSQL sequences exceed max migrated IDs by &ge; 1000</li>
 *   <li>Column Mapping Completeness — no columns silently dropped during migration</li>
 *   <li>Data Type Conversion Spot-Checks — booleans, nulls, decimals, dates preserved</li>
 * </ol>
 *
 * <p>This class uses plain JDBC only — no Spring, Hibernate, or MyBatis. It has its own
 * {@code main()} method for standalone execution from the command line or migration scripts.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * java -cp migration.jar:hsqldb.jar:postgresql.jar \
 *   -Dhsqldb.url=jdbc:hsqldb:hsql://localhost/jpetstore \
 *   -Dhsqldb.user=SA -Dhsqldb.password= \
 *   -Daccount.db.url=jdbc:postgresql://localhost:5432/jpetstore_account \
 *   -Daccount.db.user=postgres -Daccount.db.password=postgres \
 *   -Dcatalog.db.url=jdbc:postgresql://localhost:5432/jpetstore_catalog \
 *   -Dcatalog.db.user=postgres -Dcatalog.db.password=postgres \
 *   -Dorder.db.url=jdbc:postgresql://localhost:5432/jpetstore_order \
 *   -Dorder.db.user=postgres -Dorder.db.password=postgres \
 *   com.jpetstore.migration.IntegrityValidator
 * }</pre>
 *
 * <p>Exit code: 0 = all 7 checks passed, 1 = one or more checks failed.</p>
 */
public class IntegrityValidator {

    // ==========================================
    // Public Inner Types
    // ==========================================

    /**
     * Result of a single validation check.
     *
     * @param checkName  human-readable name of the check (e.g., "Row Count Match")
     * @param passed     true if the check completed without any errors
     * @param details    formatted multi-line details string for the report
     * @param errorCount number of individual errors or mismatches found
     */
    public record ValidationResult(
            String checkName,
            boolean passed,
            String details,
            int errorCount
    ) {}

    /**
     * Aggregated report of all 7 validation checks.
     *
     * @param overallPassed  true only if ALL 7 checks passed
     * @param results        ordered list of individual check results
     * @param timestamp      when the validation gate execution started
     * @param executionTimeMs total wall-clock execution time in milliseconds
     */
    public record ValidationReport(
            boolean overallPassed,
            List<ValidationResult> results,
            LocalDateTime timestamp,
            long executionTimeMs
    ) {}

    // ==========================================
    // Connection Configuration Fields
    // ==========================================

    private final String hsqldbUrl;
    private final String hsqldbUser;
    private final String hsqldbPassword;
    private final String accountDbUrl;
    private final String accountDbUser;
    private final String accountDbPassword;
    private final String catalogDbUrl;
    private final String catalogDbUser;
    private final String catalogDbPassword;
    private final String orderDbUrl;
    private final String orderDbUser;
    private final String orderDbPassword;

    /** Maps database names to [url, user, password] tuples for fast connection lookup. */
    private final Map<String, String[]> pgConnectionParams;

    // ==========================================
    // Constructor
    // ==========================================

    /**
     * Creates a new IntegrityValidator with JDBC connection parameters for all 4 databases.
     *
     * @param hsqldbUrl         JDBC URL for the source HSQLDB database
     * @param hsqldbUser        HSQLDB username (typically "SA")
     * @param hsqldbPassword    HSQLDB password (typically empty string)
     * @param accountDbUrl      JDBC URL for the jpetstore_account PostgreSQL database
     * @param accountDbUser     account DB username
     * @param accountDbPassword account DB password
     * @param catalogDbUrl      JDBC URL for the jpetstore_catalog PostgreSQL database
     * @param catalogDbUser     catalog DB username
     * @param catalogDbPassword catalog DB password
     * @param orderDbUrl        JDBC URL for the jpetstore_order PostgreSQL database
     * @param orderDbUser       order DB username
     * @param orderDbPassword   order DB password
     */
    public IntegrityValidator(
            String hsqldbUrl, String hsqldbUser, String hsqldbPassword,
            String accountDbUrl, String accountDbUser, String accountDbPassword,
            String catalogDbUrl, String catalogDbUser, String catalogDbPassword,
            String orderDbUrl, String orderDbUser, String orderDbPassword) {
        this.hsqldbUrl = hsqldbUrl;
        this.hsqldbUser = hsqldbUser;
        this.hsqldbPassword = hsqldbPassword;
        this.accountDbUrl = accountDbUrl;
        this.accountDbUser = accountDbUser;
        this.accountDbPassword = accountDbPassword;
        this.catalogDbUrl = catalogDbUrl;
        this.catalogDbUser = catalogDbUser;
        this.catalogDbPassword = catalogDbPassword;
        this.orderDbUrl = orderDbUrl;
        this.orderDbUser = orderDbUser;
        this.orderDbPassword = orderDbPassword;

        // Build HashMap-based connection parameter lookup for PostgreSQL databases
        this.pgConnectionParams = new HashMap<>();
        this.pgConnectionParams.put(SchemaMapper.ACCOUNT_DB,
                new String[]{accountDbUrl, accountDbUser, accountDbPassword});
        this.pgConnectionParams.put(SchemaMapper.CATALOG_DB,
                new String[]{catalogDbUrl, catalogDbUser, catalogDbPassword});
        this.pgConnectionParams.put(SchemaMapper.ORDER_DB,
                new String[]{orderDbUrl, orderDbUser, orderDbPassword});
    }

    // ==========================================
    // Connection Helper Methods
    // ==========================================

    /**
     * Opens a JDBC connection to the source HSQLDB database.
     *
     * @return an open HSQLDB connection
     * @throws SQLException if connection fails
     */
    private Connection getHsqldbConnection() throws SQLException {
        return DriverManager.getConnection(hsqldbUrl, hsqldbUser, hsqldbPassword);
    }

    /**
     * Opens a JDBC connection to the specified PostgreSQL target database.
     *
     * @param databaseName one of {@link SchemaMapper#ACCOUNT_DB},
     *                     {@link SchemaMapper#CATALOG_DB}, or {@link SchemaMapper#ORDER_DB}
     * @return an open PostgreSQL connection
     * @throws SQLException              if connection fails
     * @throws IllegalArgumentException  if the database name is not recognized
     */
    private Connection getPostgresConnection(String databaseName) throws SQLException {
        String[] params = pgConnectionParams.get(databaseName);
        if (params == null) {
            throw new IllegalArgumentException("Unknown database: " + databaseName
                    + ". Valid: " + SchemaMapper.getAllDatabaseNames());
        }
        return DriverManager.getConnection(params[0], params[1], params[2]);
    }

    // ==========================================
    // Check 1: Row Count Match
    // ==========================================

    /**
     * Validates that every table has the same row count in HSQLDB and the corresponding
     * PostgreSQL database. All 13 tables are checked. The check passes only if ALL
     * row counts match exactly.
     *
     * @return validation result with per-table comparison details
     */
    public ValidationResult validateRowCounts() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        // Collect HSQLDB row counts for all 13 tables
        Map<String, Long> hsqldbCounts = new LinkedHashMap<>();
        try (Connection conn = getHsqldbConnection()) {
            for (String table : SchemaMapper.getAllTableNames()) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    hsqldbCounts.put(table, rs.next() ? rs.getLong(1) : -1L);
                }
            }
        } catch (SQLException e) {
            details.append("  ERROR: Failed to connect to HSQLDB — ").append(e.getMessage()).append("\n");
            return new ValidationResult("Row Count Match", false, details.toString(),
                    SchemaMapper.getAllTableNames().size());
        }

        // Collect PostgreSQL row counts, one connection per target database
        Map<String, Long> pgCounts = new LinkedHashMap<>();
        for (String dbName : SchemaMapper.getAllDatabaseNames()) {
            try (Connection conn = getPostgresConnection(dbName)) {
                for (String table : SchemaMapper.getTablesForDatabase(dbName)) {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        pgCounts.put(table, rs.next() ? rs.getLong(1) : -1L);
                    }
                }
            } catch (SQLException e) {
                details.append("  ERROR: Failed to connect to ").append(dbName)
                        .append(" — ").append(e.getMessage()).append("\n");
                for (String table : SchemaMapper.getTablesForDatabase(dbName)) {
                    pgCounts.put(table, -1L);
                }
            }
        }

        // Compare counts for each table
        for (String table : SchemaMapper.getAllTableNames()) {
            long hsqldb = hsqldbCounts.getOrDefault(table, -1L);
            long pg = pgCounts.getOrDefault(table, -1L);
            boolean match = (hsqldb >= 0 && pg >= 0 && hsqldb == pg);
            String status = match ? "PASS" : "FAIL";
            details.append(String.format("  %s: HSQLDB=%d, PostgreSQL=%d — %s%n",
                    table, hsqldb, pg, status));
            if (!match) {
                errorCount++;
            }
        }

        return new ValidationResult("Row Count Match", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 2: Primary Key Uniqueness
    // ==========================================

    /**
     * Validates that no duplicate primary keys exist in any PostgreSQL table.
     * Uses {@link SchemaMapper#getPrimaryKeyColumns(String)} to obtain PK column names
     * (including composite PKs for orderstatus and lineitem). A table with zero
     * duplicate PK rows passes; any duplicates cause a failure.
     *
     * @return validation result with per-table duplicate count details
     */
    public ValidationResult validatePrimaryKeyUniqueness() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        for (String dbName : SchemaMapper.getAllDatabaseNames()) {
            try (Connection conn = getPostgresConnection(dbName)) {
                for (String table : SchemaMapper.getTablesForDatabase(dbName)) {
                    List<String> pkCols = SchemaMapper.getPrimaryKeyColumns(table);
                    String pkColList = String.join(", ", pkCols);
                    String sql = "SELECT " + pkColList + ", COUNT(*) AS cnt FROM " + table
                            + " GROUP BY " + pkColList + " HAVING COUNT(*) > 1";
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        int duplicates = 0;
                        while (rs.next()) {
                            duplicates++;
                        }
                        String status = (duplicates == 0) ? "PASS" : "FAIL";
                        details.append(String.format("  %s: %d duplicate PKs found — %s%n",
                                table, duplicates, status));
                        if (duplicates > 0) {
                            errorCount++;
                        }
                    }
                }
            } catch (SQLException e) {
                details.append("  ERROR: Failed to connect to ").append(dbName)
                        .append(" — ").append(e.getMessage()).append("\n");
                errorCount += SchemaMapper.getTablesForDatabase(dbName).size();
            }
        }

        return new ValidationResult("Primary Key Uniqueness", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 3: Intra-Service FK Integrity
    // ==========================================

    /**
     * Validates foreign key references within each service's PostgreSQL database.
     * All intra-service FKs are in the Catalog DB:
     * <ul>
     *   <li>product.category → category.catid</li>
     *   <li>item.productid → product.productid</li>
     *   <li>item.supplier → supplier.suppid</li>
     * </ul>
     * Uses LEFT JOIN to detect orphaned rows where a FK value has no matching PK.
     * The check passes only if zero orphaned rows are found across all FK relationships.
     *
     * @return validation result with per-FK orphan count details
     */
    public ValidationResult validateIntraServiceForeignKeys() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        List<ForeignKey> intraFks = SchemaMapper.getIntraServiceForeignKeys();

        if (intraFks.isEmpty()) {
            details.append("  No intra-service foreign keys to validate\n");
            return new ValidationResult("Intra-Service FK Integrity", true,
                    details.toString(), 0);
        }

        // All intra-service FKs reside within the Catalog DB
        try (Connection conn = getPostgresConnection(SchemaMapper.CATALOG_DB)) {
            for (ForeignKey fk : intraFks) {
                String sql = """
                        SELECT COUNT(*) FROM %s a
                        LEFT JOIN %s b ON a.%s = b.%s
                        WHERE b.%s IS NULL AND a.%s IS NOT NULL
                        """.formatted(
                        fk.sourceTable(), fk.targetTable(),
                        fk.sourceColumn(), fk.targetColumn(),
                        fk.targetColumn(), fk.sourceColumn()
                );
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    long orphans = rs.next() ? rs.getLong(1) : 0;
                    String status = (orphans == 0) ? "PASS" : "FAIL";
                    details.append(String.format(
                            "  %s.%s → %s.%s: %d orphaned rows — %s%n",
                            fk.sourceTable(), fk.sourceColumn(),
                            fk.targetTable(), fk.targetColumn(),
                            orphans, status));
                    if (orphans > 0) {
                        errorCount++;
                    }
                }
            }
        } catch (SQLException e) {
            details.append("  ERROR: Failed to connect to Catalog DB — ")
                    .append(e.getMessage()).append("\n");
            errorCount += intraFks.size();
        }

        return new ValidationResult("Intra-Service FK Integrity", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 4: Cross-Service Reference Integrity
    // ==========================================

    /**
     * Validates cross-boundary foreign key references that span separate PostgreSQL databases.
     * These FK constraints were removed as database constraints and must be enforced
     * at the application layer, but the migrated data must still satisfy them:
     * <ul>
     *   <li>lineitem.item_id → item.itemid (Order DB → Catalog DB)</li>
     *   <li>orders.username → account.userid (Order DB → Account DB)</li>
     * </ul>
     * Collects distinct FK values from the source table, then verifies every value
     * exists in the target table (in a different database). Missing references are
     * reported (up to 10 sample values for diagnostics).
     *
     * @return validation result with per-FK missing reference details
     */
    public ValidationResult validateCrossServiceReferences() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        List<ForeignKey> crossFks = SchemaMapper.getCrossServiceForeignKeys();

        for (ForeignKey fk : crossFks) {
            String sourceDb = SchemaMapper.getTargetDatabase(fk.sourceTable());
            String targetDb = SchemaMapper.getTargetDatabase(fk.targetTable());

            // Collect distinct FK values from the source table
            Set<String> sourceValues = new HashSet<>();
            try (Connection conn = getPostgresConnection(sourceDb);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT DISTINCT " + fk.sourceColumn() + " FROM " + fk.sourceTable())) {
                while (rs.next()) {
                    String val = rs.getString(1);
                    if (val != null) {
                        sourceValues.add(val);
                    }
                }
            } catch (SQLException e) {
                details.append(String.format(
                        "  %s.%s → %s.%s [cross-service: %s]: ERROR reading source — %s%n",
                        fk.sourceTable(), fk.sourceColumn(),
                        fk.targetTable(), fk.targetColumn(),
                        fk.crossService(), e.getMessage()));
                errorCount++;
                continue;
            }

            // Collect all PK values from the target table in the other database
            Set<String> targetValues = new HashSet<>();
            try (Connection conn = getPostgresConnection(targetDb);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT " + fk.targetColumn() + " FROM " + fk.targetTable())) {
                while (rs.next()) {
                    String val = rs.getString(1);
                    if (val != null) {
                        targetValues.add(val);
                    }
                }
            } catch (SQLException e) {
                details.append(String.format(
                        "  %s.%s → %s.%s [cross-service: %s]: ERROR reading target — %s%n",
                        fk.sourceTable(), fk.sourceColumn(),
                        fk.targetTable(), fk.targetColumn(),
                        fk.crossService(), e.getMessage()));
                errorCount++;
                continue;
            }

            // Find source values that have no matching target value
            Set<String> missing = new HashSet<>(sourceValues);
            missing.removeAll(targetValues);

            String status = missing.isEmpty() ? "PASS" : "FAIL";
            details.append(String.format(
                    "  %s.%s → %s.%s [cross-service: %s]: %d references checked, %d missing — %s%n",
                    fk.sourceTable(), fk.sourceColumn(),
                    fk.targetTable(), fk.targetColumn(),
                    fk.crossService(), sourceValues.size(), missing.size(), status));

            if (!missing.isEmpty()) {
                errorCount++;
                // Report up to 10 missing values for diagnostic purposes
                int shown = 0;
                for (String val : missing) {
                    if (shown >= 10) {
                        details.append("    ... and ").append(missing.size() - 10)
                                .append(" more missing references\n");
                        break;
                    }
                    details.append("    missing: ").append(val).append("\n");
                    shown++;
                }
            }
        }

        if (crossFks.isEmpty()) {
            details.append("  No cross-service foreign keys to validate\n");
        }

        return new ValidationResult("Cross-Service Reference Integrity", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 5: Sequence Safety
    // ==========================================

    /**
     * Validates that the PostgreSQL {@code order_id_seq} sequence's current value exceeds
     * the maximum migrated order ID by at least 1000. This prevents ID collisions between
     * migrated data and newly generated order IDs.
     *
     * <p>The AAP specifies: "starting value > max migrated id + 1000".</p>
     *
     * @return validation result with sequence value, max ID, and gap details
     */
    public ValidationResult validateSequenceSafety() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        try (Connection conn = getPostgresConnection(SchemaMapper.ORDER_DB)) {
            // Read current sequence value
            long seqValue = -1;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_value FROM order_id_seq")) {
                if (rs.next()) {
                    seqValue = rs.getLong(1);
                }
            } catch (SQLException e) {
                details.append("  ERROR: Could not read order_id_seq — ")
                        .append(e.getMessage()).append("\n");
                details.append("  The sequence may not exist yet. Ensure Liquibase migrations ran.\n");
                return new ValidationResult("Sequence Safety", false,
                        details.toString(), 1);
            }

            // Read maximum migrated order ID (0 if no orders exist)
            long maxOrderId = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COALESCE(MAX(order_id), 0) FROM orders")) {
                if (rs.next()) {
                    maxOrderId = rs.getLong(1);
                }
            }

            // Evaluate safety gap
            long gap = seqValue - maxOrderId;
            boolean seqExceedsMax = seqValue > maxOrderId;
            boolean safeGap = gap >= 1000;

            details.append(String.format("  order_id_seq current value: %d%n", seqValue));
            details.append(String.format("  MAX(order_id) in orders table: %d%n", maxOrderId));
            details.append(String.format("  Gap: %d (minimum required: 1000)%n", gap));

            if (!seqExceedsMax) {
                details.append("  FAIL: Sequence value does not exceed max order ID\n");
                errorCount++;
            } else if (!safeGap) {
                details.append("  FAIL: Sequence exceeds max order ID but gap < 1000\n");
                errorCount++;
            } else {
                details.append("  PASS: Sequence safely ahead of max order ID\n");
            }

        } catch (SQLException e) {
            details.append("  ERROR: Failed to connect to Order DB — ")
                    .append(e.getMessage()).append("\n");
            errorCount++;
        }

        return new ValidationResult("Sequence Safety", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 6: Column Mapping Completeness
    // ==========================================

    /**
     * Validates that no columns were silently dropped during migration. For each of the
     * 13 tables, compares the actual columns in HSQLDB (via {@link ResultSetMetaData})
     * and PostgreSQL (via {@link DatabaseMetaData#getColumns}) against the expected
     * column mapping from {@link SchemaMapper#getColumnMapping(String)}.
     *
     * <p>A table passes if every HSQLDB column in the mapping exists in the actual
     * HSQLDB table, and every PostgreSQL column in the mapping exists in the actual
     * PostgreSQL table.</p>
     *
     * @return validation result with per-table column comparison details
     */
    public ValidationResult validateColumnMappingCompleteness() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        for (String table : SchemaMapper.getAllTableNames()) {
            String dbName = SchemaMapper.getTargetDatabase(table);
            Map<String, String> expectedMapping = SchemaMapper.getColumnMapping(table);

            // Discover actual HSQLDB columns using ResultSetMetaData
            Set<String> hsqldbActualCols = new HashSet<>();
            try (Connection conn = getHsqldbConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT * FROM " + table + " WHERE 1 = 0")) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    hsqldbActualCols.add(meta.getColumnName(i).toLowerCase());
                }
            } catch (SQLException e) {
                details.append(String.format("  %s: ERROR reading HSQLDB columns — %s%n",
                        table, e.getMessage()));
                errorCount++;
                continue;
            }

            // Discover actual PostgreSQL columns using DatabaseMetaData.getColumns()
            Set<String> pgActualCols = new HashSet<>();
            try (Connection conn = getPostgresConnection(dbName)) {
                DatabaseMetaData dbMeta = conn.getMetaData();
                try (ResultSet colRs = dbMeta.getColumns(null, "public", table, null)) {
                    while (colRs.next()) {
                        pgActualCols.add(colRs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            } catch (SQLException e) {
                details.append(String.format("  %s: ERROR reading PostgreSQL columns — %s%n",
                        table, e.getMessage()));
                errorCount++;
                continue;
            }

            // Verify each mapped column exists in the actual databases
            List<String> missingHsqldb = new ArrayList<>();
            List<String> missingPg = new ArrayList<>();

            for (Map.Entry<String, String> entry : expectedMapping.entrySet()) {
                String hsqldbCol = entry.getKey();
                String pgCol = entry.getValue();

                if (!hsqldbActualCols.contains(hsqldbCol)) {
                    missingHsqldb.add(hsqldbCol);
                }
                if (!pgActualCols.contains(pgCol)) {
                    missingPg.add(pgCol);
                }
            }

            boolean tablePass = missingHsqldb.isEmpty() && missingPg.isEmpty();
            String status = tablePass ? "PASS" : "FAIL";
            details.append(String.format(
                    "  %s: HSQLDB=%d cols, PostgreSQL=%d cols, mapped=%d — %s%n",
                    table, hsqldbActualCols.size(), pgActualCols.size(),
                    expectedMapping.size(), status));

            if (!missingHsqldb.isEmpty()) {
                details.append("    Missing in HSQLDB: ").append(missingHsqldb).append("\n");
                errorCount++;
            }
            if (!missingPg.isEmpty()) {
                details.append("    Missing in PostgreSQL: ").append(missingPg).append("\n");
                errorCount++;
            }
        }

        return new ValidationResult("Column Mapping Completeness", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Check 7: Data Type Conversion Spot-Checks
    // ==========================================

    /**
     * Performs targeted spot-checks for common data type conversion pitfalls:
     * <ul>
     *   <li><b>7a: Boolean/Integer</b> — profile.mylistopt, profile.banneropt
     *       (int values 0/1 preserved correctly)</li>
     *   <li><b>7b: Null Sentinels</b> — account.addr2, item.attr2–attr5,
     *       profile.favcategory (NULLs preserved as NULL, not empty string)</li>
     *   <li><b>7c: Decimal Precision</b> — item.listprice, item.unitcost
     *       (DECIMAL(10,2) values match to exact precision)</li>
     *   <li><b>7d: Date/Timestamp</b> — orders.orderdate
     *       (date values correctly converted)</li>
     * </ul>
     *
     * @return validation result with per-subcheck details
     */
    public ValidationResult validateDataTypeConversions() {
        StringBuilder details = new StringBuilder();
        int errorCount = 0;

        // ----- Sub-check 7a: Boolean/Integer preservation -----
        details.append("  [7a] Boolean/Integer check (profile.mylistopt, profile.banneropt):\n");
        try {
            Map<String, int[]> hsqldbProfileInts = new LinkedHashMap<>();
            try (Connection conn = getHsqldbConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT userid, mylistopt, banneropt FROM profile ORDER BY userid")) {
                while (rs.next()) {
                    hsqldbProfileInts.put(
                            rs.getString("userid"),
                            new int[]{rs.getInt("mylistopt"), rs.getInt("banneropt")});
                }
            }

            Map<String, int[]> pgProfileInts = new LinkedHashMap<>();
            try (Connection conn = getPostgresConnection(SchemaMapper.ACCOUNT_DB);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT userid, mylistopt, banneropt FROM profile ORDER BY userid")) {
                while (rs.next()) {
                    pgProfileInts.put(
                            rs.getString("userid"),
                            new int[]{rs.getInt("mylistopt"), rs.getInt("banneropt")});
                }
            }

            boolean intCheck = true;
            for (Map.Entry<String, int[]> entry : hsqldbProfileInts.entrySet()) {
                int[] pgVals = pgProfileInts.get(entry.getKey());
                if (pgVals == null) {
                    details.append(String.format("    %s: missing in PostgreSQL — FAIL%n",
                            entry.getKey()));
                    intCheck = false;
                } else if (entry.getValue()[0] != pgVals[0]
                        || entry.getValue()[1] != pgVals[1]) {
                    details.append(String.format(
                            "    %s: HSQLDB=(%d,%d), PG=(%d,%d) — FAIL%n",
                            entry.getKey(),
                            entry.getValue()[0], entry.getValue()[1],
                            pgVals[0], pgVals[1]));
                    intCheck = false;
                }
            }
            if (intCheck) {
                details.append(String.format("    All %d profile int values match — PASS%n",
                        hsqldbProfileInts.size()));
            } else {
                errorCount++;
            }
        } catch (SQLException e) {
            details.append("    ERROR: ").append(e.getMessage()).append("\n");
            errorCount++;
        }

        // ----- Sub-check 7b: Null sentinel preservation -----
        details.append("  [7b] Null sentinel check:\n");
        try {
            // Define nullable columns to check: {table, hsqldbCol, pgDb, pgCol}
            String[][] nullChecks = {
                    {"account", "addr2", SchemaMapper.ACCOUNT_DB, "addr2"},
                    {"item", "attr2", SchemaMapper.CATALOG_DB, "attr2"},
                    {"item", "attr3", SchemaMapper.CATALOG_DB, "attr3"},
                    {"item", "attr4", SchemaMapper.CATALOG_DB, "attr4"},
                    {"item", "attr5", SchemaMapper.CATALOG_DB, "attr5"},
                    {"profile", "favcategory", SchemaMapper.ACCOUNT_DB, "favcategory"}
            };

            boolean nullCheck = true;
            for (String[] check : nullChecks) {
                String table = check[0];
                String hsqldbCol = check[1];
                String pgDb = check[2];
                String pgCol = check[3];

                long hsqldbNulls = countNulls(table, hsqldbCol, true, null);
                long pgNulls = countNulls(table, pgCol, false, pgDb);

                boolean match = (hsqldbNulls >= 0 && pgNulls >= 0 && hsqldbNulls == pgNulls);
                if (!match) {
                    details.append(String.format(
                            "    %s.%s: HSQLDB NULL count=%d, PG NULL count=%d — FAIL%n",
                            table, hsqldbCol, hsqldbNulls, pgNulls));
                    nullCheck = false;
                }
            }
            if (nullCheck) {
                details.append(String.format("    All %d null sentinel checks match — PASS%n",
                        nullChecks.length));
            } else {
                errorCount++;
            }
        } catch (SQLException e) {
            details.append("    ERROR: ").append(e.getMessage()).append("\n");
            errorCount++;
        }

        // ----- Sub-check 7c: Decimal precision -----
        details.append("  [7c] Decimal precision check (item.listprice, item.unitcost):\n");
        try {
            // Fetch first 5 items from HSQLDB
            Map<String, BigDecimal[]> hsqldbDecimals = new LinkedHashMap<>();
            try (Connection conn = getHsqldbConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT itemid, listprice, unitcost FROM item ORDER BY itemid")) {
                int count = 0;
                while (rs.next() && count < 5) {
                    hsqldbDecimals.put(rs.getString("itemid"),
                            new BigDecimal[]{
                                    rs.getBigDecimal("listprice"),
                                    rs.getBigDecimal("unitcost")});
                    count++;
                }
            }

            // Fetch same items from PostgreSQL using PreparedStatement for targeted lookup
            boolean decimalCheck = true;
            try (Connection conn = getPostgresConnection(SchemaMapper.CATALOG_DB)) {
                for (Map.Entry<String, BigDecimal[]> entry : hsqldbDecimals.entrySet()) {
                    String itemId = entry.getKey();
                    BigDecimal hsqldbPrice = entry.getValue()[0];
                    BigDecimal hsqldbCost = entry.getValue()[1];

                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT listprice, unitcost FROM item WHERE itemid = ?")) {
                        pstmt.setString(1, itemId);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (!rs.next()) {
                                details.append(String.format(
                                        "    %s: missing in PostgreSQL — FAIL%n", itemId));
                                decimalCheck = false;
                                continue;
                            }
                            BigDecimal pgPrice = rs.getBigDecimal("listprice");
                            BigDecimal pgCost = rs.getBigDecimal("unitcost");

                            boolean priceMatch = decimalsEqual(hsqldbPrice, pgPrice);
                            boolean costMatch = decimalsEqual(hsqldbCost, pgCost);

                            if (!priceMatch || !costMatch) {
                                details.append(String.format(
                                        "    %s: listprice HSQLDB=%s PG=%s, "
                                                + "unitcost HSQLDB=%s PG=%s — FAIL%n",
                                        itemId,
                                        decimalToString(hsqldbPrice),
                                        decimalToString(pgPrice),
                                        decimalToString(hsqldbCost),
                                        decimalToString(pgCost)));
                                decimalCheck = false;
                            }
                        }
                    }
                }
            }
            if (decimalCheck) {
                details.append(String.format("    All %d sampled decimal values match — PASS%n",
                        hsqldbDecimals.size()));
            } else {
                errorCount++;
            }
        } catch (SQLException e) {
            details.append("    ERROR: ").append(e.getMessage()).append("\n");
            errorCount++;
        }

        // ----- Sub-check 7d: Date/Timestamp conversion -----
        details.append("  [7d] Date/Timestamp check (orders.orderdate):\n");
        try {
            // HSQLDB uses original column names; PostgreSQL uses snake_case
            Map<Integer, String> hsqldbDates = new LinkedHashMap<>();
            try (Connection conn = getHsqldbConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT orderid, orderdate FROM orders ORDER BY orderid")) {
                while (rs.next()) {
                    hsqldbDates.put(rs.getInt("orderid"), rs.getString("orderdate"));
                }
            }

            if (hsqldbDates.isEmpty()) {
                details.append("    No order records to validate — PASS (skipped)\n");
            } else {
                Map<Integer, String> pgDates = new LinkedHashMap<>();
                try (Connection conn = getPostgresConnection(SchemaMapper.ORDER_DB);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT order_id, order_date FROM orders ORDER BY order_id")) {
                    while (rs.next()) {
                        pgDates.put(rs.getInt("order_id"), rs.getString("order_date"));
                    }
                }

                boolean dateCheck = true;
                for (Map.Entry<Integer, String> entry : hsqldbDates.entrySet()) {
                    String pgDate = pgDates.get(entry.getKey());
                    if (pgDate == null) {
                        details.append(String.format(
                                "    order %d: missing in PostgreSQL — FAIL%n",
                                entry.getKey()));
                        dateCheck = false;
                    } else if (!datesMatch(entry.getValue(), pgDate)) {
                        details.append(String.format(
                                "    order %d: HSQLDB=%s, PG=%s — FAIL%n",
                                entry.getKey(), entry.getValue(), pgDate));
                        dateCheck = false;
                    }
                }
                if (dateCheck) {
                    details.append(String.format(
                            "    All %d order date values match — PASS%n",
                            hsqldbDates.size()));
                } else {
                    errorCount++;
                }
            }
        } catch (SQLException e) {
            details.append("    ERROR: ").append(e.getMessage()).append("\n");
            errorCount++;
        }

        return new ValidationResult("Data Type Conversion Spot-Checks", errorCount == 0,
                details.toString(), errorCount);
    }

    // ==========================================
    // Private Helper Methods for Check 7
    // ==========================================

    /**
     * Counts NULL values in a specified column of a table.
     *
     * @param table      the table name
     * @param column     the column name to check for NULLs
     * @param isHsqldb   true to query HSQLDB, false to query PostgreSQL
     * @param pgDbName   PostgreSQL database name (ignored if isHsqldb is true)
     * @return count of NULL values, or -1 on error
     * @throws SQLException if connection fails
     */
    private long countNulls(String table, String column, boolean isHsqldb,
                            String pgDbName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NULL";
        try (Connection conn = isHsqldb ? getHsqldbConnection()
                : getPostgresConnection(pgDbName);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    /**
     * Compares two BigDecimal values for equality, treating both-null as equal.
     * Uses {@link BigDecimal#compareTo(BigDecimal)} for value comparison
     * (ignoring scale differences, e.g., 16.50 == 16.5).
     *
     * @param a first value (may be null)
     * @param b second value (may be null)
     * @return true if both null or both non-null with equal value
     */
    private boolean decimalsEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    /**
     * Converts a BigDecimal to a display string, handling nulls.
     *
     * @param value the value (may be null)
     * @return plain string representation, or "NULL" if null
     */
    private String decimalToString(BigDecimal value) {
        return value != null ? value.toPlainString() : "NULL";
    }

    /**
     * Compares date strings from HSQLDB and PostgreSQL, tolerating format differences.
     * HSQLDB dates are typically formatted as "YYYY-MM-DD". PostgreSQL timestamps may
     * include time and timezone info (e.g., "2025-01-15 00:00:00+00"). Dates match if
     * the date portion (first 10 characters, YYYY-MM-DD) is identical.
     *
     * @param hsqldbDate date string from HSQLDB (may be null)
     * @param pgDate     date string from PostgreSQL (may be null)
     * @return true if the date portions match, or both are null
     */
    private boolean datesMatch(String hsqldbDate, String pgDate) {
        if (hsqldbDate == null && pgDate == null) {
            return true;
        }
        if (hsqldbDate == null || pgDate == null) {
            return false;
        }
        // Extract date portion (YYYY-MM-DD) from both strings
        String hsqldbPart = hsqldbDate.length() >= 10
                ? hsqldbDate.substring(0, 10) : hsqldbDate;
        String pgPart = pgDate.length() >= 10
                ? pgDate.substring(0, 10) : pgDate;
        return hsqldbPart.equals(pgPart);
    }

    // ==========================================
    // Validation Orchestrator
    // ==========================================

    /**
     * Executes all 7 validation checks in sequence and produces an aggregated report.
     * The overall result passes only if ALL 7 individual checks pass.
     *
     * @return a {@link ValidationReport} containing all check results and timing info
     */
    public ValidationReport validate() {
        LocalDateTime timestamp = LocalDateTime.now();
        long startTime = System.currentTimeMillis();

        List<ValidationResult> results = new ArrayList<>(Arrays.asList(
                validateRowCounts(),
                validatePrimaryKeyUniqueness(),
                validateIntraServiceForeignKeys(),
                validateCrossServiceReferences(),
                validateSequenceSafety(),
                validateColumnMappingCompleteness(),
                validateDataTypeConversions()
        ));

        long executionTimeMs = System.currentTimeMillis() - startTime;
        boolean overallPassed = results.stream().allMatch(ValidationResult::passed);

        return new ValidationReport(overallPassed, results, timestamp, executionTimeMs);
    }

    // ==========================================
    // Report Printer
    // ==========================================

    /**
     * Prints a structured, human-readable validation report to the specified output stream.
     * Format includes a header with timestamp, per-check results with details, and
     * a summary footer with pass/fail counts and execution time.
     *
     * @param report the validation report to print
     * @param out    the output stream (e.g., {@code System.out})
     */
    public void printReport(ValidationReport report, PrintStream out) {
        out.println("========================================");
        out.println("JPetStore Data Migration Validation Report");
        out.printf("Timestamp: %s%n", report.timestamp().toString());
        out.println("========================================");
        out.println();

        int passed = 0;
        int failed = 0;
        for (int i = 0; i < report.results().size(); i++) {
            ValidationResult result = report.results().get(i);
            String tag = result.passed() ? "PASS" : "FAIL";
            out.printf("[%s] Check %d: %s%n", tag, i + 1, result.checkName());
            out.print(result.details());
            out.println();
            if (result.passed()) {
                passed++;
            } else {
                failed++;
            }
        }

        out.println("========================================");
        out.printf("OVERALL RESULT: %s%n", report.overallPassed() ? "PASS" : "FAIL");
        out.printf("Total checks: %d, Passed: %d, Failed: %d%n",
                report.results().size(), passed, failed);
        out.printf("Execution time: %dms%n", report.executionTimeMs());
        out.println("========================================");
    }

    // ==========================================
    // Standalone Entry Point
    // ==========================================

    /**
     * Standalone entry point for running the validation gate from the command line
     * or migration shell scripts. Accepts database connection parameters as system
     * properties with sensible defaults for local development.
     *
     * <p>System properties:</p>
     * <ul>
     *   <li>{@code hsqldb.url} — HSQLDB JDBC URL
     *       (default: {@code jdbc:hsqldb:hsql://localhost/jpetstore})</li>
     *   <li>{@code hsqldb.user} — HSQLDB username (default: {@code SA})</li>
     *   <li>{@code hsqldb.password} — HSQLDB password (default: empty)</li>
     *   <li>{@code account.db.url} — Account DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_account})</li>
     *   <li>{@code account.db.user} — Account DB username (default: {@code postgres})</li>
     *   <li>{@code account.db.password} — Account DB password (default: {@code postgres})</li>
     *   <li>{@code catalog.db.url} — Catalog DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_catalog})</li>
     *   <li>{@code catalog.db.user} — Catalog DB username (default: {@code postgres})</li>
     *   <li>{@code catalog.db.password} — Catalog DB password (default: {@code postgres})</li>
     *   <li>{@code order.db.url} — Order DB JDBC URL
     *       (default: {@code jdbc:postgresql://localhost:5432/jpetstore_order})</li>
     *   <li>{@code order.db.user} — Order DB username (default: {@code postgres})</li>
     *   <li>{@code order.db.password} — Order DB password (default: {@code postgres})</li>
     * </ul>
     *
     * <p>Exit code: 0 if all 7 checks pass, 1 if any check fails.</p>
     *
     * @param args command-line arguments (currently unused; use system properties)
     */
    public static void main(String[] args) {
        String hsqldbUrl = System.getProperty("hsqldb.url",
                "jdbc:hsqldb:hsql://localhost/jpetstore");
        String hsqldbUser = System.getProperty("hsqldb.user", "SA");
        String hsqldbPassword = System.getProperty("hsqldb.password", "");

        String accountDbUrl = System.getProperty("account.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_account");
        String accountDbUser = System.getProperty("account.db.user", "postgres");
        String accountDbPassword = System.getProperty("account.db.password", "postgres");

        String catalogDbUrl = System.getProperty("catalog.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_catalog");
        String catalogDbUser = System.getProperty("catalog.db.user", "postgres");
        String catalogDbPassword = System.getProperty("catalog.db.password", "postgres");

        String orderDbUrl = System.getProperty("order.db.url",
                "jdbc:postgresql://localhost:5432/jpetstore_order");
        String orderDbUser = System.getProperty("order.db.user", "postgres");
        String orderDbPassword = System.getProperty("order.db.password", "postgres");

        IntegrityValidator validator = new IntegrityValidator(
                hsqldbUrl, hsqldbUser, hsqldbPassword,
                accountDbUrl, accountDbUser, accountDbPassword,
                catalogDbUrl, catalogDbUser, catalogDbPassword,
                orderDbUrl, orderDbUser, orderDbPassword
        );

        ValidationReport report = validator.validate();
        validator.printReport(report, System.out);

        System.exit(report.overallPassed() ? 0 : 1);
    }
}
