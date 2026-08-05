package aniyomi.lib.mediafireextractor

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

internal class MediaFireApi(private val client: OkHttpClient, private val baseUrl: String) {

    companion object {
        private const val TAG = "MediaFireApi"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun apiFoldersUrl(key: String, chunk: Int) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=folders&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFilesUrl(key: String, chunk: Int) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=files&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFolderInfoUrl(key: String) = "$baseUrl/api/1.5/folder/get_info.php" +
        "?folder_key=$key&version=1.5&response_format=json"

    fun fetchFolderName(key: String): String = try {
        val body = client.newCall(GET(apiFolderInfoUrl(key))).execute().body.string()
        json.decodeFromString<MediaFireRoot>(body).response.folder_info?.name
            ?.takeIf { it.isNotBlank() } ?: key
    } catch (e: Exception) {
        key
    }

    fun isFolderMissing(key: String): Boolean = try {
        val body = client.newCall(GET(apiFolderInfoUrl(key))).execute().body.string()
        // La API devuelve result=Error con error=112 cuando la carpeta no existe.
        // Puede venir en JSON o XML dependiendo del estado.
        "Error" in body && ("112" in body || "Unknown or invalid" in body)
    } catch (e: Exception) {
        false
    }

    fun fetchAllFolders(key: String): List<MediaFireSubFolder> {
        val list = mutableListOf<MediaFireSubFolder>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFoldersUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MediaFireRoot>(body).response.folder_content ?: break
            list += (content.folders ?: emptyList()).map {
                MediaFireSubFolder(key = it.folderkey, name = it.name, created = it.created)
            }
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    fun fetchAllFiles(key: String): List<MediaFireFolderEntry> {
        val list = mutableListOf<MediaFireFolderEntry>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFilesUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MediaFireRoot>(body).response.folder_content ?: break
            list += (content.files ?: emptyList()).map {
                MediaFireFolderEntry(quickkey = it.quickkey, filename = it.filename, created = it.created)
            }
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    fun fetchFileNameFromPage(quickkey: String, browserHeaders: Headers): String? = try {
        val document = client.newCall(GET("$baseUrl/file/$quickkey/", browserHeaders)).execute().use { it.asJsoup() }
        document.selectFirst(".dl-btn-label")?.attr("title")
            ?: document.selectFirst("div.filename")?.text()
    } catch (e: Exception) {
        null
    }

    /**
     * A veces MediaFire devuelve `normal_download` envuelto en sintaxis
     * markdown, p.ej. `https://[host/path](https://host/path)`, en vez de
     * una URL limpia. Si detectamos ese patrón, extraemos la URL real de
     * dentro de los paréntesis en vez de descartar el campo.
     */
    private fun sanitizeDownloadUrl(raw: String): String? {
        val markdownMatch = Regex("""\((https?://[^)]+)\)""").find(raw)
        if (markdownMatch != null) {
            Log.w(TAG, "normal_download vino envuelto en markdown, se extrajo la URL interna")
            return markdownMatch.groupValues[1]
        }
        return raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun fetchNormalDownloadLink(quickkey: String, browserHeaders: Headers): String? = try {
        val apiUrl = "$baseUrl/api/1.5/file/get_links.php" +
            "?quick_key=$quickkey&link_type=normal_download&response_format=json"
        val body = client.newCall(GET(apiUrl, browserHeaders)).execute().body.string()
        val rawUrl = json.decodeFromString<MediaFireLinksRoot>(body)
            .response.links?.firstOrNull()?.normal_download
            ?.takeIf { it.isNotBlank() }
        Log.d(TAG, "fetchNormalDownloadLink quickkey=$quickkey rawUrl=$rawUrl")
        rawUrl?.let(::sanitizeDownloadUrl)
    } catch (e: Exception) {
        Log.e(TAG, "fetchNormalDownloadLink falló para quickkey=$quickkey: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // Cliente aislado (no hereda el `client` de la app) usado solo para
    // verificar el link de la API contra mediafire.com. No se usa contra los
    // hosts downloadXXXX.mediafire.com: en algunos dispositivos el handshake
    // TLS con esos hosts falla del lado del sistema, así que esa verificación
    // se evita en resolveDirectVideoUrl y se confía directo en el href del
    // botón de descarga.
    private val plainRedirectClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .build()
    }

    fun followRedirectLocation(url: String, browserHeaders: Headers): String? = try {
        val resp = plainRedirectClient.newCall(GET(url, browserHeaders)).execute()
        val location = resp.header("Location")
        Log.d(TAG, "followRedirectLocation url=$url code=${resp.code} location=$location")
        resp.close()
        location
    } catch (e: Exception) {
        Log.e(TAG, "followRedirectLocation falló para url=$url: ${e.javaClass.simpleName}: ${e.message}")
        throw e
    }

    fun fetchDownloadButtonHref(pageUrl: String, browserHeaders: Headers): String? = try {
        val document = client.newCall(GET(pageUrl, browserHeaders)).execute().use { it.asJsoup() }
        val href = document.selectFirst("a#downloadButton")?.attr("abs:href")?.takeIf { it.isNotBlank() }
        Log.d(TAG, "fetchDownloadButtonHref pageUrl=$pageUrl href=$href")
        href
    } catch (e: Exception) {
        Log.e(TAG, "fetchDownloadButtonHref falló para pageUrl=$pageUrl: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // La página intermedia con ?dkey=...&r=... NO redirige por HTTP: entrega
    // el link final del CDN dentro de un <script> mediante
    //   setTimeout(function () { window.location.href = '...'; }, 1000);
    // Jsoup no ejecuta JS, así que hay que extraer la URL del propio HTML con
    // una expresión regular en vez de esperar un header Location.
    private val cdnRedirectScriptRegex =
        Regex("""window\.location\.href\s*=\s*'(https?://[^']+)'""")

    fun fetchScriptRedirectUrl(url: String, browserHeaders: Headers): String? = try {
        val body = client.newCall(GET(url, browserHeaders)).execute().use { it.body.string() }
        val redirectUrl = cdnRedirectScriptRegex.find(body)?.groupValues?.get(1)
        Log.d(TAG, "fetchScriptRedirectUrl url=$url redirectUrl=$redirectUrl")
        redirectUrl
    } catch (e: Exception) {
        Log.e(TAG, "fetchScriptRedirectUrl falló para url=$url: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
