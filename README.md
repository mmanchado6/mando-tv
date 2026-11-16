# Mando TV

Mando a distancia para **Android TV / Google TV** que funciona por WiFi desde el móvil Android.
Usa el mismo protocolo que la app oficial de Google TV (**Android TV Remote v2**), así que no
necesita ADB ni activar las opciones de desarrollador en la tele.

- Kotlin + Jetpack Compose, `targetSdk 37` (Android 17); funciona desde Android 10.
- Solo habla con tu red local: sin Internet, sin anuncios, sin analíticas y sin cuentas.
- Se compila con un solo comando dentro de Docker; no hace falta Android Studio.

---

## Por qué existe este proyecto

Las apps de mando para Android TV que hay en las tiendas suelen pedir más de lo que necesitan:
anuncios, analíticas, permisos de ubicación o contactos, cuentas, o conexiones a servidores de
terceros. Para mandar «volumen +» a una tele que está en la misma habitación no hace falta nada de eso.

**Mando TV** demuestra que un mando completo puede ser:

- **Privado por diseño**: la app no puede conectarse a nada que no sea una IP privada de tu WiFi,
  y lo comprueba antes de abrir cualquier conexión.
- **Mínimo**: 4 permisos, cada uno justificado, y ninguna librería de terceros dentro de la app.
- **Auditable**: el protocolo y la criptografía están escritos a mano en pocas líneas, sin
  dependencias opacas, y cubiertos por tests.
- **Reproducible**: cualquiera puede compilar exactamente la misma app con Docker y comprobar qué
  contiene el APK.

---

## Funciones

| Pestaña | Contenido |
|---|---|
| **Mando** | Encender/apagar, TV, Silencio · cruceta + OK · Atrás, Play/Pausa, Home · VOL± y CH± con anillo de volumen |
| **Multimedia** | Play/Pausa, Stop, Retroceder, Avanzar, Anterior, Siguiente, VOL±, Silencio |
| **123** | Teclado numérico y CH± |
| **Apps** | Accesos directos para abrir apps en la tele, sin límite y con logo opcional |

- **Búsqueda automática** de teles en la WiFi (mDNS), o conexión escribiendo la IP.
- **Emparejamiento una sola vez**: la tele muestra un código, lo escribes en el móvil y queda guardado.
- **Reconexión automática** con esperas crecientes. Si la tele cambia de IP, la vuelve a encontrar
  y comprueba que es la misma.
- **Estado siempre visible**: conectado, reconectando, sin WiFi o hay que emparejar, y el volumen
  de la tele (el botón Silencio se ilumina si está silenciada).
- **Teclado**: escribe en la tele con el teclado del móvil, letra a letra. Se abre solo cuando la
  tele avisa de que hay un campo de texto activo, o a mano con el icono de teclado.
- **Teclado en modo incógnito**: lo que escribes para la tele (búsquedas, contraseñas de tus
  cuentas de streaming…) no se guarda en la app, y se le pide al teclado del móvil que no lo aprenda,
  así que no te lo sugerirá después en otras apps.
- **Mantener pulsado** volumen, canal o las flechas repite la orden. Mantener OK envía una
  pulsación larga (en Google TV abre el menú de opciones).
- **Vibración** al pulsar, desactivable.
- **Opcionales** (en Ajustes): botones de volumen del móvil para la tele, Wake-on-LAN para encender
  la tele desde apagada, e iconos de apps del móvil en los accesos directos.
- **Diseño**: tema oscuro, vertical, botones grandes; se adapta a la altura del móvil sin scroll.

> **Compatibilidad**: el protocolo es estándar, pero cada fabricante decide qué teclas acepta, así
> que alguna tecla puede no tener efecto en tu tele. Tampoco todas las apps de la tele avisan cuando
> se puede escribir. Probado en una Sony BRAVIA con Android TV.

---

## Descargar

Si solo quieres usar la app, descarga el APK de la última versión en
[**Releases**](https://github.com/mmanchado6/mando-tv/releases/latest) e instálalo en el móvil
(ver [Instalarlo en el móvil](#instalarlo-en-el-móvil)).

Para comprobar que el APK es auténtico y no lo ha modificado nadie:

- Su **SHA-256** debe coincidir con el publicado en la versión (`sha256sum MandoTV-*.apk`).
- El **certificado de firma** debe ser siempre este (`apksigner verify --print-certs MandoTV-*.apk`):
  `8387f031ba6ac190abf89b7121139ae83b7d57acc6c23ebb969097eb1cd1835f`

Si prefieres no fiarte de un APK ajeno, compílalo tú mismo como se explica a continuación. Ten en
cuenta que un APK compilado por ti lleva tu propia firma, así que no se puede instalar encima del
publicado (ni al revés) sin desinstalar antes.

---

## Cómo compilarlo y ejecutarlo

### Requisitos

- **Docker** en Linux, macOS o Windows (con WSL2). En Ubuntu/Debian:

  ```bash
  sudo apt update && sudo apt install -y docker.io
  sudo usermod -aG docker $USER     # después, cierra sesión y vuelve a entrar
  docker run --rm hello-world       # comprobación
  ```

  En macOS y Windows, instala [Docker Desktop](https://docs.docker.com/get-docker/).
- Unos **5 GB** libres de disco y conexión a Internet en el PC para la primera compilación.
- Un móvil con **Android 10 o superior** en la misma WiFi que la tele.
- Una tele **Android TV o Google TV** (lleva de serie el servicio «Android TV Remote Service»).

### Compilar (un solo comando)

```bash
git clone https://github.com/mmanchado6/mando-tv.git
cd mando-tv
./compilar.sh
```

`compilar.sh` hace todo dentro de Docker:

1. Construye la imagen de compilación con JDK 21, Android SDK 37 y Gradle, con versiones fijas.
   Cada descarga se verifica con su SHA-256. Solo la primera vez.
2. Ejecuta todos los tests. **Si alguno falla, no se genera el APK.**
3. La primera vez crea **tu propia clave de firma** en `firma/`.
4. Compila el APK de release (optimizado con R8) y lo firma.
5. Muestra los permisos que pide el APK y su huella SHA-256.

El resultado queda en **`salida/MandoTV.apk`**. La primera compilación tarda unos 10-15 minutos y
descarga unos 2 GB; las siguientes, 1-3 minutos.

Solo los tests: `./compilar.sh tests`. El informe queda en
`app/build/reports/tests/testDebugUnitTest/index.html` y las capturas de las pantallas, en
`salida/capturas/`.

> ⚠️ **Guarda la carpeta `firma/` y no la compartas.** Con ella puedes instalar versiones nuevas
> encima de la anterior sin perder el emparejamiento. Quien la tenga podría firmar «actualizaciones»
> de tu app.

> Al compilar, el Dockerfile acepta automáticamente la
> [licencia del Android SDK](https://developer.android.com/studio/terms), necesaria para descargarlo.

### Instalarlo en el móvil

**Opción A: sin opciones de desarrollador**

1. Copia `salida/MandoTV.apk` al móvil (por cable USB en modo «Transferencia de archivos», por
   ejemplo, a la carpeta Download).
2. Ábrelo con la app de archivos del móvil y permite «Instalar apps desconocidas» para esa app.
3. Pulsa **Instalar**. Si Play Protect avisa de que la app no es conocida, elige
   «Instalar de todas formas».

**Opción B: con `adb`** (requiere las opciones de desarrollador en el **móvil**, nunca en la tele):

```bash
adb install -r salida/MandoTV.apk
```

Para actualizar, instala el APK nuevo encima: se conservan las teles emparejadas.

> Google ha anunciado que irá exigiendo desarrolladores verificados para instalar apps fuera de
> Google Play en los móviles certificados (empezando por algunos países en 2026 y de forma global
> en 2027). La instalación con `adb` queda exenta.

### Primer uso

1. Abre **Mando TV** y concede el permiso de **red local** (Android 17 lo exige para encontrar la
   tele y hablar con ella).
2. Elige tu tele en la lista o escribe su IP (en la tele: Ajustes → Red).
3. Escribe el código de 6 caracteres que aparece en la tele. Listo: no hay que repetirlo.

---

## Arquitectura

```
 Móvil (Mando TV)                                        Tele (Android TV Remote Service)
 ┌──────────────────────────────┐
 │ UI (Jetpack Compose)         │
 │        │                     │
 │ MandoViewModel               │
 │        │                     │
 │ RemoteController / Pairing   │   TLS + certificado de cliente
 │        │                     │   ─── 6467: emparejamiento (código) ───►
 │ LanGate (solo red local) ────┼──────────────────────────────────────────►  puerto 6467
 │        │                     │   ─── 6466: mando (teclas, volumen,  ───►  puerto 6466
 │ socket atado a la WiFi       │        teclado, apps, pings)
 └──────────────────────────────┘
          ▲ mDNS (_androidtvremote2._tcp) con el NsdManager del sistema
```

### Protocolo

1. **Emparejamiento (puerto 6467)**: la app genera en el móvil un certificado propio (RSA 2048) y
   se conecta por TLS. La tele muestra un código de 6 caracteres hexadecimales; la app calcula un
   SHA-256 con las claves públicas de ambos y el código, y lo envía. A partir de ahí la tele
   reconoce el certificado del móvil.
2. **Mando (puerto 6466)**: conexión TLS con el mismo certificado. Tras un intercambio de
   configuración, la app envía las teclas como códigos estándar de Android (`KeyEvent`) y recibe el
   estado de la tele (encendida, volumen, campo de texto activo).
3. **Mensajes**: protobuf, con cada mensaje precedido de su longitud (varint). El codificador es
   propio y mínimo; no se usa la librería de protobuf.

### Código (`app/src/main/java/local/mandotv/`)

| Paquete | Qué contiene |
|---|---|
| `protocol/` | Protobuf mínimo hecho a mano, mensajes de emparejamiento y de mando, códigos de tecla |
| `crypto/` | Certificado X.509 autofirmado (DER escrito a mano) y secreto de emparejamiento |
| `net/` | `LanGate`, la puerta única de red, y TLS con el certificado de la tele fijado |
| `remote/` | Emparejamiento, conexión con reconexión automática, teclado y Wake-on-LAN |
| `android/` | WiFi (atar sockets), búsqueda mDNS, iconos de apps y vibración |
| `data/` | Teles, accesos directos y ajustes; identidad cifrada con el Android Keystore |
| `ui/` | Pantallas Compose y el `ViewModel` que lo coordina todo |

### Compilación y tests

| Archivo | Para qué |
|---|---|
| `compilar.sh` | El único comando: tests + APK firmado |
| `docker/Dockerfile` | JDK 21 + Android SDK 37 + Gradle 9.6, versiones fijas y verificadas por SHA-256 |
| `docker/dentro.sh` | Lo que se ejecuta dentro del contenedor |
| `.github/workflows/tests.yml` | Los mismos tests en GitHub Actions en cada push y pull request |
| `app/src/test/` | Tests unitarios y de interfaz (Robolectric) |

Los tests incluyen una **«tele falsa»** que implementa el lado de la tele con TLS real: emparejamiento
con código correcto e incorrecto, envío de teclas, pings, reconexión, tele que ya no reconoce el
móvil y tele impostora. También comprueban que la puerta de red bloquea todo lo que no es local y
que el mando cabe sin scroll en pantallas pequeñas y grandes.

---

## Seguridad y privacidad

### Qué datos maneja y dónde quedan

| Dato | Dónde se guarda | ¿Sale del móvil? |
|---|---|---|
| Clave privada y certificado del móvil | Almacenamiento privado de la app, cifrado con AES-256-GCM y una clave del Android Keystore | No |
| Teles emparejadas (nombre, IP, huella del certificado, MAC opcional) | Almacenamiento privado de la app | No |
| Accesos directos, logos y ajustes | Almacenamiento privado de la app | No |
| Texto que escribes para la tele | No se guarda; se envía solo a la tele | Solo a la tele, por TLS |
| Registro de diagnóstico | Solo en memoria; se borra al cerrar la app | Solo si tú lo copias |

Las **copias de seguridad están desactivadas** (`allowBackup=false` y reglas que excluyen todo): ni
Google Drive ni la transferencia a otro móvil se llevan estos datos.

### Red: solo la red local

Toda conexión pasa por un único punto (`net/LanGate.kt`) que, **antes de abrir el socket**, exige:

- una dirección **IPv4 privada** (10.x, 172.16-31.x o 192.168.x) escrita en número, de modo que
  nunca se consulta un DNS;
- uno de los puertos del protocolo: **6466** (mando), **6467** (emparejamiento) o **9** (Wake-on-LAN);
- que el socket vaya **atado a la WiFi** (o Ethernet), así que nunca sale por los datos móviles.

Cualquier otro destino se rechaza sin llegar a crear el socket, y hay tests que lo comprueban.

### Permisos

| Permiso | Por qué |
|---|---|
| `INTERNET` | Android lo exige para abrir cualquier conexión, también la local. La puerta de red bloquea todo lo que no sea tu WiFi. |
| `ACCESS_LOCAL_NETWORK` | Obligatorio en Android 17 para buscar la tele (mDNS) y hablar con ella. Se pide al abrir la app. |
| `ACCESS_NETWORK_STATE` | Saber si hay WiFi, atar la conexión a ella y reconectar cuando vuelve. |
| `VIBRATE` | Vibrar al pulsar. Android lo concede solo y se puede desactivar en los ajustes de la app. |

No pide ubicación, contactos, almacenamiento, micrófono ni notificaciones.

El ajuste opcional **«Iconos de apps del móvil»** (desactivado por defecto) necesita declarar en el
manifiesto que la app puede ver las apps con icono del móvil (`<queries>`); Android no permite
activarlo o desactivarlo en tiempo de ejecución. Con el ajuste desactivado, la app nunca consulta
esa lista (hay un test que lo comprueba), y aunque se active, la lista no sale del móvil.

### Dependencias

- Dentro del APK solo hay **Kotlin, kotlinx-coroutines y AndroidX/Jetpack Compose**. Ninguna hace
  conexiones de red.
- Sin Firebase, Google Play Services, Crashlytics, analíticas ni SDKs de anuncios.
- El protobuf y el certificado están escritos a mano, sin librerías de protobuf ni BouncyCastle.
- El APK no incluye EmojiCompat, que podría pedir una fuente de emojis a Google Play Services, ni
  ProfileInstaller, y el manifiesto los bloquea por si alguna dependencia los añadiera en el futuro.
- Robolectric y JUnit se usan **solo en los tests**; no forman parte del APK.

### Cifrado y autenticación

- Las dos conexiones con la tele van por **TLS** con certificado de cliente.
- Tras emparejar, la app **fija la huella del certificado de la tele**. Si otro aparato se hace
  pasar por ella, la conexión se rechaza y la app pide emparejar de nuevo.
- El campo de texto pide al teclado del móvil el **modo incógnito**
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`): que no aprenda lo que escribes para la tele, ni lo guarde en
  su historial o diccionario personal. Gboard y el teclado de Samsung lo respetan (Gboard muestra un
  icono de incógnito). La app tampoco guarda ese texto: solo lo envía a la tele.
- El registro de diagnóstico guarda solo números y longitudes de los mensajes de la tele, nunca
  el texto que escribes.

### Limitaciones conocidas

- **Emparejamiento**: el código del protocolo solo tiene 4 caracteres de valor aleatorio. Alguien
  que ya esté dentro de tu red y pueda interceptar el tráfico en el momento exacto del
  emparejamiento podría suplantar a la tele. Es una limitación del protocolo de Google, no de esta
  app. Empareja solo en una red de confianza.
- **Datos en el móvil**: un móvil rooteado o con el almacenamiento de apps comprometido podría
  exponer los datos guardados. La clave privada va cifrada con el Keystore, pero el resto de datos
  (teles, ajustes) se guarda sin cifrar en el almacenamiento privado de la app.
- **Wake-on-LAN** envía un paquete de difusión a toda tu red local (sin salir de ella).
- La app **no se ha sometido a una auditoría de seguridad externa**.

### Cómo verificarlo tú

- Revisa `net/LanGate.kt` (el único sitio que abre conexiones) y `AndroidManifest.xml`.
- `./compilar.sh` imprime los permisos reales del APK al terminar.
- Ejecuta los tests de `SecurityTest` y `ConnectionTest`.

¿Has encontrado un problema de seguridad? No abras un *issue* público: avísalo en privado desde la
pestaña **Security → Report a vulnerability** de este repositorio.

---

## Si algo no funciona

| Problema | Qué hacer |
|---|---|
| No aparece la tele | Móvil y tele en la misma WiFi (no en la de invitados) y tele encendida. Prueba a escribir la IP. |
| «La tele no responde» | Tele en reposo profundo: configura Wake-on-LAN o enciéndela con su mando. |
| «Hay que emparejar de nuevo» | La tele se reseteó o borró el móvil: pulsa *Emparejar*. |
| El teclado no se abre solo | Esa app de la tele no avisa de que hay un campo de texto: ábrelo con el icono de teclado. |
| «Intro» no busca o borra una letra | En Ajustes → «Teclado: botón Intro», prueba otra opción. |
| Un botón no hace nada | Esa tecla no la acepta tu tele por este protocolo. |
| Hay una VPN activa en el móvil | Algunas VPN bloquean el tráfico local: prueba a desactivarla. |
| Otra cosa | Ajustes → Diagnóstico → **Copiar registro**, y adjúntalo al informar del problema. |

---

## Licencia

Copyright (c) 2026 Manuel Manchado Barquero

Este proyecto se distribuye bajo la licencia
[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).

Puedes usar, copiar, modificar y redistribuir el código para **fines no comerciales**
(uso personal, estudio, investigación, proyectos de afición, y uso por organizaciones
educativas, benéficas o públicas). Cualquier copia o derivado debe conservar el aviso
de copyright y los términos de esta licencia.

**El uso comercial no está permitido.** Si te interesa usarlo comercialmente,
contáctame: mmanchado135@gmail.com
