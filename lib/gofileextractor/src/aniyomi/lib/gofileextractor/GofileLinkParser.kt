package aniyomi.lib.gofileextractor

/**
 * Parseo de enlaces compartidos de Gofile a [GofileLink].
 * No hace peticiones de red: es análisis puro del texto de la URL.
 *
 * Formato aceptado:
 *  - https://gofile.io/d/<id>
 */
object GofileLinkParser {

    private val SHARE_REGEX = Regex("""(?i)gofile\.io/d/([A-Za-z0-9-]+)""")

    fun parse(rawUrl: String): GofileLink? = SHARE_REGEX.find(rawUrl.trim())
        ?.let { GofileLink(it.groupValues[1]) }
}
