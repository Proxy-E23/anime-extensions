package aniyomi.lib.megaextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

/**
 * Punto de entrada público de la librería. Encapsula:
 *  - Parseo de links de MEGA (archivo/carpeta).
 *  - Listado de árboles de carpeta ya descifrados (nombres, tamaños).
 *  - Resolución de un [Video] reproducible a través de un proxy local que
 *    descifra el contenido al vuelo (ver [MegaProxyServer]).
 *
 * El proxy se comparte (singleton por proceso) entre todos los videos
 * servidos por esta instancia, para no abrir un socket por episodio. No se
 * auto-apaga por inactividad; se detiene solo con [shutdown], pensado para
 * conectarse a un botón en los ajustes de cada extensión.
 */
class MegaExtractor(private val client: OkHttpClient) {

    private val api = MegaApi(client)

    // Se guarda la instancia actual (o null) en vez de usar `lazy`, para
    // poder crear una nueva si la anterior fue detenida con shutdown().
    @Volatile
    private var currentProxyServer: MegaProxyServer? = null

    private val proxyServerLock = Any()

    /** Devuelve el proxy activo, creando uno nuevo si no hay ninguno vivo. */
    private fun obtainProxyServer(): MegaProxyServer {
        synchronized(proxyServerLock) {
            currentProxyServer?.let { existing ->
                if (existing.isAlive) return existing
            }
            val fresh = MegaProxyServer(client = client)
            fresh.start()
            currentProxyServer = fresh
            return fresh
        }
    }

    // ── API de listado (usada por la fuente para construir el catálogo) ───

    fun parseLink(url: String): MegaLink? = MegaLinkParser.parse(url)

    /**
     * Lista el contenido de una carpeta pública ya descifrado. Lanza
     * [MegaApi.MegaApiException] si el link es inválido o fue eliminado.
     *
     * Filtra archivos que no sean de video (p.ej. .rar, .zip, .txt); las
     * carpetas siempre se conservan para no perder acceso a sus hijos.
     */
    fun listFolder(folderLink: MegaLink.Folder): List<MegaNode> {
        val folderKeyRaw = MegaCrypto.megaBase64Decode(folderLink.key)
        return api.listFolder(folderLink.handle, folderKeyRaw)
            .filter { it.isFolder || it.isVideo() }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "mpg", "mpeg",
        )

        /** True si el nodo es un archivo con extensión de video reproducible. */
        fun MegaNode.isVideo(): Boolean {
            if (isFolder) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }

    // DIAGNÓSTICO TEMPORAL: expone el JSON crudo de la última llamada a "f"
    // para poder mostrarlo en pantalla sin logcat. Quitar una vez resuelto.
    fun lastRawFolderResponse(): String? = api.lastRawResponse

    /**
     * Resuelve el nombre y tamaño de un archivo único (link tipo /file/),
     * sin necesidad de listar ninguna carpeta.
     */
    fun resolveSingleFile(fileLink: MegaLink.File): SingleFileMeta {
        val fullKey = MegaCrypto.megaBase64Decode(fileLink.key)
        val keyMaterial = MegaCrypto.deriveFileKey(fullKey)
        val download = api.getDownloadUrl(fileLink.handle)
        val name = download.at?.let { api.decryptSingleFileName(it, keyMaterial.aesKey) } ?: fileLink.handle
        return SingleFileMeta(
            handle = fileLink.handle,
            name = name,
            size = download.s ?: 0L,
            keyMaterial = keyMaterial,
        )
    }

    data class SingleFileMeta(
        val handle: String,
        val name: String,
        val size: Long,
        val keyMaterial: MegaFileKeyMaterial,
    )

    // ── Resolución de video reproducible ───────────────────────────────────

    /**
     * Dado el handle de un archivo (obtenido al listar carpeta o de un link
     * de archivo único) y su material de clave, pide a MEGA la URL temporal
     * de descarga y registra un stream en el proxy local. Devuelve un
     * [Video] cuya URL apunta al proxy (127.0.0.1), no directamente a MEGA.
     */
    fun videoFromNode(node: MegaNode, folderHandle: String? = null): List<Video> {
        val keyMaterial = node.fileKey
            ?: return emptyList()

        val download = api.getDownloadUrl(node.handle, folderHandle)
        val downloadUrl = download.g ?: return emptyList()
        val totalSize = download.s ?: node.size

        return buildVideoFromDownload(
            streamId = node.handle,
            downloadUrl = downloadUrl,
            keyMaterial = keyMaterial,
            totalSize = totalSize,
            displayName = node.name,
        )
    }

    fun videoFromSingleFile(meta: SingleFileMeta): List<Video> {
        val download = api.getDownloadUrl(meta.handle)
        val downloadUrl = download.g ?: return emptyList()
        val totalSize = download.s ?: meta.size

        return buildVideoFromDownload(
            streamId = meta.handle,
            downloadUrl = downloadUrl,
            keyMaterial = meta.keyMaterial,
            totalSize = totalSize,
            displayName = meta.name,
        )
    }

    private fun buildVideoFromDownload(
        streamId: String,
        downloadUrl: String,
        keyMaterial: MegaFileKeyMaterial,
        totalSize: Long,
        displayName: String,
    ): List<Video> {
        val server = obtainProxyServer()

        server.registerStream(
            streamId,
            MegaProxyServer.StreamInfo(
                downloadUrl = downloadUrl,
                aesKey = keyMaterial.aesKey,
                nonce = keyMaterial.nonce,
                totalSize = totalSize,
            ),
        )

        val localUrl = server.urlFor(streamId)
        return listOf(Video(localUrl, displayName, localUrl))
    }

    /**
     * Detiene el servidor proxy local. Es la única forma de apagarlo: no hay
     * auto-apagado por inactividad. Pensado para conectarse a un botón en
     * los ajustes de la extensión. Si luego se pide otro video, el proxy se
     * vuelve a levantar solo (ver [obtainProxyServer]).
     */
    fun shutdown() {
        synchronized(proxyServerLock) {
            currentProxyServer?.let { server ->
                if (server.isAlive) server.stop()
            }
            currentProxyServer = null
        }
    }

    /** True si el proxy local está corriendo ahora mismo. */
    fun isProxyRunning(): Boolean = currentProxyServer?.isAlive == true
}
