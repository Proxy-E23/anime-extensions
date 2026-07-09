package eu.kanade.tachiyomi.animeextension.all.meganz

import kotlinx.serialization.Serializable

/**
 * Persistido en SAnime.url. Identifica si la entrada es un archivo único,
 * una carpeta, o una entrada "fantasma" (carpeta eliminada/inválida que se
 * muestra con instrucciones de borrado, igual que en Drive/MediaFire).
 */
@Serializable
data class MegaLinkData(
    val url: String,
    val type: String, // "single" | "multi" | "error404"
    val info: MegaLinkDataInfo? = null,
)

@Serializable
data class MegaLinkDataInfo(
    val title: String,
    val size: String,
)

/**
 * Persistido en SEpisode.url. Contiene lo mínimo necesario para resolver el
 * video sin tener que volver a parsear el link original:
 *  - Si [folderHandle] es null, el episodio viene de un link de archivo
 *    único y [fileKey] trae la key completa del propio link.
 *  - Si [folderHandle] no es null, el episodio viene de listar una carpeta;
 *    [fileKey] queda null porque la key del nodo ya se resuelve a partir de
 *    la master key de la carpeta (ver nodeCache en Mega.kt).
 */
@Serializable
data class MegaEpisodeData(
    val fileHandle: String,
    val fileKey: String?,
    val folderHandle: String?,
)
