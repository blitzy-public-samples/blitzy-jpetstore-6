#!/usr/bin/env bash
#
# Copyright 2010-2024 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
# provision-postgres.sh
#
# Provisions 3 PostgreSQL databases for the JPetStore microservices
# decomposition. Creates a shared service user with appropriate permissions
# and initializes PostgreSQL sequences required by the Order Service.
#
# Database-per-service pattern (3 bounded contexts):
#   - jpetstore_account : Account/User Management (account, profile, signon, bannerdata)
#   - jpetstore_catalog : Catalog/Inventory       (category, product, item, inventory, supplier)
#   - jpetstore_order   : Order/Cart              (orders, orderstatus, lineitem, sequence)
#
# Idempotent: Re-running this script does not fail if databases, users,
# or sequences already exist.
#
# Usage:
#   ./provision-postgres.sh
#
# Environment variables (all optional — defaults in parentheses):
#   PG_HOST           PostgreSQL host          (localhost)
#   PG_PORT           PostgreSQL port          (5432)
#   PG_ADMIN_USER     Admin superuser          (postgres)
#   PG_ADMIN_PASSWORD Admin password           (postgres)
#   ACCOUNT_DB        Account database name    (jpetstore_account)
#   CATALOG_DB        Catalog database name    (jpetstore_catalog)
#   ORDER_DB          Order database name      (jpetstore_order)
#   SERVICE_USER      Service user name        (jpetstore)
#   SERVICE_PASSWORD  Service user password    (jpetstore)
##############################################################################

# Strict error handling: exit on error, unset variables, and pipe failures
set -euo pipefail

# ============================================================================
# ANSI Color Constants
# ============================================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m' # No Color

# ============================================================================
# Helper Functions
# ============================================================================

# Print an informational message with a cyan prefix
log_info() {
    echo -e "${CYAN}[INFO]${NC} $*"
}

# Print a success message with a green check mark
log_success() {
    echo -e "${GREEN}  ✓${NC} $*"
}

# Print an error message with a red prefix
log_error() {
    echo -e "${RED}[ERROR]${NC} $*" >&2
}

# Execute a SQL command against the PostgreSQL server using admin credentials.
# Exports PGPASSWORD for the duration of the call.
# Arguments:
#   $1 - Database to connect to (e.g., "postgres")
#   $2 - SQL command string
#   $3 - (optional) Additional psql flags (e.g., "-tAc" for unformatted output)
run_admin_sql() {
    local db="$1"
    local sql="$2"
    local flags="${3:-"-c"}"

    PGPASSWORD="${PG_ADMIN_PASSWORD}" psql \
        -h "${PG_HOST}" \
        -p "${PG_PORT}" \
        -U "${PG_ADMIN_USER}" \
        -d "${db}" \
        "${flags}" "${sql}"
}

# ============================================================================
# Phase 1: Banner
# ============================================================================
echo ""
echo -e "${BOLD}============================================${NC}"
echo -e "${BOLD}  JPetStore Microservices — PostgreSQL      ${NC}"
echo -e "${BOLD}  Database Provisioning                     ${NC}"
echo -e "${BOLD}============================================${NC}"
echo ""

# ============================================================================
# Phase 2: Environment Variables and Defaults
# ============================================================================
# PostgreSQL admin connection parameters (configurable via environment)
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_ADMIN_USER="${PG_ADMIN_USER:-postgres}"
PG_ADMIN_PASSWORD="${PG_ADMIN_PASSWORD:-postgres}"

# Target database names — one per bounded context
ACCOUNT_DB="${ACCOUNT_DB:-jpetstore_account}"
CATALOG_DB="${CATALOG_DB:-jpetstore_catalog}"
ORDER_DB="${ORDER_DB:-jpetstore_order}"

# Shared service user credentials for all three databases
SERVICE_USER="${SERVICE_USER:-jpetstore}"
SERVICE_PASSWORD="${SERVICE_PASSWORD:-jpetstore}"

# Starting value for the order ID sequence — set high to avoid collision
# with migrated data. Seed data starts ordernum at 1000, so 2000 provides
# a generous buffer. The load-data.sh script will adjust this to
# MAX(orderid) + 1000 after data is loaded.
readonly ORDER_SEQ_START=2000

# Collect database names in an array for iteration
readonly DATABASES=("${ACCOUNT_DB}" "${CATALOG_DB}" "${ORDER_DB}")

log_info "Configuration:"
log_info "  Host:         ${PG_HOST}:${PG_PORT}"
log_info "  Admin User:   ${PG_ADMIN_USER}"
log_info "  Service User: ${SERVICE_USER}"
log_info "  Databases:    ${ACCOUNT_DB}, ${CATALOG_DB}, ${ORDER_DB}"
echo ""

# ============================================================================
# Phase 3: Pre-flight Checks
# ============================================================================
log_info "Running pre-flight checks..."

# Check 1: Verify psql client is installed and available on PATH
if ! command -v psql &>/dev/null; then
    log_error "psql not found on PATH. Install PostgreSQL client tools."
    log_error "  Ubuntu/Debian: sudo apt-get install -y postgresql-client"
    log_error "  macOS:         brew install libpq"
    exit 1
fi
log_success "psql client found: $(command -v psql)"

# Check 2: Verify psql version (informational)
PSQL_VERSION=$(psql --version 2>/dev/null | head -1 || echo "unknown")
log_success "psql version: ${PSQL_VERSION}"

# Check 3: Test connectivity to PostgreSQL server with admin credentials
if ! PGPASSWORD="${PG_ADMIN_PASSWORD}" psql \
    -h "${PG_HOST}" \
    -p "${PG_PORT}" \
    -U "${PG_ADMIN_USER}" \
    -d postgres \
    -c "SELECT 1;" &>/dev/null; then
    log_error "Cannot connect to PostgreSQL at ${PG_HOST}:${PG_PORT}"
    log_error "  Verify the server is running and credentials are correct."
    log_error "  Connection parameters:"
    log_error "    PG_HOST=${PG_HOST}"
    log_error "    PG_PORT=${PG_PORT}"
    log_error "    PG_ADMIN_USER=${PG_ADMIN_USER}"
    exit 1
fi
log_success "Connected to PostgreSQL at ${PG_HOST}:${PG_PORT}"

# Display the server version for operational context
PG_SERVER_VERSION=$(run_admin_sql "postgres" "SHOW server_version;" "-tAc" 2>/dev/null || echo "unknown")
log_success "PostgreSQL server version: ${PG_SERVER_VERSION}"
echo ""

# ============================================================================
# Phase 4: Create Service User (Idempotent)
# ============================================================================
log_info "Creating service user '${SERVICE_USER}' (if not exists)..."

# Use a PL/pgSQL DO block for fully idempotent role creation.
# CREATE ROLE IF NOT EXISTS is not available in all PostgreSQL versions,
# so the DO block approach is the most portable and reliable pattern.
run_admin_sql "postgres" "
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = '${SERVICE_USER}'
    ) THEN
        CREATE ROLE ${SERVICE_USER} WITH
            LOGIN
            PASSWORD '${SERVICE_PASSWORD}'
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            INHERIT
            NOREPLICATION
            CONNECTION LIMIT -1;
        RAISE NOTICE 'Role ${SERVICE_USER} created successfully';
    ELSE
        -- Ensure password is current even if role already exists
        ALTER ROLE ${SERVICE_USER} WITH PASSWORD '${SERVICE_PASSWORD}';
        RAISE NOTICE 'Role ${SERVICE_USER} already exists — password updated';
    END IF;
END
\$\$;
" &>/dev/null

log_success "Service user '${SERVICE_USER}' is ready"
echo ""

# ============================================================================
# Phase 5: Create Databases (Idempotent)
# ============================================================================
log_info "Creating databases..."

for DB_NAME in "${DATABASES[@]}"; do
    # Check if the database already exists by querying pg_database
    DB_EXISTS=$(run_admin_sql "postgres" \
        "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}';" \
        "-tAc" 2>/dev/null || echo "")

    if echo "${DB_EXISTS}" | grep -q "1"; then
        log_success "Database '${DB_NAME}' already exists — skipping creation"
    else
        log_info "Creating database '${DB_NAME}'..."

        # Create the database with UTF-8 encoding, owned by the service user.
        # Uses template0 to allow specifying locale/encoding independently.
        # LC_COLLATE and LC_CTYPE are set to C to avoid locale-dependent issues
        # in environments where en_US.UTF-8 may not be available (e.g., Docker
        # containers). The C locale provides deterministic, byte-order collation
        # which is safe for all data and portable across systems.
        run_admin_sql "postgres" \
            "CREATE DATABASE ${DB_NAME}
                OWNER ${SERVICE_USER}
                ENCODING 'UTF8'
                LC_COLLATE = 'C'
                LC_CTYPE = 'C'
                TEMPLATE template0;" &>/dev/null

        log_success "Database '${DB_NAME}' created successfully"
    fi
done
echo ""

# ============================================================================
# Phase 6: Grant Permissions
# ============================================================================
log_info "Granting permissions to '${SERVICE_USER}' on all databases..."

for DB_NAME in "${DATABASES[@]}"; do
    log_info "  Granting permissions on '${DB_NAME}'..."

    # Grant database-level privileges
    run_admin_sql "postgres" \
        "GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${SERVICE_USER};" &>/dev/null

    # Grant schema-level privileges. These must be run while connected to the
    # target database, not the postgres database.
    run_admin_sql "${DB_NAME}" \
        "GRANT ALL ON SCHEMA public TO ${SERVICE_USER};" &>/dev/null

    # Grant CREATE on schema public so the service user can run Liquibase
    # migrations that create tables, indexes, and sequences.
    run_admin_sql "${DB_NAME}" \
        "GRANT CREATE ON SCHEMA public TO ${SERVICE_USER};" &>/dev/null

    # Set default privileges so that any future tables, sequences, and functions
    # created by the admin user are also accessible to the service user.
    # This covers Liquibase migrations that may run as admin.
    run_admin_sql "${DB_NAME}" "
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT ALL ON TABLES TO ${SERVICE_USER};
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT ALL ON SEQUENCES TO ${SERVICE_USER};
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT ALL ON FUNCTIONS TO ${SERVICE_USER};
    " &>/dev/null

    log_success "Permissions granted on '${DB_NAME}'"
done
echo ""

# ============================================================================
# Phase 7: Create PostgreSQL Sequences for Order Service
# ============================================================================
# The original JPetStore monolith uses a shared 'sequence' table with a
# non-thread-safe read-then-update pattern (getNextId). This is replaced
# by native PostgreSQL sequences which are atomic and thread-safe.
#
# Per AAP Section 0.7.3:
#   "starting value = max_migrated_id + 1000"
# The seed data has ordernum starting at 1000. We set START WITH 2000 as
# a safe default. The load-data.sh script will adjust this to
# MAX(orderid) + 1000 after actual data is loaded.
#
# This sequence is referenced by the Order Service JPA entity:
#   @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
#   @SequenceGenerator(name = "order_seq", sequenceName = "order_id_seq")
# ============================================================================
log_info "Creating PostgreSQL sequences in '${ORDER_DB}'..."

# Create the order_id_seq sequence if it does not already exist.
# Using CREATE SEQUENCE IF NOT EXISTS for idempotency.
run_admin_sql "${ORDER_DB}" \
    "CREATE SEQUENCE IF NOT EXISTS order_id_seq START WITH ${ORDER_SEQ_START};" &>/dev/null

# Grant usage and select on the sequence to the service user so the
# application can call nextval('order_id_seq').
run_admin_sql "${ORDER_DB}" \
    "GRANT USAGE, SELECT ON SEQUENCE order_id_seq TO ${SERVICE_USER};" &>/dev/null

log_success "Sequence 'order_id_seq' is ready in '${ORDER_DB}' (starts at ${ORDER_SEQ_START})"
echo ""

# ============================================================================
# Phase 8: Verification
# ============================================================================
log_info "Verifying database provisioning..."

VERIFICATION_FAILED=0

# Verify each database exists in pg_database
for DB_NAME in "${DATABASES[@]}"; do
    DB_CHECK=$(run_admin_sql "postgres" \
        "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}';" \
        "-tAc" 2>/dev/null || echo "")

    if echo "${DB_CHECK}" | grep -q "1"; then
        log_success "${DB_NAME} exists"
    else
        log_error "${DB_NAME} MISSING — database not found"
        VERIFICATION_FAILED=1
    fi
done

# Verify the service user can connect to each database
for DB_NAME in "${DATABASES[@]}"; do
    if PGPASSWORD="${SERVICE_PASSWORD}" psql \
        -h "${PG_HOST}" \
        -p "${PG_PORT}" \
        -U "${SERVICE_USER}" \
        -d "${DB_NAME}" \
        -c "SELECT 1;" &>/dev/null; then
        log_success "Service user '${SERVICE_USER}' can connect to '${DB_NAME}'"
    else
        log_error "Service user '${SERVICE_USER}' CANNOT connect to '${DB_NAME}'"
        VERIFICATION_FAILED=1
    fi
done

# Verify the order_id_seq sequence exists in the order database
SEQ_CHECK=$(run_admin_sql "${ORDER_DB}" \
    "SELECT 1 FROM pg_sequences WHERE sequencename = 'order_id_seq';" \
    "-tAc" 2>/dev/null || echo "")

if echo "${SEQ_CHECK}" | grep -q "1"; then
    SEQ_START_VAL=$(run_admin_sql "${ORDER_DB}" \
        "SELECT start_value FROM pg_sequences WHERE sequencename = 'order_id_seq';" \
        "-tAc" 2>/dev/null || echo "unknown")
    log_success "Sequence 'order_id_seq' exists in '${ORDER_DB}' (start_value=${SEQ_START_VAL})"
else
    log_error "Sequence 'order_id_seq' MISSING in '${ORDER_DB}'"
    VERIFICATION_FAILED=1
fi

echo ""

# ============================================================================
# Phase 9: Summary and Exit
# ============================================================================
if [ "${VERIFICATION_FAILED}" -ne 0 ]; then
    echo -e "${RED}${BOLD}============================================${NC}"
    echo -e "${RED}${BOLD}  POSTGRESQL PROVISIONING FAILED            ${NC}"
    echo -e "${RED}${BOLD}============================================${NC}"
    echo ""
    log_error "One or more verification checks failed. Review the output above."
    exit 1
fi

echo -e "${GREEN}${BOLD}============================================${NC}"
echo -e "${GREEN}${BOLD}  POSTGRESQL PROVISIONING COMPLETE           ${NC}"
echo -e "${GREEN}${BOLD}============================================${NC}"
echo ""
echo -e "  Host:         ${BOLD}${PG_HOST}:${PG_PORT}${NC}"
echo -e "  Service User: ${BOLD}${SERVICE_USER}${NC}"
echo ""
echo -e "  ${BOLD}Databases:${NC}"
echo -e "    ${GREEN}✓${NC} ${ACCOUNT_DB}  (Account Service — account, profile, signon, bannerdata)"
echo -e "    ${GREEN}✓${NC} ${CATALOG_DB}  (Catalog Service — category, product, item, inventory, supplier)"
echo -e "    ${GREEN}✓${NC} ${ORDER_DB}    (Order Service  — orders, orderstatus, lineitem, sequence)"
echo ""
echo -e "  ${BOLD}Sequences:${NC}"
echo -e "    ${GREEN}✓${NC} order_id_seq in ${ORDER_DB} (starting at ${ORDER_SEQ_START})"
echo ""
echo -e "${BOLD}============================================${NC}"
echo ""
echo -e "${CYAN}Next steps:${NC}"
echo "  1. Run Liquibase migrations for each service to create table schemas"
echo "  2. Run export-hsqldb.sh to export data from the monolith HSQLDB"
echo "  3. Run load-data.sh to load data into PostgreSQL databases"
echo "  4. Run validate-integrity.sh to verify data integrity"
echo ""

exit 0
