package eu.kanade.tachiyomi.animeextension.all.googledrive

import kotlinx.serialization.Serializable

@Serializable
data class LinkData(
    val url: String,
    val type: String,
    val info: LinkDataInfo? = null,
)

@Serializable
data class LinkDataInfo(
    val title: String,
    val size: String,
)
