package aniyomi.lib.drimeextractor

/**
 * Parseo de enlaces compartidos de Drime a [DrimeLink].
 * No hace peticiones de red -- es análisis puro del texto de la URL.
 *
 * Formatos aceptados:
 *  - https://app.drime.cloud/drive/s/<hash>
 *  - https://app.drime.cloud/drive/shares/<hash>
 *  - https://dri.me/<hash>
 */
object DrimeLinkParser {

    private val SHARE_REGEX = Regex("""(?i)(?:drime\.cloud/drive/(?:s|shares)/|dri\.me/)([A-Za-z0-9_-]+)""")

    fun parse(rawUrl: String): DrimeLink? = SHARE_REGEX.find(rawUrl.trim())
        ?.let { DrimeLink(it.groupValues[1]) }
}
