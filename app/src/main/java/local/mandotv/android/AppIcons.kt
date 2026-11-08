package local.mandotv.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import local.mandotv.data.Shortcut
import java.io.File
import java.util.UUID

/** Una app instalada en el MÓVIL (solo para tomar prestado su icono). */
data class PhoneApp(val label: String, val packageName: String)

/**
 * Acceso a la lista de apps del móvil, SIEMPRE condicionado al ajuste «Iconos de apps del móvil»
 * (desactivado por defecto). Si el ajuste está desactivado, nunca se llama a [query] ni a [iconOf]:
 * la app no mira qué tienes instalado.
 */
class PhoneAppsGate(
    val enabled: () -> Boolean,
    private val query: () -> List<PhoneApp>,
    private val iconOf: (String) -> Bitmap?,
) {
    fun list(): List<PhoneApp> = if (enabled()) query() else emptyList()
    fun icon(packageName: String): Bitmap? = if (enabled() && packageName.isNotEmpty()) iconOf(packageName) else null
}

/** Logos de los accesos directos. Todo es local: nada se descarga de Internet. */
class AppIcons(private val context: Context, phoneIconsEnabled: () -> Boolean) {
    private val dir = File(context.filesDir, "iconos").apply { mkdirs() }
    private val cache = HashMap<String, ImageBitmap?>()

    val phoneApps = PhoneAppsGate(
        enabled = phoneIconsEnabled,
        query = {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { PhoneApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        },
        iconOf = { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(SIZE) }.getOrNull() },
    )

    /** Icono a mostrar para un acceso: imagen de la galería, o icono de la app del móvil (si se permite). */
    @Synchronized
    fun iconFor(s: Shortcut): ImageBitmap? {
        if (s.iconFile.isNotEmpty()) {
            return cache.getOrPut("f:${s.iconFile}") {
                runCatching { BitmapFactory.decodeFile(File(dir, s.iconFile).path)?.asImageBitmap() }.getOrNull()
            }
        }
        if (!phoneApps.enabled()) return null
        // App elegida a mano o, si no, la del móvil que se llame igual que el acceso
        val pkg = s.phonePackage.ifEmpty { matchByLabel(s.label, installed())?.packageName } ?: return null
        return cache.getOrPut("p:$pkg") { phoneApps.icon(pkg)?.asImageBitmap() }
    }

    @Volatile private var installedCache: List<PhoneApp>? = null

    /** Lista de apps del móvil (solo si el ajuste está activado; si no, vacía y sin consultar). */
    fun installed(): List<PhoneApp> {
        if (!phoneApps.enabled()) { installedCache = null; return emptyList() }
        return installedCache ?: phoneApps.list().also { installedCache = it }
    }

    /** Precarga la lista y los iconos (en segundo plano) para que la pantalla no espere. */
    fun preload(shortcuts: List<Shortcut>) {
        synchronized(this) { cache.clear() }
        installedCache = null
        shortcuts.forEach { iconFor(it) }
    }

    /** Copia (reducida a 192 px) la imagen elegida con el selector de fotos. Devuelve el nombre del archivo. */
    fun importFromGallery(uri: Uri): String? = runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = squareScaled(src, SIZE)
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }.getOrNull()

    /** Borra las imágenes que ya no usa ningún acceso. */
    fun cleanup(inUse: List<Shortcut>) {
        val keep = inUse.map { it.iconFile }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        synchronized(this) { cache.clear() }
    }

    companion object {
        const val SIZE = 192

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /**
         * Busca entre las apps del móvil la que corresponde a un acceso por su nombre: primero
         * nombre igual («YouTube»), luego que empiece por él, luego que lo contenga («Prime Video»
         * → «Amazon Prime Video»). Ignora mayúsculas, espacios y símbolos («Disney+» = «disney»).
         */
        fun matchByLabel(label: String, apps: List<PhoneApp>): PhoneApp? {
            val n = norm(label)
            if (n.length < 3) return null
            return apps.firstOrNull { norm(it.label) == n }
                ?: apps.firstOrNull { norm(it.label).startsWith(n) }
                ?: apps.firstOrNull { val a = norm(it.label); a.length >= 4 && n.startsWith(a) }
                ?: apps.firstOrNull { norm(it.label).contains(n) }
        }

        fun squareScaled(src: Bitmap, size: Int): Bitmap {
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val square = Bitmap.createBitmap(src, x, y, side, side)
            return Bitmap.createScaledBitmap(square, size, size, true)
        }

        private fun Drawable.toBitmap(size: Int): Bitmap {
            if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, size, size, true)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            setBounds(0, 0, size, size)
            draw(c)
            return bmp
        }
    }
}
