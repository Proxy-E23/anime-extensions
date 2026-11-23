package aniyomi.lib.gofileextractor

import kotlinx.serialization.Serializable

@Serializable
internal class GofileResponse(
    val status: String = "",
    val data: GofileDataDto? = null,
)

// Los hijos de una carpeta llegan como mapa "<id>" -> entrada; la entrada
// tiene la misma forma que el contenido raíz, salvo que no trae hijos.
@Serializable
internal class GofileDataDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val mimetype: String = "",
    val size: Long = 0,
    val createTime: Long = 0,
    val link: String? = null,
    val canAccess: Boolean = true,
    val passwordStatus: String? = null,
    val children: Map<String, GofileDataDto> = emptyMap(),
)

@Serializable
internal class GofileAccountDto(
    val token: String = "",
)

@Serializable
internal class GofileAccountResponse(
    val status: String = "",
    val data: GofileAccountDto? = null,
)
