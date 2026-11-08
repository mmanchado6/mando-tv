package local.mandotv.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Codificador DER (ASN.1) mínimo: lo justo para un certificado X.509 autofirmado. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> { out.write(0x81); out.write(len) }
            len <= 0xFFFF -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun nul() = byteArrayOf(0x05, 0x00)
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    fun octetString(bytes: ByteArray) = tlv(0x04, bytes)
    fun explicit(n: Int, content: ByteArray) = tlv(0xA0 or n, content)

    /** UTCTime hasta 2049 y GeneralizedTime desde 2050, como exige RFC 5280. */
    fun time(date: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date).toInt()
        return if (year < 2050) {
            tlv(0x17, fmt("yyMMddHHmmss'Z'", date).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, fmt("yyyyMMddHHmmss'Z'", date).toByteArray(Charsets.US_ASCII))
        }
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun fmt(p: String, d: Date) = SimpleDateFormat(p, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(); parts.forEach { out.write(it) }; return out.toByteArray()
    }
}

/** Identidad del móvil ante la tele: clave privada RSA + certificado autofirmado. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** Serialización para guardarla (cifrada) en el móvil. */
    fun encode(): ByteArray {
        val key = privateKey.encoded
        val cert = certificate.encoded
        return ByteArrayOutputStream().apply {
            write(intBytes(key.size)); write(key); write(intBytes(cert.size)); write(cert)
        }.toByteArray()
    }

    companion object {
        private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
        private const val COMMON_NAME = "2.5.4.3"
        private const val BASIC_CONSTRAINTS = "2.5.29.19"

        /** Genera una clave RSA 2048 y un certificado X.509 v3 autofirmado (SHA256withRSA). */
        fun generate(
            commonName: String = "Mando TV",
            random: SecureRandom = SecureRandom(),
            now: Date = Date(),
        ): ClientIdentity {
            val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val algorithm = Der.seq(Der.oid(SHA256_WITH_RSA), Der.nul())
            val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
            val notBefore = Date(now.time - 24L * 3600 * 1000)
            val notAfter = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
            val serial = BigInteger(63, random).add(BigInteger.ONE)
            val extensions = Der.explicit(
                3,
                Der.seq(Der.seq(Der.oid(BASIC_CONSTRAINTS), Der.bool(true), Der.octetString(Der.seq()))),
            )
            val tbs = Der.seq(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // versión v3
                Der.integer(serial),
                algorithm,
                name,
                Der.seq(Der.time(notBefore), Der.time(notAfter)),
                name,
                keyPair.public.encoded, // SubjectPublicKeyInfo ya viene en DER
                extensions,
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private); update(tbs); sign()
            }
            val der = Der.seq(tbs, algorithm, Der.bitString(signature))
            return ClientIdentity(keyPair.private, parseCertificate(der))
        }

        fun decode(data: ByteArray): ClientIdentity {
            var pos = 0
            fun chunk(): ByteArray {
                require(pos + 4 <= data.size) { "identidad dañada" }
                val n = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4
                require(n in 1..(data.size - pos)) { "identidad dañada" }
                return data.copyOfRange(pos, pos + n).also { pos += n }
            }
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(chunk()))
            return ClientIdentity(key, parseCertificate(chunk()))
        }

        fun parseCertificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        private fun intBytes(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
}

/** Huella SHA-256 de la clave pública de un certificado (para fijar el de la tele). */
fun X509Certificate.publicKeyFingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).joinToString("") { "%02x".format(it) }

/**
 * Cálculo del secreto de emparejamiento del protocolo «polo»:
 *   SHA-256( módulo y exponente del cliente, módulo y exponente de la tele, últimos 4 caracteres del código )
 * El primer byte del hash debe coincidir con los 2 primeros caracteres del código: es una suma de
 * control que permite detectar un código mal escrito antes de enviarlo.
 */
object PairingSecret {
    class InvalidCodeException(message: String) : Exception(message)

    /** Normaliza lo que escribe el usuario: quita espacios y pasa a mayúsculas. */
    fun normalize(code: String): String = code.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)

    fun isWellFormed(code: String): Boolean = code.length == 6 && code.all { it in '0'..'9' || it in 'A'..'F' }

    /** Devuelve el secreto (32 bytes) o lanza [InvalidCodeException] si el código no es válido. */
    fun compute(client: X509Certificate, server: X509Certificate, rawCode: String): ByteArray {
        val code = normalize(rawCode)
        if (!isWellFormed(code)) throw InvalidCodeException("El código debe tener 6 caracteres (0-9, A-F)")
        val c = client.publicKey as? RSAPublicKey ?: throw InvalidCodeException("Clave del móvil no RSA")
        val s = server.publicKey as? RSAPublicKey ?: throw InvalidCodeException("Clave de la tele no RSA")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(c.modulus))
        digest.update(unsigned(c.publicExponent))
        digest.update(unsigned(s.modulus))
        digest.update(unsigned(s.publicExponent))
        digest.update(hex(code.substring(2)))
        val hash = digest.digest()
        if (hash[0] != hex(code.substring(0, 2))[0]) throw InvalidCodeException("Código incorrecto")
        return hash
    }

    /** Bytes big-endian sin signo y sin ceros a la izquierda (como hace la implementación oficial). */
    internal fun unsigned(v: BigInteger): ByteArray {
        val b = v.abs().toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
