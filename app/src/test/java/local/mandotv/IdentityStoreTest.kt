package local.mandotv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.mandotv.crypto.ClientIdentity
import local.mandotv.data.IdentityStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * El archivo de identidad (clave privada + certificado del móvil) tiene que quedar cifrado en el
 * disco. Aquí la clave AES es una de prueba, porque el Android Keystore real solo existe en un
 * móvil; el cifrado, el formato del archivo y la reacción a archivos dañados son los de la app.
 */
class IdentityStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = aesKey()

    private fun file() = File(tmp.root, "identidad.bin")
    private fun store(file: File = file(), k: SecretKey = key) = IdentityStore(file) { k }

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { i -> part.indices.all { j -> this[i + j] == part[j] } }

    private fun ClientIdentity.sameAs(other: ClientIdentity) =
        certificate.encoded.contentEquals(other.certificate.encoded) && privateKey.encoded.contentEquals(other.privateKey.encoded)

    @Test fun fileOnDiskDoesNotContainTheKeyOrCertificateInClear() {
        val identity = store().get()
        val disk = file().readBytes()
        val privateKey = identity.privateKey.encoded
        val certificate = identity.certificate.encoded
        // Ni enteros ni a trozos: se busca cada ventana de 16 bytes de la clave y del certificado.
        for (secret in listOf(privateKey, certificate)) {
            for (start in 0..secret.size - 16 step 16) {
                assertFalse("hay bytes sin cifrar en el archivo", disk.contains(secret.copyOfRange(start, start + 16)))
            }
        }
        assertFalse("el nombre del certificado se lee en claro", disk.contains("Mando TV".toByteArray()))
        // IV + contenido (del mismo tamaño que en claro, GCM no rellena) + etiqueta de autenticación.
        assertEquals(IdentityStore.IV_BYTES + identity.encode().size + IdentityStore.TAG_BITS / 8, disk.size)
    }

    @Test fun sameIdentityIsReadBackAfterRestart() {
        val first = store().get()
        assertTrue(store().exists())
        val again = store().get() // otra instancia: como al volver a abrir la app
        assertTrue("debía leerse la misma identidad", first.sameAs(again))
    }

    @Test fun withoutTheKeyTheFileCannotBeRead() {
        val identity = store().get()
        val disk = file().readBytes()
        try {
            store(k = aesKey()).decrypt(disk)
            fail("con otra clave no debía poder descifrarse")
        } catch (_: java.security.GeneralSecurityException) {}
        assertArrayEquals(identity.encode(), store().decrypt(disk))
    }

    @Test fun anyTamperingIsDetectedAndANewIdentityIsCreated() {
        val original = store().get()
        val disk = file().readBytes()
        // Un solo bit cambiado en el IV, en el contenido o en la etiqueta.
        for (position in listOf(0, IdentityStore.IV_BYTES + 5, disk.size / 2, disk.size - 1)) {
            val tampered = disk.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            try {
                store().decrypt(tampered)
                fail("un archivo manipulado (byte $position) no debía aceptarse")
            } catch (_: java.security.GeneralSecurityException) {}
        }
        file().writeBytes(disk.copyOf().also { it[disk.size / 2] = (it[disk.size / 2].toInt() xor 0x01).toByte() })
        val regenerated = store().get() // no falla: crea otra identidad (habrá que volver a emparejar)
        assertFalse(original.sameAs(regenerated))
        assertTrue("la nueva identidad queda guardada", regenerated.sameAs(store().get()))
    }

    @Test fun truncatedOrEmptyFileDoesNotCrash() {
        val original = store().get()
        val disk = file().readBytes()
        for (broken in listOf(ByteArray(0), disk.copyOf(5), disk.copyOf(IdentityStore.IV_BYTES), disk.copyOf(disk.size - 40))) {
            file().writeBytes(broken)
            val regenerated = store().get()
            assertFalse(original.sameAs(regenerated))
            assertTrue(file().length() > 1000)
        }
    }

    @Test fun everyEncryptionUsesAFreshRandomIv() {
        val s = store()
        val plain = ByteArray(64) { it.toByte() }
        val a = s.encrypt(plain)
        val b = s.encrypt(plain)
        assertNotEquals(a.copyOf(IdentityStore.IV_BYTES).toList(), b.copyOf(IdentityStore.IV_BYTES).toList())
        assertNotEquals("el mismo contenido no debe dar el mismo cifrado", a.toList(), b.toList())
        assertArrayEquals(plain, s.decrypt(a))
        assertArrayEquals(plain, s.decrypt(b))
    }

    @Test fun eachInstallGetsItsOwnIdentity() {
        val a = store(File(tmp.root, "a.bin")).get()
        val b = store(File(tmp.root, "b.bin")).get()
        assertFalse(a.sameAs(b))
    }
}

/** Dónde se guarda en el móvil: en la carpeta que Android nunca incluye en copias de seguridad. */
@RunWith(RobolectricTestRunner::class)
class IdentityStoreLocationTest {
    @Test fun identityLivesInTheNoBackupFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = IdentityStore.fileIn(context)
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
        assertEquals("identidad.bin", file.name)
        assertNotEquals(context.filesDir.canonicalFile, file.parentFile!!.canonicalFile)
        assertNotEquals(context.cacheDir.canonicalFile, file.parentFile!!.canonicalFile)
    }
}
