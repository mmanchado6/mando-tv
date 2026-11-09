# Mando TV

Mando a distancia para Android TV / Google TV por WiFi, con el mismo protocolo que la app oficial
de Google TV (Android TV Remote v2). No necesita ADB ni opciones de desarrollador en la tele.

- Kotlin + Jetpack Compose, `targetSdk 37` (Android 17), funciona desde Android 10.
- Solo habla con tu red local. Sin anuncios, analíticas ni informes de errores.

---

## 1. Docker

**Ya lo tienes instalado y funcionando** (Docker 29.9.3, el servicio arranca solo y tu usuario está
en el grupo `docker`, así que no necesitas `sudo`). No hay que hacer nada.

Por si algún día reinstalas el sistema (Ubuntu):

```bash
sudo apt update && sudo apt install -y docker.io
sudo usermod -aG docker $USER     # después cierra sesión y vuelve a entrar
docker run --rm hello-world       # comprobación
```

## 2. Compilar (un solo comando)

```bash
cd ~/Documentos/"app mando"
./compilar.sh
```

Qué hace:

1. Construye la imagen de Docker con JDK 21, Android SDK 37 y Gradle. Solo la primera vez, y cada
   descarga se comprueba con su SHA-256.
2. Ejecuta todos los tests. **Si alguno falla, no se genera el APK.**
3. La primera vez crea tu clave de firma en `firma/`.
4. Compila el APK de release (optimizado con R8) y lo firma.
5. Muestra los permisos que pide el APK y su huella SHA-256.

La primera vez tarda unos 10-15 minutos y descarga unos 2 GB. Las siguientes, 1-3 minutos.

Si solo quieres ejecutar los tests: `./compilar.sh tests`. El informe se genera en
`app/build/reports/tests/testDebugUnitTest/index.html` y las capturas de las pantallas en
`salida/capturas/`.

> ⚠️ **Guarda la carpeta `firma/`** (por ejemplo, en un USB). Con ella puedes instalar versiones
> nuevas encima de la actual sin perder el emparejamiento. Si la pierdes, tendrás que desinstalar la
> app y emparejar otra vez. No la compartas con nadie.

## 3. Dónde está el APK

```
salida/MandoTV.apk
```

## 4. Instalarlo en el móvil

**Opción A: por cable, sin opciones de desarrollador**

1. Conecta el móvil al PC por USB. En la notificación «Cargando por USB» del móvil, elige
   **Transferencia de archivos**.
2. Copia `salida/MandoTV.apk` a la carpeta **Download** del móvil.
3. En el móvil, abre la app **Archivos** → Descargas → toca `MandoTV.apk`.
4. Android te pedirá permitir **«Instalar apps desconocidas»** para Archivos. Actívalo, vuelve
   atrás y pulsa **Instalar**.
5. Si Play Protect avisa de que la app no es conocida, elige **«Instalar de todas formas»**. Si te
   ofrece enviar la app para analizarla, puedes decir que no.

**Opción B: con `adb`** (requiere activar las opciones de desarrollador en el **móvil**, nunca en
la tele):

```bash
sudo apt install -y adb
adb install -r salida/MandoTV.apk
```

Para **actualizar** después, repite cualquiera de las dos opciones con el APK nuevo: se instala
encima y conserva tus teles.

> En España, a día de hoy, se puede instalar sin restricciones. Google ha anunciado que en 2027
> exigirá desarrolladores verificados en todo el mundo, pero la instalación con `adb` seguirá
> exenta.

## 5. Primer uso

1. Abre **Mando TV** y concede el permiso **«red local»**. Android 17 lo exige para que la app pueda
   encontrar la tele en tu WiFi y hablar con ella.
2. Elige tu tele en la lista o escribe su IP.
3. La tele mostrará un código de 6 caracteres. Escríbelo en el móvil y listo: no tendrás que
   repetirlo.

## Funciones

| Pestaña | Botones |
|---|---|
| **Mando** | Encender/apagar, TV, Silencio · cruceta + OK · Atrás, Play/Pausa, Home · VOL± y CH± con anillo de volumen |
| **Multimedia** | Play/Pausa, Stop, Retroceder, Avanzar, Anterior, Siguiente, VOL±, anillo de volumen, Silencio |
| **123** | Teclado numérico, CH± |
| **Apps** | Accesos directos sin límite, personalizables y con logo opcional |

- **Teclado automático**: cuando la tele avisa de que hay un campo de texto activo, en el móvil se
  abre solo un panel con el teclado. No todas las apps de la tele avisan (el buscador de Google TV
  sí; el de YouTube, en muchas teles, no): si no se abre, usa el icono de teclado de arriba. Lo que
  escribes llega a la tele letra a letra (también al borrar) y la tecla de buscar/Intro hace «Intro».
  El teclado va en **modo incógnito**: se le pide que no aprenda lo que escribes.
- **Silencio**: el botón se pone ámbar y el anillo de volumen muestra el altavoz tachado cuando la
  tele está silenciada.
- **Pantalla adaptable**: el mando se ajusta a la altura del móvil y cabe sin scroll, también en
  móviles pequeños.
- **Mantener pulsado** volumen, canal o las flechas repite la orden.
- **Vibración** al pulsar (se puede desactivar en Ajustes). Funciona aunque tengas desactivada la
  «respuesta táctil» del sistema.
- **Mantener OK** envía una pulsación larga (en Google TV abre el menú de opciones).
- **Botón Intro del teclado**: si en tu tele borra una letra o no busca, en Ajustes → «Teclado: botón
  Intro» puedes elegir otra forma de enviarlo.
- **Botones de volumen del móvil → tele**: opcional, en Ajustes (desactivado de serie).
- **Wake-on-LAN**: si la tele no responde al pulsar Encender, se le envía el «paquete mágico».
  Necesita la MAC de la tele (Ajustes de la app) y tener activado en la tele «Encender con
  Wi-Fi/LAN» o similar.
- **Reconexión automática**: si se corta, reintenta sola con esperas crecientes (0,5 s → 15 s). Si la
  tele cambia de IP, la vuelve a encontrar por mDNS y comprueba que es la misma.
- **Estado siempre visible**: verde (conectado), ámbar (conectando o reintentando), azul (hay que
  emparejar), gris (sin WiFi). También muestra el volumen de la tele.
- **Accesos directos**: en Apps → Editar puedes añadir, reordenar y quitar accesos. Cada uno es un
  enlace (`https://…`, `plex://…`) o el nombre del paquete de la app en la tele (p. ej.
  `org.xbmc.kodi`). Logo opcional: una imagen de tu galería (selector de fotos del sistema, sin
  permisos) o el icono de una app del móvil (solo si activas «Iconos de apps del móvil» en Ajustes).

## Privacidad

- **Un único punto de control para toda conexión** (`net/LanGate.kt`). Antes de abrir un socket
  comprueba que:
  - el destino es una IPv4 privada (10.x, 172.16-31.x o 192.168.x), escrita en número (nunca se
    consulta un DNS);
  - el puerto es 6466 (mando), 6467 (emparejamiento) o 9 (Wake-on-LAN).

  Además, el socket se ata a la WiFi, así que nunca sale por los datos móviles. Cualquier otro
  destino se bloquea, y hay tests que lo comprueban.
- **Permisos (solo 4):**

  | Permiso | Para qué |
  |---|---|
  | `INTERNET` | Android lo exige para abrir cualquier conexión, también la local. |
  | `ACCESS_LOCAL_NETWORK` | Obligatorio en Android 17 para hablar con la tele en tu red. |
  | `ACCESS_NETWORK_STATE` | Saber si hay WiFi, atar la conexión a ella y reconectar. |
  | `VIBRATE` | Vibrar al pulsar (Android lo concede solo; se apaga en Ajustes de la app). |

- **Nada sale del móvil:**
  - las copias de seguridad están desactivadas (ni Google Drive ni transferencia a otro móvil);
  - la clave del móvil se guarda cifrada con el Android Keystore;
  - la huella del certificado de la tele queda fijada, así que se rechaza a cualquier impostor;
  - el registro de diagnóstico solo vive en memoria.
- **Iconos de apps del móvil (desactivado por defecto)**: si lo activas en Ajustes, la app puede ver
  la lista de apps con icono instaladas en el móvil para usar su logo. La lista no sale del móvil.
  Android obliga a declarar esta posibilidad en el manifiesto (`<queries>`), pero con el ajuste
  desactivado la app nunca la consulta (hay un test que lo comprueba). Nada se descarga de Internet.
- **Sin librerías de terceros en la app** más allá de Kotlin y AndroidX/Compose. El protocolo
  (protobuf) y el certificado están escritos a mano. Tampoco se usa EmojiCompat, que podría pedir
  una fuente a Google Play Services.
- **Comprobado en el APK final:** no contiene SDKs de rastreo ni clases para hacer peticiones web.

## Si algo no funciona

| Problema | Qué hacer |
|---|---|
| No aparece la tele | Misma WiFi (no la de invitados) y tele encendida. Prueba a escribir la IP. |
| «La tele no responde» | Tele en reposo profundo: configura Wake-on-LAN o enciéndela con su mando. |
| «Hay que emparejar de nuevo» | La tele se reseteó o borró el móvil: pulsa *Emparejar*. |
| El texto no llega o el teclado no se abre solo | En la tele tiene que haber un campo de texto activo. Si falla, copia el registro de diagnóstico: apunta los avisos de teclado de la tele (sin el texto). |
| Fuente no cambia | Prueba las distintas entradas del menú: cada marca acepta unas teclas u otras. |
| Hay una VPN activa en el móvil | Algunas VPN bloquean el tráfico local: prueba a desactivarla. |
| Otra cosa | Ajustes → Diagnóstico → **Copiar registro**, y compártelo. |

## Estructura del proyecto

```
compilar.sh                 un solo comando: tests + APK
docker/Dockerfile           JDK 21 + Android SDK 37 + Gradle 9.6 (versiones fijas, SHA-256)
docker/dentro.sh            lo que se ejecuta dentro del contenedor
app/src/main/java/local/mandotv/
  protocol/   protobuf mínimo, mensajes de emparejamiento y de mando, códigos de tecla
  crypto/     certificado autofirmado (DER a mano) y secreto de emparejamiento
  net/        punto de control «solo red local» y TLS con certificado fijado
  remote/     emparejamiento, conexión con reconexión automática, Wake-on-LAN
  android/    WiFi (atar sockets) y búsqueda mDNS
  data/       teles, accesos directos y ajustes (solo en el móvil)
  ui/         pantallas Compose
app/src/test/  72 tests: protocolo, criptografía, red, «tele falsa» por TLS real, interfaz

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
```
