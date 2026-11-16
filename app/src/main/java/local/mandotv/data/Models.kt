package local.mandotv.data

import local.mandotv.remote.TvTarget

/** Una tele emparejada. Todo esto se guarda solo en el móvil. */
data class SavedTv(
    val id: String,
    val name: String,
    val host: String,
    /** Huella SHA-256 de la clave pública de la tele, para reconocerla y evitar impostores. */
    val fingerprint: String,
    /** MAC para Wake-on-LAN (opcional). */
    val mac: String = "",
) {
    fun target() = TvTarget(id, name, host, fingerprint)
}

/**
 * Acceso directo a una app de la tele. [link] es un enlace o un «market://launch?id=paquete».
 * Logo opcional: [iconFile] (imagen elegida de la galería, copiada en la app) o
 * [phonePackage] (usar el icono de esa app instalada en el móvil, si el ajuste lo permite).
 */
data class Shortcut(
    val id: String,
    val label: String,
    val link: String,
    val iconFile: String = "",
    val phonePackage: String = "",
)

data class Settings(
    val vibration: Boolean = true,
    /** Los botones de volumen del móvil controlan la tele mientras la app está abierta. */
    val phoneVolumeKeys: Boolean = false,
    val wakeOnLan: Boolean = true,
    /** Permitir que la app lea las apps del móvil para usar sus iconos. Desactivado por defecto. */
    val phoneAppIcons: Boolean = false,
    /** Qué hace el botón Intro del teclado (cada tele reacciona distinto). */
    val enterMode: EnterMode = EnterMode.ENTER,
    /** Botón de micrófono para hablar al asistente de la tele. Desactivado por defecto. */
    val voice: Boolean = false,
)

/** Formas de mandar «Intro» desde el teclado. */
enum class EnterMode(val title: String) {
    ENTER("Tecla Intro"),
    NUMPAD_ENTER("Tecla Intro del teclado numérico"),
    NEWLINE("Salto de línea como texto"),
}

object Shortcuts {
    val defaults = listOf(
        Shortcut("yt", "YouTube", "https://www.youtube.com"),
        Shortcut("nf", "Netflix", "https://www.netflix.com/title"),
        Shortcut("pv", "Prime Video", "https://app.primevideo.com"),
        Shortcut("dp", "Disney+", "https://www.disneyplus.com"),
    )

    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val uri = Regex("^[A-Za-z][A-Za-z0-9+.-]*://\\S*$")

    /**
     * Convierte lo que escribe el usuario en el enlace que se envía a la tele:
     *  - «com.netflix.ninja» (nombre de paquete) → «market://launch?id=com.netflix.ninja»
     *  - «https://…» o «esquema://…» → tal cual
     * Devuelve null si no es válido. El enlace lo abre la TELE; el móvil nunca accede a él.
     */
    fun normalizeLink(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.length > 300) return null
        return when {
            uri.matches(t) -> t
            packageName.matches(t) -> "market://launch?id=$t"
            else -> null
        }
    }

    fun normalizeLabel(input: String): String? = input.trim().take(16).ifEmpty { null }
}

/**
 * Formato de guardado sencillo y legible (un registro por línea, campos separados por tabulador,
 * con escapes), para no depender de librerías de serialización.
 */
object RecordCodec {
    fun encode(records: List<List<String>>): String =
        records.joinToString("\n") { fields -> fields.joinToString("\t") { escape(it) } }

    fun decode(text: String): List<List<String>> =
        if (text.isEmpty()) emptyList() else text.split("\n").map { line -> line.split("\t").map { unescape(it) } }

    private fun escape(s: String) = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else { append(c); i++ }
        }
    }

    fun tvs(text: String): List<SavedTv> = decode(text).mapNotNull {
        if (it.size < 4) null else SavedTv(it[0], it[1], it[2], it[3], it.getOrElse(4) { "" })
    }

    fun encodeTvs(list: List<SavedTv>) = encode(list.map { listOf(it.id, it.name, it.host, it.fingerprint, it.mac) })

    fun shortcuts(text: String): List<Shortcut> = decode(text).mapNotNull {
        if (it.size < 3) null else Shortcut(it[0], it[1], it[2], it.getOrElse(3) { "" }, it.getOrElse(4) { "" })
    }

    fun encodeShortcuts(list: List<Shortcut>) =
        encode(list.map { listOf(it.id, it.label, it.link, it.iconFile, it.phonePackage) })
}
