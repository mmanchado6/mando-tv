package local.mandotv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guardas de privacidad del proyecto: fallan si alguien (o una librería nueva) abre la app más de
 * lo previsto. Si un fallo es intencionado, se actualiza aquí y en CLAUDE.md a la vez.
 */
class ProjectGuardTest {

    // ---------- Manifiesto fusionado de release (el que acaba en el APK) ----------

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val path = System.getProperty("manifest.release") ?: error("falta la propiedad manifest.release (ver app/build.gradle.kts)")
        val file = File(path)
        assertTrue("no existe el manifiesto fusionado: $path", file.isFile)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }.map { it as Element }

    private fun Element.attr(name: String): String? = getAttributeNS(android, name).takeIf { it.isNotEmpty() }

    @Test fun apkRequestsExactlyTheDocumentedPermissions() {
        val permissions = manifest.children("uses-permission").map { it.attr("name") }.toSet()
        assertEquals(
            "El APK pide permisos distintos a los documentados en CLAUDE.md. " +
                "Un permiso nuevo hay que justificarlo y consultarlo.",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.VIBRATE",
                "android.permission.RECORD_AUDIO",
            ),
            permissions,
        )
        assertTrue("no debe declarar permisos propios", manifest.children("permission").isEmpty())
        assertTrue(manifest.children("uses-permission-sdk-23").isEmpty())
    }

    @Test fun microphoneIsNotRequiredHardware() {
        val mic = manifest.children("uses-feature").single { it.attr("name") == "android.hardware.microphone" }
        assertEquals("false", mic.attr("required"))
    }

    @Test fun backupsAndCleartextTrafficAreOff() {
        val app = manifest.children("application").single()
        assertEquals("false", app.attr("allowBackup"))
        assertEquals("false", app.attr("usesCleartextTraffic"))
        assertNotEquals("true", app.attr("debuggable"))
        assertNotNull(app.attr("dataExtractionRules"))
    }

    @Test fun onlyTheLauncherActivityIsReachableFromOutside() {
        val app = manifest.children("application").single()
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { app.children(it) }
        assertTrue("debe haber al menos la actividad principal", components.isNotEmpty())
        for (c in components) {
            val name = c.attr("name") ?: "?"
            val hasFilter = c.children("intent-filter").isNotEmpty()
            val exposed = c.attr("exported") == "true" || (c.attr("exported") == null && hasFilter)
            if (name.endsWith(".MainActivity")) {
                assertTrue("MainActivity debe seguir exportada (es el icono de la app)", exposed)
            } else {
                assertFalse("$name es accesible desde otras apps", exposed)
            }
        }
        // La actividad principal solo responde al icono del lanzador: sin enlaces ni acciones extra.
        val main = components.single { (it.attr("name") ?: "").endsWith(".MainActivity") }
        val filter = main.children("intent-filter").single()
        assertEquals(setOf("android.intent.action.MAIN"), filter.children("action").map { it.attr("name") }.toSet())
        assertEquals(setOf("android.intent.category.LAUNCHER"), filter.children("category").map { it.attr("name") }.toSet())
        assertTrue("sin enlaces profundos", filter.children("data").isEmpty())
    }

    // ---------- La red solo pasa por LanGate ----------

    private val sources: List<File> by lazy {
        val root = File(System.getProperty("main.sources") ?: error("falta la propiedad main.sources (ver app/build.gradle.kts)"))
        assertTrue("no existe $root", root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Quita comentarios para no dar falsos positivos con lo que solo se menciona en texto. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun rel(file: File) = file.path.substringAfter("local/mandotv/")

    @Test fun scannerSeesTheSourceTree() {
        // Si la ruta estuviera mal, los demás tests pasarían sin mirar nada.
        assertTrue("solo ${sources.size} archivos", sources.size > 20)
        assertTrue(sources.any { rel(it) == "net/LanGate.kt" })
    }

    @Test fun socketsAreOnlyCreatedInsideLanGate() {
        // Constructores de sockets (también DatagramSocket/ServerSocket/MulticastSocket) y canales NIO.
        val creation = Regex("(?<![A-Za-z0-9_])(Datagram|Server|Multicast)?Socket\\(|SocketChannel\\.open|AsynchronousSocketChannel|DatagramChannel\\.open")
        val offenders = sources.filter { rel(it) != "net/LanGate.kt" }
            .filter { creation.containsMatchIn(code(it)) }.map(::rel)
        assertEquals("Solo net/LanGate.kt puede abrir sockets (así siempre pasan por las reglas de red local)", emptyList<String>(), offenders)
        // Y LanGate sí los crea: si no, este test estaría mirando mal.
        assertTrue(creation.containsMatchIn(code(sources.single { rel(it) == "net/LanGate.kt" })))
    }

    @Test fun noInternetCapableApisAreUsedAnywhere() {
        val forbidden = mapOf(
            "URL(" to Regex("(?<![A-Za-z0-9_])URL\\("),
            "HttpURLConnection" to Regex("HttpURLConnection|HttpsURLConnection"),
            "java.net.http" to Regex("java\\.net\\.http"),
            "OkHttp / Retrofit / Ktor" to Regex("okhttp3|retrofit2|io\\.ktor"),
            "WebView" to Regex("(?<![A-Za-z0-9_])WebView"),
            "DNS (InetAddress.getByName/getAllByName)" to Regex("InetAddress\\.(getByName|getAllByName)"),
            "DownloadManager" to Regex("DownloadManager"),
            "Firebase / Play Services / analíticas" to Regex("com\\.google\\.firebase|com\\.google\\.android\\.gms|Crashlytics|Analytics"),
            "procesos externos" to Regex("ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec"),
            "librerías nativas" to Regex("System\\.(loadLibrary|load)\\("),
        )
        val problems = buildList {
            for (f in sources) {
                val text = code(f)
                for ((what, re) in forbidden) if (re.containsMatchIn(text)) add("${rel(f)}: $what")
            }
        }
        assertEquals("Código que podría sacar datos de la red local", emptyList<String>(), problems)
    }

    @Test fun tlsOnlyWrapsSocketsThatLanGateAlreadyConnected() {
        // Tls.wrap recibe un socket ya conectado; no puede abrir uno nuevo con la fábrica de sockets.
        val tls = code(sources.single { rel(it) == "net/Tls.kt" })
        val calls = Regex("createSocket\\([^)]*\\)").findAll(tls).map { it.value }.toList()
        assertEquals(listOf("createSocket(socket, host, socket.port, true)"), calls)
    }
}
