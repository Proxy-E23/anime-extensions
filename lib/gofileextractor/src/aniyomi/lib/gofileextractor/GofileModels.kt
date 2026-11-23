package aniyomi.lib.gofileextractor

/**
 * Archivo o carpeta dentro de un enlace de Gofile, expuesto fuera de la
 * lib para que la extensión no dependa de los DTOs internos.
 *
 * [created] son segundos desde epoch (0 si Gofile no lo informa).
 * [link] solo viene en archivos y es la URL de descarga directa.
 */
data class GofileNode(
    val id: String,
    val name: String,
    val type: String,
    val mimetype: String,
    val size: Long,
    val created: Long,
    val link: String?,
) {
    val isFolder: Boolean get() = type == "folder"

    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val isVideo: Boolean get() = !isFolder && (mimetype.startsWith("video/") || extension in VIDEO_EXTENSIONS)

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")
    }
}

/**
 * Contenido de un enlace de Gofile.
 *
 * [children] solo se llena cuando [root] es una carpeta, y solo trae su primer nivel.
 */
data class GofileContent(
    val root: GofileNode,
    val children: List<GofileNode>,
)
