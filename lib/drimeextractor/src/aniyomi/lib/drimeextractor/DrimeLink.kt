package aniyomi.lib.drimeextractor

/**
 * Un enlace público de Drime ya identificado. Solo guarda el hash: si es
 * carpeta o archivo lo dice la API ([DrimeNode.isFolder]), no la URL.
 */
data class DrimeLink(val hash: String)
