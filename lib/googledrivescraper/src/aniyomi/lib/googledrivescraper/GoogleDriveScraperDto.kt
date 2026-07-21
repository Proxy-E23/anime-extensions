package aniyomi.lib.googledrivescraper

import kotlinx.serialization.Serializable

/**
 * Respuesta cruda de la API interna de Drive (v2internal/files) al listar
 * el contenido de una carpeta.
 */
@Serializable
internal data class PostResponse(
    val nextPageToken: String? = null,
    val items: List<ResponseItem>? = null,
) {
    @Serializable
    data class ResponseItem(
        val id: String,
        val title: String,
        val mimeType: String,
        val fileSize: String? = null,
        val modifiedDate: String? = null,
        val shortcutDetails: ShortcutDetails? = null,
    )

    @Serializable
    data class ShortcutDetails(
        val targetId: String = "",
        val targetMimeType: String = "",
    )
}

/**
 * Respuesta cruda de la API interna de Drive al pedir los metadatos de un
 * único archivo por su ID (sin necesitar la carpeta que lo contiene).
 */
@Serializable
internal data class SingleFileResponse(
    val id: String,
    val title: String,
    val mimeType: String,
    val fileSize: String? = null,
    val modifiedDate: String? = null,
)

@Serializable
internal data class DownloadResponse(
    val downloadUrl: String,
)

/**
 * Archivo `details.json` opcional que se puede colocar dentro de una
 * carpeta para describir el anime (autor, géneros, sinopsis, etc). El
 * título NO se expone aquí a propósito -- ver [ScrapedDetails].
 */
@Serializable
internal data class DetailsJson(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
)

/** Item ya resuelto (video o carpeta, con shortcuts destrabados) de un listado de carpeta. */
data class ScrapedItem(
    val id: String,
    val title: String,
    val isVideo: Boolean,
    val isFolder: Boolean,
    val fileSize: Long?,
    val modifiedDateMillis: Long?,
)

/** Página de resultados al listar el contenido de una carpeta (un solo nivel, sin recursión). */
data class FolderPage(
    val items: List<ScrapedItem>,
    val nextPageToken: String?,
)

/**
 * Distingue "la carpeta ya no existe" (404 real) de cualquier otro fallo de
 * red, para poder mostrarle al usuario "esta carpeta fue eliminada" solo
 * cuando corresponde.
 */
sealed class FolderResult {
    data class Success(val page: FolderPage) : FolderResult()
    data object NotFound : FolderResult()
    data object NetworkError : FolderResult()
}

/** Metadatos completos (autenticados) de un archivo suelto de Drive. */
data class FileMetadata(
    val title: String,
    val fileSize: Long?,
    val modifiedDateMillis: Long?,
)

/** Episodio ya resuelto por el recorrido recursivo de alto nivel [GoogleDriveScraper.scrapeEpisodes]. */
data class ScrapedEpisode(
    val name: String,
    val url: String,
    val episodeNumber: Float,
    val dateUploadMillis: Long,
    val sizeLabel: String,
    val path: String,
)

/** Subcarpeta de primer nivel encontrada al listar una carpeta raíz como catálogo. */
data class ScrapedFolder(
    val id: String,
    val name: String,
)

/** Detalles de una carpeta (portada + `details.json`, si existe). El nombre del anime NO se incluye -- ver comentario en [DetailsJson]. */
data class ScrapedDetails(
    val coverUrl: String?,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: String?,
    val status: String?,
)
