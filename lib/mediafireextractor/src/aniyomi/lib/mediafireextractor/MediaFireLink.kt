package aniyomi.lib.mediafireextractor

/**
 * Un link de MediaFire ya identificado como archivo o carpeta.
 *
 * [File.filename] puede venir nulo si la URL original no traía el nombre en
 * el path (por ejemplo un link recortado a solo el quickkey); en ese caso
 * el nombre real se resuelve aparte con [MediaFireExtractor.resolveFileName].
 */
sealed class MediaFireLink {
    data class File(val quickkey: String, val filename: String?) : MediaFireLink()
    data class Folder(val key: String) : MediaFireLink()
}
