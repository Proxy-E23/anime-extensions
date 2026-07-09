package aniyomi.lib.megaextractor

/**
 * Parsea los distintos formatos de link público de MEGA:
 *
 *  - Archivo legacy:   https://mega.nz/#!<handle>!<key>
 *  - Archivo nuevo:    https://mega.nz/file/<handle>#<key>
 *  - Carpeta legacy:   https://mega.nz/#F!<handle>!<key>
 *  - Carpeta nueva:    https://mega.nz/folder/<handle>#<key>
 *  - Archivo dentro de carpeta:
 *      https://mega.nz/folder/<handle>#<key>/file/<subHandle>
 */
internal object MegaLinkParser {

    private val FILE_NEW = Regex("""mega\.nz/file/([\w-]+)#([\w-]+)""")
    private val FILE_LEGACY = Regex("""mega\.nz/#!([\w-]+)!([\w-]+)""")
    private val FOLDER_NEW = Regex("""mega\.nz/folder/([\w-]+)#([\w-]+)(?:/file/([\w-]+))?""")
    private val FOLDER_LEGACY = Regex("""mega\.nz/#F!([\w-]+)!([\w-]+)""")

    fun parse(rawUrl: String): MegaLink? {
        val url = rawUrl.trim()

        FOLDER_NEW.find(url)?.let { m ->
            val (handle, key, subHandle) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
            return MegaLink.Folder(handle, key, subHandle?.takeIf { it.isNotBlank() })
        }
        FOLDER_LEGACY.find(url)?.let { m ->
            return MegaLink.Folder(m.groupValues[1], m.groupValues[2], null)
        }
        FILE_NEW.find(url)?.let { m ->
            return MegaLink.File(m.groupValues[1], m.groupValues[2])
        }
        FILE_LEGACY.find(url)?.let { m ->
            return MegaLink.File(m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    fun isMegaUrl(url: String): Boolean = "mega.nz" in url || "mega.co.nz" in url
}

/**
 * Resultado de parsear un link público de MEGA. Es el único tipo de esta
 * librería pensado para ser inspeccionado con un `when` desde la extensión
 * (p.ej. para decidir si tratar el enlace como archivo único o carpeta).
 */
sealed class MegaLink {
    data class File(val handle: String, val key: String) : MegaLink()
    data class Folder(val handle: String, val key: String, val subFileHandle: String?) : MegaLink()
}
