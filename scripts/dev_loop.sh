#!/usr/bin/env bash
set -euo pipefail

# Local developer loop:
# 1) build
# 2) run instrumentation smoke test on connected device/emulator
# 3) build release

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f secrets.properties ]]; then
  cat > secrets.properties <<'EOF'
INGEST_ENDPOINT=https://example.invalid/ingest-health
INGEST_SECRET=dummy
SUPABASE_ANON_KEY=dummy
EOF
  echo "[dev_loop] secrets.properties 없어서 더미 파일 생성함"
fi

echo "[dev_loop] assembleDebug"
./gradlew assembleDebug

echo "[dev_loop] connectedDebugAndroidTest"
./gradlew connectedDebugAndroidTest

echo "[dev_loop] assembleRelease"
./gradlew assembleRelease -x lint -x lintVitalRelease

echo "[dev_loop] done"
