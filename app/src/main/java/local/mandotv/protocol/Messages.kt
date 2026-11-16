package local.mandotv.protocol

/**
 * Mensajes del emparejamiento (puerto 6467), basados en polo.proto (OuterMessage).
 */
object PairingMessages {
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200

    // Campos de OuterMessage
    const val F_VERSION = 1
    const val F_STATUS = 2
    const val F_PAIRING_REQUEST = 10
    const val F_PAIRING_REQUEST_ACK = 11
    const val F_OPTIONS = 20
    const val F_CONFIGURATION = 30
    const val F_CONFIGURATION_ACK = 31
    const val F_SECRET = 40
    const val F_SECRET_ACK = 41

    const val ENCODING_HEXADECIMAL = 3
    const val ROLE_INPUT = 1
    const val CODE_LENGTH = 6

    private fun outer(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(F_VERSION, PROTOCOL_VERSION).int(F_STATUS, STATUS_OK).apply(build).toByteArray()

    fun pairingRequest(clientName: String, serviceName: String = "atvremote"): ByteArray = outer {
        message(F_PAIRING_REQUEST) { string(1, serviceName); string(2, clientName) }
    }

    fun options(): ByteArray = outer {
        message(F_OPTIONS) {
            message(1) { int(1, ENCODING_HEXADECIMAL); int(2, CODE_LENGTH) } // input_encodings
            int(3, ROLE_INPUT) // preferred_role
        }
    }

    fun configuration(): ByteArray = outer {
        message(F_CONFIGURATION) {
            message(1) { int(1, ENCODING_HEXADECIMAL); int(2, CODE_LENGTH) } // encoding
            int(2, ROLE_INPUT) // client_role
        }
    }

    fun secret(secret: ByteArray): ByteArray = outer { message(F_SECRET) { bytes(1, secret) } }

    /** Respuesta recibida de la tele durante el emparejamiento. */
    data class Reply(val status: Int, val kind: Int?) {
        val ok get() = status == STATUS_OK
    }

    fun parse(data: ByteArray): Reply {
        val m = ProtoMessage.parse(data)
        val kind = listOf(F_PAIRING_REQUEST_ACK, F_OPTIONS, F_CONFIGURATION_ACK, F_SECRET_ACK).firstOrNull { m.has(it) }
        return Reply(m.int(F_STATUS), kind)
    }
}

/**
 * Mensajes de la sesión de mando (puerto 6466), basados en remotemessage.proto (RemoteMessage).
 */
object RemoteMessages {
    const val F_CONFIGURE = 1
    const val F_SET_ACTIVE = 2
    const val F_ERROR = 3
    const val F_PING_REQUEST = 8
    const val F_PING_RESPONSE = 9
    const val F_KEY_INJECT = 10
    const val F_IME_KEY_INJECT = 20
    const val F_IME_BATCH_EDIT = 21
    const val F_IME_SHOW_REQUEST = 22
    /** Sin documentar: la tele lo envía (vacío) justo después de cerrar su teclado en pantalla. */
    const val F_IME_CLOSED = 24
    // Voz: «empieza» (la tele lo envía tras la tecla Buscar y el mando lo repite), audio y «fin».
    const val F_VOICE_BEGIN = 30
    const val F_VOICE_PAYLOAD = 31
    const val F_VOICE_END = 32
    const val F_START = 40
    const val F_SET_VOLUME_LEVEL = 50
    const val F_APP_LINK = 90

    // Dirección de la pulsación (RemoteDirection)
    const val START_LONG = 1
    const val END_LONG = 2
    const val SHORT = 3

    // Funciones que anunciamos a la tele (bitmask). Como la app oficial y otras implementaciones,
    // anunciamos siempre VOICE (aunque el micrófono esté desactivado en Ajustes, que es lo normal):
    // algunas teles solo informan del teclado a mandos «completos».
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_VOICE = 8
    /** Función sin documentar que la app oficial anuncia; parece relacionada con el teclado moderno. */
    const val FEATURE_UNKNOWN_16 = 16
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512
    const val FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_VOICE or FEATURE_UNKNOWN_16 or
        FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    /** Lo que anunciamos: lo nuestro que la tele también admite (si la tele no dice nada, todo lo nuestro). */
    fun negotiate(tvFeatures: Int): Int = if (tvFeatures == 0) FEATURES else FEATURES and tvFeatures

    /** Respuesta a remote_configure. Solo nos presentamos como «Mando TV», sin datos del móvil. */
    fun configure(appVersion: String, features: Int = FEATURES): ByteArray = ProtoWriter().message(F_CONFIGURE) {
        int(1, features)
        message(2) {
            string(1, "Mando TV") // model
            string(2, "Mando TV") // vendor
            int(3, 1)
            string(4, "1")
            string(5, "local.mandotv")
            string(6, appVersion)
        }
    }.toByteArray()

    fun setActive(features: Int = FEATURES): ByteArray = ProtoWriter().message(F_SET_ACTIVE) { int(1, features) }.toByteArray()

    fun pingResponse(value: Int): ByteArray = ProtoWriter().message(F_PING_RESPONSE) { int(1, value) }.toByteArray()

    fun key(keyCode: Int, direction: Int = SHORT): ByteArray =
        ProtoWriter().message(F_KEY_INJECT) { int(1, keyCode); int(2, direction) }.toByteArray()

    /** Escribe texto en el campo activo de la tele (deja el cursor al final). */
    fun text(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        val len = text.length
        return ProtoWriter().message(F_IME_BATCH_EDIT) {
            int(1, imeCounter)
            int(2, fieldCounter)
            message(3) {
                int(1, 1) // insert
                message(2) { int(1, len); int(2, len); string(3, text) }
            }
        }.toByteArray()
    }

    /**
     * Cambio de texto «letra a letra»: cuántos caracteres hay que borrar al final del campo de
     * la tele y qué texto hay que añadir después. Se calcula comparando el texto anterior con el
     * nuevo; si el cambio es en medio, se borra hasta ese punto y se reescribe el resto.
     */
    data class TextEdit(val deletes: Int, val insert: String)

    fun textEdit(old: String, new: String): TextEdit? {
        if (old == new) return null
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        if (p > 0 && Character.isHighSurrogate(old[p - 1])) p-- // no partir un emoji
        return TextEdit(old.codePointCount(p, old.length), new.substring(p))
    }

    // ---------- Voz ----------
    // El audio es PCM de 16 bits, 8 kHz, mono. Cada mensaje lleva exactamente 20 KB de muestras
    // (1,28 s): con trozos menores la tele recibe el audio pero no reconoce nada. Lo que falte se
    // rellena con silencio.
    const val VOICE_SAMPLE_RATE = 8000
    const val VOICE_CHUNK = 20 * 1024
    /** Lo que dura el audio de un mensaje. */
    const val VOICE_CHUNK_MS = VOICE_CHUNK * 1000L / (VOICE_SAMPLE_RATE * 2)

    fun voiceBegin(sessionId: Int): ByteArray = ProtoWriter().message(F_VOICE_BEGIN) { int(1, sessionId) }.toByteArray()

    fun voiceEnd(sessionId: Int): ByteArray = ProtoWriter().message(F_VOICE_END) { int(1, sessionId) }.toByteArray()

    /** Mensajes de audio para [pcm]: en trozos de 20 KB, el último relleno con silencio. */
    fun voicePayloads(sessionId: Int, pcm: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < pcm.size) {
            val end = minOf(pos + VOICE_CHUNK, pcm.size)
            val samples = pcm.copyOfRange(pos, end).let { if (it.size < VOICE_CHUNK) it.copyOf(VOICE_CHUNK) else it }
            out += ProtoWriter().message(F_VOICE_PAYLOAD) { int(1, sessionId); bytes(2, samples) }.toByteArray()
            pos = end
        }
        return out
    }

    fun appLink(link: String): ByteArray = ProtoWriter().message(F_APP_LINK) { string(1, link) }.toByteArray()

    /** Lo que nos interesa de los mensajes que envía la tele. */
    sealed interface Incoming {
        data class Configure(val features: Int, val model: String, val vendor: String) : Incoming
        data object SetActive : Incoming
        data class Ping(val value: Int) : Incoming
        data class PowerState(val on: Boolean) : Incoming
        data class Volume(val level: Int, val max: Int, val muted: Boolean) : Incoming
        /**
         * Informe de teclado de la tele. Si trae [hasTextField], hay un campo de texto activo
         * (p. ej. el buscador): es la señal para abrir el teclado del móvil.
         * [structure] describe los campos recibidos (solo números y longitudes, nunca el texto).
         */
        data class ImeKeyInject(
            val appPackage: String,
            val imeCounter: Int,
            val fieldCounter: Int,
            val hasTextField: Boolean = false,
            val fieldValue: String = "",
            val fieldLabel: String = "",
            val structure: String = "",
        ) : Incoming
        /** La tele tiene un campo de texto activo: [value] es lo que ya contiene. */
        data class ImeShow(val fieldCounter: Int?, val value: String, val label: String) : Incoming
        data class ImeBatchEdit(val imeCounter: Int, val fieldCounter: Int) : Incoming
        /** La tele ha cerrado su teclado (mensaje 24, sin documentar). */
        data object ImeClosed : Incoming
        /** La tele abre una sesión de voz (respuesta a la tecla Buscar) y espera el audio. */
        data class VoiceBegin(val sessionId: Int) : Incoming
        /** La tele ha detectado el final de la frase (mensaje de audio vacío de vuelta) y la está reconociendo. */
        data class VoiceHeard(val sessionId: Int) : Incoming
        /** La tele da por terminada la sesión de voz. */
        data class VoiceEnd(val sessionId: Int) : Incoming
        data object Error : Incoming
        data class Other(val structure: String) : Incoming
    }

    fun parse(data: ByteArray): Incoming {
        val m = ProtoMessage.parse(data)
        m.message(F_CONFIGURE)?.let { c ->
            val info = c.message(2)
            return Incoming.Configure(c.int(1), info?.string(1) ?: "", info?.string(2) ?: "")
        }
        if (m.has(F_SET_ACTIVE)) return Incoming.SetActive
        m.message(F_PING_REQUEST)?.let { return Incoming.Ping(it.int(1)) }
        m.message(F_START)?.let { return Incoming.PowerState(it.bool(1)) }
        m.message(F_SET_VOLUME_LEVEL)?.let { return Incoming.Volume(it.int(7), it.int(6), it.bool(8)) }
        m.message(F_IME_KEY_INJECT)?.let { k ->
            val field = k.message(2)
            return Incoming.ImeKeyInject(
                appPackage = k.message(1)?.string(12) ?: "",
                imeCounter = k.message(1)?.int(1) ?: 0,
                fieldCounter = field?.int(1) ?: 0,
                hasTextField = field != null,
                fieldValue = field?.string(2) ?: "",
                fieldLabel = field?.string(6) ?: "",
                structure = describe(k),
            )
        }
        m.message(F_IME_SHOW_REQUEST)?.let { s ->
            val field = s.message(2)
            return Incoming.ImeShow(field?.int(1), field?.string(2) ?: "", field?.string(6) ?: "")
        }
        m.message(F_IME_BATCH_EDIT)?.let { return Incoming.ImeBatchEdit(it.int(1), it.int(2)) }
        if (m.has(F_IME_CLOSED)) return Incoming.ImeClosed
        m.message(F_VOICE_BEGIN)?.let { return Incoming.VoiceBegin(it.int(1)) }
        m.message(F_VOICE_PAYLOAD)?.let { return Incoming.VoiceHeard(it.int(1)) }
        m.message(F_VOICE_END)?.let { return Incoming.VoiceEnd(it.int(1)) }
        if (m.has(F_ERROR)) return Incoming.Error
        return Incoming.Other(describe(m))
    }

    /**
     * Resumen de la estructura de un mensaje para el registro de diagnóstico, SIN contenido:
     * los números se muestran y los textos solo como su longitud (p. ej. «12=txt(29)»).
     * Solo se abren como submensajes los que el protocolo define así (nivel 0, y campos 1-3 del
     * nivel 1); a partir de ahí todo lo que no es número se trata como texto.
     */
    fun describe(m: ProtoMessage, depth: Int = 0): String = m.fields.joinToString(" ", "{", "}") { f ->
        val b = f.bytes
        val nested = b != null && b.isNotEmpty() && (depth == 0 || (depth == 1 && f.number in 1..3))
        when {
            b == null -> "${f.number}=${f.varint}"
            nested -> runCatching { "${f.number}=" + describe(ProtoMessage.parse(b), depth + 1) }.getOrElse { "${f.number}=txt(${b.size})" }
            else -> "${f.number}=txt(${b.size})"
        }
    }
}
