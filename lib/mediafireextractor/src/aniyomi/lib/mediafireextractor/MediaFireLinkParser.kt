package aniyomi.lib.mediafireextractor

/**
 * Parseo de URLs públicas de MediaFire (archivo o carpeta) a [MediaFireLink].
 * No hace peticiones de red -- es análisis puro del texto de la URL.
 */
object MediaFireLinkParser {

    private val FOLDER_REGEX = Regex("""mediafire\.com/folder/([A-Za-z0-9]+)""")
    private val FILE_REGEX = Regex("""mediafire\.com/file/([A-Za-z0-9]+)(?:/([^/?#]+))?""")

    fun parse(rawUrl: String): MediaFireLink? {
        val url = rawUrl.trim()

        FOLDER_REGEX.find(url)?.let {
            return MediaFireLink.Folder(it.groupValues[1])
        }
        FILE_REGEX.find(url)?.let { m ->
            val quickkey = m.groupValues[1]
            val filename = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?.let(::decodeFilenameFromPath)
            return MediaFireLink.File(quickkey, filename)
        }
        return null
    }

    private fun decodeFilenameFromPath(raw: String): String = runCatching {
        java.net.URLDecoder.decode(
            java.net.URLDecoder.decode(raw, "UTF-8"),
            "UTF-8",
        )
    }.getOrElse { raw }
}
