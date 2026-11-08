#!/usr/bin/env bash
# Compila «Mando TV» dentro de Docker.
#   ./compilar.sh         -> tests + APK firmado en salida/MandoTV.apk
#   ./compilar.sh tests   -> solo los tests
set -euo pipefail
cd "$(dirname "$0")"
IMAGEN="mando-tv-builder:1"
VOLUMEN="mando-tv-cache"

echo "==> Preparando la imagen de compilación (la primera vez tarda unos minutos)"
docker build -q -t "$IMAGEN" docker/ > /dev/null
docker volume create "$VOLUMEN" > /dev/null

docker run --rm --init \
  --user "$(id -u):$(id -g)" \
  -v "$PWD":/proyecto \
  -v "$VOLUMEN":/cache \
  "$IMAGEN" bash docker/dentro.sh "${1:-todo}"
