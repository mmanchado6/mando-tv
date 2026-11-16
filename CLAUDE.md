# CLAUDE.md — Mando TV

App Android personal: **mando a distancia por WiFi para Android TV / Google TV** con el protocolo
**Android TV Remote v2** (el de la app oficial Google TV). Sin ADB ni opciones de desarrollador en la tele.
Tele del usuario: **Sony BRAVIA (Google TV)**. Móvil: **Android 17**. Licencia: PolyForm Noncommercial 1.0.0.

## Reglas del usuario (obligatorias)
- Responder **en español**. Comentarios del código en español, identificadores en inglés.
- **Antes de cambios grandes, enseñar un plan y esperar su «sí».** Arreglos pequeños, directos.
- **Privacidad ante todo**: la app solo habla con la red local. Nada de Internet, analíticas,
  anuncios, Firebase/Play Services ni informes de errores. Copias de seguridad desactivadas.
- **Ninguna librería de terceros nueva sin explicarla y que la apruebe.** En el APK solo hay Kotlin,
  kotlinx-coroutines y AndroidX/Compose (+ material-icons-extended). Robolectric aprobado solo para tests.
- **Permisos mínimos y justificados** (actuales: `INTERNET`, `ACCESS_LOCAL_NETWORK`,
  `ACCESS_NETWORK_STATE`, `VIBRATE`). Cualquier permiso nuevo hay que consultarlo.
- Nunca subir `firma/` (clave de firma) ni `salida/`. Ya están en `.gitignore`.
- Commit/push solo si lo pide. `main` está protegida en GitHub (mmanchado6/mando-tv): los cambios van
  por rama + pull request, y el check **Tests** (GitHub Actions) debe pasar.

## Compilar y probar (todo en Docker; no hay Android Studio)
- `./compilar.sh` → tests + APK firmado en `salida/MandoTV.apk` (imprime permisos y SHA-256).
- `./compilar.sh tests` → solo tests. Capturas de pantalla de los tests en `salida/capturas/`
  (revisarlas con Read tras cambios de UI). Informe: `app/build/reports/tests/testDebugUnitTest/`.
- Toolchain fijada: JDK 21, AGP 9.5.1, Gradle 9.6.0, Kotlin 2.4.21, Compose BOM 2026.09.00,
  compileSdk/targetSdk 37 (`platforms;android-37.0`), build-tools 36.0.0, minSdk 29.
- Robolectric corre con `sdk=36` (`app/src/test/resources/robolectric.properties`) porque el Espresso
  de Compose falla en API 37; su runtime lo descarga Gradle y va offline (`app/build.gradle.kts`).
- CI: `.github/workflows/tests.yml` (mismas versiones, solo acciones oficiales `actions/*`).

## Arquitectura (`app/src/main/java/local/mandotv/`)
- `protocol/` — protobuf hecho a mano (`Proto.kt`: varints, enmarcado por longitud varint),
  mensajes (`Messages.kt`: emparejamiento polo y `RemoteMessage`), `TvKey.kt` (keycodes de Android,
  repetición y pulsación larga).
- `crypto/` — certificado X.509 autofirmado RSA 2048 escrito en DER a mano; secreto de emparejamiento.
- `net/` — `LanGate`: **única puerta de red** (solo IPv4 privadas literales, sin DNS; puertos 6466/6467
  y 9 para WoL; sockets atados a la WiFi). `Tls.kt`: certificado de la tele fijado tras emparejar.
- `remote/` — `Pairing.kt`, `RemoteController.kt` (conexión, reconexión con backoff, estado, teclado),
  `WakeOnLan.kt`.
- `android/` — `WifiMonitor` (bind a WiFi), `TvDiscovery` (NsdManager `_androidtvremote2._tcp`),
  `AppIcons` (logos de accesos), `VibratorHaptics`.
- `data/` — SharedPreferences (teles, accesos, ajustes) e identidad cifrada con AES-GCM del Keystore.
- `ui/` — Compose: `RemoteScreen` (pestañas Mando/Multimedia/123/Apps, escala adaptable sin scroll),
  `KeyboardSheet` (teclado letra a letra), `OtherScreens` (teles, emparejar, ajustes, editor de accesos),
  `MandoViewModel` (coordina todo), `Components` (botones con relieve, cruceta, anillo de volumen).
- Tests (`app/src/test/`): `FakeTv` = tele falsa con TLS real en 127.0.0.x:6466/6467 (los tests usan
  `LoopbackPolicy`). ~83 tests: protocolo, seguridad, conexión, lógica y UI (Robolectric).

## Datos del protocolo (verificados)
- **Emparejamiento puerto 6467, mando puerto 6466** (TLS con certificado de cliente).
- Código de 6 hex: SHA-256(módulo+exponente cliente, módulo+exponente tele, últimos 4 hex); el primer
  byte = 2 primeros caracteres (checksum local). Bytes sin signo y sin ceros a la izquierda.
- Funciones anunciadas: 639 (PING|KEY|IME|VOICE|16|POWER|VOLUME|APP_LINK), negociado con la tele.
- La tele **no hace ping mientras recibe órdenes**: la conexión solo se da por muerta si no hay
  tráfico en ningún sentido durante 16 s.
- Android 17 + targetSdk 37 exige el permiso en tiempo de ejecución `ACCESS_LOCAL_NETWORK` (también NSD).

## Comportamiento de la Sony BRAVIA del usuario
- Funciona: teclas normales, volumen/silencio (la tele informa de nivel y mute), tecla **TV** (170),
  emparejamiento, accesos a apps por enlace (`https://…` o `market://launch?id=paquete`).
- **No funcionan** (eliminados de la UI): Fuente/INPUT (178), HDMI 1-4 (243-246), Guía, Menú, Ajustes,
  Subtítulos, Info, botones de colores (183-186).
- Teclado: el aviso de campo de texto llega como `remote_ime_key_inject` **con** `text_field_status`
  (en el buscador de Google TV sí; en el de YouTube no). Contadores: tomar los del key_inject tal cual
  (incluidos ceros) y actualizar el de campo con cada `remote_ime_show_request`. Mensaje **24** vacío
  (sin documentar) = la tele cerró su teclado.
- «Intro» del teclado: con `KEYCODE_ENTER` la tele borraba una letra; hay selector en Ajustes
  (Intro / Intro numérico 160 / salto de línea). Pendiente saber cuál funciona.
- OK mantenido envía START_LONG/END_LONG.

## Diagnóstico
Ajustes → Diagnóstico → «Copiar registro»: registro en memoria con estructura de mensajes de la tele
(solo números y longitudes, nunca texto). Es la vía para depurar con la tele real: pedir al usuario
que lo pegue.
