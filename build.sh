#!/usr/bin/env bash
# Full clean build: compile, unit tests, integration tests, then Docker image.
#
# Usage:
#   ./build.sh              # build everything
#   ./build.sh --skip-its   # skip integration tests (faster local iteration)
#   ./build.sh --no-cache   # pass --no-cache to docker compose build
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SKIP_ITS=false
DOCKER_NO_CACHE=""

for arg in "$@"; do
    case "$arg" in
        --skip-its)   SKIP_ITS=true ;;
        --no-cache)   DOCKER_NO_CACHE="--no-cache" ;;
        *) echo "Unknown argument: $arg" >&2; exit 1 ;;
    esac
done

# ── 1. Build and install viscolink + viscostore (unit tests included) ─────────────────────────────
echo "==> [1/3] Building viscolink and viscostore..."
./mvnw clean install -pl viscolink,viscostore

# ── 2. Build viscorunner ──────────────────────────────────────────────────────────────────────────
# copy-dependencies resolves from .m2 (not the reactor), so step 1 must complete first.
# 'verify' runs: package (copies WARs) → integration-test (LabEnrichmentIT) → verify.
# '--skip-its' skips only integration tests; WARs are still copied for the Docker build.
echo "==> [2/3] Building viscorunner${SKIP_ITS:+ (integration tests skipped)}..."
if [ "$SKIP_ITS" = true ]; then
    ./mvnw clean package -pl viscorunner
else
    ./mvnw clean verify -pl viscorunner
fi

# ── 3. Build Docker image ─────────────────────────────────────────────────────────────────────────
echo "==> [3/3] Building Docker image..."
(cd viscorunner && docker compose build $DOCKER_NO_CACHE)

echo ""
echo "Done."
