package aniyomi.lib.drimeextractor

/**
 * Archivo o carpeta dentro de un enlace compartido, expuesto fuera de la
 * lib para que la extensión no dependa de los DTOs internos.
 */
data class DrimeNode(
    val id: Long,
    val hash: String,
    val name: String,
    val type: String,
    val extension: String,
    val size: Long,
    val created: String,
) {
    val isFolder: Boolean get() = type == "folder"

    val isVideo: Boolean get() = !isFolder && (type == "video" || extension in VIDEO_EXTENSIONS)

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")
    }
}

/**
 * Contenido de un enlace compartido.
 *
 * [linkId] es el id numérico del enlace: la ruta de descarga lo exige en
 * `shareable_link` (con el hash responde 404).
 * [children] solo se llena cuando [root] es una carpeta, y solo trae su
 * primer nivel.
 */
data class DrimeShare(
    val linkId: Long,
    val hash: String,
    val owner: String?,
    val root: DrimeNode,
    val children: List<DrimeNode>,
)

/**
 * Contenido de una carpeta de la cuenta del usuario (con token).
 * [root] es la carpeta listada, o null cuando se lista la raíz del Drive.
 */
data class DrimeFolderListing(
    val root: DrimeNode?,
    val children: List<DrimeNode>,
)
