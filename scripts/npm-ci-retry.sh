#!/usr/bin/env bash
set -euo pipefail

install_log="$(mktemp)"
trap 'rm -f "$install_log"' EXIT

for attempt in 1 2 3; do
  echo "npm ci attempt $attempt/3"
  if npm ci --prefer-offline --no-audit --no-fund \
    --fetch-retries=5 \
    --fetch-retry-factor=2 \
    --fetch-retry-mintimeout=10000 \
    --fetch-retry-maxtimeout=120000 >"$install_log" 2>&1; then
    cat "$install_log"
    exit 0
  else
    status=$?
  fi

  cat "$install_log"
  if (( attempt == 3 )) || ! grep -Eiq 'ECONNRESET|ETIMEDOUT|EAI_AGAIN|ECONNREFUSED|ENOTFOUND|socket hang up|network|fetch failed' "$install_log"; then
    exit "$status"
  fi

  echo "::warning title=Retrying npm dependency download::Retrying npm ci after a network error"
  sleep "$((attempt * 10))"
done
