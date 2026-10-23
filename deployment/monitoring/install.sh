#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then echo 'Run with sudo.' >&2; exit 1; fi
cd "$(dirname "$0")"
: "${MONITOR_BUILD:?Set MONITOR_BUILD to the reviewed commit}"
test -s /etc/yumpoo-monitor/secrets.json
test -s /etc/yumpoo-monitor/prometheus.yml
test -s /etc/yumpoo-monitor/alert-token
test -s /etc/yumpoo-monitor/grafana-password
install -d -m 750 /var/lib/yumpoo-monitor
for component in hub discovery prometheus alertmanager grafana; do install -d -m 750 "/var/lib/yumpoo-monitor/$component"; done
chown 10001:10001 /var/lib/yumpoo-monitor/hub /var/lib/yumpoo-monitor/discovery
chmod 755 /var/lib/yumpoo-monitor/discovery
chown 65534:65534 /var/lib/yumpoo-monitor/prometheus /var/lib/yumpoo-monitor/alertmanager
chown 472:472 /var/lib/yumpoo-monitor/grafana
chmod 755 /etc/yumpoo-monitor
chown 10001:10001 /etc/yumpoo-monitor/secrets.json
chmod 600 /etc/yumpoo-monitor/secrets.json
chown 472:472 /etc/yumpoo-monitor/grafana-password
chmod 600 /etc/yumpoo-monitor/grafana-password
chown 65534:65534 /etc/yumpoo-monitor/alert-token
chmod 600 /etc/yumpoo-monitor/alert-token
chmod 644 /etc/yumpoo-monitor/prometheus.yml
compose=(docker compose)
if [ -n "${MONITOR_OFFLINE_DIR:-}" ]; then
  python3 ./load-offline.py "$MONITOR_OFFLINE_DIR" "$MONITOR_BUILD"
  compose+=( -f compose.yml -f "$MONITOR_OFFLINE_DIR/compose.offline.yml" )
else
  "${compose[@]}" pull --ignore-buildable
  "${compose[@]}" build hub
fi
"${compose[@]}" config --quiet
"${compose[@]}" run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
"${compose[@]}" run --rm --no-deps --entrypoint amtool alertmanager check-config /etc/alertmanager.yml
"${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 120
curl --fail --silent http://127.0.0.1:8788/healthz
curl --fail --silent http://127.0.0.1:9090/-/ready
curl --fail --silent http://127.0.0.1:9093/-/ready
curl --fail --silent http://127.0.0.1:3000/api/health
