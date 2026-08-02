#!/usr/bin/env bash
# scripts/setup.sh - one-command local setup via Docker Compose

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[info]${RESET}  $*"; }
success() { echo -e "${GREEN}[ok]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[warn]${RESET}  $*"; }
error()   { echo -e "${RED}[error]${RESET} $*" >&2; }
die()     { error "$*"; exit 1; }

usage() {
    cat <<'EOF'
Usage: ./scripts/setup.sh [options]

Options:
  --profile PROFILE  Stack profile: default, ai, rail, virtual-account, or infra
                     (default: default)
  -y, --yes          Skip the optional environment-edit prompt
  -h, --help         Show this help

Examples:
  ./scripts/setup.sh
  ./scripts/setup.sh --profile ai
  ./scripts/setup.sh --profile infra --yes
EOF
}

PROFILE="default"
ASSUME_YES=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --profile)
            [ "$#" -ge 2 ] || die "--profile requires a value"
            PROFILE="$2"
            shift 2
            ;;
        -y|--yes)
            ASSUME_YES=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "Unknown option: $1"
            ;;
    esac
done

case "$PROFILE" in
    default|ai|rail|virtual-account|infra) ;;
    *) die "Unknown profile '$PROFILE'. Use default, ai, rail, virtual-account, or infra." ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$ROOT/.env"
cd "$ROOT"

COMPOSE_DISPLAY="docker compose"
if [ "$PROFILE" != "default" ]; then
    COMPOSE_DISPLAY="docker compose --profile $PROFILE"
fi

compose() {
    if [ "$PROFILE" = "default" ]; then
        docker compose "$@"
    else
        docker compose --profile "$PROFILE" "$@"
    fi
}

env_value() {
    local key="$1"
    local line
    line=$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)
    printf '%s' "${line#*=}"
}

set_env_value() {
    local key="$1"
    local value="$2"

    if grep -q -E "^${key}=" "$ENV_FILE"; then
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
        else
            sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
        fi
    else
        printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
    fi
}

random_secret() {
    openssl rand -base64 32 | tr -d '\n'
}

echo ""
echo -e "${BOLD}MasonXPay Local Setup${RESET}"
echo "---------------------------------------------"
info "Selected profile: $PROFILE"

info "Checking prerequisites..."
command -v docker &>/dev/null || die "Docker is not installed. https://docs.docker.com/get-docker/"
docker compose version &>/dev/null 2>&1 \
    || die "Docker Compose v2 is required. https://docs.docker.com/compose/install/"
docker info &>/dev/null || die "Docker daemon is not running. Start Docker and retry."

success "Docker $(docker --version | awk '{print $3}' | tr -d ',')"
success "Compose $(docker compose version --short)"

info "Checking .env..."
if [ -f "$ENV_FILE" ]; then
    warn ".env already exists; preserving its configured values"
else
    [ -f "$ROOT/.env.docker.example" ] \
        || die ".env.docker.example not found. Are you in the repository root?"
    command -v openssl &>/dev/null \
        || die "OpenSSL is required to generate local secrets for a new .env file."

    cp "$ROOT/.env.docker.example" "$ENV_FILE"
    set_env_value "JWT_SECRET" "$(random_secret)"
    set_env_value "ENCRYPTION_KEY" "$(random_secret)"
    set_env_value "AI_SERVICE_AUTH_TOKEN" "$(random_secret)"
    success ".env created with generated JWT, encryption, and AI service secrets"

    if [ "$ASSUME_YES" = false ] && [ -t 0 ]; then
        echo ""
        warn "Optional: edit .env now to add provider or SMTP credentials."
        read -r -p "Press Enter to continue, or Ctrl+C to stop... "
    fi
fi

JWT_SECRET_VALUE=$(env_value "JWT_SECRET")
ENCRYPTION_KEY_VALUE=$(env_value "ENCRYPTION_KEY")
if [ -z "$JWT_SECRET_VALUE" ] || [[ "$JWT_SECRET_VALUE" == change-me* ]]; then
    warn "JWT_SECRET is empty or still uses a placeholder value in .env"
fi
if [ -z "$ENCRYPTION_KEY_VALUE" ]; then
    warn "ENCRYPTION_KEY is empty; stored connector credentials will not work"
fi

# Profiles start their corresponding integrations for this Compose deployment.
# Existing .env feature choices remain untouched.
case "$PROFILE" in
    ai)
        if [ -z "$(env_value "AI_SERVICE_AUTH_TOKEN")" ]; then
            command -v openssl &>/dev/null \
                || die "OpenSSL is required to generate AI_SERVICE_AUTH_TOKEN."
            set_env_value "AI_SERVICE_AUTH_TOKEN" "$(random_secret)"
            success "Generated AI_SERVICE_AUTH_TOKEN in .env"
        fi
        export AI_ASSISTANT_ENABLED=true
        export RAG_REQUIRE_AUTH=true
        ;;
    rail)
        export RAIL_ENABLED=true
        ;;
    infra)
        if [ -z "$(env_value "AI_SERVICE_AUTH_TOKEN")" ]; then
            command -v openssl &>/dev/null \
                || die "OpenSSL is required to generate AI_SERVICE_AUTH_TOKEN."
            set_env_value "AI_SERVICE_AUTH_TOKEN" "$(random_secret)"
            success "Generated AI_SERVICE_AUTH_TOKEN in .env"
        fi
        export AI_ASSISTANT_ENABLED=true
        export RAG_REQUIRE_AUTH=true
        export RAIL_ENABLED=true
        export KAFKA_OUTBOX_ENABLED=true
        export KAFKA_WEBHOOK_CONSUMER_ENABLED=true
        export KAFKA_PAYMENT_PROJECTION_ENABLED=true
        export WEBHOOK_OUTBOX_POLLER_ENABLED=false
        export REDIS_HOT_PATH_ENABLED=true
        export REDIS_RATE_LIMIT_ENABLED=true
        export REDIS_IDEMPOTENCY_CACHE_ENABLED=true
        export REDIS_PROVIDER_HEALTH_CACHE_ENABLED=true
        export REDIS_HEALTH_ENABLED=true
        ;;
esac

compose config --quiet \
    || die "Docker Compose configuration is invalid."

echo ""
info "Building images and starting the $PROFILE stack..."
info "First-time builds can take 10-15 minutes."

EXPECTED_SERVICES=(postgres gateway-service dashboard)
case "$PROFILE" in
    ai)
        EXPECTED_SERVICES+=(qdrant ai-service)
        ;;
    rail)
        EXPECTED_SERVICES+=(kafka rail-service rail-simulator)
        ;;
    virtual-account)
        EXPECTED_SERVICES+=(kafka virtual-account)
        ;;
    infra)
        EXPECTED_SERVICES+=(postgres-exporter kafka redis qdrant ai-service prometheus grafana rail-service rail-simulator virtual-account)
        ;;
esac

if ! compose up --build --wait --wait-timeout 300; then
    echo ""
    error "The $PROFILE stack did not become ready."
    compose ps || true
    echo ""
    warn "Recent service logs:"
    compose logs --tail 80 "${EXPECTED_SERVICES[@]}" || true
    die "Setup failed. Review the logs above, then rerun the script."
fi

echo ""
echo "---------------------------------------------"
success "$PROFILE stack is ready"
echo ""
echo -e "  ${BOLD}Dashboard${RESET}           http://localhost:3000"
echo -e "  ${BOLD}Gateway API${RESET}         http://localhost:8080"

case "$PROFILE" in
    ai)
        echo -e "  ${BOLD}AI service${RESET}          http://localhost:8090"
        echo -e "  ${BOLD}Qdrant${RESET}              http://localhost:6333"
        ;;
    rail)
        echo -e "  ${BOLD}Rail service${RESET}         http://localhost:8081"
        echo -e "  ${BOLD}Rail simulator${RESET}       http://localhost:9099"
        ;;
    virtual-account)
        echo -e "  ${BOLD}Virtual accounts${RESET}     http://localhost:8086"
        ;;
    infra)
        echo -e "  ${BOLD}Rail service${RESET}         http://localhost:8081"
        echo -e "  ${BOLD}Virtual accounts${RESET}     http://localhost:8086"
        echo -e "  ${BOLD}AI service${RESET}          http://localhost:8090"
        echo -e "  ${BOLD}Prometheus${RESET}          http://localhost:9090"
        echo -e "  ${BOLD}Grafana${RESET}             http://localhost:3001"
        echo -e "  ${BOLD}Qdrant${RESET}              http://localhost:6333"
        ;;
esac

echo ""
echo "Useful commands:"
echo -e "  ${CYAN}${COMPOSE_DISPLAY} logs -f${RESET}"
echo -e "  ${CYAN}${COMPOSE_DISPLAY} logs -f gateway-service${RESET}"
echo -e "  ${CYAN}docker compose down${RESET}"
echo -e "  ${CYAN}docker compose down -v${RESET}  remove containers and ALL local persisted data"
echo ""
