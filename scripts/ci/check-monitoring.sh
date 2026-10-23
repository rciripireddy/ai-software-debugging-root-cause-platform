#!/bin/sh
# Validates the monitoring configuration with the real tools, using the images docker-compose.yml
# pins (see docs/ci.md):
#   1. promtool checks prometheus.yml and the alert rules it loads;
#   2. Prometheus and Grafana are started on their own (no application services) under a separate
#      Compose project, and their APIs confirm that every alert rule and scrape job is loaded, the
#      provisioned dashboard exists, and Grafana's provisioned datasource reaches Prometheus.
# The temporary project and its volumes are removed afterwards; other projects are not touched.
#
# Usage: scripts/ci/check-monitoring.sh <env-file>
set -eu
# Git Bash on Windows would otherwise rewrite container paths such as /etc/prometheus/... into
# Windows paths. No effect elsewhere.
export MSYS2_ARG_CONV_EXCL=/etc/

env_file=${1:?usage: check-monitoring.sh <env-file>}
project=rcp-ci-monitoring
compose() {
    docker compose -p "$project" --env-file "$env_file" -f docker-compose.yml "$@"
}
port() {
    grep -E "^$1=" "$env_file" | cut -d= -f2
}
prometheus=http://127.0.0.1:$(port PROMETHEUS_PORT)
grafana=http://127.0.0.1:$(port GRAFANA_PORT)

failures=0
fail() {
    echo "FAIL: $*"
    failures=$((failures + 1))
}
expect() {
    # expect <description> <url> <text the response must contain>
    # Retried for up to 60 s: Grafana reports healthy shortly before it finishes provisioning.
    attempt=0
    while [ "$attempt" -lt 30 ]; do
        if curl -fsS --max-time 10 "$2" 2>/dev/null | grep -qF -- "$3"; then
            echo "ok: $1"
            return
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    fail "$1 ($2 does not contain $3)"
}

echo "promtool: prometheus.yml and alert rules"
compose run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml

cleanup() {
    compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting Prometheus and Grafana (project $project)"
if ! compose up -d --wait --wait-timeout 180 grafana; then
    compose logs --no-color --tail 50
    exit 1
fi

for alert in $(grep -oE -- '- alert: *[A-Za-z]+' monitoring/prometheus/alerts.yml | sed -E 's/.*: *//'); do
    expect "alert rule $alert loaded" "$prometheus/api/v1/rules" "\"name\":\"$alert\""
done
for job in $(grep -oE -- '- job_name: *[a-z-]+' monitoring/prometheus/prometheus.yml | sed -E 's/.*: *//'); do
    expect "scrape job $job configured" "$prometheus/api/v1/targets?state=any" "\"scrapePool\":\"$job\""
done
expect "Grafana dashboard provisioned" "$grafana/api/dashboards/uid/root-cause-platform" '"provisioned":true'
expect "Grafana datasource reaches Prometheus" "$grafana/api/datasources/uid/prometheus/health" '"status":"OK"'

if [ "$failures" -gt 0 ]; then
    compose logs --no-color --tail 50
    echo "$failures monitoring check(s) failed."
    exit 1
fi
echo "Monitoring checks passed."
