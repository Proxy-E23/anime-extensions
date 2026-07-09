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
 * servidos por esta instancia, para no abrir un socket por episodio.
 */
class MegaExtractor(private val client: OkHttpClient) {

    private val api = MegaApi(client)

    @Volatile
    private var proxyServerStarted = false

    private val proxyServer: MegaProxyServer by lazy {
        MegaProxyServer(client).also {
            it.start()
            proxyServerStarted = true
        }
    }

    // ── API de listado (usada por la fuente para construir el catálogo) ───

    fun parseLink(url: String): MegaLink? = MegaLinkParser.parse(url)

    /**
     * Lista el contenido de una carpeta pública ya descifrado. Lanza
     * [MegaApi.MegaApiException] si el link es inválido o fue eliminado.
     */
    fun listFolder(folderLink: MegaLink.Folder): List<MegaNode> {
        val folderKeyRaw = MegaCrypto.megaBase64Decode(folderLink.key)
        return api.listFolder(folderLink.handle, folderKeyRaw)
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
        val server = proxyServer

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
     * Libera el servidor proxy local. Debe llamarse cuando la fuente ya no
     * necesita servir más streams (p.ej. desde onDestroy de la extensión, si
     * el framework lo expone) para no dejar el socket abierto.
     */
    fun shutdown() {
        if (proxyServerStarted) {
            proxyServer.stop()
        }
    }
}
