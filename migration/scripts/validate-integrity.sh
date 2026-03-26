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
# 7-Check Data Integrity Validation Gate for HSQLDB → PostgreSQL Migration
# ============================================================================
#
# Final step in the JPetStore data migration pipeline. This script MUST pass
# ALL 7 checks completely before any service cutover routing flag is switched.
# It orchestrates the migration module's IntegrityValidator.java class across
# the HSQLDB source database and the 3 target PostgreSQL databases.
#
# Pipeline Order (each step is a prerequisite for the next):
#   1. provision-postgres.sh  — Creates 3 PostgreSQL databases and order_id_seq
#   2. Liquibase migrations   — Creates table schemas in each database
#   3. export-hsqldb.sh       — Exports all 13 HSQLDB tables to CSV
#   4. load-data.sh           — Loads CSV into PostgreSQL
#   5. validate-integrity.sh  — THIS SCRIPT: validates data integrity
#
# The 7 Checks:
#   1. Row Count Match          — every table: HSQLDB count == PostgreSQL count
#   2. Primary Key Uniqueness   — no duplicate PKs in any PostgreSQL table
#   3. Intra-Service FK         — catalog DB FK relationships: no orphans
#   4. Cross-Service Refs       — orders.userid→account, lineitem.itemid→item
#   5. Sequence Safety          — order_id_seq > MAX(orderid) + 1000
#   6. Column Completeness      — no columns silently dropped in migration
#   7. Type Conversion          — decimals, dates, booleans, nulls preserved
#
# Database-per-Service Boundary:
#   Account DB (jpetstore_account): signon, account, profile, bannerdata
#   Catalog DB (jpetstore_catalog): supplier, category, product, item, inventory
#   Order DB   (jpetstore_order):   orders, orderstatus, lineitem
#
# Usage:
#   ./validate-integrity.sh
#
# Environment Variables (all optional — sensible defaults provided):
#   HSQLDB_URL         HSQLDB JDBC URL    (default: jdbc:hsqldb:hsql://localhost:9001/jpetstore)
#   HSQLDB_USER        HSQLDB user        (default: sa)
#   HSQLDB_PASSWORD    HSQLDB password    (default: empty)
#   PG_ACCOUNT_URL     Account DB JDBC    (default: jdbc:postgresql://localhost:5432/jpetstore_account)
#   PG_CATALOG_URL     Catalog DB JDBC    (default: jdbc:postgresql://localhost:5433/jpetstore_catalog)
#   PG_ORDER_URL       Order DB JDBC      (default: jdbc:postgresql://localhost:5434/jpetstore_order)
#   PG_ACCOUNT_USER    Account DB user    (default: account_user)
#   PG_ACCOUNT_PASSWORD Account DB pass   (default: account_pass)
#   PG_CATALOG_USER    Catalog DB user    (default: catalog_user)
#   PG_CATALOG_PASSWORD Catalog DB pass   (default: catalog_pass)
#   PG_ORDER_USER      Order DB user      (default: order_user)
#   PG_ORDER_PASSWORD  Order DB pass      (default: order_pass)
#   EXPORT_DIR         CSV export dir     (default: <project_root>/migration/output/export)
#   JAVA_HOME          JDK location       (optional if java is on PATH)
#
# Exit Codes:
#   0 — All 7 validation checks PASSED. Safe to proceed with service cutover.
#   1 — One or more checks FAILED. DO NOT proceed with service cutover.
#
# Make executable: chmod +x validate-integrity.sh
# ============================================================================

# Strict error handling: exit on error, unset variable references, pipe failures
set -euo pipefail

# ============================================================================
# ANSI Color Constants for Terminal Output
# ============================================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m' # No Color (reset)

# ============================================================================
# Script Directory Resolution (works when run from any working directory)
# ============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ============================================================================
# Environment Variables and Defaults
# ============================================================================

# HSQLDB source database connection parameters
HSQLDB_URL="${HSQLDB_URL:-jdbc:hsqldb:hsql://localhost:9001/jpetstore}"
HSQLDB_USER="${HSQLDB_USER:-sa}"
HSQLDB_PASSWORD="${HSQLDB_PASSWORD:-}"

# PostgreSQL target database JDBC URLs (one per bounded context)
PG_ACCOUNT_URL="${PG_ACCOUNT_URL:-jdbc:postgresql://localhost:5432/jpetstore_account}"
PG_CATALOG_URL="${PG_CATALOG_URL:-jdbc:postgresql://localhost:5433/jpetstore_catalog}"
PG_ORDER_URL="${PG_ORDER_URL:-jdbc:postgresql://localhost:5434/jpetstore_order}"

# Per-database PostgreSQL service credentials (matching docker-compose.yml)
PG_ACCOUNT_USER="${PG_ACCOUNT_USER:-account_user}"
PG_ACCOUNT_PASSWORD="${PG_ACCOUNT_PASSWORD:-account_pass}"
PG_CATALOG_USER="${PG_CATALOG_USER:-catalog_user}"
PG_CATALOG_PASSWORD="${PG_CATALOG_PASSWORD:-catalog_pass}"
PG_ORDER_USER="${PG_ORDER_USER:-order_user}"
PG_ORDER_PASSWORD="${PG_ORDER_PASSWORD:-order_pass}"

# Export directory containing CSV files from export-hsqldb.sh
EXPORT_DIR="${EXPORT_DIR:-${PROJECT_ROOT}/migration/output/export}"

# Output directory for validation reports
OUTPUT_DIR="${PROJECT_ROOT}/migration/output"

# Java command — respects JAVA_HOME when set, otherwise relies on PATH
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

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

# Print a warning message to stderr
log_warn() {
    echo -e "${YELLOW}[WARN]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# Print an error message to stderr
log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# Extract host from a JDBC PostgreSQL URL
# Usage: extract_pg_host "jdbc:postgresql://localhost:5432/dbname"
extract_pg_host() {
    local url="$1"
    # Remove jdbc:postgresql:// prefix, then extract host before : or /
    local remainder="${url#jdbc:postgresql://}"
    echo "${remainder%%:*}"
}

# Extract port from a JDBC PostgreSQL URL
# Usage: extract_pg_port "jdbc:postgresql://localhost:5432/dbname"
extract_pg_port() {
    local url="$1"
    local remainder="${url#jdbc:postgresql://}"
    local host_port="${remainder%%/*}"
    if [[ "${host_port}" == *":"* ]]; then
        echo "${host_port##*:}"
    else
        echo "5432"
    fi
}

# Extract database name from a JDBC PostgreSQL URL
# Usage: extract_pg_db "jdbc:postgresql://localhost:5432/dbname"
extract_pg_db() {
    local url="$1"
    local remainder="${url#jdbc:postgresql://}"
    local db_part="${remainder#*/}"
    # Remove any query string parameters after ?
    echo "${db_part%%\?*}"
}

# ============================================================================
# Banner
# ============================================================================
echo ""
echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  JPetStore Migration — Data Integrity Gate ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""
echo "7-check data integrity validation gate for HSQLDB → PostgreSQL migration"
echo "This gate MUST pass ALL 7 checks before any service cutover proceeds."
echo ""

# ============================================================================
# Print Configuration Summary
# ============================================================================
log_info "Configuration:"
log_info "  HSQLDB URL:     ${HSQLDB_URL}"
log_info "  HSQLDB User:    ${HSQLDB_USER}"
log_info "  Account DB:     ${PG_ACCOUNT_URL} (user: ${PG_ACCOUNT_USER})"
log_info "  Catalog DB:     ${PG_CATALOG_URL} (user: ${PG_CATALOG_USER})"
log_info "  Order DB:       ${PG_ORDER_URL} (user: ${PG_ORDER_USER})"
log_info "  Export Dir:     ${EXPORT_DIR}"
log_info "  Project Root:   ${PROJECT_ROOT}"
echo ""

# ============================================================================
# Pre-flight Check 1: Java Runtime
# ============================================================================
log_info "Running pre-flight checks..."
echo ""

log_info "Check: Java runtime..."
if ! command -v "${JAVA_CMD}" &>/dev/null; then
    log_error "Java not found. Set JAVA_HOME or ensure 'java' is on PATH."
    log_error "Required: JDK 17 for running IntegrityValidator."
    exit 1
fi
JAVA_VERSION=$("${JAVA_CMD}" -version 2>&1 | head -1)
log_success "Java: ${JAVA_VERSION}"

# ============================================================================
# Pre-flight Check 2: Migration JAR
# ============================================================================
log_info "Check: Migration JAR..."
MIGRATION_JAR=""
for jar in "${PROJECT_ROOT}"/migration/target/migration-*.jar; do
    # Skip Maven-generated sources and javadoc JARs
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

# ============================================================================
# Pre-flight Check 3: Dependency JARs (JDBC drivers, SLF4J, etc.)
# ============================================================================
DEP_DIR="${PROJECT_ROOT}/migration/target/dependency"
log_info "Check: Dependency JARs..."

if [[ ! -d "${DEP_DIR}" ]] || [[ -z "$(ls "${DEP_DIR}"/*.jar 2>/dev/null || true)" ]]; then
    log_info "Dependency JARs not found. Copying via Maven dependency:copy-dependencies..."
    if ! (cd "${PROJECT_ROOT}" && ./mvnw dependency:copy-dependencies -B -q -pl migration 2>&1); then
        log_error "Failed to copy migration dependencies. Ensure Maven is configured correctly."
        exit 1
    fi
fi

# Verify PostgreSQL and HSQLDB drivers are present
MISSING_DEPS=0
if ! ls "${DEP_DIR}"/postgresql-*.jar 1>/dev/null 2>&1; then
    log_error "PostgreSQL driver JAR not found in ${DEP_DIR}."
    MISSING_DEPS=1
fi
if ! ls "${DEP_DIR}"/hsqldb-*.jar 1>/dev/null 2>&1; then
    log_error "HSQLDB driver JAR not found in ${DEP_DIR}."
    MISSING_DEPS=1
fi
if [[ "${MISSING_DEPS}" -eq 1 ]]; then
    log_error "Dependencies may not have been copied correctly."
    log_error "Run: cd ${PROJECT_ROOT} && ./mvnw dependency:copy-dependencies -B -pl migration"
    exit 1
fi
log_success "Dependency directory: ${DEP_DIR}"

# Build the classpath for Java invocation: migration JAR + all dependency JARs
CLASSPATH="${MIGRATION_JAR}:${DEP_DIR}/*"

# ============================================================================
# Pre-flight Check 4: Export Directory and CSV Baseline
# ============================================================================
log_info "Check: Export directory..."
if [[ ! -d "${EXPORT_DIR}" ]]; then
    log_error "Export directory not found: ${EXPORT_DIR}"
    log_error "Run export-hsqldb.sh first to export HSQLDB data."
    exit 1
fi

# Count CSV files in the export directory
CSV_COUNT=$(find "${EXPORT_DIR}" -maxdepth 1 -name "*.csv" -type f 2>/dev/null | grep -c "" || true)
if [[ "${CSV_COUNT}" -eq 0 ]]; then
    log_warn "No CSV files found in ${EXPORT_DIR}."
    log_warn "The export may not have been run yet. IntegrityValidator will connect to HSQLDB directly."
else
    log_success "Export directory: ${EXPORT_DIR} (${CSV_COUNT} CSV files found)"
fi

# Check for baseline row counts manifest
if [[ -f "${EXPORT_DIR}/row-counts-baseline.txt" ]]; then
    log_success "Baseline manifest: ${EXPORT_DIR}/row-counts-baseline.txt"
else
    log_warn "Baseline manifest not found: ${EXPORT_DIR}/row-counts-baseline.txt"
    log_warn "IntegrityValidator will compare live HSQLDB counts directly."
fi

# ============================================================================
# Pre-flight Check 5: PostgreSQL Connectivity
# ============================================================================
log_info "Check: PostgreSQL connectivity..."

# Extract host/port/db from each JDBC URL for pg_isready checks
PG_ACCOUNT_HOST=$(extract_pg_host "${PG_ACCOUNT_URL}")
PG_ACCOUNT_PORT=$(extract_pg_port "${PG_ACCOUNT_URL}")
PG_ACCOUNT_DB=$(extract_pg_db "${PG_ACCOUNT_URL}")

PG_CATALOG_HOST=$(extract_pg_host "${PG_CATALOG_URL}")
PG_CATALOG_PORT=$(extract_pg_port "${PG_CATALOG_URL}")
PG_CATALOG_DB=$(extract_pg_db "${PG_CATALOG_URL}")

PG_ORDER_HOST=$(extract_pg_host "${PG_ORDER_URL}")
PG_ORDER_PORT=$(extract_pg_port "${PG_ORDER_URL}")
PG_ORDER_DB=$(extract_pg_db "${PG_ORDER_URL}")

PG_CONN_OK=true

# Check each PostgreSQL database. Use pg_isready if available, otherwise warn.
if command -v pg_isready &>/dev/null; then
    for db_label in "Account:${PG_ACCOUNT_HOST}:${PG_ACCOUNT_PORT}:${PG_ACCOUNT_DB}" \
                    "Catalog:${PG_CATALOG_HOST}:${PG_CATALOG_PORT}:${PG_CATALOG_DB}" \
                    "Order:${PG_ORDER_HOST}:${PG_ORDER_PORT}:${PG_ORDER_DB}"; do
        IFS=':' read -r label host port db <<< "${db_label}"
        if pg_isready -h "${host}" -p "${port}" -d "${db}" -t 5 &>/dev/null; then
            log_success "${label} DB (${db}@${host}:${port}): reachable"
        else
            log_error "${label} DB (${db}@${host}:${port}): NOT reachable"
            PG_CONN_OK=false
        fi
    done
else
    log_warn "pg_isready not found. Skipping PostgreSQL connectivity pre-check."
    log_warn "If databases are unreachable, IntegrityValidator will fail with connection errors."
fi

if [[ "${PG_CONN_OK}" != "true" ]]; then
    log_error "One or more PostgreSQL databases are not reachable."
    log_error "Ensure all 3 PostgreSQL databases are running and accessible."
    log_error "Required: jpetstore_account, jpetstore_catalog, jpetstore_order"
    exit 1
fi

echo ""
log_info "All pre-flight checks passed. Starting 7-check validation gate..."
echo ""

# ============================================================================
# Create Output Directory for Validation Reports
# ============================================================================
mkdir -p "${OUTPUT_DIR}"

# Generate timestamped report filename
TIMESTAMP=$(date '+%Y%m%d-%H%M%S')
REPORT_FILE="${OUTPUT_DIR}/validation-report-${TIMESTAMP}.txt"

# ============================================================================
# Execute the 7-Check Validation Gate via IntegrityValidator.java
# ============================================================================
#
# IntegrityValidator.main() accepts database connection parameters as JVM
# system properties (-D flags). It executes all 7 checks internally:
#
#   Check 1: Row Count Match          — HSQLDB vs PostgreSQL for all 13 tables
#   Check 2: Primary Key Uniqueness   — no duplicate PKs in any PG table
#   Check 3: Intra-Service FK         — catalog DB: product→category,
#                                        item→product, item→supplier
#   Check 4: Cross-Service Refs       — orders.userid→account.userid,
#                                        lineitem.itemid→item.itemid
#   Check 5: Sequence Safety          — order_id_seq > MAX(orderid) + 1000
#   Check 6: Column Completeness      — no columns dropped during migration
#   Check 7: Type Conversion          — decimals, dates, booleans, nulls
#
# The validator prints a structured report to stdout and exits with:
#   0 — all 7 checks passed
#   1 — one or more checks failed
#
# We capture stdout to both terminal (with colors added) and to a plain-text
# report file for operational review.
# ============================================================================

log_info "Invoking IntegrityValidator with the following databases:"
log_info "  Source:  HSQLDB   → ${HSQLDB_URL}"
log_info "  Target:  Account  → ${PG_ACCOUNT_URL}"
log_info "  Target:  Catalog  → ${PG_CATALOG_URL}"
log_info "  Target:  Order    → ${PG_ORDER_URL}"
echo ""

# Temporary file to capture raw validator output
RAW_OUTPUT=$(mktemp)
trap 'rm -f "${RAW_OUTPUT}"' EXIT

# Run the IntegrityValidator Java class using system properties
# The IntegrityValidator.main() reads all connection params from -D properties
VALIDATOR_EXIT_CODE=0
"${JAVA_CMD}" -cp "${CLASSPATH}" \
    -Dhsqldb.url="${HSQLDB_URL}" \
    -Dhsqldb.user="${HSQLDB_USER}" \
    -Dhsqldb.password="${HSQLDB_PASSWORD}" \
    -Daccount.db.url="${PG_ACCOUNT_URL}" \
    -Daccount.db.user="${PG_ACCOUNT_USER}" \
    -Daccount.db.password="${PG_ACCOUNT_PASSWORD}" \
    -Dcatalog.db.url="${PG_CATALOG_URL}" \
    -Dcatalog.db.user="${PG_CATALOG_USER}" \
    -Dcatalog.db.password="${PG_CATALOG_PASSWORD}" \
    -Dorder.db.url="${PG_ORDER_URL}" \
    -Dorder.db.user="${PG_ORDER_USER}" \
    -Dorder.db.password="${PG_ORDER_PASSWORD}" \
    com.jpetstore.migration.IntegrityValidator \
    > "${RAW_OUTPUT}" 2>&1 || VALIDATOR_EXIT_CODE=$?

# ============================================================================
# Process and Display Validation Results
# ============================================================================

# Save the raw output as the plain-text report file
cp "${RAW_OUTPUT}" "${REPORT_FILE}"

# Display the report with color coding
echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  DATA INTEGRITY VALIDATION REPORT          ${NC}"
echo -e "${BOLD}============================================${NC}"
echo -e "  Report file: ${REPORT_FILE}"
echo -e "  Timestamp:   $(date '+%Y-%m-%d %H:%M:%S')"
echo -e "${BOLD}============================================${NC}"
echo ""

# Read and colorize the raw output line by line
while IFS= read -r line; do
    if echo "${line}" | grep -q '^\[PASS\]'; then
        echo -e "${GREEN}${line}${NC}"
    elif echo "${line}" | grep -q '^\[FAIL\]'; then
        echo -e "${RED}${line}${NC}"
    elif echo "${line}" | grep -q 'OVERALL RESULT: PASS'; then
        echo -e "${GREEN}${BOLD}${line}${NC}"
    elif echo "${line}" | grep -q 'OVERALL RESULT: FAIL'; then
        echo -e "${RED}${BOLD}${line}${NC}"
    elif echo "${line}" | grep -q '— PASS'; then
        echo -e "${GREEN}${line}${NC}"
    elif echo "${line}" | grep -q '— FAIL'; then
        echo -e "${RED}${line}${NC}"
    elif echo "${line}" | grep -q 'ERROR:'; then
        echo -e "${RED}${line}${NC}"
    elif echo "${line}" | grep -q '===='; then
        echo -e "${BOLD}${line}${NC}"
    else
        echo "${line}"
    fi
done < "${RAW_OUTPUT}"

echo ""

# ============================================================================
# Final Gate Decision and Exit Code
# ============================================================================

# Count individual check results from the raw output for the summary
PASS_COUNT=$(grep -c '^\[PASS\]' "${RAW_OUTPUT}" 2>/dev/null || true)
FAIL_COUNT=$(grep -c '^\[FAIL\]' "${RAW_OUTPUT}" 2>/dev/null || true)

echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  VALIDATION GATE SUMMARY                   ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""

# Produce a structured check-by-check summary
CHECK_NAMES=(
    "Row Count Match"
    "Primary Key Uniqueness"
    "Intra-Service FK"
    "Cross-Service Refs"
    "Sequence Safety"
    "Column Completeness"
    "Type Conversion"
)

for i in "${!CHECK_NAMES[@]}"; do
    CHECK_NUM=$((i + 1))
    CHECK_NAME="${CHECK_NAMES[$i]}"
    # Check if this specific check passed or failed in the raw output
    if grep -q "^\[PASS\] Check ${CHECK_NUM}:" "${RAW_OUTPUT}" 2>/dev/null; then
        printf "  Check %d: %-28s ${GREEN}[PASS]${NC}\n" "${CHECK_NUM}" "${CHECK_NAME}"
    elif grep -q "^\[FAIL\] Check ${CHECK_NUM}:" "${RAW_OUTPUT}" 2>/dev/null; then
        printf "  Check %d: %-28s ${RED}[FAIL]${NC}\n" "${CHECK_NUM}" "${CHECK_NAME}"
    else
        # Fallback: if output format doesn't match expected pattern,
        # derive from overall exit code for safety
        if [[ "${VALIDATOR_EXIT_CODE}" -eq 0 ]]; then
            printf "  Check %d: %-28s ${GREEN}[PASS]${NC}\n" "${CHECK_NUM}" "${CHECK_NAME}"
        else
            printf "  Check %d: %-28s ${YELLOW}[UNKNOWN]${NC}\n" "${CHECK_NUM}" "${CHECK_NAME}"
        fi
    fi
done

echo ""
echo -e "  Checks passed: ${PASS_COUNT:-0}"
echo -e "  Checks failed: ${FAIL_COUNT:-0}"
echo ""

if [[ "${VALIDATOR_EXIT_CODE}" -eq 0 ]]; then
    echo -e "${GREEN}${BOLD}  OVERALL: PASS${NC}"
    echo ""
    echo -e "${GREEN}  ✅ All 7 validation checks PASSED.${NC}"
    echo -e "${GREEN}  Migration is safe to proceed with service cutover.${NC}"
    echo ""
    echo "  Report saved to: ${REPORT_FILE}"
    echo ""
    echo "  Next steps:"
    echo "    1. Review the detailed report above or in: ${REPORT_FILE}"
    echo "    2. Confirm 48-hour observation window plan is in place"
    echo "    3. Switch the routing flag for the next service cutover"
    echo ""
    exit 0
else
    echo -e "${RED}${BOLD}  OVERALL: FAIL${NC}"
    echo ""
    echo -e "${RED}  ❌ Validation FAILED. DO NOT proceed with service cutover.${NC}"
    echo -e "${RED}  Review the report above and fix all failing checks before retrying.${NC}"
    echo ""
    echo "  Report saved to: ${REPORT_FILE}"
    echo ""
    echo "  Troubleshooting:"
    echo "    - Check 1 failures: Re-run export-hsqldb.sh and load-data.sh"
    echo "    - Check 2 failures: Investigate duplicate PK rows in PostgreSQL"
    echo "    - Check 3 failures: Verify FK load order in load-data.sh"
    echo "    - Check 4 failures: Verify cross-service data consistency"
    echo "    - Check 5 failures: Re-run load-data.sh to reset sequences"
    echo "    - Check 6 failures: Verify Liquibase schema matches HSQLDB columns"
    echo "    - Check 7 failures: Investigate data type conversion in DataLoader"
    echo ""
    exit 1
fi
