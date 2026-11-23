package aniyomi.lib.gofileextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Punto de entrada público de la librería. Encapsula:
 *  - Parseo de enlaces compartidos de Gofile.
 *  - Lectura de un enlace (carpeta o archivo) con una sesión de invitado.
 *  - Resolución de la URL directa de reproducción de un archivo.
 *
 * Gofile no cifra los archivos: la URL de descarga es reproducible y acepta
 * Range, pero exige la cookie de la misma sesión de invitado con la que se leyó.
 */
class GofileExtractor(
    client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String = "https://gofile.io",
    apiUrl: String = "https://api.gofile.io",
) {

    private val api = GofileApi(client, headers, baseUrl, apiUrl)

    fun parseLink(url: String): GofileLink? = GofileLinkParser.parse(url)

    /**
     * null si el enlace (o la subcarpeta indicada) no existe, no es público
     * o requiere contraseña.
     */
    fun fetchContent(contentId: String): GofileContent? = api.fetchContent(contentId)

    /**
     * Arma el [Video] reproducible de un archivo. La URL de descarga se pide en el
     * momento de reproducir, leyendo de nuevo la carpeta contenedora [parentId]
     * (que puede ser el propio archivo si el enlace es de un archivo suelto).
     */
    fun videoFromFile(
        parentId: String,
        fileId: String,
        filename: String,
        displayName: String = filename.substringBeforeLast('.'),
    ): List<Video> {
        val content = api.fetchContent(parentId)
            ?: throw IOException("El enlace de Gofile ya no está disponible")

        val file = when {
            !content.root.isFolder -> content.root
            else -> content.children.firstOrNull { it.id == fileId }
                ?: content.children.firstOrNull { it.name == filename }
        } ?: throw IOException("El archivo ya no está en la carpeta de Gofile")

        val directUrl = file.link ?: throw IOException("Gofile no entregó el enlace de descarga")

        val token = api.token()
        val videoHeaders = headers.newBuilder()
            .set("Cookie", "accountToken=$token")
            .set("Authorization", "Bearer $token")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        return listOf(Video(videoUrl = directUrl, videoTitle = displayName, headers = videoHeaders))
    }
}
