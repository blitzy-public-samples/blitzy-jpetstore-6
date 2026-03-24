package com.jpetstore.migration;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central utility class providing all HSQLDB-to-PostgreSQL schema mapping information
 * for the JPetStore data migration pipeline.
 *
 * <p>This class maps all 13 HSQLDB tables across three bounded contexts to their target
 * PostgreSQL databases (jpetstore_account, jpetstore_catalog, jpetstore_order). It provides
 * column name conversion (UPPERCASE to snake_case), data type mapping, primary key definitions,
 * and foreign key relationship tracking (both intra-service and cross-service).</p>
 *
 * <p>This is a pure utility class with no JDBC connections or I/O operations. All methods
 * are static and all returned collections are unmodifiable, making it inherently thread-safe.</p>
 *
 * <p>Referenced by {@code DataExporter}, {@code DataLoader}, and {@code IntegrityValidator}.</p>
 */
public final class SchemaMapper {

    /** Prevent instantiation — all methods are static. */
    private SchemaMapper() {
        throw new AssertionError("SchemaMapper is a utility class and cannot be instantiated");
    }

    // ==========================================
    // Public Nested Types
    // ==========================================

    /**
     * Represents a foreign key relationship between two tables.
     *
     * @param sourceTable  the table containing the foreign key column
     * @param sourceColumn the foreign key column name (PostgreSQL snake_case)
     * @param targetTable  the referenced table
     * @param targetColumn the referenced primary key column name (PostgreSQL snake_case)
     * @param crossService true if this FK spans service/database boundaries;
     *                     false if both tables reside in the same PostgreSQL database
     */
    public record ForeignKey(
            String sourceTable,
            String sourceColumn,
            String targetTable,
            String targetColumn,
            boolean crossService
    ) {}

    // ==========================================
    // Internal Column Definition Record
    // ==========================================

    /**
     * Internal metadata record for a single column definition.
     * Serves as the single source of truth for column name, HSQLDB type,
     * PostgreSQL type, and nullability.
     *
     * @param name       column name (lowercase, as defined in HSQLDB schema.sql)
     * @param hsqldbType HSQLDB data type declaration (e.g., "varchar(80)", "int", "decimal(10,2)")
     * @param pgType     corresponding PostgreSQL data type (e.g., "varchar(80)", "integer", "numeric(10,2)")
     * @param nullable   true if the column allows NULL values
     */
    private record ColDef(String name, String hsqldbType, String pgType, boolean nullable) {}

    // ==========================================
    // Database Name Constants
    // ==========================================

    /** Target PostgreSQL database for the Account/User bounded context (signon, account, profile, bannerdata). */
    public static final String ACCOUNT_DB = "jpetstore_account";

    /** Target PostgreSQL database for the Catalog/Inventory bounded context (supplier, category, product, item, inventory). */
    public static final String CATALOG_DB = "jpetstore_catalog";

    /** Target PostgreSQL database for the Order/Cart bounded context (orders, orderstatus, lineitem, sequence). */
    public static final String ORDER_DB = "jpetstore_order";

    // ==========================================
    // Static Data Structures
    // ==========================================

    /** Set of all valid table names for O(1) lookup validation. */
    private static final Set<String> VALID_TABLE_NAMES = Set.of(
            "supplier", "signon", "account", "profile", "bannerdata",
            "orders", "orderstatus", "lineitem",
            "category", "product", "item", "inventory", "sequence"
    );

    /** Maps each table name to its target PostgreSQL database. */
    private static final Map<String, String> TABLE_TO_DB;

    /** Maps each database name to its tables in FK-safe load order. */
    private static final Map<String, List<String>> DB_TO_TABLES;

    /** All 13 table names in export order (account DB tables, then catalog DB, then order DB). */
    private static final List<String> ALL_TABLE_NAMES;

    /** All 3 database names in recommended cutover order. */
    private static final List<String> ALL_DATABASE_NAMES = List.of(ACCOUNT_DB, CATALOG_DB, ORDER_DB);

    /** Single source of truth: maps each table to its ordered column definitions. */
    private static final Map<String, List<ColDef>> TABLE_COLUMNS;

    /** Maps each table to its primary key column(s) in PostgreSQL snake_case. */
    private static final Map<String, List<String>> PRIMARY_KEYS;

    /** Intra-service foreign keys (preserved as DB constraints in PostgreSQL). */
    private static final List<ForeignKey> INTRA_SERVICE_FKS;

    /** Cross-service foreign keys (removed as DB constraints, enforced at application layer). */
    private static final List<ForeignKey> CROSS_SERVICE_FKS;

    /** All foreign keys combined (intra-service + cross-service). */
    private static final List<ForeignKey> ALL_FKS;

    // ==========================================
    // Static Initializer — Single Source of Truth
    // ==========================================

    static {
        // ------------------------------------------------------------------
        // 1. Database-to-Tables mapping (FK-safe load order within each DB)
        //    Parents are listed before children to satisfy FK constraints.
        // ------------------------------------------------------------------
        DB_TO_TABLES = Map.of(
                ACCOUNT_DB, List.of("signon", "account", "profile", "bannerdata"),
                CATALOG_DB, List.of("supplier", "category", "product", "item", "inventory"),
                ORDER_DB, List.of("sequence", "orders", "orderstatus", "lineitem")
        );

        // ------------------------------------------------------------------
        // 2. All table names in export order (account DB → catalog DB → order DB)
        // ------------------------------------------------------------------
        var tableNameBuilder = new java.util.ArrayList<String>();
        for (String db : ALL_DATABASE_NAMES) {
            tableNameBuilder.addAll(DB_TO_TABLES.get(db));
        }
        ALL_TABLE_NAMES = List.copyOf(tableNameBuilder);

        // ------------------------------------------------------------------
        // 3. Table-to-Database reverse mapping (derived from DB_TO_TABLES)
        // ------------------------------------------------------------------
        HashMap<String, String> tableDbMap = new HashMap<>();
        for (String db : ALL_DATABASE_NAMES) {
            for (String table : DB_TO_TABLES.get(db)) {
                tableDbMap.put(table, db);
            }
        }
        TABLE_TO_DB = Map.copyOf(tableDbMap);

        // ------------------------------------------------------------------
        // 4. Column definitions for all 13 tables
        //    ColDef(name, hsqldbType, pgType, nullable)
        //    Column order matches the CREATE TABLE statement in schema.sql.
        //    Type mapping rules:
        //      int          → integer
        //      varchar(N)   → varchar(N)   (size preserved)
        //      decimal(P,S) → numeric(P,S) (precision/scale preserved)
        //      date         → timestamp with time zone
        // ------------------------------------------------------------------
        HashMap<String, List<ColDef>> colMap = new HashMap<>();

        // --- Account DB tables ---

        colMap.put("signon", List.of(
                new ColDef("username", "varchar(25)", "varchar(25)", false),
                new ColDef("password", "varchar(25)", "varchar(25)", false)
        ));

        colMap.put("account", List.of(
                new ColDef("userid", "varchar(80)", "varchar(80)", false),
                new ColDef("email", "varchar(80)", "varchar(80)", false),
                new ColDef("firstname", "varchar(80)", "varchar(80)", false),
                new ColDef("lastname", "varchar(80)", "varchar(80)", false),
                new ColDef("status", "varchar(2)", "varchar(2)", true),
                new ColDef("addr1", "varchar(80)", "varchar(80)", false),
                new ColDef("addr2", "varchar(40)", "varchar(40)", true),
                new ColDef("city", "varchar(80)", "varchar(80)", false),
                new ColDef("state", "varchar(80)", "varchar(80)", false),
                new ColDef("zip", "varchar(20)", "varchar(20)", false),
                new ColDef("country", "varchar(20)", "varchar(20)", false),
                new ColDef("phone", "varchar(80)", "varchar(80)", false)
        ));

        colMap.put("profile", List.of(
                new ColDef("userid", "varchar(80)", "varchar(80)", false),
                new ColDef("langpref", "varchar(80)", "varchar(80)", false),
                new ColDef("favcategory", "varchar(30)", "varchar(30)", true),
                new ColDef("mylistopt", "int", "integer", true),
                new ColDef("banneropt", "int", "integer", true)
        ));

        colMap.put("bannerdata", List.of(
                new ColDef("favcategory", "varchar(80)", "varchar(80)", false),
                new ColDef("bannername", "varchar(255)", "varchar(255)", true)
        ));

        // --- Catalog DB tables ---

        colMap.put("supplier", List.of(
                new ColDef("suppid", "int", "integer", false),
                new ColDef("name", "varchar(80)", "varchar(80)", true),
                new ColDef("status", "varchar(2)", "varchar(2)", false),
                new ColDef("addr1", "varchar(80)", "varchar(80)", true),
                new ColDef("addr2", "varchar(80)", "varchar(80)", true),
                new ColDef("city", "varchar(80)", "varchar(80)", true),
                new ColDef("state", "varchar(80)", "varchar(80)", true),
                new ColDef("zip", "varchar(5)", "varchar(5)", true),
                new ColDef("phone", "varchar(80)", "varchar(80)", true)
        ));

        colMap.put("category", List.of(
                new ColDef("catid", "varchar(10)", "varchar(10)", false),
                new ColDef("name", "varchar(80)", "varchar(80)", true),
                new ColDef("descn", "varchar(255)", "varchar(255)", true)
        ));

        colMap.put("product", List.of(
                new ColDef("productid", "varchar(10)", "varchar(10)", false),
                new ColDef("category", "varchar(10)", "varchar(10)", false),
                new ColDef("name", "varchar(80)", "varchar(80)", true),
                new ColDef("descn", "varchar(255)", "varchar(255)", true)
        ));

        colMap.put("item", List.of(
                new ColDef("itemid", "varchar(10)", "varchar(10)", false),
                new ColDef("productid", "varchar(10)", "varchar(10)", false),
                new ColDef("listprice", "decimal(10,2)", "numeric(10,2)", true),
                new ColDef("unitcost", "decimal(10,2)", "numeric(10,2)", true),
                new ColDef("supplier", "int", "integer", true),
                new ColDef("status", "varchar(2)", "varchar(2)", true),
                new ColDef("attr1", "varchar(80)", "varchar(80)", true),
                new ColDef("attr2", "varchar(80)", "varchar(80)", true),
                new ColDef("attr3", "varchar(80)", "varchar(80)", true),
                new ColDef("attr4", "varchar(80)", "varchar(80)", true),
                new ColDef("attr5", "varchar(80)", "varchar(80)", true)
        ));

        colMap.put("inventory", List.of(
                new ColDef("itemid", "varchar(10)", "varchar(10)", false),
                new ColDef("qty", "int", "integer", false)
        ));

        // --- Order DB tables ---

        colMap.put("sequence", List.of(
                new ColDef("name", "varchar(30)", "varchar(30)", false),
                new ColDef("nextid", "int", "integer", false)
        ));

        colMap.put("orders", List.of(
                new ColDef("orderid", "int", "integer", false),
                new ColDef("userid", "varchar(80)", "varchar(80)", false),
                new ColDef("orderdate", "date", "timestamp with time zone", false),
                new ColDef("shipaddr1", "varchar(80)", "varchar(80)", false),
                new ColDef("shipaddr2", "varchar(80)", "varchar(80)", true),
                new ColDef("shipcity", "varchar(80)", "varchar(80)", false),
                new ColDef("shipstate", "varchar(80)", "varchar(80)", false),
                new ColDef("shipzip", "varchar(20)", "varchar(20)", false),
                new ColDef("shipcountry", "varchar(20)", "varchar(20)", false),
                new ColDef("billaddr1", "varchar(80)", "varchar(80)", false),
                new ColDef("billaddr2", "varchar(80)", "varchar(80)", true),
                new ColDef("billcity", "varchar(80)", "varchar(80)", false),
                new ColDef("billstate", "varchar(80)", "varchar(80)", false),
                new ColDef("billzip", "varchar(20)", "varchar(20)", false),
                new ColDef("billcountry", "varchar(20)", "varchar(20)", false),
                new ColDef("courier", "varchar(80)", "varchar(80)", false),
                new ColDef("totalprice", "decimal(10,2)", "numeric(10,2)", false),
                new ColDef("billtofirstname", "varchar(80)", "varchar(80)", false),
                new ColDef("billtolastname", "varchar(80)", "varchar(80)", false),
                new ColDef("shiptofirstname", "varchar(80)", "varchar(80)", false),
                new ColDef("shiptolastname", "varchar(80)", "varchar(80)", false),
                new ColDef("creditcard", "varchar(80)", "varchar(80)", false),
                new ColDef("exprdate", "varchar(7)", "varchar(7)", false),
                new ColDef("cardtype", "varchar(80)", "varchar(80)", false),
                new ColDef("locale", "varchar(80)", "varchar(80)", false)
        ));

        colMap.put("orderstatus", List.of(
                new ColDef("orderid", "int", "integer", false),
                new ColDef("linenum", "int", "integer", false),
                new ColDef("timestamp", "date", "timestamp with time zone", false),
                new ColDef("status", "varchar(2)", "varchar(2)", false)
        ));

        colMap.put("lineitem", List.of(
                new ColDef("orderid", "int", "integer", false),
                new ColDef("linenum", "int", "integer", false),
                new ColDef("itemid", "varchar(10)", "varchar(10)", false),
                new ColDef("quantity", "int", "integer", false),
                new ColDef("unitprice", "decimal(10,2)", "numeric(10,2)", false)
        ));

        TABLE_COLUMNS = Map.copyOf(colMap);

        // ------------------------------------------------------------------
        // 5. Primary Key definitions (column names in PostgreSQL snake_case)
        //    Composite PKs use Collections.unmodifiableList(Arrays.asList(...))
        //    to produce truly immutable lists from multiple elements.
        // ------------------------------------------------------------------
        HashMap<String, List<String>> pkMap = new HashMap<>();
        pkMap.put("supplier", List.of("suppid"));
        pkMap.put("signon", List.of("username"));
        pkMap.put("account", List.of("userid"));
        pkMap.put("profile", List.of("userid"));
        pkMap.put("bannerdata", List.of("favcategory"));
        pkMap.put("orders", List.of("orderid"));
        pkMap.put("orderstatus", Collections.unmodifiableList(Arrays.asList("orderid", "linenum")));
        pkMap.put("lineitem", Collections.unmodifiableList(Arrays.asList("orderid", "linenum")));
        pkMap.put("category", List.of("catid"));
        pkMap.put("product", List.of("productid"));
        pkMap.put("item", List.of("itemid"));
        pkMap.put("inventory", List.of("itemid"));
        pkMap.put("sequence", List.of("name"));
        PRIMARY_KEYS = Map.copyOf(pkMap);

        // ------------------------------------------------------------------
        // 6. Foreign Key definitions
        // ------------------------------------------------------------------

        // Intra-service FKs: all within Catalog DB, preserved as database-level constraints.
        INTRA_SERVICE_FKS = List.of(
                new ForeignKey("product", "category", "category", "catid", false),
                new ForeignKey("item", "productid", "product", "productid", false),
                new ForeignKey("item", "supplier", "supplier", "suppid", false)
        );

        // Cross-service FKs: span database boundaries (Order DB -> Catalog DB, Order DB -> Account DB).
        // These are removed as database constraints and enforced at the application layer only.
        CROSS_SERVICE_FKS = List.of(
                new ForeignKey("lineitem", "itemid", "item", "itemid", true),
                new ForeignKey("orders", "userid", "account", "userid", true)
        );

        // Combined list of all FKs (5 total: 3 intra-service + 2 cross-service)
        var allFkBuilder = new java.util.ArrayList<ForeignKey>();
        allFkBuilder.addAll(INTRA_SERVICE_FKS);
        allFkBuilder.addAll(CROSS_SERVICE_FKS);
        ALL_FKS = Collections.unmodifiableList(allFkBuilder);
    }

    // ==========================================
    // Public API -- Database Mapping Methods
    // ==========================================

    /**
     * Returns the target PostgreSQL database name for the given HSQLDB table.
     *
     * @param tableName the lowercase HSQLDB table name (e.g., "account", "orders", "category")
     * @return the target database name (e.g., "jpetstore_account")
     * @throws IllegalArgumentException if the table name is not recognized
     */
    public static String getTargetDatabase(String tableName) {
        validateTableName(tableName);
        return TABLE_TO_DB.get(tableName);
    }

    /**
     * Returns the list of tables belonging to the given database, in FK-safe load order.
     * Parent tables are listed before child tables to satisfy foreign key constraints
     * during data loading.
     *
     * @param databaseName the target database name (use ACCOUNT_DB, CATALOG_DB, or ORDER_DB constants)
     * @return unmodifiable list of table names in load order
     * @throws IllegalArgumentException if the database name is not recognized
     */
    public static List<String> getTablesForDatabase(String databaseName) {
        List<String> tables = DB_TO_TABLES.get(databaseName);
        if (tables == null) {
            throw new IllegalArgumentException("Unknown database: " + databaseName
                    + ". Valid databases: " + ALL_DATABASE_NAMES);
        }
        return tables;
    }

    /**
     * Returns all 13 table names in export order (account DB tables first,
     * then catalog DB tables, then order DB tables).
     *
     * @return unmodifiable list of all table names
     */
    public static List<String> getAllTableNames() {
        return ALL_TABLE_NAMES;
    }

    /**
     * Returns all 3 target PostgreSQL database names in recommended cutover order.
     *
     * @return unmodifiable list: [jpetstore_account, jpetstore_catalog, jpetstore_order]
     */
    public static List<String> getAllDatabaseNames() {
        return ALL_DATABASE_NAMES;
    }

    // ==========================================
    // Public API -- Column Name Conversion
    // ==========================================

    /**
     * Converts an HSQLDB column name to PostgreSQL snake_case.
     *
     * <p>The HSQLDB JDBC driver may return column names in UPPERCASE regardless of
     * how they were defined in the DDL. This method normalizes such names to lowercase
     * PostgreSQL convention. It also handles mixed-case (camelCase) input by inserting
     * underscores at lowercase-to-uppercase transitions.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "BILLTOFIRSTNAME"} to {@code "billtofirstname"}</li>
     *   <li>{@code "userid"} to {@code "userid"}</li>
     *   <li>{@code "favouriteCategoryId"} to {@code "favourite_category_id"}</li>
     *   <li>{@code "ORDERDATE"} to {@code "orderdate"}</li>
     * </ul>
     *
     * @param hsqldbColumnName the column name as returned by HSQLDB (may be UPPERCASE or mixed-case)
     * @return the lowercase PostgreSQL column name
     * @throws IllegalArgumentException if the input is null or blank
     */
    public static String toSnakeCase(String hsqldbColumnName) {
        if (hsqldbColumnName == null || hsqldbColumnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be null or blank");
        }

        // Build the snake_case representation by inserting underscores at
        // lowercase-to-uppercase transitions, then lowering everything.
        StringBuilder result = new StringBuilder(hsqldbColumnName.length() + 4);
        char[] chars = hsqldbColumnName.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char current = chars[i];

            // Insert underscore before an uppercase char that follows a lowercase char
            if (i > 0 && Character.isUpperCase(current) && Character.isLowerCase(chars[i - 1])) {
                result.append('_');
            }

            result.append(Character.toLowerCase(current));
        }

        return result.toString();
    }

    // ==========================================
    // Public API -- Column Mapping Methods
    // ==========================================

    /**
     * Returns the HSQLDB-to-PostgreSQL column name mapping for the given table.
     *
     * <p>The returned map preserves column definition order (insertion order) from the
     * original schema.sql. Keys are the HSQLDB column names (lowercase) and values
     * are the corresponding PostgreSQL column names (also lowercase -- the mapping is
     * identity since the actual schema uses lowercase names throughout).</p>
     *
     * @param tableName the lowercase table name
     * @return unmodifiable ordered map of HSQLDB to PostgreSQL column names
     * @throws IllegalArgumentException if the table name is not recognized
     */
    public static Map<String, String> getColumnMapping(String tableName) {
        validateTableName(tableName);
        List<ColDef> cols = TABLE_COLUMNS.get(tableName);
        LinkedHashMap<String, String> mapping = new LinkedHashMap<>(cols.size());
        for (ColDef col : cols) {
            mapping.put(col.name(), col.name());
        }
        return Collections.unmodifiableMap(mapping);
    }

    /**
     * Returns the HSQLDB data type declaration for a specific column.
     *
     * @param tableName  the lowercase table name
     * @param columnName the lowercase column name
     * @return the HSQLDB type string (e.g., "varchar(80)", "int", "decimal(10,2)", "date")
     * @throws IllegalArgumentException if the table or column name is not recognized
     */
    public static String getHsqldbType(String tableName, String columnName) {
        ColDef col = findColDef(tableName, columnName);
        return col.hsqldbType();
    }

    /**
     * Returns the PostgreSQL data type for a specific column.
     *
     * @param tableName  the lowercase table name
     * @param columnName the lowercase column name
     * @return the PostgreSQL type (e.g., "varchar(80)", "integer", "numeric(10,2)",
     *         "timestamp with time zone")
     * @throws IllegalArgumentException if the table or column name is not recognized
     */
    public static String getPostgresType(String tableName, String columnName) {
        ColDef col = findColDef(tableName, columnName);
        return col.pgType();
    }

    /**
     * Maps a JDBC SQL type constant to the corresponding PostgreSQL type string.
     *
     * <p>This method is used when dynamically discovering column types from JDBC
     * {@link java.sql.ResultSetMetaData} during data export. Only the constants
     * class is used -- no actual JDBC connections are opened.</p>
     *
     * @param sqlType a constant from {@link java.sql.Types}
     * @return the PostgreSQL type string
     * @throws IllegalArgumentException if the JDBC type is not supported by this mapper
     */
    public static String mapJdbcType(int sqlType) {
        return switch (sqlType) {
            case Types.INTEGER -> "integer";
            case Types.VARCHAR, Types.CHAR -> "varchar";
            case Types.DECIMAL, Types.NUMERIC -> "numeric";
            case Types.DATE, Types.TIMESTAMP -> "timestamp with time zone";
            default -> throw new IllegalArgumentException(
                    "Unsupported JDBC type code: " + sqlType
                            + ". Supported: INTEGER, VARCHAR, CHAR, DECIMAL, NUMERIC, DATE, TIMESTAMP");
        };
    }

    // ==========================================
    // Public API -- Primary Key Methods
    // ==========================================

    /**
     * Returns the primary key column name(s) for the given table.
     *
     * <p>For tables with composite primary keys (orderstatus, lineitem), the returned
     * list contains multiple column names in the order defined by the schema constraint.</p>
     *
     * @param tableName the lowercase table name
     * @return unmodifiable list of PK column names (PostgreSQL snake_case)
     * @throws IllegalArgumentException if the table name is not recognized
     */
    public static List<String> getPrimaryKeyColumns(String tableName) {
        validateTableName(tableName);
        return PRIMARY_KEYS.get(tableName);
    }

    // ==========================================
    // Public API -- Foreign Key Methods
    // ==========================================

    /**
     * Returns all intra-service foreign keys -- FK relationships where both tables
     * reside in the same PostgreSQL database. These are preserved as database-level
     * constraints in the target schema.
     *
     * <p>All 3 intra-service FKs are within the Catalog DB:</p>
     * <ul>
     *   <li>product.category to category.catid</li>
     *   <li>item.productid to product.productid</li>
     *   <li>item.supplier to supplier.suppid</li>
     * </ul>
     *
     * @return unmodifiable list of intra-service ForeignKey records
     */
    public static List<ForeignKey> getIntraServiceForeignKeys() {
        return INTRA_SERVICE_FKS;
    }

    /**
     * Returns all cross-service foreign keys -- FK relationships where the source
     * and target tables reside in different PostgreSQL databases. These are removed
     * as database constraints and enforced at the application layer only.
     *
     * <p>Cross-service FKs:</p>
     * <ul>
     *   <li>lineitem.itemid to item.itemid (Order DB to Catalog DB)</li>
     *   <li>orders.userid to account.userid (Order DB to Account DB)</li>
     * </ul>
     *
     * @return unmodifiable list of cross-service ForeignKey records
     */
    public static List<ForeignKey> getCrossServiceForeignKeys() {
        return CROSS_SERVICE_FKS;
    }

    /**
     * Returns all foreign keys (both intra-service and cross-service combined).
     *
     * @return unmodifiable list of all 5 ForeignKey records
     */
    public static List<ForeignKey> getAllForeignKeys() {
        return ALL_FKS;
    }

    // ==========================================
    // Public API -- Utility Methods
    // ==========================================

    /**
     * Returns all column names for the given table in schema definition order.
     * Names are in PostgreSQL snake_case (identical to the lowercase HSQLDB names).
     *
     * @param tableName the lowercase table name
     * @return unmodifiable list of column names in schema order
     * @throws IllegalArgumentException if the table name is not recognized
     */
    public static List<String> getColumnNames(String tableName) {
        validateTableName(tableName);
        List<ColDef> cols = TABLE_COLUMNS.get(tableName);
        var names = new java.util.ArrayList<String>(cols.size());
        for (ColDef col : cols) {
            names.add(col.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Returns the number of columns in the given table.
     *
     * @param tableName the lowercase table name
     * @return the column count
     * @throws IllegalArgumentException if the table name is not recognized
     */
    public static int getColumnCount(String tableName) {
        validateTableName(tableName);
        return TABLE_COLUMNS.get(tableName).size();
    }

    /**
     * Returns whether the specified column allows NULL values.
     *
     * <p>Nullability is determined from the NOT NULL constraints defined in the
     * HSQLDB schema.sql. Columns without an explicit NOT NULL constraint (or with
     * an explicit NULL keyword) are considered nullable.</p>
     *
     * @param tableName  the lowercase table name
     * @param columnName the lowercase column name
     * @return true if the column allows NULL values; false if NOT NULL is enforced
     * @throws IllegalArgumentException if the table or column name is not recognized
     */
    public static boolean isNullable(String tableName, String columnName) {
        ColDef col = findColDef(tableName, columnName);
        return col.nullable();
    }

    // ==========================================
    // Private Helper Methods
    // ==========================================

    /**
     * Validates that the given table name is one of the 13 known HSQLDB tables.
     *
     * @param tableName the table name to validate
     * @throws IllegalArgumentException if the table name is null or not recognized
     */
    private static void validateTableName(String tableName) {
        if (tableName == null || !VALID_TABLE_NAMES.contains(tableName)) {
            throw new IllegalArgumentException("Unknown table: " + tableName
                    + ". Valid tables: " + ALL_TABLE_NAMES);
        }
    }

    /**
     * Finds the ColDef record for a specific column in a specific table.
     *
     * @param tableName  the lowercase table name
     * @param columnName the lowercase column name
     * @return the matching ColDef record
     * @throws IllegalArgumentException if the table or column name is not recognized
     */
    private static ColDef findColDef(String tableName, String columnName) {
        validateTableName(tableName);
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be null or blank");
        }
        List<ColDef> cols = TABLE_COLUMNS.get(tableName);
        for (ColDef col : cols) {
            if (col.name().equals(columnName)) {
                return col;
            }
        }
        throw new IllegalArgumentException("Unknown column '" + columnName
                + "' in table '" + tableName + "'. Valid columns: "
                + cols.stream().map(ColDef::name).toList());
    }
}
