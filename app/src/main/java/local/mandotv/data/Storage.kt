package local.mandotv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import local.mandotv.crypto.ClientIdentity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identidad del móvil (clave privada + certificado). Se guarda en el almacenamiento privado de
 * la app, cifrada con AES-256-GCM usando una clave que vive en el Android Keystore (no se
 * puede extraer del móvil). Si se pierde o no se puede descifrar, se crea una nueva y habrá que
 * volver a emparejar.
 */
class IdentityStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "identidad.bin")
    @Volatile private var cached: ClientIdentity? = null

    @Synchronized
    fun get(): ClientIdentity {
        cached?.let { return it }
        val loaded = runCatching { if (file.exists()) decrypt(file.readBytes()) else null }.getOrNull()
        val identity = loaded?.let { ClientIdentity.decode(it) } ?: ClientIdentity.generate("Mando TV").also {
            file.writeBytes(encrypt(it.encode()))
        }
        cached = identity
        return identity
    }

    /** ¿Ya existía una identidad? (Si se regenera, las teles guardadas dejan de valer.) */
    fun exists() = file.exists()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    companion object {
        private const val ALIAS = "mandotv_identidad"
    }
}

/** Teles emparejadas, tele elegida, accesos directos y ajustes. Solo en el móvil. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("mando", Context.MODE_PRIVATE)

    private val _tvs = MutableStateFlow(RecordCodec.tvs(prefs.getString(K_TVS, "")!!))
    val tvs: StateFlow<List<SavedTv>> = _tvs.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(K_SELECTED, null))
    val selectedId: StateFlow<String?> = _selected.asStateFlow()

    private val _shortcuts = MutableStateFlow(
        if (prefs.contains(K_SHORTCUTS)) RecordCodec.shortcuts(prefs.getString(K_SHORTCUTS, "")!!) else Shortcuts.defaults,
    )
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts.asStateFlow()

    private val _settings = MutableStateFlow(
        Settings(
            vibration = prefs.getBoolean(K_VIBRATION, true),
            phoneVolumeKeys = prefs.getBoolean(K_VOLUME_KEYS, false),
            wakeOnLan = prefs.getBoolean(K_WOL, true),
            phoneAppIcons = prefs.getBoolean(K_PHONE_ICONS, false),
            enterMode = EnterMode.entries.firstOrNull { it.name == prefs.getString(K_ENTER, null) } ?: EnterMode.ENTER,
        ),
    )
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun saveTv(tv: SavedTv) {
        _tvs.update { list -> list.filterNot { it.id == tv.id } + tv }
        prefs.edit().putString(K_TVS, RecordCodec.encodeTvs(_tvs.value)).apply()
    }

    fun removeTv(id: String) {
        _tvs.update { list -> list.filterNot { it.id == id } }
        prefs.edit().putString(K_TVS, RecordCodec.encodeTvs(_tvs.value)).apply()
        if (_selected.value == id) select(_tvs.value.firstOrNull()?.id)
    }

    fun select(id: String?) {
        _selected.value = id
        prefs.edit().putString(K_SELECTED, id).apply()
    }

    fun setShortcuts(list: List<Shortcut>) {
        _shortcuts.value = list
        prefs.edit().putString(K_SHORTCUTS, RecordCodec.encodeShortcuts(_shortcuts.value)).apply()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putBoolean(K_VIBRATION, s.vibration)
            .putBoolean(K_VOLUME_KEYS, s.phoneVolumeKeys)
            .putBoolean(K_WOL, s.wakeOnLan)
            .putBoolean(K_PHONE_ICONS, s.phoneAppIcons)
            .putString(K_ENTER, s.enterMode.name)
            .apply()
    }

    /** Si la identidad del móvil se regeneró, los emparejamientos anteriores ya no sirven. */
    fun forgetAllTvs() {
        _tvs.value = emptyList()
        prefs.edit().remove(K_TVS).apply()
        select(null)
    }

    companion object {
        private const val K_TVS = "teles"
        private const val K_SELECTED = "tele_elegida"
        private const val K_SHORTCUTS = "accesos"
        private const val K_VIBRATION = "vibracion"
        private const val K_VOLUME_KEYS = "volumen_movil"
        private const val K_WOL = "wake_on_lan"
        private const val K_PHONE_ICONS = "iconos_apps_movil"
        private const val K_ENTER = "modo_intro"
    }
}
