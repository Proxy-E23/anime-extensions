package aniyomi.lib.mediafireextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Punto de entrada público de la librería. Encapsula:
 *  - Parseo de links de MediaFire (archivo/carpeta).
 *  - Listado de carpetas (subcarpetas y archivos), con detección de
 *    carpetas eliminadas o inválidas.
 *  - Resolución del nombre real de un archivo cuando la URL no lo trae.
 *  - Resolución de la URL directa de descarga/reproducción de un archivo.
 *
 * A diferencia de MEGA, MediaFire no requiere descifrado ni proxy local: las
 * URLs de descarga que expone la API son directamente reproducibles.
 */
class MediaFireExtractor(
    private val client: OkHttpClient,
    private val browserHeaders: Headers,
    private val baseUrl: String = "https://www.mediafire.com",
) {

    private val api = MediaFireApi(client, baseUrl)

    companion object {
        private const val TAG = "MediaFireExtractor"
    }

    // ── Parseo y metadatos ──────────────────────────────────────────────────

    fun parseLink(url: String): MediaFireLink? = MediaFireLinkParser.parse(url)

    fun fetchFolderName(key: String): String = api.fetchFolderName(key)

    fun isFolderMissing(key: String): Boolean = api.isFolderMissing(key)

    fun listSubFolders(key: String): List<MediaFireSubFolder> = api.fetchAllFolders(key)

    fun listFiles(key: String): List<MediaFireFolderEntry> = api.fetchAllFiles(key)

    /**
     * Resuelve el nombre real de un archivo. Si [hint] viene con valor (por
     * ejemplo tomado del path de la URL por [MediaFireLinkParser]), se
     * devuelve tal cual sin pedir nada a la red; si viene nulo, se escanea
     * la página del archivo.
     */
    fun resolveFileName(quickkey: String, hint: String?): String = hint
        ?: api.fetchFileNameFromPage(quickkey, browserHeaders) ?: quickkey

    // ── Resolución de video reproducible ────────────────────────────────────

    /**
     * Resuelve la URL directa de descarga de un archivo. Primero intenta la
     * API oficial (`get_links.php`); si no da una URL utilizable, cae a
     * escanear el botón de descarga en la página del archivo.
     */
    fun resolveDirectVideoUrl(quickkey: String, filename: String): String {
        val normalUrl = runCatching { api.fetchNormalDownloadLink(quickkey, browserHeaders) }
            .onFailure { Log.e(TAG, "resolveDirectVideoUrl: fetchNormalDownloadLink lanzó excepción: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (normalUrl != null) {
            val location = runCatching { api.followRedirectLocation(normalUrl, browserHeaders) }
                .onFailure { Log.e(TAG, "resolveDirectVideoUrl: followRedirectLocation(normalUrl) lanzó excepción: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (!location.isNullOrBlank() && "download" in location) {
                Log.d(TAG, "resolveDirectVideoUrl: resuelto vía API directa -> $location")
                return location
            }
        }

        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val pageUrl = "$baseUrl/file/$quickkey/$encodedFilename"
        val btnHref = api.fetchDownloadButtonHref(pageUrl, browserHeaders) ?: run {
            Log.w(TAG, "resolveDirectVideoUrl: botón de descarga no encontrado en pageUrl=$pageUrl, devolviendo pageUrl")
            return pageUrl
        }

        // El btnHref puede venir en dos formas:
        //   1) URL final al CDN: https://downloadXXXX.mediafire.com/...mp4
        //      No se verifica con una petición extra: en algunos dispositivos
        //      el handshake TLS contra ese host falla del lado del sistema
        //      aunque el certificado sea válido, así que se confía tal cual.
        //   2) URL intermedia con dkey: https://www.mediafire.com/file/...?dkey=...&r=...
        //      Esta página NO redirige por HTTP (no manda header Location):
        //      entrega el link final del CDN dentro de un <script> vía
        //      `window.location.href = '...'`. Hay que leer ese script.
        val result = if (needsRedirectResolution(btnHref)) {
            val scriptUrl = runCatching { api.fetchScriptRedirectUrl(btnHref, browserHeaders) }
                .onFailure { Log.e(TAG, "resolveDirectVideoUrl: fetchScriptRedirectUrl(btnHref) lanzó excepción: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (!scriptUrl.isNullOrBlank()) scriptUrl else btnHref
        } else {
            btnHref
        }

        Log.d(TAG, "resolveDirectVideoUrl: resuelto vía fallback de botón -> $result")
        return result
    }

    /**
     * true si [href] necesita un paso extra de resolución de redirect
     * (apunta a una página intermedia con `dkey` en vez de al CDN directo).
     */
    private fun needsRedirectResolution(href: String): Boolean {
        // CDN directo: host empieza con "download" (downloadXXXX.mediafire.com)
        if (href.contains("://download") && "mediafire.com" in href) return false
        // Página intermedia: contiene /file/ y dkey=
        if ("/file/" in href && "dkey=" in href) return true
        // Default conservador: si no reconocemos el patrón, no seguimos.
        return false
    }

    /**
     * Arma el [Video] reproducible a partir de un archivo ya identificado.
     * [displayName] se usa como nombre visible en el selector de calidad.
     */
    fun videoFromFile(quickkey: String, filename: String, displayName: String = filename.substringBeforeLast('.')): List<Video> {
        val directUrl = resolveDirectVideoUrl(quickkey, filename)
        val videoHeaders = browserHeaders.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return listOf(Video(directUrl, displayName, directUrl, videoHeaders))
    }
}
