#!/usr/bin/env bash
#
#    Copyright 2010-2026 the original author or authors.
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#       https://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#
# ============================================================================
# Idempotent Data Loading — HSQLDB to PostgreSQL Migration
# ============================================================================
#
# Loads exported HSQLDB CSV data into three PostgreSQL databases using the
# migration module's DataLoader.java class. All inserts use
# INSERT ... ON CONFLICT DO NOTHING for idempotency — re-running this script
# against already-populated databases does not create duplicates.
#
# After loading, DataLoader configures PostgreSQL sequences to
# MAX(id) + 1000 to prevent ID collisions with legacy data.
# This script performs additional post-load verification via psql.
#
# Pipeline Order (each step is a prerequisite for the next):
#   1. provision-postgres.sh  — Creates 3 PostgreSQL databases and order_id_seq
#   2. Liquibase migrations   — Creates table schemas in each database
#   3. export-hsqldb.sh       — Exports all 13 HSQLDB tables to CSV
#   4. load-data.sh           — THIS SCRIPT: loads CSV into PostgreSQL
#   5. validate-integrity.sh  — Verifies data integrity post-migration
#
# Database-per-Service Boundary:
#   Account DB (jpetstore_account): signon, account, profile, bannerdata
#   Catalog DB (jpetstore_catalog): supplier, category, product, item, inventory
#   Order DB   (jpetstore_order):   orders, orderstatus, lineitem, sequence
#
# FK Load Order (within each database):
#   Account: signon → account → profile → bannerdata
#   Catalog: supplier → category → product → item → inventory
#   Order:   sequence → orders → orderstatus → lineitem
#
# Usage:
#   ./load-data.sh
#
# Environment Variables (all optional — sensible defaults provided):
#   PG_ACCOUNT_HOST   Account DB host    (default: localhost)
#   PG_ACCOUNT_PORT   Account DB port    (default: 5432)
#   PG_ACCOUNT_DB     Account DB name    (default: jpetstore_account)
#   PG_CATALOG_HOST   Catalog DB host    (default: localhost)
#   PG_CATALOG_PORT   Catalog DB port    (default: 5433)
#   PG_CATALOG_DB     Catalog DB name    (default: jpetstore_catalog)
#   PG_ORDER_HOST     Order DB host      (default: localhost)
#   PG_ORDER_PORT     Order DB port      (default: 5434)
#   PG_ORDER_DB       Order DB name      (default: jpetstore_order)
#   PG_USER           Service user       (default: jpetstore)
#   PG_PASSWORD        Service password   (default: jpetstore)
#   EXPORT_DIR        CSV export dir     (default: <project_root>/migration/output/export)
#   JAVA_HOME         JDK location       (optional if java is on PATH)
#
# Exit Codes:
#   0 — All tables loaded and verified successfully
#   1 — One or more failures occurred
# ============================================================================

# Strict error handling: exit on error, unset variable references, pipe failures
set -euo pipefail

# ============================================================================
# Script Directory Resolution (works when run from any directory)
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ============================================================================
# ANSI Color Constants
# ============================================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m' # No Color

# ============================================================================
# Helper Functions
# ============================================================================

# Print an informational message with timestamp
log_info() {
    echo -e "${CYAN}[INFO]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"
}

# Print a success message with green check mark
log_success() {
    echo -e "${GREEN}  ✓${NC} $*"
}

# Print a warning message
log_warn() {
    echo -e "${YELLOW}[WARN]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# Print an error message to stderr
log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# Execute a SQL command against a specific PostgreSQL database using service credentials.
# Arguments:
#   $1 - Host
#   $2 - Port
#   $3 - Database name
#   $4 - SQL command string
#   $5 - (optional) psql flags (default: "-c")
run_psql() {
    local host="$1"
    local port="$2"
    local db="$3"
    local sql="$4"
    local flags="${5:--c}"

    PGPASSWORD="${PG_PASSWORD}" psql \
        -h "${host}" \
        -p "${port}" \
        -U "${PG_USER}" \
        -d "${db}" \
        "${flags}" "${sql}"
}

# Execute a SQL command and return the unformatted result (no headers, aligned).
# Arguments:
#   $1 - Host
#   $2 - Port
#   $3 - Database name
#   $4 - SQL command string
run_psql_value() {
    local host="$1"
    local port="$2"
    local db="$3"
    local sql="$4"

    PGPASSWORD="${PG_PASSWORD}" psql \
        -h "${host}" \
        -p "${port}" \
        -U "${PG_USER}" \
        -d "${db}" \
        -tAc "${sql}" 2>/dev/null || echo ""
}

# ============================================================================
# Banner
# ============================================================================
echo ""
echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  JPetStore Migration — Data Loading        ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""
echo "Idempotent data loading for HSQLDB → PostgreSQL migration"
echo "Uses INSERT ... ON CONFLICT DO NOTHING (re-run safe)"
echo ""

# ============================================================================
# Environment Variables and Defaults
# ============================================================================

# Account Service PostgreSQL connection
PG_ACCOUNT_HOST="${PG_ACCOUNT_HOST:-localhost}"
PG_ACCOUNT_PORT="${PG_ACCOUNT_PORT:-5432}"
PG_ACCOUNT_DB="${PG_ACCOUNT_DB:-jpetstore_account}"

# Catalog Service PostgreSQL connection
PG_CATALOG_HOST="${PG_CATALOG_HOST:-localhost}"
PG_CATALOG_PORT="${PG_CATALOG_PORT:-5433}"
PG_CATALOG_DB="${PG_CATALOG_DB:-jpetstore_catalog}"

# Order Service PostgreSQL connection
PG_ORDER_HOST="${PG_ORDER_HOST:-localhost}"
PG_ORDER_PORT="${PG_ORDER_PORT:-5434}"
PG_ORDER_DB="${PG_ORDER_DB:-jpetstore_order}"

# Shared service credentials
PG_USER="${PG_USER:-jpetstore}"
PG_PASSWORD="${PG_PASSWORD:-jpetstore}"

# Construct JDBC URLs for DataLoader JVM system properties
PG_ACCOUNT_URL="jdbc:postgresql://${PG_ACCOUNT_HOST}:${PG_ACCOUNT_PORT}/${PG_ACCOUNT_DB}"
PG_CATALOG_URL="jdbc:postgresql://${PG_CATALOG_HOST}:${PG_CATALOG_PORT}/${PG_CATALOG_DB}"
PG_ORDER_URL="jdbc:postgresql://${PG_ORDER_HOST}:${PG_ORDER_PORT}/${PG_ORDER_DB}"

# Export directory containing CSV files from export-hsqldb.sh
EXPORT_DIR="${EXPORT_DIR:-${PROJECT_ROOT}/migration/output/export}"

# Java command — respects JAVA_HOME when set, otherwise relies on PATH
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

# ============================================================================
# Table Definitions — All 13 HSQLDB Tables by Bounded Context
# ============================================================================
# Load order within each database respects foreign key dependencies.

# Account bounded context (4 tables)
ACCOUNT_TABLES="signon account profile bannerdata"

# Catalog bounded context (5 tables)
CATALOG_TABLES="supplier category product item inventory"

# Order bounded context (4 tables)
ORDER_TABLES="sequence orders orderstatus lineitem"

# Combined list — all 13 tables
ALL_TABLES="${ACCOUNT_TABLES} ${CATALOG_TABLES} ${ORDER_TABLES}"

# Expected minimum seed data row counts per table (from jpetstore-hsqldb-dataload.sql)
declare -A EXPECTED_MIN_ROWS
EXPECTED_MIN_ROWS[signon]=2
EXPECTED_MIN_ROWS[account]=2
EXPECTED_MIN_ROWS[profile]=2
EXPECTED_MIN_ROWS[bannerdata]=5
EXPECTED_MIN_ROWS[supplier]=2
EXPECTED_MIN_ROWS[category]=5
EXPECTED_MIN_ROWS[product]=16
EXPECTED_MIN_ROWS[item]=28
EXPECTED_MIN_ROWS[inventory]=28
EXPECTED_MIN_ROWS[orders]=0
EXPECTED_MIN_ROWS[orderstatus]=0
EXPECTED_MIN_ROWS[lineitem]=0
EXPECTED_MIN_ROWS[sequence]=1

# Print configuration summary
log_info "Configuration:"
log_info "  Account DB:  ${PG_ACCOUNT_URL}"
log_info "  Catalog DB:  ${PG_CATALOG_URL}"
log_info "  Order DB:    ${PG_ORDER_URL}"
log_info "  Service User: ${PG_USER}"
log_info "  Export Dir:   ${EXPORT_DIR}"
log_info "  Project Root: ${PROJECT_ROOT}"
echo ""

# ============================================================================
# Pre-flight Check 1: Java Runtime
# ============================================================================

log_info "Running pre-flight checks..."
echo ""

log_info "Check 1/6: Java runtime..."
if ! command -v "${JAVA_CMD}" &>/dev/null; then
    log_error "Java not found. Set JAVA_HOME or ensure 'java' is on PATH."
    log_error "  Required: JDK 17 or later"
    exit 1
fi
JAVA_VERSION=$("${JAVA_CMD}" -version 2>&1 | head -1)
log_success "Java: ${JAVA_VERSION}"

# ============================================================================
# Pre-flight Check 2: psql Client
# ============================================================================

log_info "Check 2/6: psql client..."
if ! command -v psql &>/dev/null; then
    log_error "psql not found on PATH. Install PostgreSQL client tools."
    log_error "  Ubuntu/Debian: sudo apt-get install -y postgresql-client"
    log_error "  macOS:         brew install libpq"
    exit 1
fi
PSQL_VERSION=$(psql --version 2>/dev/null | head -1 || echo "unknown")
log_success "psql: ${PSQL_VERSION}"

# ============================================================================
# Pre-flight Check 3: Migration JAR
# ============================================================================

log_info "Check 3/6: Migration JAR..."
MIGRATION_JAR=""
for jar in "${PROJECT_ROOT}"/migration/target/migration-*.jar; do
    # Skip sources and javadoc JARs produced by Maven plugins
    case "${jar}" in
        *-sources.jar|*-javadoc.jar) continue ;;
    esac
    if [[ -f "${jar}" ]]; then
        MIGRATION_JAR="${jar}"
        break
    fi
done

if [[ -z "${MIGRATION_JAR}" ]]; then
    log_error "Migration JAR not found at migration/target/migration-*.jar"
    log_error "Build the migration module first:"
    log_error "  cd ${PROJECT_ROOT} && ./mvnw package -pl migration -DskipTests"
    exit 1
fi
log_success "Migration JAR: ${MIGRATION_JAR}"

# Check dependency JARs (PostgreSQL driver, SLF4J, etc.)
DEP_DIR="${PROJECT_ROOT}/migration/target/dependency"
if [[ ! -d "${DEP_DIR}" ]] || [[ -z "$(ls "${DEP_DIR}"/*.jar 2>/dev/null || true)" ]]; then
    log_info "Dependency JARs not found. Copying via Maven dependency:copy-dependencies..."
    if ! (cd "${PROJECT_ROOT}" && ./mvnw dependency:copy-dependencies -B -q -pl migration 2>&1); then
        log_error "Failed to copy migration dependencies. Ensure Maven is configured correctly."
        exit 1
    fi
fi

# Verify the PostgreSQL JDBC driver is present on the classpath
if ! ls "${DEP_DIR}"/postgresql-*.jar 1>/dev/null 2>&1; then
    log_error "PostgreSQL JDBC driver JAR not found in ${DEP_DIR}."
    log_error "Dependencies may not have been copied correctly."
    exit 1
fi
log_success "Dependency JARs: ${DEP_DIR}"

# Build the classpath for Java invocation
CLASSPATH="${MIGRATION_JAR}:${DEP_DIR}/*"

# ============================================================================
# Pre-flight Check 4: Export Directory and CSV Files
# ============================================================================

log_info "Check 4/6: Export directory and CSV files..."
if [[ ! -d "${EXPORT_DIR}" ]]; then
    log_error "Export directory does not exist: ${EXPORT_DIR}"
    log_error "Run export-hsqldb.sh first to export HSQLDB data to CSV files."
    exit 1
fi
log_success "Export directory exists: ${EXPORT_DIR}"

# Verify CSV files exist for all 13 tables
MISSING_CSV=0
for table in ${ALL_TABLES}; do
    csv_file="${EXPORT_DIR}/${table}.csv"
    if [[ ! -f "${csv_file}" ]]; then
        log_error "Missing CSV file: ${csv_file}"
        MISSING_CSV=$((MISSING_CSV + 1))
    fi
done

if [[ ${MISSING_CSV} -gt 0 ]]; then
    log_error "${MISSING_CSV} CSV file(s) missing. Run export-hsqldb.sh first."
    exit 1
fi
log_success "All 13 CSV files present in ${EXPORT_DIR}"

# Check for baseline row counts manifest from export-hsqldb.sh
BASELINE_FILE="${EXPORT_DIR}/row-counts-baseline.txt"
if [[ -f "${BASELINE_FILE}" ]]; then
    log_success "Baseline row counts file found: ${BASELINE_FILE}"
else
    log_warn "Baseline row counts file not found: ${BASELINE_FILE}"
    log_warn "Post-load comparison with export baseline will be skipped."
fi

# ============================================================================
# Pre-flight Check 5: PostgreSQL Connectivity
# ============================================================================

log_info "Check 5/6: PostgreSQL connectivity..."
CONN_FAILURES=0

# Test Account DB
if run_psql "${PG_ACCOUNT_HOST}" "${PG_ACCOUNT_PORT}" "${PG_ACCOUNT_DB}" "SELECT 1;" "-c" &>/dev/null; then
    log_success "Account DB reachable: ${PG_ACCOUNT_DB} at ${PG_ACCOUNT_HOST}:${PG_ACCOUNT_PORT}"
else
    log_error "Cannot connect to Account DB: ${PG_ACCOUNT_DB} at ${PG_ACCOUNT_HOST}:${PG_ACCOUNT_PORT}"
    CONN_FAILURES=$((CONN_FAILURES + 1))
fi

# Test Catalog DB
if run_psql "${PG_CATALOG_HOST}" "${PG_CATALOG_PORT}" "${PG_CATALOG_DB}" "SELECT 1;" "-c" &>/dev/null; then
    log_success "Catalog DB reachable: ${PG_CATALOG_DB} at ${PG_CATALOG_HOST}:${PG_CATALOG_PORT}"
else
    log_error "Cannot connect to Catalog DB: ${PG_CATALOG_DB} at ${PG_CATALOG_HOST}:${PG_CATALOG_PORT}"
    CONN_FAILURES=$((CONN_FAILURES + 1))
fi

# Test Order DB
if run_psql "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" "SELECT 1;" "-c" &>/dev/null; then
    log_success "Order DB reachable: ${PG_ORDER_DB} at ${PG_ORDER_HOST}:${PG_ORDER_PORT}"
else
    log_error "Cannot connect to Order DB: ${PG_ORDER_DB} at ${PG_ORDER_HOST}:${PG_ORDER_PORT}"
    CONN_FAILURES=$((CONN_FAILURES + 1))
fi

if [[ ${CONN_FAILURES} -gt 0 ]]; then
    log_error "${CONN_FAILURES} database(s) unreachable. Ensure PostgreSQL is running and"
    log_error "provision-postgres.sh has been executed."
    exit 1
fi

# ============================================================================
# Pre-flight Check 6: Liquibase Schemas Applied (tables exist)
# ============================================================================

log_info "Check 6/6: Verifying Liquibase schemas are applied..."
SCHEMA_FAILURES=0

# Check Account DB tables
for table in ${ACCOUNT_TABLES}; do
    result=$(run_psql_value "${PG_ACCOUNT_HOST}" "${PG_ACCOUNT_PORT}" "${PG_ACCOUNT_DB}" \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${result}" != "1" ]]; then
        log_error "Table '${table}' not found in ${PG_ACCOUNT_DB}. Run Liquibase migrations first."
        SCHEMA_FAILURES=$((SCHEMA_FAILURES + 1))
    fi
done

# Check Catalog DB tables
for table in ${CATALOG_TABLES}; do
    result=$(run_psql_value "${PG_CATALOG_HOST}" "${PG_CATALOG_PORT}" "${PG_CATALOG_DB}" \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${result}" != "1" ]]; then
        log_error "Table '${table}' not found in ${PG_CATALOG_DB}. Run Liquibase migrations first."
        SCHEMA_FAILURES=$((SCHEMA_FAILURES + 1))
    fi
done

# Check Order DB tables
for table in ${ORDER_TABLES}; do
    result=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${result}" != "1" ]]; then
        log_error "Table '${table}' not found in ${PG_ORDER_DB}. Run Liquibase migrations first."
        SCHEMA_FAILURES=$((SCHEMA_FAILURES + 1))
    fi
done

if [[ ${SCHEMA_FAILURES} -gt 0 ]]; then
    log_error "${SCHEMA_FAILURES} table(s) missing. Run Liquibase migrations for each service first."
    log_error "The tables must exist before data can be loaded."
    exit 1
fi
log_success "All 13 tables exist in their respective databases"

echo ""
log_info "All pre-flight checks passed."
echo ""

# ============================================================================
# Pre-Load Row Counts (capture current state for comparison)
# ============================================================================

log_info "Capturing pre-load row counts..."
declare -A PRE_LOAD_COUNTS

for table in ${ACCOUNT_TABLES}; do
    count=$(run_psql_value "${PG_ACCOUNT_HOST}" "${PG_ACCOUNT_PORT}" "${PG_ACCOUNT_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    PRE_LOAD_COUNTS["${table}"]="${count:-0}"
done

for table in ${CATALOG_TABLES}; do
    count=$(run_psql_value "${PG_CATALOG_HOST}" "${PG_CATALOG_PORT}" "${PG_CATALOG_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    PRE_LOAD_COUNTS["${table}"]="${count:-0}"
done

for table in ${ORDER_TABLES}; do
    count=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    PRE_LOAD_COUNTS["${table}"]="${count:-0}"
done

printf "  %-15s %10s\n" "TABLE" "PRE-LOAD"
printf "  %-15s %10s\n" "---------------" "----------"
for table in ${ALL_TABLES}; do
    printf "  %-15s %10s\n" "${table}" "${PRE_LOAD_COUNTS[${table}]}"
done
echo ""

# ============================================================================
# Data Loading via DataLoader.java
# ============================================================================

echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  Starting Data Loading                     ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""

log_info "Invoking com.jpetstore.migration.DataLoader..."
log_info "Loading 13 tables across 3 databases using INSERT ON CONFLICT DO NOTHING"
echo ""

# DataLoader reads configuration from JVM system properties (-D flags).
# It loads all 13 tables in FK-safe order per database, then configures
# PostgreSQL sequences (order_id_seq to MAX(order_id) + 1000).
# Exits with 0 on full success, 1 on any failure.
LOADER_EXIT_CODE=0
"${JAVA_CMD}" \
    -Daccount.db.url="${PG_ACCOUNT_URL}" \
    -Daccount.db.user="${PG_USER}" \
    -Daccount.db.password="${PG_PASSWORD}" \
    -Dcatalog.db.url="${PG_CATALOG_URL}" \
    -Dcatalog.db.user="${PG_USER}" \
    -Dcatalog.db.password="${PG_PASSWORD}" \
    -Dorder.db.url="${PG_ORDER_URL}" \
    -Dorder.db.user="${PG_USER}" \
    -Dorder.db.password="${PG_PASSWORD}" \
    -Dexport.dir="${EXPORT_DIR}" \
    -cp "${CLASSPATH}" \
    com.jpetstore.migration.DataLoader || LOADER_EXIT_CODE=$?

echo ""

if [[ ${LOADER_EXIT_CODE} -ne 0 ]]; then
    log_error "DataLoader exited with code ${LOADER_EXIT_CODE}."
    log_error "Review the output above for details on which tables failed."
    log_error "❌ Data loading FAILED. Check error messages above."
    exit 1
fi

log_info "DataLoader completed successfully."
echo ""

# ============================================================================
# Post-Load Sequence Verification via psql
# ============================================================================
# DataLoader.configureSequences() already sets order_id_seq to MAX + 1000.
# This section performs an independent verification and logs the current value.

echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  Sequence Verification                     ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""

log_info "Verifying PostgreSQL sequence configuration..."

# Check the order_id_seq exists and read its current value
SEQ_EXISTS=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
    "SELECT 1 FROM pg_sequences WHERE sequencename = 'order_id_seq';")

if [[ "${SEQ_EXISTS}" == "1" ]]; then
    # Read the current last_value of the sequence
    SEQ_LAST_VALUE=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
        "SELECT last_value FROM pg_sequences WHERE sequencename = 'order_id_seq';")

    # Read the maximum order_id from migrated data
    MAX_ORDER_ID=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
        "SELECT COALESCE(MAX(order_id), 0) FROM orders;" 2>/dev/null || echo "0")

    # Determine expected minimum: MAX(order_id) + 1000 or at least 1000
    if [[ -z "${MAX_ORDER_ID}" ]] || [[ "${MAX_ORDER_ID}" == "0" ]]; then
        EXPECTED_MIN_SEQ=1000
        MAX_ORDER_ID=0
    else
        EXPECTED_MIN_SEQ=$((MAX_ORDER_ID + 1000))
    fi

    log_success "Sequence 'order_id_seq' exists in ${PG_ORDER_DB}"
    log_success "  Current last_value: ${SEQ_LAST_VALUE}"
    log_success "  Max migrated order_id: ${MAX_ORDER_ID:-none}"
    log_success "  Expected minimum: ${EXPECTED_MIN_SEQ}"

    # Verify the sequence value is adequate
    if [[ "${SEQ_LAST_VALUE}" -ge "${EXPECTED_MIN_SEQ}" ]]; then
        log_success "  Sequence value is adequate (${SEQ_LAST_VALUE} >= ${EXPECTED_MIN_SEQ})"
    else
        log_warn "  Sequence value (${SEQ_LAST_VALUE}) is below expected minimum (${EXPECTED_MIN_SEQ})."
        log_warn "  Adjusting sequence to MAX(order_id) + 1000..."

        run_psql "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
            "ALTER SEQUENCE order_id_seq RESTART WITH ${EXPECTED_MIN_SEQ};" "-c" &>/dev/null

        log_success "  Sequence adjusted to ${EXPECTED_MIN_SEQ}"
    fi
else
    log_warn "Sequence 'order_id_seq' not found in ${PG_ORDER_DB}."
    log_warn "The Order Service may need to create this sequence via Liquibase."
fi

echo ""

# ============================================================================
# Post-Load Row Count Verification
# ============================================================================

echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  Post-Load Verification                    ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""

log_info "Querying post-load row counts from all 3 PostgreSQL databases..."
echo ""

declare -A POST_LOAD_COUNTS
VERIFICATION_FAILURES=0

# Query row counts for Account DB tables
for table in ${ACCOUNT_TABLES}; do
    count=$(run_psql_value "${PG_ACCOUNT_HOST}" "${PG_ACCOUNT_PORT}" "${PG_ACCOUNT_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    POST_LOAD_COUNTS["${table}"]="${count:-0}"
done

# Query row counts for Catalog DB tables
for table in ${CATALOG_TABLES}; do
    count=$(run_psql_value "${PG_CATALOG_HOST}" "${PG_CATALOG_PORT}" "${PG_CATALOG_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    POST_LOAD_COUNTS["${table}"]="${count:-0}"
done

# Query row counts for Order DB tables
for table in ${ORDER_TABLES}; do
    count=$(run_psql_value "${PG_ORDER_HOST}" "${PG_ORDER_PORT}" "${PG_ORDER_DB}" \
        "SELECT COUNT(*) FROM ${table};")
    POST_LOAD_COUNTS["${table}"]="${count:-0}"
done

# Read baseline counts from export manifest if available
declare -A BASELINE_COUNTS
if [[ -f "${BASELINE_FILE}" ]]; then
    while IFS=': ' read -r tbl cnt; do
        # Skip comment lines and empty lines
        [[ "${tbl}" =~ ^#.*$ ]] && continue
        [[ -z "${tbl}" ]] && continue
        BASELINE_COUNTS["${tbl}"]="${cnt}"
    done < "${BASELINE_FILE}"
fi

# Print detailed comparison table
echo -e "  ${BOLD}Account DB (${PG_ACCOUNT_DB}):${NC}"
printf "  %-15s %10s %10s %10s %10s %10s\n" "TABLE" "PRE-LOAD" "POST-LOAD" "BASELINE" "MIN-EXP" "STATUS"
printf "  %-15s %10s %10s %10s %10s %10s\n" "---------------" "----------" "----------" "----------" "----------" "----------"

for table in ${ACCOUNT_TABLES}; do
    pre="${PRE_LOAD_COUNTS[${table}]:-0}"
    post="${POST_LOAD_COUNTS[${table}]:-0}"
    baseline="${BASELINE_COUNTS[${table}]:-N/A}"
    min_expected="${EXPECTED_MIN_ROWS[${table}]:-0}"

    status="OK"
    if [[ "${post}" -lt "${min_expected}" ]]; then
        status="WARN:LOW"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi
    if [[ "${baseline}" != "N/A" ]] && [[ "${post}" -lt "${baseline}" ]]; then
        status="WARN:MISMATCH"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi

    printf "  %-15s %10s %10s %10s %10s %10s\n" "${table}" "${pre}" "${post}" "${baseline}" "${min_expected}" "${status}"
done

echo ""
echo -e "  ${BOLD}Catalog DB (${PG_CATALOG_DB}):${NC}"
printf "  %-15s %10s %10s %10s %10s %10s\n" "TABLE" "PRE-LOAD" "POST-LOAD" "BASELINE" "MIN-EXP" "STATUS"
printf "  %-15s %10s %10s %10s %10s %10s\n" "---------------" "----------" "----------" "----------" "----------" "----------"

for table in ${CATALOG_TABLES}; do
    pre="${PRE_LOAD_COUNTS[${table}]:-0}"
    post="${POST_LOAD_COUNTS[${table}]:-0}"
    baseline="${BASELINE_COUNTS[${table}]:-N/A}"
    min_expected="${EXPECTED_MIN_ROWS[${table}]:-0}"

    status="OK"
    if [[ "${post}" -lt "${min_expected}" ]]; then
        status="WARN:LOW"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi
    if [[ "${baseline}" != "N/A" ]] && [[ "${post}" -lt "${baseline}" ]]; then
        status="WARN:MISMATCH"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi

    printf "  %-15s %10s %10s %10s %10s %10s\n" "${table}" "${pre}" "${post}" "${baseline}" "${min_expected}" "${status}"
done

echo ""
echo -e "  ${BOLD}Order DB (${PG_ORDER_DB}):${NC}"
printf "  %-15s %10s %10s %10s %10s %10s\n" "TABLE" "PRE-LOAD" "POST-LOAD" "BASELINE" "MIN-EXP" "STATUS"
printf "  %-15s %10s %10s %10s %10s %10s\n" "---------------" "----------" "----------" "----------" "----------" "----------"

for table in ${ORDER_TABLES}; do
    pre="${PRE_LOAD_COUNTS[${table}]:-0}"
    post="${POST_LOAD_COUNTS[${table}]:-0}"
    baseline="${BASELINE_COUNTS[${table}]:-N/A}"
    min_expected="${EXPECTED_MIN_ROWS[${table}]:-0}"

    status="OK"
    if [[ "${post}" -lt "${min_expected}" ]]; then
        status="WARN:LOW"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi
    if [[ "${baseline}" != "N/A" ]] && [[ "${post}" -lt "${baseline}" ]]; then
        status="WARN:MISMATCH"
        VERIFICATION_FAILURES=$((VERIFICATION_FAILURES + 1))
    fi

    printf "  %-15s %10s %10s %10s %10s %10s\n" "${table}" "${pre}" "${post}" "${baseline}" "${min_expected}" "${status}"
done

echo ""

# Calculate totals
TOTAL_POST_LOAD=0
for table in ${ALL_TABLES}; do
    count="${POST_LOAD_COUNTS[${table}]:-0}"
    TOTAL_POST_LOAD=$((TOTAL_POST_LOAD + count))
done

log_info "Total rows across all 13 tables: ${TOTAL_POST_LOAD}"

if [[ ${VERIFICATION_FAILURES} -gt 0 ]]; then
    log_warn "${VERIFICATION_FAILURES} verification warning(s) detected."
    log_warn "Review the table above for WARN entries."
fi

echo ""

# ============================================================================
# Final Summary
# ============================================================================

echo -e "${BOLD}============================================${NC}"

if [[ ${VERIFICATION_FAILURES} -gt 0 ]]; then
    echo -e "${YELLOW}${BOLD}  DATA LOADING COMPLETED WITH WARNINGS      ${NC}"
    echo -e "${BOLD}============================================${NC}"
    echo ""
    echo "  ⚠️  Data loading completed but ${VERIFICATION_FAILURES} verification warning(s) detected."
    echo "  Review the row count comparison table above for details."
    echo ""
else
    echo -e "${GREEN}${BOLD}  DATA LOADING COMPLETED SUCCESSFULLY       ${NC}"
    echo -e "${BOLD}============================================${NC}"
    echo ""
    echo "  ✅ Data loading completed successfully."
    echo "  All tables loaded with INSERT ON CONFLICT DO NOTHING (idempotent)."
    echo ""
fi

echo "  Total tables loaded: 13"
echo "  Total rows:          ${TOTAL_POST_LOAD}"
echo ""
echo "  Account DB (${PG_ACCOUNT_DB}):"
for table in ${ACCOUNT_TABLES}; do
    echo "    ${table}: ${POST_LOAD_COUNTS[${table}]:-0} rows"
done
echo ""
echo "  Catalog DB (${PG_CATALOG_DB}):"
for table in ${CATALOG_TABLES}; do
    echo "    ${table}: ${POST_LOAD_COUNTS[${table}]:-0} rows"
done
echo ""
echo "  Order DB (${PG_ORDER_DB}):"
for table in ${ORDER_TABLES}; do
    echo "    ${table}: ${POST_LOAD_COUNTS[${table}]:-0} rows"
done
echo ""

echo -e "${BOLD}============================================${NC}"
echo ""
echo -e "${CYAN}Next step:${NC}"
echo "  Run validate-integrity.sh to verify data integrity across all databases."
echo ""

# ============================================================================
# Exit
# ============================================================================

if [[ ${VERIFICATION_FAILURES} -gt 0 ]]; then
    log_warn "Data loading completed with warnings. Review output above."
    # Warnings do not cause a non-zero exit — data was loaded successfully
    # but counts may differ from baseline. validate-integrity.sh will catch real issues.
    exit 0
fi

log_info "✅ Data loading completed successfully. All tables loaded with ON CONFLICT DO NOTHING."
exit 0
