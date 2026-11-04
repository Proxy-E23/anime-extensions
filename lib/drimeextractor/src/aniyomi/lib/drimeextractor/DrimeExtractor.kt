package aniyomi.lib.drimeextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Punto de entrada público de la librería. Encapsula:
 *  - Parseo de enlaces compartidos de Drime.
 *  - Lectura de un enlace (carpeta o archivo) sin iniciar sesión.
 *  - Resolución de la URL directa de reproducción de un archivo.
 *
 * Como MediaFire, Drime no cifra los archivos: la URL que devuelve la API
 * es directamente reproducible y acepta Range.
 */
class DrimeExtractor(
    client: OkHttpClient,
    private val headers: Headers,
    baseUrl: String = "https://app.drime.cloud",
) {

    private val api = DrimeApi(client, headers, baseUrl)

    fun parseLink(url: String): DrimeLink? = DrimeLinkParser.parse(url)

    /**
     * null si el enlace (o la subcarpeta indicada en [folderHash]) no existe,
     * fue revocado o no es accesible sin sesión.
     */
    fun fetchShare(hash: String, folderHash: String? = null): DrimeShare? = api.fetchShare(hash, folderHash)

    fun videoFromFile(
        shareHash: String,
        linkId: Long,
        entryHash: String,
        filename: String,
        displayName: String = filename.substringBeforeLast('.'),
    ): List<Video> {
        val directUrl = api.resolveDirectUrl(shareHash, linkId, entryHash)
        return listOf(Video(directUrl, displayName, directUrl, headers))
    }

    /**
     * Lista una carpeta de la cuenta del usuario con su token de API
     * ([folderHash] null = raíz del Drive). null si la carpeta ya no existe.
     */
    fun listDrive(token: String, folderHash: String? = null): DrimeFolderListing? = api.listDrive(token, folderHash)

    fun videoFromDriveFile(
        token: String,
        entryHash: String,
        filename: String,
        displayName: String = filename.substringBeforeLast('.'),
    ): List<Video> {
        val directUrl = api.resolveDriveUrl(token, entryHash)
        return listOf(Video(directUrl, displayName, directUrl, headers))
    }
}
