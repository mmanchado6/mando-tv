#!/usr/bin/env bash
# Se ejecuta DENTRO del contenedor. Uso: dentro.sh [todo|tests]
set -euo pipefail
modo="${1:-todo}"
mkdir -p "$HOME" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
GRADLE="gradle --no-daemon --console=plain"

echo "==> Ejecutando tests unitarios"
$GRADLE :app:testDebugUnitTest
echo "==> Tests OK (informe: app/build/reports/tests/testDebugUnitTest/index.html)"
[ "$modo" = "tests" ] && exit 0

# Clave de firma propia: se crea solo la primera vez y se guarda en firma/
if [ ! -f firma/mando-tv.jks ]; then
  echo "==> Creando tu clave de firma en firma/ (guárdala bien)"
  mkdir -p firma
  pass="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  keytool -genkeypair -noprompt -keystore firma/mando-tv.jks -storetype PKCS12 \
    -alias mandotv -keyalg RSA -keysize 3072 -validity 36500 \
    -dname "CN=Mando TV" -storepass "$pass" -keypass "$pass" > /dev/null
  printf 'storeFile=../firma/mando-tv.jks\nstorePassword=%s\nkeyAlias=mandotv\nkeyPassword=%s\n' "$pass" "$pass" > firma/firma.properties
  chmod 600 firma/firma.properties firma/mando-tv.jks
fi

echo "==> Compilando APK de release"
$GRADLE :app:assembleRelease
mkdir -p salida
cp app/build/outputs/apk/release/app-release.apk salida/MandoTV.apk
echo
echo "==> Permisos que pide el APK:"
aapt2 dump permissions salida/MandoTV.apk | grep -E 'uses-permission' | sed 's/^/    /'
echo
echo "==> APK listo: salida/MandoTV.apk"
echo "    SHA-256: $(sha256sum salida/MandoTV.apk | cut -d' ' -f1)"
