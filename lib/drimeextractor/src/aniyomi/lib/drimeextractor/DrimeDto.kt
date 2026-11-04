package aniyomi.lib.drimeextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// folderChildren es JsonElement porque, si el enlace es de un archivo, puede
// venir ausente o con otra forma; se decodifica a mano solo si es un objeto.
@Serializable
internal data class DrimeShareRoot(
    val link: DrimeShareLinkDto? = null,
    val folderChildren: JsonElement? = null,
    val status: String = "",
)

@Serializable
internal data class DrimeShareLinkDto(
    val id: Long = 0,
    val hash: String = "",
    val revoked_at: String? = null,
    val expires_at: String? = null,
    val user: DrimeUserDto? = null,
    val entry: DrimeEntryDto? = null,
)

@Serializable
internal data class DrimeUserDto(
    val display_name: String? = null,
)

@Serializable
internal data class DrimeEntryDto(
    val id: Long = 0,
    val name: String = "",
    val type: String = "",
    val hash: String = "",
    val extension: String? = null,
    val file_size: Long? = null,
    val created_at: String? = null,
)

@Serializable
internal data class DrimeChildrenPage(
    val next_page: Int? = null,
    val data: List<DrimeEntryDto> = emptyList(),
)

// Respuesta de GET /drive/file-entries (cuenta con token). `folder` es la carpeta
// listada, o null cuando se lista la raíz.
@Serializable
internal data class DrimeEntriesPage(
    val data: List<DrimeEntryDto> = emptyList(),
    val current_page: Int = 1,
    val last_page: Int = 1,
    val folder: DrimeEntryDto? = null,
)
