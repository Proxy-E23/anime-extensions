package eu.kanade.tachiyomi.animeextension.all.collectednotes

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.googledrivescraper.GoogleDriveScraper
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class CollectedNotesSrc :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Collected Notes"
    override val baseUrl = "https://collectednotes.com"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private val scraper by lazy { GoogleDriveScraper(client, headers) }

    private var cachedAnimes: List<SAnime>? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val entries = CollectedNotesPreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val notes = fetchAllNotes(entry.sitePath)
            notes.forEach { note ->
                val hasValidLinks = DRIVE_LINK_REGEX.findAll(note.body)
                    .any { it.groupValues[2].isNotBlank() }
                if (!hasValidLinks) return@forEach

                animeList.add(
                    SAnime.create().apply {
                        title = note.title
                        url = "${entry.sitePath}::${note.path}"
                        thumbnail_url = BODY_IMAGE_REGEX.find(note.body)?.groupValues?.get(1) ?: ""
                        description = extractDescription(note.body)
                        artist = entry.name
                        status = if (note.body.contains("[Drive]()")) {
                            SAnime.ONGOING
                        } else {
                            SAnime.COMPLETED
                        }
                        initialized = true
                    },
                )
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is AddUrlFilter } as AddUrlFilter
        val nameFilter = filterList.find { it is AddNameFilter } as AddNameFilter
        val siteFilter = filterList.find { it is SiteFilter } as SiteFilter

        val rawUrl = urlFilter.state.trim()

        if (rawUrl.isNotBlank()) {
            val sitePath = rawUrl
                .trimEnd('/')
                .removePrefix("https://collectednotes.com/")
                .removePrefix("http://collectednotes.com/")
                .removeSuffix(".json")
                .trim()

            CollectedNotesPreferences.findBySitePath(preferences, sitePath)?.let { existing ->
                throw Exception("Este sitio ya está guardado como \"${existing.name}\"")
            }

            val resolvedName = nameFilter.state.trim().takeIf { it.isNotBlank() } ?: sitePath
            CollectedNotesPreferences.addEntry(preferences, resolvedName, "https://collectednotes.com/$sitePath")
            cachedAnimes = null
            return getPopularAnime(page)
        }

        val all = getPopularAnime(page)

        val filteredBySite = if (siteFilter.state > 0) {
            val selectedSite = CollectedNotesPreferences.getEntries(preferences)
                .getOrNull(siteFilter.state - 1)
            if (selectedSite != null) {
                all.animes.filter { it.url.startsWith("${selectedSite.sitePath}::") }
            } else {
                all.animes
            }
        } else {
            all.animes
        }

        val results = if (query.isNotBlank()) {
            filteredBySite.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            filteredBySite
        }

        return AnimesPage(results, false)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        val entries = CollectedNotesPreferences.getEntries(preferences)
        val siteOptions = arrayOf("Todos los fansubs") + entries.map { it.name }.toTypedArray()

        return AnimeFilterList(
            AnimeFilter.Header("Filtrar por fansub"),
            SiteFilter(siteOptions),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Agregar fansub de Collected Notes"),
            AddUrlFilter(),
            AddNameFilter(),
        )
    }

    private class SiteFilter(options: Array<String>) : AnimeFilter.Select<String>("Fansub", options)

    private class AddUrlFilter : AnimeFilter.Text("URL del sitio (collectednotes.com/usuario)")

    private class AddNameFilter : AnimeFilter.Text("Nombre del fansub (opcional)")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parts = anime.url.split("::", limit = 2)
        val sitePath = parts[0]
        val notePath = parts[1]
        return GET("$baseUrl/$sitePath/$notePath")
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("::", limit = 2)
        val sitePath = parts[0]
        val notePath = parts[1]

        val responseBody = client.newCall(GET("$baseUrl/$sitePath/$notePath.json")).execute().body.string()
        val note = json.decodeFromString<CNNoteResponse>(responseBody).note

        val episodes = mutableListOf<Pair<SEpisode, String>>()
        val fallbackDate = runCatching {
            dateFormat.parse(note.updated_at)?.time ?: 0L
        }.getOrElse { 0L }

        DRIVE_LINK_REGEX.findAll(note.body).forEach { match ->
            val epName = match.groupValues[1].trim()
            val driveUrl = match.groupValues[2].trim()

            if (driveUrl.isBlank()) return@forEach

            val isFolderUrl = "drive/folders" in driveUrl

            if (isFolderUrl) {
                // Sin recursión: por ahora ningún fansub agregado usa subcarpetas.
                val scraped = scraper.scrapeEpisodes(driveUrl, maxRecursionDepth = 1)
                scraped.forEach { ep ->
                    val display = FilenameUtils.buildEpisodeDisplay(ep.name, showFilename)
                    episodes.add(
                        SEpisode.create().apply {
                            name = display.name
                            url = ep.url
                            episode_number = display.episodeNumber
                            date_upload = ep.dateUploadMillis
                            scanlator = ep.sizeLabel
                        } to ep.name,
                    )
                }
            } else {
                val fileId = DRIVE_FILE_ID_REGEX.find(driveUrl)?.groupValues?.get(1)
                val metadata = fileId?.let { scraper.fetchFileMetadata(it) }

                val rawName = metadata?.title ?: epName
                val display = FilenameUtils.buildEpisodeDisplay(rawName, showFilename)
                val size = metadata?.fileSize?.let { formatBytes(it) } ?: ""

                episodes.add(
                    SEpisode.create().apply {
                        name = display.name
                        url = driveUrl
                        episode_number = display.episodeNumber
                        date_upload = metadata?.modifiedDateMillis ?: fallbackDate
                        scanlator = size
                    } to rawName,
                )
            }
        }

        // Especiales (OP, ED, OVA...) arriba, luego episodios de mayor a menor.
        return FilenameUtils.sortByEpisodeNumberDescending(episodes) { it.second }
            .map { it.first }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val fileId = DRIVE_FILE_ID_REGEX.find(episode.url)?.groupValues?.get(1)
            ?: DRIVE_UC_ID_REGEX.find(episode.url)?.groupValues?.get(1)
            ?: throw Exception("No se pudo extraer el ID del archivo de Drive")
        return GoogleDriveExtractor(client, headers).videosFromUrl(fileId)
    }

    // ============================= Utilities ==============================

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun fetchAllNotes(sitePath: String): List<CNNote> {
        val allNotes = mutableListOf<CNNote>()
        val seenIds = mutableSetOf<Int>()
        var page = 1

        while (true) {
            val url = if (page == 1) "$baseUrl/$sitePath.json" else "$baseUrl/$sitePath.json?page=$page"
            val responseBody = client.newCall(GET(url)).execute().body.string()
            val siteResponse = json.decodeFromString<CNSiteResponse>(responseBody)

            val newNotes = siteResponse.notes.filter { it.id !in seenIds }
            if (newNotes.isEmpty()) break

            allNotes.addAll(newNotes)
            seenIds.addAll(newNotes.map { it.id })

            if (allNotes.size >= siteResponse.total_notes) break
            page++
        }

        return allNotes
    }

    private fun extractDescription(body: String): String {
        var text = body
        text = text.replace(Regex("^#+.+$", RegexOption.MULTILINE), "").trim()
        text = text.replace(Regex("!\\[.*?\\]\\([^)]+\\)"), "").trim()
        val specIdx = text.indexOf("Especificaciones")
        if (specIdx != -1) text = text.substring(0, specIdx)
        return text.trim()
    }

    private val showFilename: Boolean
        get() = CollectedNotesPreferences.showFilename(preferences)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = CollectedNotesPreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del episodio.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        val siteListPref = EditTextPreference(screen.context).apply {
            key = CollectedNotesPreferences.PREF_KEY
            title = "Fansubs guardados"
            summary = "Toca para editar tus fansubs guardados"
            dialogTitle = "Fansubs guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Collected Notes\n\n" +
                    "Para eliminar una entrada, borra la línea completa.\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        lateinit var removeEntryPref: EditTextPreference
        removeEntryPref = EditTextPreference(screen.context).apply {
            key = CollectedNotesPreferences.REMOVE_ENTRY_KEY
            title = "Eliminar fansub"
            summary = "Pega aquí el nombre o el link de Collected Notes del fansub que quieres quitar"
            dialogTitle = "Eliminar fansub"
            setDialogMessage(
                "Pega el nombre o la URL de Collected Notes de la entrada que quieres quitar (puedes copiar el nombre " +
                    "del fansub desde la etiqueta que aparece bajo el título de cada anime en el catálogo).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = CollectedNotesPreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    siteListPref.text = updated

                    preferences.edit().putString(CollectedNotesPreferences.REMOVE_ENTRY_KEY, "").commit()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        removeEntryPref.text = ""
                    }
                }
                true
            }
        }
        screen.addPreference(removeEntryPref)
    }

    companion object {
        // Regex para links de Drive en el body del fansub
        private val DRIVE_LINK_REGEX = Regex(
            """[-*]\s*[-*]?\s*(.+?)\s*\[(?:Drive|drive)\]\((https://drive\.google\.com/[^)]*)\)""",
            RegexOption.IGNORE_CASE,
        )

        private val DRIVE_FILE_ID_REGEX = Regex("""drive\.google\.com/file/d/([^/?]+)""")
        private val DRIVE_UC_ID_REGEX = Regex("""drive\.google\.com/uc\?id=([^&/]+)""")

        private val BODY_IMAGE_REGEX = Regex("""!\[.*?\]\(([^)]+)\)""")
    }
}
