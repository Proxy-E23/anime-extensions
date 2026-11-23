package aniyomi.lib.gofileextractor

/**
 * Un enlace público de Gofile ya identificado. Solo guarda el id del contenido:
 * si es carpeta o archivo lo dice la API ([GofileNode.isFolder]), no la URL.
 */
data class GofileLink(val id: String)
