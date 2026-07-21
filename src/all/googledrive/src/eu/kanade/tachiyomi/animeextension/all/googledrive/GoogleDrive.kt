package eu.kanade.tachiyomi.animeextension.all.googledrive

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.googledrivescraper.FolderResult
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class GoogleDrive :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = "https://drive.google.com"

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val scraper by lazy { GoogleDriveScraper(client, headers) }

    private var cachedAnimes: List<SAnime>? = null

    // Caché en memoria de detalles ya resueltos por entrada, para no repetir
    // las llamadas de red de getAnimeDetails al reabrir la misma entrada.
    private val detailsCache = mutableMapOf<String, SAnime>()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun migrateLegacyPrefs() {
        val legacy = preferences.getString("domain_list", "") ?: return
        if (legacy.isBlank()) return

        legacy.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { url ->
                val match = DRIVE_FOLDER_REGEX.matchEntire(url) ?: return@forEach
                val name = match.groups["name"]?.value
                    ?.substringAfter("[")?.substringBeforeLast("]")
                    ?: match.groups["id"]!!.value
                GoogleDrivePreferences.addEntry(preferences, name, url)
            }

        preferences.edit().remove("domain_list").apply()
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        migrateLegacyPrefs()

        val entries = GoogleDrivePreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val fileMatch = DRIVE_FILE_REGEX.matchEntire(entry.url)
            if (fileMatch != null) {
                val fileId = fileMatch.groups["id1"]?.value ?: fileMatch.groups["id2"]!!.value
                animeList.add(
                    SAnime.create().apply {
                        title = entry.name
                        url = LinkData(
                            "https://drive.google.com/uc?id=$fileId",
                            "single",
                            LinkDataInfo(entry.name, ""),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
                return@forEach
            }

            val match = DRIVE_FOLDER_REGEX.matchEntire(entry.url) ?: return@forEach
            val folderId = match.groups["id"]!!.value
            val recurDepth = match.groups["depth"]?.value ?: ""
            val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"

            var pageToken: String? = ""
            // Solo la primera página decide si la raíz es un solo anime
            // (todo video, sin subcarpetas) o un catálogo de subcarpetas.
            var firstPage = true

            while (pageToken != null) {
                when (val result = scraper.listFolderItemsResult(folderId, pageToken)) {
                    is FolderResult.NotFound -> {
                        animeList.add(
                            SAnime.create().apply {
                                title = "❌ ${entry.name} (Carpeta eliminada)"
                                thumbnail_url = "https://http.cat/404"
                                url = LinkData(entry.url, "error404", LinkDataInfo(entry.name, entry.url)).toJsonString()
                                status = SAnime.UNKNOWN
                                description = "Esta carpeta fue eliminada o ya no está disponible.\n\n" + removeEntryHint(entry.name, entry.url)
                            },
                        )
                        return@forEach
                    }
                    FolderResult.NetworkError -> return@forEach
                    is FolderResult.Success -> {
                        val folders = result.page.items.filter { it.isFolder }
                        val videos = result.page.items.filter { it.isVideo }

                        if (firstPage && folders.isEmpty() && videos.isNotEmpty()) {
                            animeList.add(
                                SAnime.create().apply {
                                    title = entry.name
                                    url = LinkData(folderUrl, "multi").toJsonString()
                                    thumbnail_url = ""
                                },
                            )
                            return@forEach
                        }

                        folders.forEach { item ->
                            animeList.add(
                                SAnime.create().apply {
                                    title = item.title
                                    url = LinkData(
                                        "https://drive.google.com/drive/folders/${item.id}$recurDepth",
                                        "multi",
                                    ).toJsonString()
                                    thumbnail_url = ""
                                },
                            )
                        }
                        videos.forEach { item ->
                            animeList.add(
                                SAnime.create().apply {
                                    title = item.title
                                    url = LinkData(
                                        "https://drive.google.com/uc?id=${item.id}",
                                        "single",
                                        LinkDataInfo(item.title, item.fileSize?.let { formatBytes(it) } ?: ""),
                                    ).toJsonString()
                                    thumbnail_url = ""
                                },
                            )
                        }

                        pageToken = result.page.nextPageToken
                        firstPage = false
                    }
                }
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter
        val nameFilter = filterList.find { it is NameFilter } as NameFilter

        return if (urlFilter.state.isNotBlank()) {
            val pastedUrl = urlFilter.state.trim()
            val folderMatch = DRIVE_FOLDER_REGEX.matchEntire(pastedUrl)

            if (folderMatch != null) {
                val folderId = folderMatch.groups["id"]!!.value
                val recurDepth = folderMatch.groups["depth"]?.value ?: ""
                val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"

                GoogleDrivePreferences.findByUrl(preferences, folderUrl)?.let { existing ->
                    throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
                }

                val entryName = nameFilter.state.trim().takeIf { it.isNotBlank() }
                    ?: folderMatch.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                    ?: folderId

                GoogleDrivePreferences.addEntry(preferences, entryName, folderUrl)
                cachedAnimes = null
                detailsCache.clear()

                addSinglePage(folderUrl)
            } else {
                val fileMatch = DRIVE_FILE_REGEX.matchEntire(pastedUrl)
                    ?: throw Exception("URL de Google Drive inválida")

                val fileId = fileMatch.groups["id1"]?.value ?: fileMatch.groups["id2"]!!.value
                val fileUrl = "https://drive.google.com/uc?id=$fileId"

                GoogleDrivePreferences.findByUrl(preferences, fileUrl)?.let { existing ->
                    throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
                }

                val entryName = nameFilter.state.trim().takeIf { it.isNotBlank() }
                    ?: fileMatch.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                    ?: fileId

                GoogleDrivePreferences.addEntry(preferences, entryName, fileUrl)
                cachedAnimes = null
                detailsCache.clear()

                addSinglePage(fileUrl, entryName, isFile = true)
            }
        } else if (query.isNotBlank()) {
            val all = getPopularAnime(page)
            AnimesPage(all.animes.filter { it.title.contains(query, ignoreCase = true) }, false)
        } else {
            getPopularAnime(page)
        }
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Agregar carpeta o archivo de Google Drive"),
        URLFilter(),
        NameFilter(),
    )

    private class URLFilter : AnimeFilter.Text("URL de carpeta o archivo")

    private class NameFilter : AnimeFilter.Text("Nombre (opcional)")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return GET(baseUrl, headers = headers)
        }
        if (parsed.type == "error404") return GET(baseUrl, headers = headers)
        return GET(parsed.url, headers = headers)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return anime
        }

        if (parsed.type == "error404") return anime

        // Un archivo suelto no tiene carpeta que recorrer ni details.json/cover que buscar.
        if (parsed.type == "single") {
            return anime.apply { description = removeEntryHint(anime.title, parsed.url) }
        }

        // anime.url completa como key: dos entradas pueden apuntar al mismo
        // folderId (mismo link agregado dos veces con nombres distintos).
        val cacheKey = anime.url

        detailsCache[cacheKey]?.let { cached ->
            return anime.apply {
                thumbnail_url = cached.thumbnail_url
                author = cached.author
                artist = cached.artist
                description = cached.description
                genre = cached.genre
                status = cached.status
            }
        }

        // El nombre lo elige el usuario al guardar el enlace y nunca lo
        // sobrescribe details.json (que puede traer algo genérico como "Folder").
        val originalName = anime.title

        val details = scraper.scrapeFolderDetails(parsed.url)
            ?: return anime.apply { description = removeEntryHint(originalName, parsed.url) }

        details.coverUrl?.let { anime.thumbnail_url = it }
        details.author?.let { anime.author = it }
        details.artist?.let { anime.artist = it }
        details.genre?.let { anime.genre = it }
        details.status?.let { anime.status = it.toIntOrNull() ?: SAnime.UNKNOWN }

        // Se reconstruye desde details.description, no desde anime.description:
        // Aniyomi/Anikku persiste ese campo entre sesiones y puede traer un hint
        // ya escrito de una carga anterior, acumulándose en cada carga.
        anime.description = (details.description?.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") +
            removeEntryHint(originalName, parsed.url)

        // Copia de los valores, no la referencia mutable de "anime".
        detailsCache[cacheKey] = SAnime.create().apply {
            thumbnail_url = anime.thumbnail_url
            author = anime.author
            artist = anime.artist
            description = anime.description
            genre = anime.genre
            status = anime.status
        }

        return anime
    }

    /**
     * Texto reutilizado en la descripción de cada entrada para poder
     * eliminarla desde Ajustes → Eliminar enlace (pegando el nombre o la
     * URL) sin tener que buscarla a mano en "Enlaces guardados".
     */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parsed = json.decodeFromString<LinkData>(anime.url)

        if (parsed.type == "error404") return emptyList()

        if (parsed.type == "single") {
            val fileId = parsed.url.substringAfter("id=")
            val metadata = scraper.fetchFileMetadata(fileId)

            val size = metadata?.fileSize?.let { formatBytes(it) } ?: ""

            // Mismo criterio que las carpetas: nombre real o "Episodio N" según showFilename.
            val display = FilenameUtils.buildEpisodeDisplay(
                metadata?.title ?: "Video",
                GoogleDrivePreferences.showFilename(preferences),
            )

            return listOf(
                SEpisode.create().apply {
                    name = display.name
                    scanlator = size
                    url = parsed.url
                    episode_number = display.episodeNumber
                    date_upload = metadata?.modifiedDateMillis ?: -1L
                },
            )
        }

        val match = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!
        val maxRecursionDepth = match.groups["depth"]?.let {
            it.value.substringAfter("#").substringBefore(",").toInt()
        } ?: 2
        val (start, stop) = match.groups["range"]?.let {
            it.value.substringAfter(",").split(",").map { it.toInt() }
        } ?: listOf(null, null)

        val scraped = scraper.scrapeEpisodes(parsed.url, maxRecursionDepth, start, stop)
        val showFilename = GoogleDrivePreferences.showFilename(preferences)

        // El rango start/stop ya se aplicó dentro de scrapeEpisodes, por posición original.
        val episodeList = scraped.map { ep ->
            val display = FilenameUtils.buildEpisodeDisplay(ep.name, showFilename)
            SEpisode.create().apply {
                name = display.name
                url = ep.url
                episode_number = display.episodeNumber
                date_upload = ep.dateUploadMillis
                scanlator = ep.sizeLabel
            } to ep.name
        }

        // Especiales (OP, ED, OVA...) arriba, luego episodios de mayor a menor.
        return FilenameUtils.sortByEpisodeNumberDescending(episodeList) { it.second }
            .map { it.first }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))

    // ============================= Utilities ==============================

    private fun addSinglePage(url: String, entryName: String? = null, isFile: Boolean = false): AnimesPage {
        val anime = if (isFile) {
            SAnime.create().apply {
                title = entryName ?: "Archivo"
                this.url = LinkData(
                    url,
                    "single",
                    LinkDataInfo(entryName ?: "Archivo", ""),
                ).toJsonString()
                thumbnail_url = ""
            }
        } else {
            val match = DRIVE_FOLDER_REGEX.matchEntire(url) ?: throw Exception("URL de Google Drive inválida")
            val recurDepth = match.groups["depth"]?.value ?: ""

            SAnime.create().apply {
                title = entryName ?: match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                    ?: "Folder"
                this.url = LinkData(
                    "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                    "multi",
                ).toJsonString()
                thumbnail_url = ""
            }
        }
        return AnimesPage(listOf(anime), false)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun LinkData.toJsonString(): String = json.encodeToString(this)

    companion object {
        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )

        // Ej.: .../file/d/<id>/view?usp=sharing o .../uc?id=<id>
        private val DRIVE_FILE_REGEX = Regex(
            """(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/(?:file\/d\/(?<id1>[\w-]{25,})(?:\/[^?]*)?|uc\?(?:[^&]*&)*id=(?<id2>[\w-]{25,}))(?:[?&][^;]*)?${'$'}""",
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = GoogleDrivePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = GoogleDrivePreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Google Drive\n\n" +
                    "Para eliminar una entrada, borra la línea completa.\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                detailsCache.clear()
                true
            }
        }.also(screen::addPreference)

        lateinit var removeEntryPref: EditTextPreference
        removeEntryPref = EditTextPreference(screen.context).apply {
            key = GoogleDrivePreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de Google Drive del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de Google Drive de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = GoogleDrivePreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    detailsCache.clear()
                    folderListPref.text = updated

                    preferences.edit().putString(GoogleDrivePreferences.REMOVE_ENTRY_KEY, "").commit()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        removeEntryPref.text = ""
                    }
                }
                true
            }
        }
        screen.addPreference(removeEntryPref)
    }
}
