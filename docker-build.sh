#!/bin/bash
# Compila l'app in un container Docker. Uso:
#   ./docker-build.sh                      # test del protocollo + APK debug -> dist/
#   ./docker-build.sh :core:selfTest       # solo i test
#   ./docker-build.sh :app:assembleRelease # un task a scelta
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=fuji-transfer-build
PLATFORM=linux/amd64

# Se la rete aziendale intercetta il TLS, la sua CA va dentro l'immagine (no-op se non serve).
if ! ls docker/certs/*.crt > /dev/null 2>&1; then
    docker/export-ca.sh || echo "Export della CA non riuscito: se la build fallisce con errori di certificato, vedi README." >&2
fi

docker build --platform "$PLATFORM" -t "$IMAGE" .
mkdir -p dist
docker run --rm --platform "$PLATFORM" \
    -v "$PWD":/src:ro \
    -v "$PWD/dist":/out \
    -v fuji-gradle-cache:/gradle-cache \
    "$IMAGE" "$@"
