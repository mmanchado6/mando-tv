package local.mandotv

import local.mandotv.crypto.ClientIdentity
import local.mandotv.crypto.PairingSecret
import local.mandotv.net.BlockedDestinationException
import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import local.mandotv.remote.Backoff
import local.mandotv.remote.WakeOnLan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.interfaces.RSAPublicKey
import java.util.Date

class SecurityTest {

    // ---------- Certificado del móvil ----------

    @Test fun generatedCertificateIsValidSelfSignedRsa2048() {
        val id = TestKeys.phone
        val cert = id.certificate
        cert.checkValidity()
        cert.verify(cert.publicKey) // autofirmado: su propia clave valida la firma
        assertEquals(2048, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertTrue(cert.subjectX500Principal.name.contains("CN=Mando TV"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertTrue(cert.notAfter.after(Date(System.currentTimeMillis() + 19L * 365 * 24 * 3600 * 1000)))
    }

    @Test fun certificateDatesFrom2050UseGeneralizedTime() {
        val future = ClientIdentity.generate(now = Date(2_600_000_000_000L)) // año 2052
        assertTrue(future.certificate.notAfter.after(future.certificate.notBefore))
    }

    @Test fun identityRoundTrip() {
        val decoded = ClientIdentity.decode(TestKeys.phone.encode())
        assertArrayEquals(TestKeys.phone.certificate.encoded, decoded.certificate.encoded)
        assertArrayEquals(TestKeys.phone.privateKey.encoded, decoded.privateKey.encoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptIdentityIsRejected() {
        ClientIdentity.decode(byteArrayOf(0, 0, 0, 50, 1, 2, 3))
    }

    // ---------- Secreto de emparejamiento ----------

    private fun codeFor(nonce: ByteArray): String {
        val hash = FakeTv.expectedHash(TestKeys.phone.certificate, TestKeys.tv.certificate, nonce)
        return "%02X".format(hash[0]) + nonce.joinToString("") { "%02X".format(it) }
    }

    @Test fun secretMatchesIndependentImplementation() {
        for (nonce in listOf(byteArrayOf(0, 0), byteArrayOf(0x12, 0x34), byteArrayOf(-1, -1), byteArrayOf(0x0A, -0x50))) {
            val code = codeFor(nonce)
            val secret = PairingSecret.compute(TestKeys.phone.certificate, TestKeys.tv.certificate, code)
            assertArrayEquals(FakeTv.expectedHash(TestKeys.phone.certificate, TestKeys.tv.certificate, nonce), secret)
            assertEquals(32, secret.size)
            // En minúsculas y con espacios también vale
            PairingSecret.compute(TestKeys.phone.certificate, TestKeys.tv.certificate, " " + code.lowercase().chunked(3).joinToString(" "))
        }
    }

    @Test fun wrongChecksumIsDetectedLocally() {
        val good = codeFor(byteArrayOf(0x55, 0x66))
        val badFirst = "%02X".format((good.substring(0, 2).toInt(16) + 1) and 0xFF) + good.substring(2)
        try {
            PairingSecret.compute(TestKeys.phone.certificate, TestKeys.tv.certificate, badFirst)
            fail("el código erróneo debía rechazarse")
        } catch (e: PairingSecret.InvalidCodeException) {
            assertEquals("Código incorrecto", e.message)
        }
    }

    @Test fun malformedCodesAreRejected() {
        for (bad in listOf("", "12345", "1234567", "12345G", "ÑÑÑÑÑÑ", "12 34 5")) {
            try {
                PairingSecret.compute(TestKeys.phone.certificate, TestKeys.tv.certificate, bad)
                fail("«$bad» debía rechazarse")
            } catch (_: PairingSecret.InvalidCodeException) {}
        }
    }

    @Test fun unsignedBytesDropTheSignByte() {
        // 2048 bits con el bit alto a 1: BigInteger añade un 0x00 que el protocolo NO incluye
        val n = BigInteger.ONE.shiftLeft(2047).add(BigInteger.ONE)
        assertEquals(257, n.toByteArray().size)
        assertEquals(256, PairingSecret.unsigned(n).size)
        assertArrayEquals(byteArrayOf(1, 0, 1), PairingSecret.unsigned(BigInteger.valueOf(65537)))
    }

    // ---------- Puerta de red: solo red local ----------

    private val policy = LanPolicy()

    @Test fun acceptsPrivateIpv4() {
        for (ip in listOf("192.168.1.50", "192.168.0.1", "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.255")) {
            assertEquals(ip, policy.checkTcp(ip, 6466).hostAddress)
            policy.checkTcp(ip, 6467)
        }
    }

    @Test fun blocksEverythingElse() {
        val blocked = listOf(
            "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "192.169.1.1", "11.0.0.1",
            "127.0.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.251", "169.254.1.1", "100.64.0.1",
            "192.168.1", "192.168.1.256", "192.168.01.5", "192.168.1.5.", " ", "",
            "tele.local", "google.com", "localhost", "::1", "fe80::1", "fd00::1", "2001:4860:4860::8888",
            "192.168.1.5:80", "0x7f.0.0.1", "3232235777", // 192.168.1.1 en decimal: no se acepta
        )
        for (host in blocked) {
            try { policy.checkTcp(host, 6466); fail("debía bloquearse: «$host»") } catch (_: BlockedDestinationException) {}
        }
    }

    @Test fun blocksOtherPorts() {
        for (port in listOf(0, 22, 53, 80, 443, 5555, 6465, 6468, 8008, 8009)) {
            try { policy.checkTcp("192.168.1.50", port); fail("puerto $port") } catch (_: BlockedDestinationException) {}
        }
    }

    @Test fun gateNeverOpensSocketToPublicAddress() {
        var bound = 0
        val gate = LanGate(policy, object : local.mandotv.net.NetworkBinder {
            override fun bind(socket: java.net.Socket) { bound++ }
            override fun bind(socket: java.net.DatagramSocket) { bound++ }
        })
        try { gate.connectTcp("8.8.8.8", 6466); fail() } catch (_: BlockedDestinationException) {}
        try { gate.sendUdp(InetAddress.getByName("8.8.8.8"), 9, ByteArray(102), broadcast = true); fail() } catch (_: BlockedDestinationException) {}
        try { gate.sendUdp(InetAddress.getByName("192.168.1.255"), 7, ByteArray(102), broadcast = true); fail() } catch (_: BlockedDestinationException) {}
        assertEquals("no debía llegar a crearse ningún socket", 0, bound)
    }

    @Test fun parseDoesNotUseDns() {
        // Si se resolvieran nombres, esto tardaría o devolvería algo; debe ser null al instante.
        assertNull(policy.parseIpv4("example.com"))
        assertNotNull(policy.parseIpv4("192.168.1.10"))
    }

    // ---------- Wake-on-LAN ----------

    @Test fun macParsing() {
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03)
        for (t in listOf("AA:BB:CC:01:02:03", "aa-bb-cc-01-02-03", "aabbcc010203", " AA:BB:CC:01:02:03 ")) {
            assertArrayEquals(t, expected, WakeOnLan.parseMac(t))
        }
        for (bad in listOf("", "AA:BB:CC:01:02", "GG:BB:CC:01:02:03", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            assertNull(bad, WakeOnLan.parseMac(bad))
        }
        assertEquals("AA:BB:CC:01:02:03", WakeOnLan.formatMac(expected))
    }

    @Test fun magicPacket() {
        val mac = WakeOnLan.parseMac("11:22:33:44:55:66")!!
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test fun wakeOnLanOnlyAllowsLocalBroadcastOnPort9() {
        policy.checkWakeOnLan(InetAddress.getByName("192.168.1.255"), 9)
        policy.checkWakeOnLan(InetAddress.getByName("10.0.0.255"), 9)
        for (a in listOf("255.255.255.255", "8.8.8.255")) {
            try { policy.checkWakeOnLan(InetAddress.getByName(a), 9); fail(a) } catch (_: BlockedDestinationException) {}
        }
    }

    // ---------- Reintentos ----------

    @Test fun backoffSequence() {
        assertEquals(listOf(500L, 1000, 2000, 4000, 8000, 15000, 15000), (0..6).map { Backoff.delayFor(it) })
        assertEquals(15000L, Backoff.delayFor(1000))
    }
}
