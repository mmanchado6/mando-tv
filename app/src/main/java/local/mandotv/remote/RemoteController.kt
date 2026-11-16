package local.mandotv.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import local.mandotv.crypto.ClientIdentity
import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import local.mandotv.net.Tls
import local.mandotv.net.TvTrustManager
import local.mandotv.protocol.Framing
import local.mandotv.protocol.RemoteMessages
import local.mandotv.protocol.RemoteMessages.Incoming
import local.mandotv.protocol.TvKey
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Datos mínimos de una tele para conectarse a ella. */
data class TvTarget(val id: String, val name: String, val host: String, val pinnedFingerprint: String)

/** Estado de la conexión, tal como se muestra arriba en la pantalla. */
sealed interface ConnectionState {
    data object NoTv : ConnectionState
    data object Paused : ConnectionState
    data object NoWifi : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data class Connected(val tvName: String) : ConnectionState
    data class Waiting(val retryAtMillis: Long, val reason: String) : ConnectionState
    data object NeedsPairing : ConnectionState
    data object CertificateChanged : ConnectionState
}

/** Lo que la tele nos cuenta de sí misma. */
data class TvStatus(
    val powerOn: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean = false,
    val currentApp: String? = null,
)

/** Avisos de la tele relacionados con el teclado. */
sealed interface ImeEvent {
    /** La tele tiene un campo de texto activo (p. ej. el buscador): hay que abrir el teclado. */
    data class Show(val text: String, val label: String) : ImeEvent
    /** La tele ya no tiene un campo de texto activo. */
    data object Hide : ImeEvent
}

/** Cómo acabó un intento de hablar a la tele. */
enum class VoiceResult {
    /** Se abrió la sesión y se envió audio. */
    DONE,
    NO_CONNECTION,
    /** La tele no abrió sesión de voz (esa pantalla o esa tele no la admiten). */
    NOT_OFFERED,
    /** Ya había otra sesión de voz en marcha. */
    BUSY,
}

/** Fases de una sesión de voz ya abierta. */
enum class VoicePhase {
    /** El micrófono está grabando. */
    LISTENING,
    /** Micrófono cerrado: se espera a que la tele termine de reconocer la frase. */
    FINISHING,
}

/**
 * De dónde sale el audio (el micrófono del móvil; en los tests, uno de mentira). [record] entrega
 * trozos de PCM de 16 bits, 8 kHz, mono, hasta que se cancela.
 */
fun interface VoiceSource {
    suspend fun record(onChunk: (ByteArray) -> Unit)
}

/** Esperas entre reintentos: 0,5 s, 1, 2, 4, 8 y luego 15 s como máximo. */
object Backoff {
    const val FIRST_MS = 500L
    const val MAX_MS = 15_000L
    fun delayFor(attempt: Int): Long = (FIRST_MS shl attempt.coerceIn(0, 10)).coerceAtMost(MAX_MS)
}

/**
 * Mantiene la conexión con la tele en el puerto 6466 y la recupera sola si se corta.
 *
 * Solo intenta conectar cuando hay una tele elegida, la app está activa y hay WiFi. Si cambia
 * cualquiera de esas condiciones, la conexión actual se cierra y se vuelve a evaluar.
 */
class RemoteController(
    private val gate: LanGate,
    private val identityProvider: () -> ClientIdentity,
    private val scope: CoroutineScope,
    private val appVersion: String = "1.0",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Se llama tras varios fallos seguidos: la app busca la tele por mDNS por si cambió de IP. */
    private val onUnreachable: (TvTarget) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Sin tráfico en ningún sentido durante este tiempo, la conexión se da por muerta. */
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS.toLong(),
    private val idlePollMs: Int = 4_000,
) {
    private data class Conditions(val tv: TvTarget?, val active: Boolean, val wifi: Boolean, val retryToken: Int)

    private val conditions = MutableStateFlow(Conditions(null, active = false, wifi = true, retryToken = 0))
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoTv)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _status = MutableStateFlow(TvStatus())
    val status: StateFlow<TvStatus> = _status.asStateFlow()

    private val _ime = MutableSharedFlow<ImeEvent>(extraBufferCapacity = 8)
    val imeEvents: SharedFlow<ImeEvent> = _ime.asSharedFlow()

    /** Mensajes pendientes de enviar; si la conexión se cae se descartan (no se acumulan órdenes viejas). */
    @Volatile private var outgoing: Channel<ByteArray>? = null
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var shownField: Int? = null
    @Volatile private var lastRx = 0L
    @Volatile private var lastTx = 0L
    @Volatile private var features = RemoteMessages.FEATURES
    private var loopJob: Job? = null

    /** Sesión de voz en curso: la tele la abre ([begin], null si se corta) y puede cerrarla ([ended]). */
    private class Voice {
        val begin = CompletableDeferred<Int?>()
        val ended = CompletableDeferred<Unit>()
        /** La tele ha detectado el final de la frase. */
        val heard = CompletableDeferred<Unit>()
        @Volatile var sessionId: Int? = null
    }
    @Volatile private var voice: Voice? = null

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            conditions.collectLatest { c ->
                when {
                    c.tv == null -> _state.value = ConnectionState.NoTv
                    !c.active -> _state.value = ConnectionState.Paused
                    !c.wifi -> _state.value = ConnectionState.NoWifi
                    else -> connectLoop(c.tv)
                }
            }
        }
    }

    fun setTarget(tv: TvTarget?) = conditions.update { it.copy(tv = tv) }
    fun setActive(active: Boolean) = conditions.update { it.copy(active = active) }
    fun setWifiAvailable(wifi: Boolean) = conditions.update { it.copy(wifi = wifi) }

    /** Reintenta ya (p. ej. al volver la WiFi o al pulsar «Reintentar»). */
    fun retryNow() = conditions.update { it.copy(retryToken = it.retryToken + 1) }

    fun send(key: TvKey, direction: Int = RemoteMessages.SHORT): Boolean =
        enqueue(RemoteMessages.key(key.code, direction)).also { ok ->
            val how = when (direction) { RemoteMessages.START_LONG -> " (mantener)"; RemoteMessages.END_LONG -> " (soltar)"; else -> "" }
            if (!key.repeats) log("Tecla ${key.name} (${key.code})$how ${if (ok) "enviada" else "no enviada: sin conexión"}")
        }

    fun sendText(text: String): Boolean = text.isNotEmpty() && enqueue(RemoteMessages.text(text, imeCounter, fieldCounter))

    /** Escribe en la tele letra a letra: borra [edit].deletes caracteres y añade [edit].insert. */
    fun sendEdit(edit: RemoteMessages.TextEdit): Boolean {
        if (!isConnected) return false
        repeat(edit.deletes) { if (!enqueue(RemoteMessages.key(TvKey.DEL.code))) return false }
        val ok = edit.insert.isEmpty() || enqueue(RemoteMessages.text(edit.insert, imeCounter, fieldCounter))
        // Sin el texto: solo cuántas letras y con qué contadores
        log("Teclado: enviado (borrar ${edit.deletes}, añadir ${edit.insert.length}, contadores $imeCounter/$fieldCounter)")
        return ok
    }

    fun launchApp(link: String): Boolean = link.isNotBlank() && enqueue(RemoteMessages.appLink(link.trim()))

    /**
     * Habla a la tele: envía la tecla Buscar, espera a que la tele abra la sesión de voz y le manda
     * el audio de [source] hasta que se suelta el botón ([release]), la tele detecta el final de la
     * frase o pasan [maxMs]. Entonces el micrófono se cierra y solo se envía silencio (ceros) hasta
     * que la tele avisa de que ya tiene la frase, porque cerrar la sesión antes cancela el
     * reconocimiento; la sesión se cierra [lingerMs] después. Cancelar la corrutina lo corta todo
     * en el acto.
     *
     * El audio va por la misma conexión cifrada que las teclas y no se guarda en ningún sitio. En el
     * registro solo quedan el número de sesión y cuántos bytes se enviaron.
     */
    suspend fun talk(
        source: VoiceSource,
        release: Deferred<Unit>,
        maxMs: Long = VOICE_MAX_MS,
        beginTimeoutMs: Long = VOICE_BEGIN_TIMEOUT_MS,
        tailMaxMs: Long = VOICE_TAIL_MAX_MS,
        lingerMs: Long = VOICE_LINGER_MS,
        onPhase: (VoicePhase) -> Unit = {},
    ): VoiceResult {
        if (!isConnected) return VoiceResult.NO_CONNECTION
        if (voice != null) return VoiceResult.BUSY
        val v = Voice()
        voice = v
        try {
            if (!enqueue(RemoteMessages.key(TvKey.SEARCH.code))) return VoiceResult.NO_CONNECTION
            log("Voz: tecla Buscar enviada, esperando a la tele")
            val id = withTimeoutOrNull(beginTimeoutMs) { v.begin.await() }
            if (id == null) {
                log("Voz: la tele no abrió sesión de voz")
                return if (isConnected) VoiceResult.NOT_OFFERED else VoiceResult.NO_CONNECTION
            }
            v.sessionId = id
            enqueue(RemoteMessages.voiceBegin(id)) // el protocolo pide repetir el «empieza»
            val bytes = AtomicLong()
            try {
                if (release.isCompleted) return VoiceResult.DONE // toque corto: no se llega a grabar
                onPhase(VoicePhase.LISTENING)
                coroutineScope {
                    val recording = launch {
                        source.record { pcm ->
                            RemoteMessages.voicePayloads(id, pcm).forEach { enqueue(it) }
                            bytes.addAndGet(pcm.size.toLong())
                        }
                    }
                    withTimeoutOrNull(maxMs) {
                        select<Unit> { release.onAwait {}; v.heard.onAwait {}; v.ended.onAwait {} }
                    }
                    recording.cancelAndJoin()
                }
                // Aquí el micrófono ya está cerrado: lo que sigue son ceros.
                onPhase(VoicePhase.FINISHING)
                if (!v.ended.isCompleted) coroutineScope {
                    val silence = launch {
                        val zeros = ByteArray(RemoteMessages.VOICE_CHUNK)
                        while (true) {
                            delay(RemoteMessages.VOICE_CHUNK_MS)
                            RemoteMessages.voicePayloads(id, zeros).forEach { enqueue(it) }
                        }
                    }
                    val heard = withTimeoutOrNull(tailMaxMs) {
                        select { v.heard.onAwait { true }; v.ended.onAwait { false } }
                    }
                    when (heard) {
                        true -> {
                            log("Voz: la tele ya tiene la frase")
                            withTimeoutOrNull(lingerMs) { v.ended.await() }
                        }
                        null -> log("Voz: la tele no avisó del final de la frase")
                        false -> Unit
                    }
                    silence.cancel()
                }
                return VoiceResult.DONE
            } finally {
                enqueue(RemoteMessages.voiceEnd(id))
                log("Voz: sesión $id cerrada (${bytes.get()} bytes de audio del micrófono)")
            }
        } finally {
            voice = null
        }
    }

    private fun enqueue(bytes: ByteArray): Boolean {
        if (!isConnected) return false
        return outgoing?.trySend(bytes)?.isSuccess == true
    }

    private suspend fun connectLoop(tv: TvTarget) {
        var attempt = 0
        var sslFailures = 0
        while (true) {
            _state.value = ConnectionState.Connecting(attempt)
            var connected = false
            var reachedTv = false
            val trust = TvTrustManager(tv.pinnedFingerprint)
            try {
                connectOnce(tv, trust, onReached = { reachedTv = true }) { connected = true }
                log("La tele cerró la conexión")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Conexión: ${e.javaClass.simpleName}: ${e.message}")
                if (trust.pinMismatch) {
                    _state.value = ConnectionState.CertificateChanged
                    awaitCancellation()
                }
                // Si la tele no reconoce el certificado del móvil, corta la conexión durante o justo
                // después del TLS (a veces con aviso «certificate_unknown», a veces sin más). Si la
                // tele responde pero corta 3 veces seguidas antes de terminar, hay que emparejar.
                if (!connected && (reachedTv || e.isSslRejection())) {
                    sslFailures++
                    if ((e.isSslRejection() && e.mentionsCertificate()) || sslFailures >= 3) {
                        _state.value = ConnectionState.NeedsPairing
                        awaitCancellation()
                    }
                } else if (!connected) {
                    sslFailures = 0 // fallo de red normal (tele apagada, sin WiFi...)
                }
            } finally {
                outgoing = null
            }
            if (connected) {
                attempt = 0
                sslFailures = 0
            }
            if (!connected && attempt == 1) onUnreachable(tv)
            val wait = Backoff.delayFor(attempt)
            _state.value = ConnectionState.Waiting(clock() + wait, if (connected) "Se cortó la conexión" else "La tele no responde")
            attempt++
            delay(wait)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun connectOnce(
        tv: TvTarget,
        trust: TvTrustManager,
        onReached: () -> Unit,
        onConnected: () -> Unit,
    ) = coroutineScope {
        _status.value = TvStatus()
        imeCounter = 0
        fieldCounter = 0
        shownField = null
        val ssl: SSLSocket = runInterruptible(io) {
            val tcp = gate.connectTcp(tv.host, LanPolicy.PORT_REMOTE)
            onReached() // la tele aceptó la conexión TCP
            try {
                Tls.wrap(tcp, tv.host, identityProvider(), trust).also {
                    // Se comprueba cada pocos segundos si la conexión sigue viva (ver onIdle abajo).
                    it.soTimeout = idlePollMs
                }
            } catch (e: Exception) {
                runCatching { tcp.close() }
                throw e
            }
        }
        // Al cancelar (app en segundo plano, cambio de WiFi...) se cierra el socket y la lectura termina.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { ssl.close() } }
        }
        val channel = Channel<ByteArray>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val out = ssl.outputStream
        val writer = launch(io) {
            for (msg in channel) write(out, msg)
        }
        try {
            runInterruptible(io) {
                val input = ssl.inputStream
                lastRx = clock()
                // La tele hace ping cada ~5 s, pero NO mientras le enviamos órdenes. Por eso la
                // conexión solo se da por muerta si no hay tráfico en NINGÚN sentido.
                val onIdle = {
                    if (clock() - maxOf(lastRx, lastTx) > idleTimeoutMs) {
                        throw java.io.IOException("La tele dejó de responder")
                    }
                }
                while (true) {
                    val msg = RemoteMessages.parse(Framing.read(input, onIdle))
                    lastRx = clock()
                    when (msg) {
                        is Incoming.Configure -> {
                            features = RemoteMessages.negotiate(msg.features)
                            log("La tele admite funciones ${msg.features}; anunciamos $features")
                            write(out, RemoteMessages.configure(appVersion, features))
                        }
                        is Incoming.SetActive -> {
                            write(out, RemoteMessages.setActive(features))
                            if (outgoing == null) {
                                outgoing = channel
                                _state.value = ConnectionState.Connected(tv.name)
                                onConnected()
                            }
                        }
                        is Incoming.Ping -> write(out, RemoteMessages.pingResponse(msg.value))
                        is Incoming.PowerState -> _status.update { it.copy(powerOn = msg.on) }
                        is Incoming.Volume -> _status.update {
                            it.copy(volume = msg.level, volumeMax = msg.max, muted = msg.muted)
                        }
                        is Incoming.ImeKeyInject -> {
                            log("Teclado: informe de la tele ${msg.structure}")
                            // Los contadores se toman tal cual llegan (también los ceros): si se
                            // conservan valores viejos, la tele ignora el texto (pasa en Sony BRAVIA).
                            imeCounter = msg.imeCounter
                            fieldCounter = msg.fieldCounter
                            if (msg.hasTextField) {
                                if (shownField == null) {
                                    shownField = msg.fieldCounter
                                    _ime.tryEmit(ImeEvent.Show(msg.fieldValue, msg.fieldLabel))
                                }
                            } else if (shownField != null) {
                                shownField = null
                                _ime.tryEmit(ImeEvent.Hide)
                            }
                            _status.update { it.copy(currentApp = msg.appPackage.ifEmpty { null }) }
                        }
                        is Incoming.ImeShow -> {
                            // Solo se registra la longitud, nunca el contenido del campo.
                            log("Teclado: la tele informa del campo (contador ${msg.fieldCounter}, ${msg.value.length} caracteres)")
                            msg.fieldCounter?.let {
                                // La tele confirma cada edición con un contador de campo nuevo: hay que usarlo.
                                fieldCounter = it
                                // Solo se abre si no estaba ya abierto (si no, se reiniciaría mientras escribes).
                                if (shownField == null) {
                                    shownField = it
                                    _ime.tryEmit(ImeEvent.Show(msg.value, msg.label))
                                }
                            }
                        }
                        is Incoming.ImeBatchEdit -> {
                            log("Teclado: la tele confirma edición (contadores ${msg.imeCounter}/${msg.fieldCounter})")
                            // Solo como último recurso, si la tele no dio contadores en su informe
                            if (imeCounter == 0 && fieldCounter == 0) {
                                imeCounter = msg.imeCounter
                                fieldCounter = msg.fieldCounter
                            }
                        }
                        Incoming.ImeClosed -> {
                            log("Teclado: la tele cerró su teclado")
                            if (shownField != null) {
                                shownField = null
                                _ime.tryEmit(ImeEvent.Hide)
                            }
                        }
                        is Incoming.VoiceBegin -> {
                            val v = voice
                            if (v != null && !v.begin.isCompleted) {
                                log("Voz: la tele abre la sesión ${msg.sessionId}")
                                v.begin.complete(msg.sessionId)
                            } else {
                                // Nadie la espera ya (se soltó el botón antes): se cierra para que la tele no se quede escuchando.
                                log("Voz: la tele abre la sesión ${msg.sessionId} sin que se espere; se cierra")
                                write(out, RemoteMessages.voiceEnd(msg.sessionId))
                            }
                        }
                        is Incoming.VoiceHeard -> {
                            log("Voz: la tele detecta el final de la frase (sesión ${msg.sessionId})")
                            voice?.takeIf { it.sessionId == msg.sessionId }?.heard?.complete(Unit)
                        }
                        is Incoming.VoiceEnd -> {
                            log("Voz: la tele cierra la sesión ${msg.sessionId}")
                            voice?.takeIf { it.sessionId == msg.sessionId }?.ended?.complete(Unit)
                        }
                        is Incoming.Error -> log("La tele informó de un error")
                        is Incoming.Other -> log("Mensaje nuevo de la tele: ${msg.structure}")
                    }
                }
            }
        } finally {
            outgoing = null
            // Si había una sesión de voz, termina aquí (deja de grabar).
            voice?.let { it.begin.complete(null); it.ended.complete(Unit) }
            channel.close()
            writer.cancel()
            closer.cancel()
            runCatching { ssl.close() }
        }
    }

    private fun write(out: OutputStream, msg: ByteArray) {
        synchronized(out) { Framing.write(out, msg) }
        lastTx = clock()
    }

    private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }
    private fun Throwable.isSslRejection() = chain().any { it is SSLException }
    private fun Throwable.mentionsCertificate() = chain().any {
        val m = it.message?.lowercase().orEmpty()
        "certificate" in m || "bad_cert" in m
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 16_000
        /** Lo que se espera a que la tele abra la sesión de voz tras la tecla Buscar. */
        const val VOICE_BEGIN_TIMEOUT_MS = 2_000L
        /** Duración máxima de una sesión de voz: el micrófono nunca se queda abierto. */
        const val VOICE_MAX_MS = 15_000L
        /** Tras soltar el botón, lo que se espera (enviando silencio) a que la tele dé la frase por terminada. */
        const val VOICE_TAIL_MAX_MS = 6_000L
        /** Lo que se mantiene abierta la sesión después, para que la tele termine de reconocer. */
        const val VOICE_LINGER_MS = 3_000L
    }
}
