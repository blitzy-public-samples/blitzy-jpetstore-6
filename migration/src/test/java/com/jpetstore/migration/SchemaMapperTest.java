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

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SchemaMapper} — the central utility class for HSQLDB-to-PostgreSQL
 * schema mapping across all 3 bounded contexts.
 *
 * <p>Tests cover database mapping, column name conversion, data type mapping,
 * primary key definitions, foreign key relationships, and input validation.</p>
 */
class SchemaMapperTest {

    // =========================================================================
    // Database Mapping Tests
    // =========================================================================

    @Nested
    @DisplayName("Database Mapping")
    class DatabaseMappingTests {

        @Test
        @DisplayName("should map Account bounded context tables to jpetstore_account")
        void shouldMapAccountTablesToAccountDb() {
            assertThat(SchemaMapper.getTargetDatabase("signon")).isEqualTo(SchemaMapper.ACCOUNT_DB);
            assertThat(SchemaMapper.getTargetDatabase("account")).isEqualTo(SchemaMapper.ACCOUNT_DB);
            assertThat(SchemaMapper.getTargetDatabase("profile")).isEqualTo(SchemaMapper.ACCOUNT_DB);
            assertThat(SchemaMapper.getTargetDatabase("bannerdata")).isEqualTo(SchemaMapper.ACCOUNT_DB);
        }

        @Test
        @DisplayName("should map Catalog bounded context tables to jpetstore_catalog")
        void shouldMapCatalogTablesToCatalogDb() {
            assertThat(SchemaMapper.getTargetDatabase("supplier")).isEqualTo(SchemaMapper.CATALOG_DB);
            assertThat(SchemaMapper.getTargetDatabase("category")).isEqualTo(SchemaMapper.CATALOG_DB);
            assertThat(SchemaMapper.getTargetDatabase("product")).isEqualTo(SchemaMapper.CATALOG_DB);
            assertThat(SchemaMapper.getTargetDatabase("item")).isEqualTo(SchemaMapper.CATALOG_DB);
            assertThat(SchemaMapper.getTargetDatabase("inventory")).isEqualTo(SchemaMapper.CATALOG_DB);
        }

        @Test
        @DisplayName("should map Order bounded context tables to jpetstore_order")
        void shouldMapOrderTablesToOrderDb() {
            assertThat(SchemaMapper.getTargetDatabase("orders")).isEqualTo(SchemaMapper.ORDER_DB);
            assertThat(SchemaMapper.getTargetDatabase("orderstatus")).isEqualTo(SchemaMapper.ORDER_DB);
            assertThat(SchemaMapper.getTargetDatabase("lineitem")).isEqualTo(SchemaMapper.ORDER_DB);
        }

        @Test
        @DisplayName("should return all 12 migrated table names (sequence excluded)")
        void shouldReturnAll12Tables() {
            List<String> tables = SchemaMapper.getAllTableNames();
            assertThat(tables).hasSize(12);
            assertThat(tables).doesNotContain("sequence");
        }

        @Test
        @DisplayName("should return 3 database names in cutover order")
        void shouldReturn3DatabaseNames() {
            List<String> dbs = SchemaMapper.getAllDatabaseNames();
            assertThat(dbs).containsExactly(
                    SchemaMapper.ACCOUNT_DB, SchemaMapper.CATALOG_DB, SchemaMapper.ORDER_DB);
        }

        @Test
        @DisplayName("should return FK-safe table order for each database")
        void shouldReturnFkSafeTableOrder() {
            assertThat(SchemaMapper.getTablesForDatabase(SchemaMapper.ACCOUNT_DB))
                    .containsExactly("signon", "account", "profile", "bannerdata");
            assertThat(SchemaMapper.getTablesForDatabase(SchemaMapper.CATALOG_DB))
                    .containsExactly("supplier", "category", "product", "item", "inventory");
            assertThat(SchemaMapper.getTablesForDatabase(SchemaMapper.ORDER_DB))
                    .containsExactly("orders", "orderstatus", "lineitem");
        }

        @Test
        @DisplayName("should reject unknown table name")
        void shouldRejectUnknownTable() {
            assertThatThrownBy(() -> SchemaMapper.getTargetDatabase("nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown table");
        }

        @Test
        @DisplayName("should reject unknown database name")
        void shouldRejectUnknownDatabase() {
            assertThatThrownBy(() -> SchemaMapper.getTablesForDatabase("unknown_db"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown database");
        }
    }

    // =========================================================================
    // Column Name Conversion Tests
    // =========================================================================

    @Nested
    @DisplayName("toSnakeCase — HSQLDB column name conversion")
    class ToSnakeCaseTests {

        @Test
        @DisplayName("should lowercase UPPERCASE column names (HSQLDB JDBC driver behavior)")
        void shouldLowercaseUppercaseNames() {
            assertThat(SchemaMapper.toSnakeCase("BILLTOFIRSTNAME")).isEqualTo("billtofirstname");
            assertThat(SchemaMapper.toSnakeCase("ORDERDATE")).isEqualTo("orderdate");
            assertThat(SchemaMapper.toSnakeCase("USERID")).isEqualTo("userid");
        }

        @Test
        @DisplayName("should insert underscores at camelCase boundaries")
        void shouldInsertUnderscoresAtCamelCaseBoundaries() {
            assertThat(SchemaMapper.toSnakeCase("favouriteCategoryId"))
                    .isEqualTo("favourite_category_id");
        }

        @Test
        @DisplayName("should preserve already-lowercase names")
        void shouldPreserveAlreadyLowercaseNames() {
            assertThat(SchemaMapper.toSnakeCase("userid")).isEqualTo("userid");
            assertThat(SchemaMapper.toSnakeCase("courier")).isEqualTo("courier");
        }

        @Test
        @DisplayName("should reject null column name")
        void shouldRejectNullColumnName() {
            assertThatThrownBy(() -> SchemaMapper.toSnakeCase(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject blank column name")
        void shouldRejectBlankColumnName() {
            assertThatThrownBy(() -> SchemaMapper.toSnakeCase("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Column Mapping Tests
    // =========================================================================

    @Nested
    @DisplayName("Column Mapping")
    class ColumnMappingTests {

        @Test
        @DisplayName("should provide identity mapping for account table (Account DB)")
        void shouldProvideIdentityMappingForAccountTable() {
            Map<String, String> mapping = SchemaMapper.getColumnMapping("account");
            assertThat(mapping).containsEntry("userid", "userid");
            assertThat(mapping).containsEntry("email", "email");
            assertThat(mapping).containsEntry("firstname", "firstname");
        }

        @Test
        @DisplayName("should provide snake_case mapping for orders table (Order DB)")
        void shouldProvideSnakeCaseMappingForOrdersTable() {
            Map<String, String> mapping = SchemaMapper.getColumnMapping("orders");
            assertThat(mapping).containsEntry("orderid", "order_id");
            assertThat(mapping).containsEntry("userid", "userid");
            assertThat(mapping).containsEntry("orderdate", "order_date");
            assertThat(mapping).containsEntry("totalprice", "total_price");
            assertThat(mapping).containsEntry("billtofirstname", "bill_to_first_name");
            assertThat(mapping).containsEntry("creditcard", "credit_card");
        }

        @Test
        @DisplayName("should return correct column count for each table")
        void shouldReturnCorrectColumnCounts() {
            assertThat(SchemaMapper.getColumnCount("signon")).isEqualTo(2);
            assertThat(SchemaMapper.getColumnCount("account")).isEqualTo(12);
            assertThat(SchemaMapper.getColumnCount("orders")).isEqualTo(25);
            assertThat(SchemaMapper.getColumnCount("lineitem")).isEqualTo(5);
            assertThat(SchemaMapper.getColumnCount("inventory")).isEqualTo(2);
        }
    }

    // =========================================================================
    // Data Type Mapping Tests
    // =========================================================================

    @Nested
    @DisplayName("Data Type Mapping")
    class DataTypeMappingTests {

        @Test
        @DisplayName("should map HSQLDB int to PostgreSQL integer")
        void shouldMapIntToInteger() {
            assertThat(SchemaMapper.getHsqldbType("supplier", "suppid")).isEqualTo("int");
            assertThat(SchemaMapper.getPostgresType("supplier", "suppid")).isEqualTo("integer");
        }

        @Test
        @DisplayName("should preserve varchar size from HSQLDB to PostgreSQL")
        void shouldPreserveVarcharSize() {
            assertThat(SchemaMapper.getPostgresType("account", "userid")).isEqualTo("varchar(80)");
            assertThat(SchemaMapper.getPostgresType("signon", "username")).isEqualTo("varchar(25)");
        }

        @Test
        @DisplayName("should map HSQLDB decimal to PostgreSQL numeric with matching precision")
        void shouldMapDecimalToNumeric() {
            assertThat(SchemaMapper.getHsqldbType("item", "listprice")).isEqualTo("decimal(10,2)");
            assertThat(SchemaMapper.getPostgresType("item", "listprice")).isEqualTo("numeric(10,2)");
        }

        @Test
        @DisplayName("should map HSQLDB date to PostgreSQL timestamp with time zone")
        void shouldMapDateToTimestamp() {
            assertThat(SchemaMapper.getPostgresType("orders", "order_date"))
                    .isEqualTo("timestamp with time zone");
        }

        @Test
        @DisplayName("mapJdbcType should map standard JDBC type constants")
        void shouldMapJdbcTypeConstants() {
            assertThat(SchemaMapper.mapJdbcType(Types.INTEGER)).isEqualTo("integer");
            assertThat(SchemaMapper.mapJdbcType(Types.VARCHAR)).isEqualTo("varchar");
            assertThat(SchemaMapper.mapJdbcType(Types.DECIMAL)).isEqualTo("numeric");
            assertThat(SchemaMapper.mapJdbcType(Types.DATE)).isEqualTo("timestamp with time zone");
            assertThat(SchemaMapper.mapJdbcType(Types.TIMESTAMP)).isEqualTo("timestamp with time zone");
        }

        @Test
        @DisplayName("mapJdbcType should reject unsupported JDBC type")
        void shouldRejectUnsupportedJdbcType() {
            assertThatThrownBy(() -> SchemaMapper.mapJdbcType(Types.BLOB))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported JDBC type");
        }
    }

    // =========================================================================
    // Primary Key Tests
    // =========================================================================

    @Nested
    @DisplayName("Primary Key Definitions")
    class PrimaryKeyTests {

        @Test
        @DisplayName("should return single-column PK for standard tables")
        void shouldReturnSingleColumnPk() {
            assertThat(SchemaMapper.getPrimaryKeyColumns("account")).containsExactly("userid");
            assertThat(SchemaMapper.getPrimaryKeyColumns("category")).containsExactly("catid");
            assertThat(SchemaMapper.getPrimaryKeyColumns("orders")).containsExactly("order_id");
        }

        @Test
        @DisplayName("should return composite PK for orderstatus and lineitem")
        void shouldReturnCompositePk() {
            assertThat(SchemaMapper.getPrimaryKeyColumns("orderstatus"))
                    .containsExactly("order_id", "line_num");
            assertThat(SchemaMapper.getPrimaryKeyColumns("lineitem"))
                    .containsExactly("order_id", "line_num");
        }
    }

    // =========================================================================
    // Foreign Key Tests
    // =========================================================================

    @Nested
    @DisplayName("Foreign Key Definitions")
    class ForeignKeyTests {

        @Test
        @DisplayName("should define 3 intra-service FKs within Catalog DB")
        void shouldDefine3IntraServiceFks() {
            List<SchemaMapper.ForeignKey> fks = SchemaMapper.getIntraServiceForeignKeys();
            assertThat(fks).hasSize(3);
            assertThat(fks).allSatisfy(fk -> assertThat(fk.crossService()).isFalse());
        }

        @Test
        @DisplayName("should define 2 cross-service FKs (Order→Catalog, Order→Account)")
        void shouldDefine2CrossServiceFks() {
            List<SchemaMapper.ForeignKey> fks = SchemaMapper.getCrossServiceForeignKeys();
            assertThat(fks).hasSize(2);
            assertThat(fks).allSatisfy(fk -> assertThat(fk.crossService()).isTrue());
        }

        @Test
        @DisplayName("should define 5 total FKs")
        void shouldDefine5TotalFks() {
            assertThat(SchemaMapper.getAllForeignKeys()).hasSize(5);
        }

        @Test
        @DisplayName("intra-service FK: product.category → category.catid")
        void shouldDefineProductCategoryFk() {
            assertThat(SchemaMapper.getIntraServiceForeignKeys())
                    .anyMatch(fk -> "product".equals(fk.sourceTable())
                            && "category".equals(fk.sourceColumn())
                            && "category".equals(fk.targetTable())
                            && "catid".equals(fk.targetColumn()));
        }

        @Test
        @DisplayName("cross-service FK: lineitem.item_id → item.itemid")
        void shouldDefineLineitemItemFk() {
            assertThat(SchemaMapper.getCrossServiceForeignKeys())
                    .anyMatch(fk -> "lineitem".equals(fk.sourceTable())
                            && "item_id".equals(fk.sourceColumn())
                            && "item".equals(fk.targetTable())
                            && "itemid".equals(fk.targetColumn()));
        }
    }

    // =========================================================================
    // Nullability Tests
    // =========================================================================

    @Nested
    @DisplayName("Column Nullability")
    class NullabilityTests {

        @Test
        @DisplayName("PK columns should be NOT NULL")
        void pkColumnsShouldNotBeNullable() {
            assertThat(SchemaMapper.isNullable("account", "userid")).isFalse();
            assertThat(SchemaMapper.isNullable("signon", "username")).isFalse();
            assertThat(SchemaMapper.isNullable("orders", "orderid")).isFalse();
        }

        @Test
        @DisplayName("optional columns should be nullable")
        void optionalColumnsShouldBeNullable() {
            assertThat(SchemaMapper.isNullable("account", "addr2")).isTrue();
            assertThat(SchemaMapper.isNullable("account", "status")).isTrue();
            assertThat(SchemaMapper.isNullable("item", "attr1")).isTrue();
        }
    }

    // =========================================================================
    // Utility Method Tests
    // =========================================================================

    @Nested
    @DisplayName("Utility — getColumnNames")
    class GetColumnNamesTests {

        @Test
        @DisplayName("should return PostgreSQL column names in schema order for signon")
        void shouldReturnPgColumnNamesForSignon() {
            assertThat(SchemaMapper.getColumnNames("signon"))
                    .containsExactly("username", "password");
        }

        @Test
        @DisplayName("should return snake_case names for Order DB tables")
        void shouldReturnSnakeCaseNamesForOrderTables() {
            List<String> cols = SchemaMapper.getColumnNames("lineitem");
            assertThat(cols).containsExactly("order_id", "line_num", "item_id", "quantity", "unit_price");
        }
    }

    // =========================================================================
    // Instantiation Prevention Test
    // =========================================================================

    @Test
    @DisplayName("should prevent instantiation (utility class)")
    void shouldPreventInstantiation() throws Exception {
        var constructor = SchemaMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance)
                .hasCauseInstanceOf(AssertionError.class);
    }
}
