#!/bin/sh
# Writes a Compose environment file with throwaway values for CI (and for running the CI checks
# locally without touching .env). Every secret is random and generated per run; none is a real
# credential, and none is printed. On GitHub Actions each value is also registered as a mask, so
# it would appear as *** if anything did print it.
#
# Usage: scripts/ci/generate-env.sh <output-file>
# Host ports can be overridden for a local run next to the development stack, for example:
#   FRONTEND_PORT=3100 PROMETHEUS_PORT=9190 GRAFANA_PORT=3101 scripts/ci/generate-env.sh /tmp/ci.env
set -eu

out=${1:?usage: generate-env.sh <output-file>}

# 48 random bytes as URL-safe base64 (64 characters): above the services' 32-byte minimum for
# keys and tokens, and within the 12-72 byte password policy.
secret() {
    openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_'
}

postgres_password=$(secret)
jwt_secret=$(secret)
service_token=$(secret)
api_key=$(secret)
admin_password=$(secret)
viewer_password=$(secret)
grafana_password=$(secret)

if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    for value in "$postgres_password" "$jwt_secret" "$service_token" "$api_key" \
        "$admin_password" "$viewer_password" "$grafana_password"; do
        echo "::add-mask::$value"
    done
fi

umask 077
cat > "$out" <<EOF
POSTGRES_DB=rootcause
POSTGRES_USER=rootcause
POSTGRES_PASSWORD=$postgres_password
KAFKA_CLUSTER_ID=5L6g3nShT-eMCtK--X86sw
FRONTEND_PORT=${FRONTEND_PORT:-3000}
PROMETHEUS_PORT=${PROMETHEUS_PORT:-9090}
GRAFANA_PORT=${GRAFANA_PORT:-3001}
APP_ENVIRONMENT=ci
AUTH_JWT_SECRET=$jwt_secret
INTERNAL_SERVICE_TOKEN=$service_token
INGESTION_API_KEY=$api_key
AUTH_BOOTSTRAP_ADMIN_USERNAME=ci-admin
AUTH_BOOTSTRAP_ADMIN_PASSWORD=$admin_password
AUTH_BOOTSTRAP_VIEWER_USERNAME=ci-viewer
AUTH_BOOTSTRAP_VIEWER_PASSWORD=$viewer_password
GRAFANA_ADMIN_PASSWORD=$grafana_password
EOF
echo "Wrote CI-only environment to $out (values not shown)."
