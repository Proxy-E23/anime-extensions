package aniyomi.lib.megaextractor

import kotlinx.serialization.Serializable

// ── Respuestas crudas de la API (a=f, listar árbol) ────────────────────────

@Serializable
internal data class MegaFolderResponse(
    val f: List<MegaRawNode>? = null,
    // La API puede responder un entero de error en vez de objeto en algunos casos,
    // pero cuando es exitoso siempre es un objeto con "f". Los errores se
    // manejan a nivel de MegaApi revisando el código HTTP/JSON crudo.
)

@Serializable
internal data class MegaRawNode(
    val h: String, // handle del nodo
    val p: String? = null, // handle del padre
    val t: Int, // tipo: 0 = archivo, 1 = carpeta
    val a: String, // atributos cifrados (nombre), base64
    val k: String? = null, // node key cifrada, formato "handle:blob" o varios separados por "/"
    val s: Long? = null, // tamaño en bytes (solo archivos)
    val ts: Long? = null, // timestamp de creación (epoch, segundos)
)

// ── Respuesta de descarga (a=g) ────────────────────────────────────────────

@Serializable
internal data class MegaDownloadResponse(
    val g: String? = null, // URL temporal de descarga
    val s: Long? = null, // tamaño en bytes
    val at: String? = null, // atributos cifrados (para archivo único, sin listar carpeta)
    val e: Int? = null, // código de error si algo falló
)

// ── Modelo ya descifrado, usado por el resto de la extensión ──────────────

/**
 * Material de clave derivado de un link/nodo de archivo: la AES key real
 * (16 bytes) y el nonce de CTR (8 bytes) necesarios para descifrar tanto el
 * nombre del archivo como su contenido en streaming.
 */
data class MegaFileKeyMaterial(
    val aesKey: ByteArray,
    val nonce: ByteArray,
    val metaMac: ByteArray,
)

data class MegaNode(
    val handle: String,
    val parentHandle: String?,
    val isFolder: Boolean,
    val name: String,
    val size: Long,
    val timestamp: Long,
    // Material de clave solo presente para archivos (permite pedir el stream)
    val fileKey: MegaFileKeyMaterial?,
    // Master key de la propia carpeta, solo si isFolder, para descifrar hijos
    val folderKey: ByteArray?,
)
