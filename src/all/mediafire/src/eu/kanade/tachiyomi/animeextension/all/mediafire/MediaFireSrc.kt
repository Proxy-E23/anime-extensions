package eu.kanade.tachiyomi.animeextension.all.mediafire

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.mediafireextractor.MediaFireExtractor
import aniyomi.lib.mediafireextractor.MediaFireFolderEntry
import aniyomi.lib.mediafireextractor.MediaFireLink
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
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

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var cachedAnimes: List<SAnime>? = null

    private val browserHeaders by lazy {
        headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .build()
    }

    private val extractor by lazy { MediaFireExtractor(client, browserHeaders, baseUrl) }

    // Caché en memoria de nombres de archivo ya resueltos (quickkey -> filename).
    private val fileNameCache = mutableMapOf<String, String>()

    private fun resolveFileName(quickkey: String, hint: String?): String = hint
        ?: fileNameCache.getOrPut(quickkey) { extractor.resolveFileName(quickkey, hint = null) }

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

        val subFolders = extractor.listSubFolders(key)
        val videoFiles = extractor.listFiles(key).filter { isVideo(it.filename) }

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
                    key = sub.key,
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

    /** Texto en la descripción de cada entrada para poder eliminarla desde Ajustes → Eliminar enlace. */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    private fun buildSingleFileAnime(entryName: String, entryUrl: String): SAnime = SAnime.create().apply {
        title = entryName
        url = "file::$entryUrl"
        status = SAnime.UNKNOWN
        initialized = true
        description = removeEntryHint(entryName, entryUrl)
    }

    /** Detecta si la carpeta fue eliminada; si no, la expande en episodios. */
    private fun buildFolderAnime(entryName: String, entryUrl: String, key: String): List<SAnime> {
        if (extractor.isFolderMissing(key)) {
            return listOf(
                SAnime.create().apply {
                    title = "❌ $entryName (Carpeta eliminada o inválida)"
                    thumbnail_url = "https://http.cat/404"
                    url = "missing::$entryUrl"
                    status = SAnime.UNKNOWN
                    description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" + removeEntryHint(entryName, entryUrl)
                },
            )
        }

        val expanded = expandFolder(key = key, folderName = entryName, parentTitle = "")
        return expanded.map { anime ->
            anime.apply { description = removeEntryHint(entryName, entryUrl) }
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val animes = MediaFirePreferences.getEntries(preferences).flatMap { entry ->
            when (val link = extractor.parseLink(entry.url)) {
                is MediaFireLink.File -> listOf(buildSingleFileAnime(entry.name, entry.url))
                is MediaFireLink.Folder -> buildFolderAnime(entry.name, entry.url, link.key)
                null -> emptyList()
            }
        }

        cachedAnimes = animes
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

        if (rawUrl.isNotBlank()) {
            val link = extractor.parseLink(rawUrl)
                ?: throw Exception("URL de MediaFire inválida")

            // Evita guardar la misma URL dos veces con nombres distintos.
            MediaFirePreferences.findByUrl(preferences, rawUrl)?.let { existing ->
                throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
            }

            val resolvedName = nameFilter?.state?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: when (link) {
                    is MediaFireLink.File -> resolveFileName(link.quickkey, link.filename).substringBeforeLast('.')
                    is MediaFireLink.Folder -> extractor.fetchFolderName(link.key)
                }

            MediaFirePreferences.addEntry(preferences, resolvedName, rawUrl)
            cachedAnimes = null

            val result = when (link) {
                is MediaFireLink.File -> listOf(buildSingleFileAnime(resolvedName, rawUrl))
                is MediaFireLink.Folder -> buildFolderAnime(resolvedName, rawUrl, link.key)
            }
            return AnimesPage(result, false)
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
        InfoFilter("Deja los campos vacios para buscar por nombre"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    // Formatos posibles de SAnime.url:
    //  - "missing::<URL>"            carpeta eliminada
    //  - "file::<URL>"                archivo único agregado por el usuario (raíz)
    //  - "file::<quickkey>::<name>"   archivo dentro de una carpeta expandida
    //  - "folder::<key>::<name>"      carpeta hoja dentro de una carpeta expandida
    //  - <URL>                        carpeta agregada por el usuario (raíz)
    // "file::<URL>" (raíz) se distingue de "file::<quickkey>::<name>" (sub-item)
    // por si el resto contiene "mediafire.com".
    private sealed class AnimeUrlRef {
        data class Missing(val entryUrl: String) : AnimeUrlRef()
        data class RootFile(val entryUrl: String) : AnimeUrlRef()
        data class RootFolder(val entryUrl: String) : AnimeUrlRef()
        data class ExpandedFile(val quickkey: String, val filename: String) : AnimeUrlRef()
        data class ExpandedFolder(val key: String, val name: String) : AnimeUrlRef()
    }

    private fun parseAnimeUrl(url: String): AnimeUrlRef = when {
        url.startsWith("missing::") -> AnimeUrlRef.Missing(url.removePrefix("missing::"))
        url.startsWith("file::") -> {
            val rest = url.removePrefix("file::")
            if ("mediafire.com" in rest) {
                AnimeUrlRef.RootFile(rest)
            } else {
                val parts = rest.split("::", limit = 2)
                AnimeUrlRef.ExpandedFile(parts[0], parts.getOrElse(1) { "" })
            }
        }
        url.startsWith("folder::") -> {
            val parts = url.removePrefix("folder::").split("::", limit = 2)
            AnimeUrlRef.ExpandedFolder(parts[0], parts.getOrElse(1) { "" })
        }
        else -> AnimeUrlRef.RootFolder(url)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = when (val ref = parseAnimeUrl(anime.url)) {
        is AnimeUrlRef.Missing -> GET(baseUrl)
        is AnimeUrlRef.RootFile -> GET(ref.entryUrl)
        is AnimeUrlRef.RootFolder -> GET(ref.entryUrl)
        is AnimeUrlRef.ExpandedFile -> GET("$baseUrl/file/${ref.quickkey}")
        is AnimeUrlRef.ExpandedFolder -> GET("$baseUrl/folder/${ref.key}")
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // La descripción se reconstruye siempre para que el aviso de "Eliminar
    // enlace" aparezca también en entradas guardadas antes de este campo.
    // Los sub-items (ExpandedFile/ExpandedFolder) no tienen enlace propio,
    // así que se devuelven sin tocar la descripción.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = when (val ref = parseAnimeUrl(anime.url)) {
        is AnimeUrlRef.Missing -> anime.apply {
            // El nombre real (sin el prefijo "❌ ...") se recupera por URL.
            val realName = MediaFirePreferences.findByUrl(preferences, ref.entryUrl)?.name ?: anime.title
            description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" +
                removeEntryHint(realName, ref.entryUrl)
        }
        is AnimeUrlRef.RootFile -> anime.apply { description = removeEntryHint(anime.title, ref.entryUrl) }
        is AnimeUrlRef.RootFolder -> anime.apply { description = removeEntryHint(anime.title, ref.entryUrl) }
        else -> anime
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ref = parseAnimeUrl(anime.url)

        // Entrada de error — sin episodios
        if (ref is AnimeUrlRef.Missing) return emptyList()

        // Archivo único: FilenameUtils solo resuelve el nombre a mostrar;
        // episode_number queda fijo en 1f.
        if (ref is AnimeUrlRef.ExpandedFile) {
            val display = FilenameUtils.buildEpisodeDisplay(ref.filename, showFilename)
            return listOf(
                SEpisode.create().apply {
                    this.url = "file::${ref.quickkey}::${ref.filename}"
                    name = display.name
                    episode_number = 1f
                    date_upload = 0L
                },
            )
        }

        if (ref is AnimeUrlRef.RootFile) {
            val link = extractor.parseLink(ref.entryUrl) as? MediaFireLink.File ?: return emptyList()
            val filename = resolveFileName(link.quickkey, link.filename)
            val display = FilenameUtils.buildEpisodeDisplay(filename, showFilename)
            return listOf(
                SEpisode.create().apply {
                    this.url = "file::${link.quickkey}::$filename"
                    name = display.name
                    episode_number = 1f
                    date_upload = 0L
                },
            )
        }

        val key = when (ref) {
            is AnimeUrlRef.RootFolder -> (extractor.parseLink(ref.entryUrl) as? MediaFireLink.Folder)?.key
            is AnimeUrlRef.ExpandedFolder -> ref.key
            else -> null
        } ?: return emptyList()

        val videoFiles: List<MediaFireFolderEntry> = extractor.listFiles(key).filter { isVideo(it.filename) }

        // Especiales (OP, ED, OVA, etc.) primero, luego episodios numerados
        // de mayor a menor. Nombre y episode_number vienen de buildEpisodeDisplay.
        val sortedFiles = FilenameUtils.sortByEpisodeNumberDescending(videoFiles) { it.filename }

        return sortedFiles.map { file ->
            val display = FilenameUtils.buildEpisodeDisplay(file.filename, showFilename)
            SEpisode.create().apply {
                this.url = "file::${file.quickkey}::${file.filename}"
                name = display.name
                episode_number = display.episodeNumber
                date_upload = runCatching {
                    dateFormat.parse(file.created)?.time ?: 0L
                }.getOrElse { 0L }
            }
        }
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("::")
        val quickkey = parts[1]
        val filename = parts[2]
        return extractor.videoFromFile(quickkey, filename)
    }

    private val showFilename: Boolean
        get() = MediaFirePreferences.showFilename(preferences)

    // ── Preferencias ──────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = MediaFirePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = MediaFirePreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de MediaFire\n\n" +
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
            key = MediaFirePreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de MediaFire del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de MediaFire de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la entrada equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = MediaFirePreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    folderListPref.text = updated

                    preferences.edit().putString(MediaFirePreferences.REMOVE_ENTRY_KEY, "").commit()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        removeEntryPref.text = ""
                    }
                }
                true
            }
        }
        screen.addPreference(removeEntryPref)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
}
