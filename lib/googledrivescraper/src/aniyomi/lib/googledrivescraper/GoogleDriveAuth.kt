package aniyomi.lib.googledrivescraper

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest

/**
 * Header de autorización que exige la API interna de Drive (v2internal)
 * para peticiones batch, derivado de la cookie SAPISID de la sesión.
 */
internal fun generateSapisidhashHeader(
    sapisid: String,
    origin: String = "https://drive.google.com",
): String {
    val timeNow = System.currentTimeMillis() / 1000
    val sapisidhash = MessageDigest
        .getInstance("SHA-1")
        .digest("$timeNow $sapisid $origin".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "SAPISIDHASH ${timeNow}_$sapisidhash"
}

internal fun OkHttpClient.cookieHeaderFor(url: String): String {
    val cookieList = cookieJar.loadForRequest(url.toHttpUrl())
    return if (cookieList.isNotEmpty()) {
        cookieList.joinToString("; ") { "${it.name}=${it.value}" }
    } else {
        ""
    }
}

internal fun OkHttpClient.sapisidFor(url: String): String = cookieJar.loadForRequest(url.toHttpUrl()).firstOrNull {
    it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
}?.value ?: ""

/**
 * Formatea un tamaño en bytes a una etiqueta legible (ej. "512.30 MB").
 * Pública porque cualquier extensión que use esta librería la necesita
 * para mostrar el tamaño de un episodio/archivo.
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
    bytes > 1 -> "$bytes bytes"
    bytes == 1L -> "$bytes byte"
    else -> ""
}
