package aniyomi.lib.mediafireextractor

import kotlinx.serialization.Serializable

@Serializable
internal data class MediaFireRoot(val response: MediaFireResponse)

@Serializable
internal data class MediaFireResponse(
    val folder_content: MediaFireContent? = null,
    val folder_info: MediaFireFolderInfo? = null,
    val result: String = "",
)

@Serializable
internal data class MediaFireContent(
    val folders: List<MediaFireFolder>? = null,
    val files: List<MediaFireFile>? = null,
    val more_chunks: String = "no",
)

@Serializable
internal data class MediaFireFolderInfo(
    val name: String = "",
    val folderkey: String = "",
)

@Serializable
internal data class MediaFireFolder(
    val folderkey: String,
    val name: String,
    val created: String = "",
)

@Serializable
internal data class MediaFireFile(
    val quickkey: String,
    val filename: String,
    val created: String = "",
)

@Serializable
internal data class MediaFireLinksRoot(val response: MediaFireLinksResponse)

@Serializable
internal data class MediaFireLinksResponse(
    val links: List<MediaFireDownloadLink>? = null,
    val result: String = "",
)

@Serializable
internal data class MediaFireDownloadLink(
    val normal_download: String = "",
    val direct_download: String = "",
)

/**
 * Nodo público equivalente a [MediaFireFile]/[MediaFireFolder], expuesto
 * fuera de la lib para que la extensión no dependa de los DTOs internos de
 * deserialización.
 */
data class MediaFireFolderEntry(
    val quickkey: String,
    val filename: String,
    val created: String,
)

data class MediaFireSubFolder(
    val key: String,
    val name: String,
    val created: String,
)
