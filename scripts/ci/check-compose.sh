#!/bin/sh
# Validates both Compose configurations with a CI-only environment file (see generate-env.sh):
#   - development: docker-compose.yml + docker-compose.override.yml (what `docker compose` loads
#     by default in the repository);
#   - production-style: docker-compose.yml alone (the override is left out explicitly).
# Also checks the port exposure decided in Phase 7: every published port is bound to 127.0.0.1,
# and the production-style file publishes only the dashboard, Prometheus and Grafana.
#
# Usage: scripts/ci/check-compose.sh <env-file>
set -eu

env_file=${1:?usage: check-compose.sh <env-file>}
failures=0
fail() {
    echo "FAIL: $*"
    failures=$((failures + 1))
}

check_ports() {
    label=$1
    expected=$2
    shift 2
    config=$(docker compose --env-file "$env_file" "$@" config)
    published=$(echo "$config" | grep -cE '^ +published: ' || true)
    loopback=$(echo "$config" | grep -cE '^ +host_ip: 127\.0\.0\.1$' || true)
    if [ "$published" -ne "$loopback" ]; then
        fail "$label: $((published - loopback)) published port(s) not bound to 127.0.0.1"
    fi
    if [ "$published" -ne "$expected" ]; then
        fail "$label: $published published ports, expected $expected"
    fi
    echo "$label: $published published ports, all on 127.0.0.1"
}

echo "Validating docker-compose.yml + docker-compose.override.yml (development)"
docker compose --env-file "$env_file" config --quiet
check_ports "development" 8

echo "Validating docker-compose.yml alone (production-style)"
docker compose --env-file "$env_file" -f docker-compose.yml config --quiet
check_ports "production-style" 3 -f docker-compose.yml
services=$(docker compose --env-file "$env_file" -f docker-compose.yml config --format json \
    | grep -oE '"published": *"[0-9]+"' | grep -oE '[0-9]+' | sort -n | tr '\n' ' ')
expected=$(grep -E '^(FRONTEND|PROMETHEUS|GRAFANA)_PORT=' "$env_file" | cut -d= -f2 | sort -n | tr '\n' ' ')
if [ "$services" != "$expected" ]; then
    fail "production-style: published ports are '$services', expected '$expected' (dashboard, Grafana, Prometheus)"
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures Compose check(s) failed."
    exit 1
fi
echo "Compose checks passed."
