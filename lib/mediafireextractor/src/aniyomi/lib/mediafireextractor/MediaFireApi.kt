package aniyomi.lib.mediafireextractor

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

internal class MediaFireApi(private val client: OkHttpClient, private val baseUrl: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun apiFoldersUrl(key: String, chunk: Int) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=folders&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFilesUrl(key: String, chunk: Int) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=files&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFolderInfoUrl(key: String) = "$baseUrl/api/1.5/folder/get_info.php" +
        "?folder_key=$key&version=1.5&response_format=json"

    fun fetchFolderName(key: String): String = try {
        val body = client.newCall(GET(apiFolderInfoUrl(key))).execute().body.string()
        json.decodeFromString<MediaFireRoot>(body).response.folder_info?.name
            ?.takeIf { it.isNotBlank() } ?: key
    } catch (e: Exception) {
        key
    }

    fun isFolderMissing(key: String): Boolean = try {
        val body = client.newCall(GET(apiFolderInfoUrl(key))).execute().body.string()
        // La API devuelve result=Error con error=112 cuando la carpeta no existe.
        // Puede venir en JSON o XML dependiendo del estado.
        "Error" in body && ("112" in body || "Unknown or invalid" in body)
    } catch (e: Exception) {
        false
    }

    fun fetchAllFolders(key: String): List<MediaFireSubFolder> {
        val list = mutableListOf<MediaFireSubFolder>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFoldersUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MediaFireRoot>(body).response.folder_content ?: break
            list += (content.folders ?: emptyList()).map {
                MediaFireSubFolder(key = it.folderkey, name = it.name, created = it.created)
            }
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    fun fetchAllFiles(key: String): List<MediaFireFolderEntry> {
        val list = mutableListOf<MediaFireFolderEntry>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFilesUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MediaFireRoot>(body).response.folder_content ?: break
            list += (content.files ?: emptyList()).map {
                MediaFireFolderEntry(quickkey = it.quickkey, filename = it.filename, created = it.created)
            }
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    fun fetchFileNameFromPage(quickkey: String, browserHeaders: Headers): String? = try {
        val document = client.newCall(GET("$baseUrl/file/$quickkey/", browserHeaders)).execute().use { it.asJsoup() }
        document.selectFirst(".dl-btn-label")?.attr("title")
            ?: document.selectFirst("div.filename")?.text()
    } catch (e: Exception) {
        null
    }

    fun fetchNormalDownloadLink(quickkey: String, browserHeaders: Headers): String? = try {
        val apiUrl = "$baseUrl/api/1.5/file/get_links.php" +
            "?quick_key=$quickkey&link_type=normal_download&response_format=json"
        val body = client.newCall(GET(apiUrl, browserHeaders)).execute().body.string()
        json.decodeFromString<MediaFireLinksRoot>(body)
            .response.links?.firstOrNull()?.normal_download
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun followRedirectLocation(url: String, browserHeaders: Headers): String? {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val resp = noRedirectClient.newCall(GET(url, browserHeaders)).execute()
        val location = resp.header("Location")
        resp.close()
        return location
    }

    fun fetchDownloadButtonHref(pageUrl: String, browserHeaders: Headers): String? = try {
        val document = client.newCall(GET(pageUrl, browserHeaders)).execute().use { it.asJsoup() }
        document.selectFirst("a#downloadButton")?.attr("abs:href")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
