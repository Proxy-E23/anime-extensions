package aniyomi.lib.megaextractor

import kotlinx.serialization.Serializable
import java.math.BigInteger

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

// ── Login de cuenta (a=us0 / a=us) ─────────────────────────────────────────

@Serializable
internal data class MegaPreloginResponse(
    val v: Int? = null, // versión de la cuenta: 1 (legacy, sin salt) o 2 (PBKDF2 con salt)
    val s: String? = null, // salt en base64, solo presente si v=2
)

@Serializable
internal data class MegaLoginResponse(
    val k: String? = null, // master key cifrada (AES-ECB con la login key), base64
    val u: String? = null, // handle del usuario, base64
    val privk: String? = null, // clave privada RSA cifrada con la master key, base64
    val csid: String? = null, // session id cifrado con la clave pública RSA, base64
    val tsid: String? = null, // alternativa a csid para cuentas sin par RSA (no soportado acá)
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

// Todo lo de acá para abajo es material de login/sesión: se queda interno a
// propósito (nunca cruza la API pública de MegaExtractor, que solo expone
// login()/logout()/isLoggedIn()/loggedInEmail()) para no filtrar sid/master
// key crudos -- material sensible -- a quien consuma la librería.

/** Key de login v2 derivada de la contraseña vía PBKDF2 (ver [MegaCrypto.deriveV2LoginKey]). */
internal data class MegaV2LoginKey(
    val key: ByteArray, // descifra "k" (master key) de la respuesta de login
    val authKey: ByteArray, // se manda como "uh" en vez de la contraseña
)

/** Componentes de la clave privada RSA de la cuenta, ya descifrados (ver [MegaCrypto.parseMegaRsaPrivateKey]). */
internal data class MegaRsaPrivateKey(
    val p: BigInteger,
    val q: BigInteger,
    val d: BigInteger,
    val u: BigInteger, // p⁻¹ mod q
)

/**
 * Sesión de cuenta ya autenticada: lo mínimo necesario para adjuntar "sid" a
 * las llamadas y para descifrar los nodos del propio Cloud Drive. Es lo que
 * persiste [MegaSessionStore] entre extensiones y reinicios de la app.
 */
internal data class MegaAccountSession(
    val email: String,
    val sid: ByteArray, // 43 bytes (SIDLEN); se adjunta en cada request como &sid=
    val masterKey: ByteArray, // 16 bytes; descifra las node keys del propio Cloud Drive
    val ownHandle: ByteArray, // 8 bytes (handle de usuario); hace falta para elegir el par correcto en un compound key
)
