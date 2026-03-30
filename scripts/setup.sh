#!/usr/bin/env bash
# scripts/setup.sh — one-click local setup via Docker
#
# Usage:
#   chmod +x scripts/setup.sh
#   ./scripts/setup.sh
#
# What it does:
#   1. Checks prerequisites (Docker, Docker Compose plugin)
#   2. Creates .env from .env.docker.example if not already present,
#      auto-generating secure random values for JWT_SECRET and ENCRYPTION_KEY
#   3. Builds images and starts all services (PostgreSQL + backend + dashboard)
#   4. Waits for the backend health check to pass
#   5. Prints the URLs

set -euo pipefail

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[info]${RESET}  $*"; }
success() { echo -e "${GREEN}[ok]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[warn]${RESET}  $*"; }
error()   { echo -e "${RED}[error]${RESET} $*" >&2; }
die()     { error "$*"; exit 1; }

# ── Resolve repo root (works regardless of where the script is called from) ───
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

echo ""
echo -e "${BOLD}MasonXPay — Local Setup${RESET}"
echo "─────────────────────────────────────────────"

# ── 1. Prerequisites ──────────────────────────────────────────────────────────
info "Checking prerequisites..."

command -v docker &>/dev/null || die "Docker is not installed. https://docs.docker.com/get-docker/"

# Docker Compose v2 plugin (docker compose) vs legacy standalone (docker-compose)
if docker compose version &>/dev/null 2>&1; then
    COMPOSE="docker compose"
elif command -v docker-compose &>/dev/null; then
    COMPOSE="docker-compose"
else
    die "Docker Compose not found. Install the Docker Compose plugin: https://docs.docker.com/compose/install/"
fi

docker info &>/dev/null || die "Docker daemon is not running. Please start Docker and retry."

success "Docker $(docker --version | awk '{print $3}' | tr -d ',')"
success "Compose $($COMPOSE version --short 2>/dev/null || $COMPOSE version | head -1)"

# ── 2. .env setup ─────────────────────────────────────────────────────────────
info "Checking .env..."

if [ -f "$ROOT/.env" ]; then
    warn ".env already exists — skipping generation (delete it to regenerate)"
else
    if [ ! -f "$ROOT/.env.docker.example" ]; then
        die ".env.docker.example not found. Are you in the right directory?"
    fi

    cp "$ROOT/.env.docker.example" "$ROOT/.env"

    # Auto-generate secure random secrets so the user doesn't ship placeholder values
    if command -v openssl &>/dev/null; then
        JWT_SECRET=$(openssl rand -base64 32)
        ENCRYPTION_KEY=$(openssl rand -base64 32)

        # Replace placeholder values in the copied .env
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s|JWT_SECRET=.*|JWT_SECRET=${JWT_SECRET}|" "$ROOT/.env"
            sed -i '' "s|ENCRYPTION_KEY=.*|ENCRYPTION_KEY=${ENCRYPTION_KEY}|" "$ROOT/.env"
        else
            sed -i "s|JWT_SECRET=.*|JWT_SECRET=${JWT_SECRET}|" "$ROOT/.env"
            sed -i "s|ENCRYPTION_KEY=.*|ENCRYPTION_KEY=${ENCRYPTION_KEY}|" "$ROOT/.env"
        fi
        success ".env created with auto-generated JWT_SECRET and ENCRYPTION_KEY"
    else
        warn ".env created from example — openssl not found, please set JWT_SECRET and ENCRYPTION_KEY manually"
    fi

    echo ""
    warn "Optional: edit .env to add Stripe keys or SMTP credentials before continuing."
    echo -e "  ${CYAN}STRIPE_SECRET_KEY${RESET}   — enables Stripe payment processing (sk_test_...)"
    echo -e "  ${CYAN}MAIL_USERNAME${RESET}        — enables team invite emails"
    echo ""
    read -r -p "Press Enter to continue, or Ctrl+C to edit .env first... "
fi

# ── 3. Build + start ──────────────────────────────────────────────────────────
echo ""
info "Building images and starting services (first run takes ~10-15 min)..."
info "You can follow the full build log with:"
echo ""
echo -e "   ${CYAN}docker compose logs -f${RESET}"
echo ""

$COMPOSE -f "$ROOT/docker-compose.yml" up -d --build

# ── 4. Wait for backend health ────────────────────────────────────────────────
echo ""
info "Waiting for backend to be healthy..."

MAX_WAIT=180  # seconds
INTERVAL=5
elapsed=0
while true; do
    STATUS=$($COMPOSE -f "$ROOT/docker-compose.yml" ps --format json backend 2>/dev/null \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null \
        || echo "")

    # Fallback: try a direct HTTP probe if the JSON parse fails
    if [ -z "$STATUS" ]; then
        if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
            STATUS="healthy"
        fi
    fi

    if [ "$STATUS" = "healthy" ]; then
        break
    fi

    if [ "$elapsed" -ge "$MAX_WAIT" ]; then
        echo ""
        warn "Backend health check timed out after ${MAX_WAIT}s."
        warn "The stack may still be starting. Check logs with:"
        echo ""
        echo -e "   ${CYAN}docker compose logs -f backend${RESET}"
        echo ""
        break
    fi

    printf "."
    sleep "$INTERVAL"
    elapsed=$((elapsed + INTERVAL))
done

# ── 5. Done ───────────────────────────────────────────────────────────────────
echo ""
echo "─────────────────────────────────────────────"
success "Stack is up!"
echo ""
echo -e "  ${BOLD}Dashboard${RESET}   http://localhost:3000"
echo -e "  ${BOLD}API${RESET}         http://localhost:8080"
echo ""
echo -e "Useful commands:"
echo -e "  ${CYAN}docker compose logs -f${RESET}            stream all logs"
echo -e "  ${CYAN}docker compose logs -f backend${RESET}    backend logs only"
echo -e "  ${CYAN}docker compose down${RESET}               stop and remove containers"
echo -e "  ${CYAN}docker compose down -v${RESET}            stop and wipe the database"
echo ""
