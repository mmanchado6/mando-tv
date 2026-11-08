package local.mandotv.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Codificador/decodificador protobuf mínimo, escrito a mano para no depender de librerías.
 * Solo implementa lo que usa el protocolo Android TV Remote v2:
 *  - tipo 0 (varint) y tipo 2 (bytes / texto / mensaje anidado).
 *  - Al leer, los tipos 1 (64 bits) y 5 (32 bits) se saltan sin error.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeVarint(out, value)
        return this
    }

    fun int(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_BYTES)
        writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeVarint(out, ((field shl 3) or wire).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_BYTES = 2
        const val WIRE_FIXED32 = 5

        /** Escribe un varint. Los negativos ocupan 10 bytes, como en protobuf (int32/int64). */
        fun writeVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun varintBytes(value: Long): ByteArray =
            ByteArrayOutputStream().also { writeVarint(it, value) }.toByteArray()
    }
}

class ProtoException(message: String) : IOException(message)

/** Un campo leído de un mensaje: número, y su valor como varint o como bytes. */
class ProtoField(val number: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val string: String get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)
    fun message(): ProtoMessage = ProtoMessage.parse(bytes ?: ByteArray(0))
}

/** Mensaje decodificado: lista de campos en orden. Los campos desconocidos simplemente se ignoran. */
class ProtoMessage private constructor(val fields: List<ProtoField>) {
    fun has(number: Int) = fields.any { it.number == number }
    fun first(number: Int): ProtoField? = fields.firstOrNull { it.number == number }
    fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }
    fun int(number: Int, default: Int = 0) = first(number)?.int ?: default
    fun bool(number: Int) = first(number)?.bool ?: false
    fun string(number: Int) = first(number)?.string ?: ""
    fun bytes(number: Int) = first(number)?.bytes
    fun message(number: Int): ProtoMessage? = first(number)?.message()

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = ArrayList<ProtoField>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw ProtoException("varint truncado")
                    val b = data[pos++].toInt() and 0xFF
                    if (shift >= 64) throw ProtoException("varint demasiado largo")
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                if (number <= 0) throw ProtoException("número de campo inválido")
                when ((key and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> fields += ProtoField(number, readVarint(), null)
                    ProtoWriter.WIRE_BYTES -> {
                        val len = readVarint()
                        if (len < 0 || len > data.size - pos) throw ProtoException("longitud inválida")
                        fields += ProtoField(number, 0, data.copyOfRange(pos, pos + len.toInt()))
                        pos += len.toInt()
                    }
                    ProtoWriter.WIRE_FIXED64 -> pos = skip(pos, 8, data.size)
                    ProtoWriter.WIRE_FIXED32 -> pos = skip(pos, 4, data.size)
                    else -> throw ProtoException("tipo de campo no soportado")
                }
            }
            return ProtoMessage(fields)
        }

        private fun skip(pos: Int, n: Int, size: Int): Int {
            if (pos + n > size) throw ProtoException("campo truncado")
            return pos + n
        }
    }
}

/**
 * Enmarcado del protocolo: cada mensaje va precedido de su longitud en formato varint.
 * Leer de un InputStream bloqueante resuelve solo los mensajes partidos o pegados.
 */
object Framing {
    /** Tamaño máximo aceptado para un mensaje (los reales son de pocos cientos de bytes). */
    const val MAX_MESSAGE = 64 * 1024

    fun frame(payload: ByteArray): ByteArray = ProtoWriter.varintBytes(payload.size.toLong()) + payload

    fun write(out: OutputStream, payload: ByteArray) {
        out.write(frame(payload))
        out.flush()
    }

    /**
     * Lee un mensaje completo. Lanza EOFException si la conexión se cierra.
     *
     * Si el socket tiene tiempo de espera, [onIdle] se llama cada vez que vence mientras se espera
     * el comienzo de un mensaje (puede lanzar una excepción para abandonar). Una vez empezado un
     * mensaje, los vencimientos se ignoran para no perder bytes a medias.
     */
    fun read(input: InputStream, onIdle: (() -> Unit)? = null): ByteArray {
        var len = 0L
        var shift = 0
        var started = false
        fun readByte(): Int {
            while (true) {
                try {
                    return input.read()
                } catch (e: java.net.SocketTimeoutException) {
                    if (!started) onIdle?.invoke() ?: throw e
                }
            }
        }
        while (true) {
            val b = readByte()
            started = true
            if (b < 0) throw EOFException("conexión cerrada")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw ProtoException("longitud de mensaje inválida")
        }
        if (len > MAX_MESSAGE) throw ProtoException("mensaje demasiado grande: $len")
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) {
            val n = try {
                input.read(buf, read, buf.size - read)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            }
            if (n < 0) throw EOFException("conexión cerrada a mitad de mensaje")
            read += n
        }
        return buf
    }
}
