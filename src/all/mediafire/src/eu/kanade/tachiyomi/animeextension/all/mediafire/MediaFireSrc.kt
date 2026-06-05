package eu.kanade.tachiyomi.animeextension.all.mediafire

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MediaFireSrc :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MediaFire"
    override val baseUrl = "https://www.mediafire.com"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── API URLs ──────────────────────────────────────────────────────────────

    private fun apiFoldersUrl(key: String, chunk: Int = 1) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=folders&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFilesUrl(key: String, chunk: Int = 1) = "$baseUrl/api/1.5/folder/get_content.php" +
        "?folder_key=$key&content_type=files&chunk=$chunk" +
        "&version=1.5&response_format=json"

    private fun apiFolderInfoUrl(key: String) = "$baseUrl/api/1.5/folder/get_info.php" +
        "?folder_key=$key&version=1.5&response_format=json"

    // ── Serialización ─────────────────────────────────────────────────────────

    @Serializable data class MFRoot(val response: MFResponse)

    @Serializable data class MFResponse(
        val folder_content: MFContent? = null,
        val folder_info: MFFolderInfo? = null,
        val result: String = "",
    )

    @Serializable data class MFContent(
        val folders: List<MFFolder>? = null,
        val files: List<MFFile>? = null,
        val more_chunks: String = "no",
    )

    @Serializable data class MFFolderInfo(
        val name: String = "",
        val folderkey: String = "",
    )

    @Serializable data class MFFolder(
        val folderkey: String,
        val name: String,
        val created: String = "",
    )

    @Serializable data class MFFile(
        val quickkey: String,
        val filename: String,
        val created: String = "",
    )

    // get_links API
    @Serializable data class MFLinksRoot(val response: MFLinksResponse)

    @Serializable data class MFLinksResponse(
        val links: List<MFLink>? = null,
        val result: String = "",
    )

    @Serializable data class MFLink(
        val normal_download: String = "",
        val direct_download: String = "",
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun folderKeyFromUrl(url: String): String {
        val m = Regex("mediafire\\.com/folder/([A-Za-z0-9]+)").find(url)
        return m?.groupValues?.get(1) ?: url.trimEnd('/').substringAfterLast('/')
    }

    private fun fetchFolderName(key: String): String = try {
        val body = client.newCall(GET(apiFolderInfoUrl(key))).execute().body.string()
        json.decodeFromString<MFRoot>(body).response.folder_info?.name
            ?.takeIf { it.isNotBlank() } ?: key
    } catch (e: Exception) {
        key
    }

    private fun fetchAllFolders(key: String): List<MFFolder> {
        val list = mutableListOf<MFFolder>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFoldersUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MFRoot>(body).response.folder_content ?: break
            list += content.folders ?: emptyList()
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    private fun fetchAllFiles(key: String): List<MFFile> {
        val list = mutableListOf<MFFile>()
        var chunk = 1
        while (true) {
            val body = client.newCall(GET(apiFilesUrl(key, chunk))).execute().body.string()
            val content = json.decodeFromString<MFRoot>(body).response.folder_content ?: break
            list += content.files ?: emptyList()
            if (content.more_chunks != "yes") break
            chunk++
        }
        return list
    }

    private val browserHeaders by lazy {
        headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .build()
    }

    /** Devuelve la URL directa del CDN para que mpv la pueda reproducir. */
    private fun resolveDirectVideoUrl(quickkey: String, filename: String): String {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()

        // 1. get_links API -> normal_download -> seguir redirect con headers de navegador
        try {
            val apiUrl = "$baseUrl/api/1.5/file/get_links.php" +
                "?quick_key=$quickkey&link_type=normal_download&response_format=json"
            val body = client.newCall(GET(apiUrl, browserHeaders)).execute().body.string()
            val normalUrl = json.decodeFromString<MFLinksRoot>(body)
                .response.links?.firstOrNull()?.normal_download
                ?.takeIf { it.isNotBlank() }

            if (normalUrl != null) {
                val resp = noRedirectClient.newCall(GET(normalUrl, browserHeaders)).execute()
                val location = resp.header("Location")
                resp.close()
                if (!location.isNullOrBlank() && "download" in location) return location
            }
        } catch (_: Exception) {}

        // 2. Fallback: cargar la pagina y seguir el redirect del boton de descarga
        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val pageUrl = "$baseUrl/file/$quickkey/$encodedFilename"
        return try {
            val document = client.newCall(GET(pageUrl, browserHeaders)).execute().use { it.asJsoup() }
            val btnHref = document.selectFirst("a#downloadButton")?.attr("abs:href")
                ?.takeIf { it.isNotBlank() } ?: return pageUrl
            val resp = noRedirectClient.newCall(GET(btnHref, browserHeaders)).execute()
            val location = resp.header("Location")
            resp.close()
            if (!location.isNullOrBlank() && "download" in location) location else btnHref
        } catch (_: Exception) {
            pageUrl
        }
    }

    private fun isVideo(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")
    }

    // ── Expansión recursiva ───────────────────────────────────────────────────

    private fun expandFolder(
        key: String,
        folderName: String,
        parentTitle: String,
        depth: Int = 0,
    ): List<SAnime> {
        if (depth > 10) return emptyList()

        val subFolders = fetchAllFolders(key)
        val videoFiles = fetchAllFiles(key).filter { isVideo(it.filename) }

        val results = mutableListOf<SAnime>()

        if (subFolders.isEmpty()) {
            if (videoFiles.isNotEmpty()) {
                val displayTitle = if (parentTitle.isBlank()) {
                    folderName
                } else {
                    "$parentTitle - $folderName"
                }
                results += SAnime.create().apply {
                    url = "folder::$key::$folderName"
                    title = displayTitle
                    status = SAnime.UNKNOWN
                    initialized = true
                }
            }
        } else {
            videoFiles.forEach { file ->
                val displayTitle = if (parentTitle.isBlank()) {
                    file.filename.substringBeforeLast('.')
                } else {
                    "$parentTitle - ${file.filename.substringBeforeLast('.')}"
                }
                results += SAnime.create().apply {
                    url = "file::${file.quickkey}::${file.filename}"
                    title = displayTitle
                    status = SAnime.UNKNOWN
                    initialized = true
                }
            }

            subFolders.forEach { sub ->
                val subTitle = if (parentTitle.isBlank()) folderName else parentTitle
                results += expandFolder(
                    key = sub.folderkey,
                    folderName = sub.name,
                    parentTitle = subTitle,
                    depth = depth + 1,
                )
            }
        }

        return results
    }

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val animes = MediaFirePreferences.getEntries(preferences).flatMap { entry ->
            if (entry.key.startsWith("file::")) {
                listOf(
                    SAnime.create().apply {
                        url = entry.key
                        title = entry.name
                        status = SAnime.UNKNOWN
                        initialized = true
                    },
                )
            } else {
                expandFolder(
                    key = entry.key,
                    folderName = entry.name,
                    parentTitle = "",
                )
            }
        }
        return AnimesPage(animes, false)
    }

    // ── Búsqueda + Filtros ────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val urlFilter = filters.filterIsInstance<UrlFilter>().firstOrNull()
        val nameFilter = filters.filterIsInstance<NameFilter>().firstOrNull()
        val rawUrl = urlFilter?.state?.trim() ?: ""

        if (rawUrl.isNotBlank() && "mediafire.com/file" in rawUrl) {
            val cleanUrl = rawUrl.trimEnd('/').removeSuffix("/file")
            val parts = cleanUrl.trimEnd('/').split("/")
            val quickkey = parts.getOrElse(parts.size - 2) { "" }
            val filename = parts.lastOrNull()?.let {
                java.net.URLDecoder.decode(
                    java.net.URLDecoder.decode(it, "UTF-8"),
                    "UTF-8",
                )
            } ?: "video"
            val resolvedName = nameFilter?.state?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: filename.substringBeforeLast('.')

            MediaFirePreferences.addEntry(preferences, resolvedName, "file::$quickkey::$filename")

            return AnimesPage(
                listOf(
                    SAnime.create().apply {
                        url = "file::$quickkey::$filename"
                        title = resolvedName
                        status = SAnime.UNKNOWN
                        initialized = true
                    },
                ),
                false,
            )
        }

        if (rawUrl.isNotBlank() && "mediafire.com/folder" in rawUrl) {
            val key = folderKeyFromUrl(rawUrl)
            val resolvedName = nameFilter?.state?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fetchFolderName(key)

            MediaFirePreferences.addEntry(preferences, resolvedName, key)

            return AnimesPage(
                expandFolder(
                    key = key,
                    folderName = resolvedName,
                    parentTitle = "",
                ),
                false,
            )
        }

        val all = getPopularAnime(page)
        val results = if (query.isBlank()) {
            all.animes
        } else {
            all.animes.filter { it.title.contains(query, ignoreCase = true) }
        }
        return AnimesPage(results, false)
    }

    override fun getFilterList() = AnimeFilterList(
        InfoFilter("Pega una URL de carpeta o archivo MediaFire para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        SeparatorFilter(),
        InfoFilter("Deja los campos vacíos para buscar por nombre"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request {
        val key = anime.url
        val webUrl = when {
            key.startsWith("file::") -> {
                val parts = key.split("::")
                "$baseUrl/file/${parts[1]}/${parts[2]}"
            }
            key.startsWith("folder::") -> {
                val folderKey = key.split("::")[1]
                "$baseUrl/folder/$folderKey"
            }
            else -> "$baseUrl/folder/$key"
        }
        return GET(webUrl)
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url

        if (url.startsWith("file::")) {
            val parts = url.split("::")
            val filename = runCatching {
                java.net.URLDecoder.decode(
                    java.net.URLDecoder.decode(parts[2], "UTF-8"),
                    "UTF-8",
                )
            }.getOrElse { parts[2] }
            val episodeName = if (showFilename) {
                filename.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: anime.title
            } else {
                "Episodio 1"
            }
            return listOf(
                SEpisode.create().apply {
                    this.url = url
                    name = episodeName
                    episode_number = 1f
                    date_upload = 0L
                },
            )
        }

        val key = url.split("::")[1]
        return fetchAllFiles(key)
            .filter { isVideo(it.filename) }
            .sortedBy { it.filename }
            .mapIndexed { idx, file ->
                SEpisode.create().apply {
                    this.url = "file::${file.quickkey}::${file.filename}"
                    name = if (showFilename) {
                        file.filename.substringBeforeLast('.')
                    } else {
                        "Episodio ${idx + 1}"
                    }
                    episode_number = (idx + 1).toFloat()
                    date_upload = runCatching {
                        dateFormat.parse(file.created)?.time ?: 0L
                    }.getOrElse { 0L }
                }
            }
            .reversed()
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("::")
        val quickkey = parts[1]
        val filename = parts[2]
        val directUrl = resolveDirectVideoUrl(quickkey, filename)
        val videoHeaders = browserHeaders.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return listOf(Video(directUrl, filename.substringBeforeLast('.'), directUrl, videoHeaders))
    }

    private val showFilename: Boolean
        get() = MediaFirePreferences.showFilename(preferences)

    // ── Preferencias ──────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "mediafire_folder_list"
            title = "Carpetas guardadas (edición directa)"
            summary = "Formato: Nombre::https://www.mediafire.com/folder/KEY/nombre\nUna por línea."
            dialogTitle = "Editar carpetas"
            setDefaultValue("")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = MediaFirePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
}
