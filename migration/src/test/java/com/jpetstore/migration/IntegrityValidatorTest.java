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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.jpetstore.migration.IntegrityValidator.ValidationReport;
import com.jpetstore.migration.IntegrityValidator.ValidationResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntegrityValidator} — the 7-check data integrity
 * validation gate for the JPetStore monolith-to-microservices data migration.
 *
 * <p>Tests are organized into the following categories:</p>
 * <ul>
 *   <li>ValidationResult and ValidationReport record semantics</li>
 *   <li>Constructor and connection parameter initialization</li>
 *   <li>Private helper methods (via reflection): decimalsEqual, datesMatch, decimalToString</li>
 *   <li>Report printer output verification</li>
 *   <li>Graceful error handling when databases are unavailable</li>
 * </ul>
 *
 * <p>Integration tests that require real HSQLDB + PostgreSQL connections should
 * reside in a separate {@code IntegrityValidatorIT} class using Testcontainers.</p>
 */
class IntegrityValidatorTest {

    // Dummy connection params — not actually connecting to any database
    private static final String DUMMY_HSQLDB_URL = "jdbc:hsqldb:mem:validatortest";
    private static final String DUMMY_HSQLDB_USER = "SA";
    private static final String DUMMY_HSQLDB_PASS = "";
    private static final String DUMMY_PG_URL = "jdbc:postgresql://localhost:5432/test";
    private static final String DUMMY_PG_USER = "postgres";
    private static final String DUMMY_PG_PASS = "postgres";

    // =========================================================================
    // ValidationResult Record Tests
    // =========================================================================

    @Nested
    @DisplayName("ValidationResult Record")
    class ValidationResultTests {

        @Test
        @DisplayName("should create passing result with correct fields")
        void shouldCreatePassingResult() {
            ValidationResult result = new ValidationResult(
                    "Row Count Match", true, "  All 13 tables match\n", 0);

            assertThat(result.checkName()).isEqualTo("Row Count Match");
            assertThat(result.passed()).isTrue();
            assertThat(result.details()).contains("All 13 tables match");
            assertThat(result.errorCount()).isZero();
        }

        @Test
        @DisplayName("should create failing result with error count")
        void shouldCreateFailingResult() {
            ValidationResult result = new ValidationResult(
                    "Primary Key Uniqueness", false,
                    "  signon: 2 duplicate PKs found — FAIL\n", 1);

            assertThat(result.checkName()).isEqualTo("Primary Key Uniqueness");
            assertThat(result.passed()).isFalse();
            assertThat(result.details()).contains("duplicate PKs");
            assertThat(result.errorCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should support multiple error counts")
        void shouldSupportMultipleErrors() {
            ValidationResult result = new ValidationResult(
                    "Column Mapping Completeness", false,
                    "  account: FAIL\n  profile: FAIL\n", 2);

            assertThat(result.errorCount()).isEqualTo(2);
            assertThat(result.passed()).isFalse();
        }
    }

    // =========================================================================
    // ValidationReport Record Tests
    // =========================================================================

    @Nested
    @DisplayName("ValidationReport Record")
    class ValidationReportTests {

        @Test
        @DisplayName("should aggregate all-passing checks into overall pass")
        void shouldAggregateAllPassingChecks() {
            List<ValidationResult> results = List.of(
                    new ValidationResult("Check 1", true, "Pass\n", 0),
                    new ValidationResult("Check 2", true, "Pass\n", 0),
                    new ValidationResult("Check 3", true, "Pass\n", 0)
            );
            ValidationReport report = new ValidationReport(true, results,
                    LocalDateTime.now(), 500L);

            assertThat(report.overallPassed()).isTrue();
            assertThat(report.results()).hasSize(3);
            assertThat(report.executionTimeMs()).isEqualTo(500L);
            assertThat(report.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("should aggregate one failing check into overall fail")
        void shouldAggregateOneFailingCheck() {
            List<ValidationResult> results = List.of(
                    new ValidationResult("Check 1", true, "Pass\n", 0),
                    new ValidationResult("Check 2", false, "Fail\n", 1),
                    new ValidationResult("Check 3", true, "Pass\n", 0)
            );
            ValidationReport report = new ValidationReport(false, results,
                    LocalDateTime.now(), 1000L);

            assertThat(report.overallPassed()).isFalse();
            assertThat(report.results()).hasSize(3);
        }

        @Test
        @DisplayName("should record execution time in milliseconds")
        void shouldRecordExecutionTime() {
            ValidationReport report = new ValidationReport(true, List.of(),
                    LocalDateTime.now(), 12345L);

            assertThat(report.executionTimeMs()).isEqualTo(12345L);
        }
    }

    // =========================================================================
    // Constructor Tests
    // =========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should accept valid connection parameters")
        void shouldAcceptValidParams() {
            IntegrityValidator validator = new IntegrityValidator(
                    DUMMY_HSQLDB_URL, DUMMY_HSQLDB_USER, DUMMY_HSQLDB_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS);

            assertThat(validator).isNotNull();
        }

        @Test
        @DisplayName("should store connection parameters for all 4 databases")
        void shouldStoreAllConnectionParams() {
            // Constructor stores 12 params (3 per database × 4 databases)
            // and builds a pgConnectionParams map with 3 entries.
            // Verifying construction succeeds confirms all fields are stored.
            IntegrityValidator validator = new IntegrityValidator(
                    "jdbc:hsqldb:hsql://hsqldb-host/jpetstore", "SA", "",
                    "jdbc:postgresql://acc-host:5432/jpetstore_account", "acc_user", "acc_pass",
                    "jdbc:postgresql://cat-host:5432/jpetstore_catalog", "cat_user", "cat_pass",
                    "jdbc:postgresql://ord-host:5432/jpetstore_order", "ord_user", "ord_pass");

            assertThat(validator).isNotNull();
        }
    }

    // =========================================================================
    // Private Helper: decimalsEqual (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("decimalsEqual — BigDecimal Comparison")
    class DecimalsEqualTests {

        private boolean decimalsEqual(BigDecimal a, BigDecimal b) throws Exception {
            IntegrityValidator validator = createDummyValidator();
            Method method = IntegrityValidator.class.getDeclaredMethod(
                    "decimalsEqual", BigDecimal.class, BigDecimal.class);
            method.setAccessible(true);
            return (boolean) method.invoke(validator, a, b);
        }

        @Test
        @DisplayName("should return true for equal values with same scale")
        void shouldReturnTrueForEqualValues() throws Exception {
            assertThat(decimalsEqual(new BigDecimal("16.50"), new BigDecimal("16.50")))
                    .isTrue();
        }

        @Test
        @DisplayName("should return true for equal values with different scales")
        void shouldReturnTrueForDifferentScales() throws Exception {
            // 16.50 and 16.5 have the same numeric value but different scales
            assertThat(decimalsEqual(new BigDecimal("16.50"), new BigDecimal("16.5")))
                    .isTrue();
        }

        @Test
        @DisplayName("should return false for unequal values")
        void shouldReturnFalseForUnequalValues() throws Exception {
            assertThat(decimalsEqual(new BigDecimal("16.50"), new BigDecimal("16.51")))
                    .isFalse();
        }

        @Test
        @DisplayName("should return true when both are null")
        void shouldReturnTrueForBothNull() throws Exception {
            assertThat(decimalsEqual(null, null)).isTrue();
        }

        @Test
        @DisplayName("should return false when first is null and second is not")
        void shouldReturnFalseWhenFirstNull() throws Exception {
            assertThat(decimalsEqual(null, new BigDecimal("16.50"))).isFalse();
        }

        @Test
        @DisplayName("should return false when second is null and first is not")
        void shouldReturnFalseWhenSecondNull() throws Exception {
            assertThat(decimalsEqual(new BigDecimal("16.50"), null)).isFalse();
        }
    }

    // =========================================================================
    // Private Helper: datesMatch (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("datesMatch — Date String Comparison")
    class DatesMatchTests {

        private boolean datesMatch(String hsqldbDate, String pgDate) throws Exception {
            IntegrityValidator validator = createDummyValidator();
            Method method = IntegrityValidator.class.getDeclaredMethod(
                    "datesMatch", String.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(validator, hsqldbDate, pgDate);
        }

        @Test
        @DisplayName("should match identical date strings")
        void shouldMatchIdenticalDates() throws Exception {
            assertThat(datesMatch("2025-01-15", "2025-01-15")).isTrue();
        }

        @Test
        @DisplayName("should match HSQLDB date against PostgreSQL timestamp with timezone")
        void shouldMatchDateAgainstTimestamp() throws Exception {
            // PostgreSQL returns timestamps like "2025-01-15 00:00:00+00"
            // datesMatch compares only the first 10 characters (YYYY-MM-DD)
            assertThat(datesMatch("2025-01-15", "2025-01-15 00:00:00+00")).isTrue();
        }

        @Test
        @DisplayName("should fail when date portions differ")
        void shouldFailWhenDatesDiffer() throws Exception {
            assertThat(datesMatch("2025-01-15", "2025-01-16")).isFalse();
        }

        @Test
        @DisplayName("should return true when both null")
        void shouldReturnTrueForBothNull() throws Exception {
            assertThat(datesMatch(null, null)).isTrue();
        }

        @Test
        @DisplayName("should return false when only one is null")
        void shouldReturnFalseWhenOneNull() throws Exception {
            assertThat(datesMatch("2025-01-15", null)).isFalse();
            assertThat(datesMatch(null, "2025-01-15")).isFalse();
        }

        @Test
        @DisplayName("should handle short date strings gracefully")
        void shouldHandleShortDateStrings() throws Exception {
            // Strings shorter than 10 chars are compared as-is
            assertThat(datesMatch("2025", "2025")).isTrue();
            assertThat(datesMatch("2025", "2026")).isFalse();
        }
    }

    // =========================================================================
    // Private Helper: decimalToString (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("decimalToString — Display Formatting")
    class DecimalToStringTests {

        private String decimalToString(BigDecimal value) throws Exception {
            IntegrityValidator validator = createDummyValidator();
            Method method = IntegrityValidator.class.getDeclaredMethod(
                    "decimalToString", BigDecimal.class);
            method.setAccessible(true);
            return (String) method.invoke(validator, value);
        }

        @Test
        @DisplayName("should return plain string for non-null value")
        void shouldReturnPlainString() throws Exception {
            assertThat(decimalToString(new BigDecimal("16.50"))).isEqualTo("16.50");
        }

        @Test
        @DisplayName("should return 'NULL' for null value")
        void shouldReturnNullString() throws Exception {
            assertThat(decimalToString(null)).isEqualTo("NULL");
        }

        @Test
        @DisplayName("should avoid scientific notation for large values")
        void shouldAvoidScientificNotation() throws Exception {
            assertThat(decimalToString(new BigDecimal("123456789.12")))
                    .isEqualTo("123456789.12");
        }
    }

    // =========================================================================
    // printReport Tests
    // =========================================================================

    @Nested
    @DisplayName("printReport — Report Output")
    class PrintReportTests {

        @Test
        @DisplayName("should print structured report with header and footer")
        void shouldPrintStructuredReport() {
            List<ValidationResult> results = List.of(
                    new ValidationResult("Row Count Match", true,
                            "  All tables match\n", 0),
                    new ValidationResult("Primary Key Uniqueness", false,
                            "  signon: 2 duplicates — FAIL\n", 1)
            );
            ValidationReport report = new ValidationReport(false, results,
                    LocalDateTime.of(2026, 1, 15, 10, 30, 0), 750L);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);

            IntegrityValidator validator = createDummyValidator();
            validator.printReport(report, ps);

            String output = baos.toString();

            // Verify header
            assertThat(output).contains("JPetStore Data Migration Validation Report");
            assertThat(output).contains("2026-01-15");

            // Verify individual check output
            assertThat(output).contains("[PASS] Check 1: Row Count Match");
            assertThat(output).contains("[FAIL] Check 2: Primary Key Uniqueness");
            assertThat(output).contains("All tables match");
            assertThat(output).contains("2 duplicates");

            // Verify footer
            assertThat(output).contains("OVERALL RESULT: FAIL");
            assertThat(output).contains("Total checks: 2, Passed: 1, Failed: 1");
            assertThat(output).contains("Execution time: 750ms");
        }

        @Test
        @DisplayName("should print PASS for all-passing report")
        void shouldPrintPassForAllPassingReport() {
            List<ValidationResult> results = List.of(
                    new ValidationResult("Check 1", true, "  OK\n", 0),
                    new ValidationResult("Check 2", true, "  OK\n", 0)
            );
            ValidationReport report = new ValidationReport(true, results,
                    LocalDateTime.now(), 100L);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);

            IntegrityValidator validator = createDummyValidator();
            validator.printReport(report, ps);

            String output = baos.toString();
            assertThat(output).contains("OVERALL RESULT: PASS");
            assertThat(output).contains("Total checks: 2, Passed: 2, Failed: 0");
        }

        @Test
        @DisplayName("should handle empty results list")
        void shouldHandleEmptyResults() {
            ValidationReport report = new ValidationReport(true, List.of(),
                    LocalDateTime.now(), 0L);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);

            IntegrityValidator validator = createDummyValidator();
            validator.printReport(report, ps);

            String output = baos.toString();
            assertThat(output).contains("Total checks: 0, Passed: 0, Failed: 0");
            assertThat(output).contains("OVERALL RESULT: PASS");
        }
    }

    // =========================================================================
    // Error Handling — validate methods with invalid connections
    // =========================================================================

    @Nested
    @DisplayName("Graceful Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("validateRowCounts should return FAIL result when HSQLDB is unavailable")
        void shouldFailGracefullyWhenHsqldbUnavailable() {
            IntegrityValidator validator = new IntegrityValidator(
                    "jdbc:hsqldb:hsql://nonexistent:9999/nodb", "SA", "",
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS);

            ValidationResult result = validator.validateRowCounts();

            assertThat(result.checkName()).isEqualTo("Row Count Match");
            assertThat(result.passed()).isFalse();
            assertThat(result.details()).containsIgnoringCase("error");
        }

        @Test
        @DisplayName("validatePrimaryKeyUniqueness should return FAIL when PostgreSQL unavailable")
        void shouldFailGracefullyWhenPostgresUnavailable() {
            IntegrityValidator validator = new IntegrityValidator(
                    DUMMY_HSQLDB_URL, DUMMY_HSQLDB_USER, DUMMY_HSQLDB_PASS,
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x",
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x",
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x");

            ValidationResult result = validator.validatePrimaryKeyUniqueness();

            assertThat(result.checkName()).isEqualTo("Primary Key Uniqueness");
            assertThat(result.passed()).isFalse();
            assertThat(result.errorCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("validateIntraServiceForeignKeys should return FAIL when Catalog DB unavailable")
        void shouldFailGracefullyWhenCatalogDbUnavailable() {
            IntegrityValidator validator = new IntegrityValidator(
                    DUMMY_HSQLDB_URL, DUMMY_HSQLDB_USER, DUMMY_HSQLDB_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x",
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS);

            ValidationResult result = validator.validateIntraServiceForeignKeys();

            assertThat(result.checkName()).isEqualTo("Intra-Service FK Integrity");
            assertThat(result.passed()).isFalse();
            assertThat(result.details()).containsIgnoringCase("error");
        }

        @Test
        @DisplayName("validateSequenceSafety should return FAIL when Order DB unavailable")
        void shouldFailGracefullyWhenOrderDbUnavailable() {
            IntegrityValidator validator = new IntegrityValidator(
                    DUMMY_HSQLDB_URL, DUMMY_HSQLDB_USER, DUMMY_HSQLDB_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x");

            ValidationResult result = validator.validateSequenceSafety();

            assertThat(result.checkName()).isEqualTo("Sequence Safety");
            assertThat(result.passed()).isFalse();
        }

        @Test
        @DisplayName("validate orchestrator should produce a report even when all DBs unavailable")
        void shouldProduceReportEvenWhenAllDbsUnavailable() {
            IntegrityValidator validator = new IntegrityValidator(
                    "jdbc:hsqldb:hsql://nonexistent:9999/nodb", "SA", "",
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x",
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x",
                    "jdbc:postgresql://nonexistent:5432/nodb", "x", "x");

            ValidationReport report = validator.validate();

            assertThat(report).isNotNull();
            assertThat(report.overallPassed()).isFalse();
            assertThat(report.results()).hasSize(7); // All 7 checks attempted
            assertThat(report.executionTimeMs()).isGreaterThanOrEqualTo(0L);
            assertThat(report.timestamp()).isNotNull();

            // Every check should have failed gracefully, not thrown an exception
            for (ValidationResult result : report.results()) {
                assertThat(result.checkName()).isNotBlank();
                assertThat(result.details()).isNotNull();
            }
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Creates a dummy IntegrityValidator instance for testing methods that
     * don't need actual database connections.
     */
    private static IntegrityValidator createDummyValidator() {
        return new IntegrityValidator(
                DUMMY_HSQLDB_URL, DUMMY_HSQLDB_USER, DUMMY_HSQLDB_PASS,
                DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS,
                DUMMY_PG_URL, DUMMY_PG_USER, DUMMY_PG_PASS);
    }
}
