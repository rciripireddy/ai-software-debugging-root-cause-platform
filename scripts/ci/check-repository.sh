#!/bin/sh
# Lightweight repository checks (run by CI, and locally from the repository root):
#   1. no local environment files, private keys or keystores are tracked by Git;
#   2. no tracked file contains a private key;
#   3. .env.example keeps every secret empty, and documents every variable Compose requires;
#   4. the configuration files the stack mounts or builds from exist.
# It catches obvious mistakes only; it is not a full secret scanner.
set -eu

failures=0
fail() {
    echo "FAIL: $*"
    failures=$((failures + 1))
}

# 1. Files that must never be committed. .env.example is the only environment file allowed.
tracked=$(git ls-files | grep -Ei \
    -e '(^|/)\.env(\..*)?$' \
    -e '\.(pem|key|p12|pfx|jks|keystore|ppk|kdbx)$' \
    -e '(^|/)id_(rsa|dsa|ecdsa|ed25519)(\.pub)?$' \
    -e '(^|/)(credentials|secrets?)\.(json|ya?ml|txt|properties)$' \
    | grep -vx '\.env\.example' || true)
if [ -n "$tracked" ]; then
    fail "secret or local environment files are tracked by Git:"
    echo "$tracked" | sed 's/^/  /'
fi

# 2. Private key blocks in any tracked text file (file names only, never the content).
keys=$(git grep -lIE -- '-----BEGIN ([A-Z]+ )*PRIVATE KEY-----' || true)
if [ -n "$keys" ]; then
    fail "tracked files contain a private key:"
    echo "$keys" | sed 's/^/  /'
fi

# 3. .env.example: secrets stay empty (each developer generates their own), and every variable
#    docker-compose.yml requires with ${NAME:?...} is documented there.
for name in AUTH_JWT_SECRET INTERNAL_SERVICE_TOKEN INGESTION_API_KEY \
    AUTH_BOOTSTRAP_ADMIN_PASSWORD AUTH_BOOTSTRAP_VIEWER_PASSWORD GRAFANA_ADMIN_PASSWORD; do
    if grep -Eq "^$name=.+" .env.example; then
        fail ".env.example sets a value for $name; secrets must stay empty there"
    fi
done
for name in $(grep -oE '\$\{[A-Z0-9_]+:\?' docker-compose.yml | sed -E 's/^\$\{//; s/:\?$//' | sort -u); do
    if ! grep -Eq "^$name=" .env.example; then
        fail "docker-compose.yml requires $name, but .env.example does not list it"
    fi
done

# 4. Files the Compose stack and the image builds depend on.
for file in \
    docker-compose.yml docker-compose.override.yml .env.example \
    database/init/01-create-schemas.sql \
    backend/ingestion-service/Dockerfile backend/ingestion-service/mvnw \
    backend/incident-service/Dockerfile backend/incident-service/mvnw \
    analysis-service/Dockerfile analysis-service/requirements.lock analysis-service/requirements-dev.lock \
    frontend/Dockerfile frontend/nginx.conf frontend/security-headers.conf frontend/package-lock.json \
    monitoring/prometheus/prometheus.yml monitoring/prometheus/alerts.yml \
    monitoring/grafana/provisioning/datasources/prometheus.yml \
    monitoring/grafana/provisioning/dashboards/dashboards.yml \
    monitoring/grafana/dashboards/root-cause-platform.json; do
    [ -f "$file" ] || fail "required file is missing: $file"
done

if [ "$failures" -gt 0 ]; then
    echo "$failures repository check(s) failed."
    exit 1
fi
echo "Repository checks passed: no tracked secrets or keys, .env.example complete, required files present."
