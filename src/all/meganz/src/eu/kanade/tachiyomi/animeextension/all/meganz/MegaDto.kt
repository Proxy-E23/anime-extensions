package eu.kanade.tachiyomi.animeextension.all.meganz

import kotlinx.serialization.Serializable

/**
 * Persistido en SAnime.url. Identifica si la entrada es un archivo único,
 * una carpeta, una carpeta de primer nivel del Cloud Drive de la cuenta
 * logueada, los videos sueltos en la raíz de ese mismo Cloud Drive, o una
 * entrada "fantasma" (carpeta eliminada/inválida que se muestra con
 * instrucciones de borrado, igual que en Drive/MediaFire).
 */
@Serializable
data class MegaLinkData(
    val url: String,
    val type: String, // "single" | "multi" | "cloud" | "cloud_folder" | "error404"
    val info: MegaLinkDataInfo? = null,
    // Solo con type="cloud_folder": handle de la carpeta de primer nivel del
    // Cloud Drive que representa esta entrada. Con type="cloud" (los videos
    // sueltos en la raíz) queda null -- ahí se filtra por parentHandle
    // directo, no por este campo.
    val folderHandle: String? = null,
)

@Serializable
data class MegaLinkDataInfo(
    val title: String,
    val size: String,
)

/**
 * Persistido en SEpisode.url. Contiene lo mínimo necesario para resolver el
 * video sin tener que volver a parsear el link original:
 *  - Si [fromCloudDrive] es true, el episodio viene del Cloud Drive de la
 *    cuenta logueada; [fileKey] y [folderHandle] quedan null porque la key
 *    del nodo se resuelve con la master key de la cuenta, no con una key
 *    embebida en un link.
 *  - Si no, y [folderHandle] es null, el episodio viene de un link de
 *    archivo único y [fileKey] trae la key completa de ese link.
 *  - Si [folderHandle] no es null, el episodio viene de listar una carpeta
 *    pública; [fileKey] queda null porque la key del nodo ya se resuelve a
 *    partir de la master key de la carpeta (ver nodeCache en Mega.kt).
 *
 * [fromCloudDrive] tiene default=false para que los episodios ya guardados
 * de antes de este campo (que no lo tienen) sigan deserializando bien.
 */
@Serializable
data class MegaEpisodeData(
    val fileHandle: String,
    val fileKey: String?,
    val folderHandle: String?,
    val fromCloudDrive: Boolean = false,
)
