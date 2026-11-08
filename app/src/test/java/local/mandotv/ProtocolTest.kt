package local.mandotv

import local.mandotv.protocol.Framing
import local.mandotv.protocol.PairingMessages
import local.mandotv.protocol.ProtoException
import local.mandotv.protocol.ProtoMessage
import local.mandotv.protocol.ProtoWriter
import local.mandotv.protocol.RemoteMessages
import local.mandotv.protocol.RemoteMessages.Incoming
import local.mandotv.protocol.TvKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

class ProtocolTest {

    @Test fun varints() {
        assertArrayEquals(hex("00"), ProtoWriter.varintBytes(0))
        assertArrayEquals(hex("7f"), ProtoWriter.varintBytes(127))
        assertArrayEquals(hex("80 01"), ProtoWriter.varintBytes(128))
        assertArrayEquals(hex("ac 02"), ProtoWriter.varintBytes(300))
        assertArrayEquals(hex("ff ff ff ff 07"), ProtoWriter.varintBytes(Int.MAX_VALUE.toLong()))
        // Un int32 negativo ocupa 10 bytes en protobuf
        assertEquals(10, ProtoWriter.varintBytes(-1).size)
        val decoded = ProtoMessage.parse(ProtoWriter().int(1, -1).int(2, 300).toByteArray())
        assertEquals(-1, decoded.int(1))
        assertEquals(300, decoded.int(2))
    }

    @Test fun exactBytesOfKeyPress() {
        // RemoteMessage{ remote_key_inject(10){ key_code=24 (VOLUMEN+), direction=SHORT(3) } }
        assertEquals("52 04 08 18 10 03", RemoteMessages.key(TvKey.VOLUME_UP.code).hex())
        assertEquals("06 52 04 08 18 10 03", Framing.frame(RemoteMessages.key(24)).sliceArray(0..6).hex())
        assertEquals("52 05 08 b0 01 10 03", RemoteMessages.key(TvKey.SETTINGS.code).hex())
    }

    @Test fun exactBytesOfPairingMessages() {
        // version=2, status=200, pairing_request{service_name="atvremote", client_name="Mando TV"}
        assertEquals(
            "08 02 10 c8 01 52 15 0a 09 61 74 76 72 65 6d 6f 74 65 12 08 4d 61 6e 64 6f 20 54 56",
            PairingMessages.pairingRequest("Mando TV").hex(),
        )
        // options{ input_encodings{type=HEX(3), symbol_length=6}, preferred_role=INPUT(1) }
        assertEquals("08 02 10 c8 01 a2 01 08 0a 04 08 03 10 06 18 01", PairingMessages.options().hex())
        // configuration{ encoding{3,6}, client_role=1 }
        assertEquals("08 02 10 c8 01 f2 01 08 0a 04 08 03 10 06 10 01", PairingMessages.configuration().hex())
        val secret = PairingMessages.secret(ByteArray(32) { it.toByte() })
        assertEquals("08 02 10 c8 01 c2 02 22 0a 20", secret.sliceArray(0..9).hex())
    }

    @Test fun pairingReplies() {
        val ack = PairingMessages.parse(hex("08 02 10 c8 01 5a 00"))
        assertTrue(ack.ok); assertEquals(PairingMessages.F_PAIRING_REQUEST_ACK, ack.kind)
        val bad = PairingMessages.parse(hex("08 02 10 92 03"))
        assertFalse(bad.ok); assertEquals(402, bad.status)
    }

    @Test fun remoteHandshakeMessages() {
        assertEquals("12 03 08 ff 04", RemoteMessages.setActive().hex()) // 639 = PING|KEY|IME|VOICE|16|POWER|VOLUME|APP_LINK
        assertEquals("4a 02 08 07", RemoteMessages.pingResponse(7).hex())
        val configure = ProtoMessage.parse(RemoteMessages.configure("1.0")).message(1)!!
        assertEquals(639, configure.int(1))
        assertEquals("Mando TV", configure.message(2)!!.string(1))
        assertEquals("local.mandotv", configure.message(2)!!.string(5))
    }

    @Test fun textAndAppLink() {
        val m = ProtoMessage.parse(RemoteMessages.text("Señor 1", imeCounter = 4, fieldCounter = 9)).message(21)!!
        assertEquals(4, m.int(1)); assertEquals(9, m.int(2))
        val edit = m.message(3)!!
        assertEquals(1, edit.int(1))
        val obj = edit.message(2)!!
        assertEquals(7, obj.int(1)); assertEquals(7, obj.int(2)); assertEquals("Señor 1", obj.string(3))
        val link = ProtoMessage.parse(RemoteMessages.appLink("https://www.youtube.com")).message(90)!!
        assertEquals("https://www.youtube.com", link.string(1))
    }

    @Test fun parsesIncomingMessages() {
        val ping = RemoteMessages.parse(ProtoWriter().message(8) { int(1, 42); int(2, 1) }.toByteArray())
        assertEquals(Incoming.Ping(42), ping)
        val start = RemoteMessages.parse(ProtoWriter().message(40) { bool(1, true) }.toByteArray())
        assertEquals(Incoming.PowerState(true), start)
        val vol = RemoteMessages.parse(ProtoWriter().message(50) {
            int(1, 1); string(3, "tv"); int(6, 100); int(7, 35); bool(8, true)
        }.toByteArray())
        assertEquals(Incoming.Volume(35, 100, true), vol)
        val ime = RemoteMessages.parse(ProtoWriter().message(20) {
            message(1) { int(1, 3); string(12, "com.google.android.youtube.tv") }
            message(2) { int(1, 5) }
        }.toByteArray())
        ime as Incoming.ImeKeyInject
        assertEquals("com.google.android.youtube.tv", ime.appPackage)
        assertEquals(3, ime.imeCounter); assertEquals(5, ime.fieldCounter); assertTrue(ime.hasTextField)
        assertEquals(Incoming.SetActive, RemoteMessages.parse(ProtoWriter().message(2) {}.toByteArray()))
        assertTrue(RemoteMessages.parse(ProtoWriter().message(61) {}.toByteArray()) is Incoming.Other)
    }

    @Test fun textEditsBetweenOldAndNewText() {
        val E = RemoteMessages::TextEdit
        assertEquals(null, RemoteMessages.textEdit("abc", "abc"))
        assertEquals(E(0, "d"), RemoteMessages.textEdit("abc", "abcd"))
        assertEquals(E(1, ""), RemoteMessages.textEdit("abc", "ab"))
        assertEquals(E(3, ""), RemoteMessages.textEdit("abc", ""))
        assertEquals(E(2, "Xc"), RemoteMessages.textEdit("abc", "aXc")) // cambio en medio: borrar y reescribir
        assertEquals(E(0, "😀"), RemoteMessages.textEdit("hola", "hola😀"))
        assertEquals(E(1, ""), RemoteMessages.textEdit("hola😀", "hola")) // un emoji = una pulsación de borrar
        assertEquals(E(1, "😃"), RemoteMessages.textEdit("x😀", "x😃"))
    }

    @Test fun imeShowRequestCarriesCurrentText() {
        val show = RemoteMessages.parse(ProtoWriter().message(22) {
            message(2) { int(1, 9); string(2, "pelí"); int(3, 4); int(4, 4); string(6, "Buscar") }
        }.toByteArray())
        assertEquals(Incoming.ImeShow(9, "pelí", "Buscar"), show)
        assertEquals(Incoming.ImeShow(null, "", ""), RemoteMessages.parse(ProtoWriter().message(22) {}.toByteArray()))
    }

    @Test fun keyInjectWithTextFieldMeansKeyboard() {
        // Como en las teles reales: sin campo de texto solo llega app_info; con el buscador activo, también text_field_status
        val noField = RemoteMessages.parse(ProtoWriter().message(20) {
            message(1) { int(1, 214); string(12, "com.google.android.youtube.tv") }
        }.toByteArray()) as Incoming.ImeKeyInject
        assertFalse(noField.hasTextField)
        val focused = RemoteMessages.parse(ProtoWriter().message(20) {
            message(1) { int(1, 215); string(12, "com.google.android.youtube.tv") }
            message(2) { int(1, 701); string(2, "gatos"); int(5, 1); string(6, "Buscar") }
        }.toByteArray()) as Incoming.ImeKeyInject
        assertTrue(focused.hasTextField)
        assertEquals(215, focused.imeCounter); assertEquals(701, focused.fieldCounter)
        assertEquals("gatos", focused.fieldValue); assertEquals("Buscar", focused.fieldLabel)
        // El resumen para el registro no contiene el texto, solo longitudes
        assertFalse(focused.structure, focused.structure.contains("gatos") || focused.structure.contains("youtube"))
        assertTrue(focused.structure, focused.structure.contains("701"))
    }

    @Test fun featureNegotiation() {
        assertEquals(639, RemoteMessages.negotiate(0))
        assertEquals(639, RemoteMessages.negotiate(639)) // lo que anuncia tu Sony
        assertEquals(0x26f, RemoteMessages.negotiate(0x26f))
        assertEquals(RemoteMessages.FEATURE_KEY or RemoteMessages.FEATURE_PING, RemoteMessages.negotiate(3))
    }

    @Test fun ignoresUnknownFieldsOfAllWireTypes() {
        val data = hex("08 05") + hex("11 01 02 03 04 05 06 07 08") + hex("1d 01 02 03 04") + hex("22 02 aa bb") + hex("28 07")
        val m = ProtoMessage.parse(data)
        assertEquals(5, m.int(1)); assertEquals(7, m.int(5))
    }

    @Test fun rejectsCorruptMessages() {
        for (bad in listOf(hex("0a 05 01"), hex("08"), hex("0b"))) {
            try { ProtoMessage.parse(bad); fail("debía fallar: ${bad.hex()}") } catch (_: ProtoException) {}
        }
    }

    @Test fun framingHandlesSplitAndJoinedMessages() {
        val a = RemoteMessages.key(19)
        val b = ByteArray(300) { 7 } // longitud de 2 bytes en varint
        val stream = Framing.frame(a) + Framing.frame(b)
        // InputStream que entrega de byte en byte (mensajes partidos en cualquier punto)
        val trickle = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < stream.size) stream[i++].toInt() and 0xFF else -1
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (i >= stream.size) return -1
                buf[off] = stream[i++]; return 1
            }
        }
        assertArrayEquals(a, Framing.read(trickle))
        assertArrayEquals(b, Framing.read(trickle))
        try { Framing.read(trickle); fail() } catch (_: EOFException) {}
        // Todo junto en un solo bloque
        val joined = ByteArrayInputStream(stream)
        assertArrayEquals(a, Framing.read(joined)); assertArrayEquals(b, Framing.read(joined))
    }

    @Test fun framingWaitsThroughTimeoutsWithoutLosingBytes() {
        val data = Framing.frame(RemoteMessages.key(19)) + Framing.frame(RemoteMessages.key(20))
        var i = 0
        var timeoutsLeft = 3
        // Vence el tiempo antes del primer byte y también a mitad de mensaje
        val flaky = object : InputStream() {
            override fun read(): Int {
                if ((i == 0 || i == 3) && timeoutsLeft-- > 0) throw java.net.SocketTimeoutException()
                return if (i < data.size) data[i++].toInt() and 0xFF else -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i == 3 && timeoutsLeft-- > 0) throw java.net.SocketTimeoutException()
                if (i >= data.size) return -1
                b[off] = data[i++]; return 1
            }
        }
        var idles = 0
        assertArrayEquals(RemoteMessages.key(19), Framing.read(flaky) { idles++ })
        assertArrayEquals(RemoteMessages.key(20), Framing.read(flaky) { idles++ })
        assertTrue("onIdle solo antes de empezar un mensaje", idles in 1..3)
        // Sin onIdle, el vencimiento se propaga
        val silent = object : InputStream() { override fun read(): Int = throw java.net.SocketTimeoutException() }
        try { Framing.read(silent); fail() } catch (_: java.net.SocketTimeoutException) {}
    }

    @Test fun message24MeansTvClosedItsKeyboard() {
        assertEquals(Incoming.ImeClosed, RemoteMessages.parse(ProtoWriter().message(24) {}.toByteArray()))
    }

    @Test fun framingRejectsHugeLength() {
        try { Framing.read(ByteArrayInputStream(ProtoWriter.varintBytes(10_000_000))); fail() } catch (_: ProtoException) {}
    }

    @Test fun everyButtonHasTheRightAndroidKeyCode() {
        val expected = mapOf(
            TvKey.POWER to 26, TvKey.VOLUME_UP to 24, TvKey.VOLUME_DOWN to 25, TvKey.MUTE to 164,
            TvKey.CHANNEL_UP to 166, TvKey.CHANNEL_DOWN to 167,
            TvKey.RED to 183, TvKey.GREEN to 184, TvKey.YELLOW to 185, TvKey.BLUE to 186,
            TvKey.HOME to 3, TvKey.BACK to 4,
            TvKey.UP to 19, TvKey.DOWN to 20, TvKey.LEFT to 21, TvKey.RIGHT to 22, TvKey.OK to 23,
            TvKey.PLAY_PAUSE to 85, TvKey.STOP to 86, TvKey.NEXT to 87, TvKey.PREVIOUS to 88,
            TvKey.REWIND to 89, TvKey.FAST_FORWARD to 90,
            TvKey.SETTINGS to 176, TvKey.MENU to 82, TvKey.INPUT to 178, TvKey.GUIDE to 172,
            TvKey.INFO to 165, TvKey.CAPTIONS to 175, TvKey.DEL to 67, TvKey.ENTER to 66,
            TvKey.NUMPAD_ENTER to 160, TvKey.HDMI_1 to 243, TvKey.HDMI_2 to 244, TvKey.HDMI_3 to 245, TvKey.HDMI_4 to 246, TvKey.TV to 170,
        )
        expected.forEach { (k, code) -> assertEquals(k.name, code, k.code) }
        TvKey.digits.forEachIndexed { i, k -> assertEquals(7 + i, k.code) }
        val repeating = TvKey.entries.filter { it.repeats }.toSet()
        assertEquals(
            setOf(TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, TvKey.CHANNEL_UP, TvKey.CHANNEL_DOWN, TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT),
            repeating,
        )
    }
}
