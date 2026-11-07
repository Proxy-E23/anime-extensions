package eu.kanade.tachiyomi.animeextension.all.mediafire

import android.content.SharedPreferences
import android.widget.Toast
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
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MediaFireSrc :
    AnimeHttpLegacySource(),
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

    /** Anime ya expandido (o restaurado de la caché) con el estado y la descripción de su entrada guardada. */
    private fun entryAnime(title: String, url: String, entry: MediaFirePreferences.Entry): SAnime = SAnime.create().apply {
        this.title = title
        this.url = url
        status = entry.status
        initialized = true
        description = removeEntryHint(entry.name, entry.url)
    }

    private fun expandFolder(
        entry: MediaFirePreferences.Entry,
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
                results += entryAnime(displayTitle, "folder::$key::$folderName", entry)
            }
        } else {
            videoFiles.forEach { file ->
                val displayTitle = if (parentTitle.isBlank()) {
                    file.filename.substringBeforeLast('.')
                } else {
                    "$parentTitle - ${file.filename.substringBeforeLast('.')}"
                }
                results += entryAnime(displayTitle, "file::${file.quickkey}::${file.filename}", entry)
            }

            subFolders.forEach { sub ->
                val subTitle = if (parentTitle.isBlank()) folderName else parentTitle
                results += expandFolder(
                    entry = entry,
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

    private fun buildSingleFileAnime(entry: MediaFirePreferences.Entry): SAnime = entryAnime(entry.name, "file::${entry.url}", entry)

    /** Detecta si la carpeta fue eliminada; si no, la expande en episodios. */
    private fun buildFolderAnime(entry: MediaFirePreferences.Entry, key: String): List<SAnime> {
        if (extractor.isFolderMissing(key)) {
            return listOf(
                SAnime.create().apply {
                    title = "❌ ${entry.name} (Carpeta eliminada o inválida)"
                    thumbnail_url = "https://http.cat/404"
                    url = "missing::${entry.url}"
                    status = SAnime.UNKNOWN
                    description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" + removeEntryHint(entry.name, entry.url)
                },
            )
        }

        return expandFolder(entry = entry, key = key, folderName = entry.name, parentTitle = "")
    }

    /**
     * Enlaces guardados. Las carpetas marcadas como completadas se guardan en disco y no
     * se vuelven a pedir (ni se comprueba si siguen existiendo); las demás (en emisión o
     * desconocido) se leen una vez por sesión. Los archivos únicos no piden nada al
     * construirse, así que no necesitan caché.
     */
    private fun buildEntryAnimes(entries: List<MediaFirePreferences.Entry>): List<SAnime> {
        val diskCache = MediaFirePreferences.readCompletedCache(preferences)
        val newDiskCache = mutableMapOf<String, List<MediaFirePreferences.CachedAnime>>()
        val results = mutableListOf<SAnime>()

        entries.forEach { entry ->
            when (val link = extractor.parseLink(entry.url)) {
                is MediaFireLink.File -> results += buildSingleFileAnime(entry)
                is MediaFireLink.Folder -> {
                    val completed = entry.status == SAnime.COMPLETED
                    // El nombre forma parte de la clave porque define los títulos ("Nombre - Subcarpeta").
                    val cacheKey = "${link.key}::${entry.name}"

                    val cached = if (completed) diskCache[cacheKey] else null
                    if (cached != null) {
                        newDiskCache[cacheKey] = cached
                        cached.forEach { results += entryAnime(it.title, it.url, entry) }
                    } else {
                        val animes = buildFolderAnime(entry, link.key)
                        results += animes
                        // Una carpeta caída ("missing::") o vacía nunca se guarda en disco.
                        val isMissing = animes.any { it.url.startsWith("missing::") }
                        if (completed && !isMissing && animes.isNotEmpty()) {
                            newDiskCache[cacheKey] = animes.map { MediaFirePreferences.CachedAnime(it.title, it.url) }
                        }
                    }
                }
                null -> Unit
            }
        }

        // Poda: solo quedan en disco las carpetas que siguen guardadas y completadas.
        if (newDiskCache != diskCache) MediaFirePreferences.writeCompletedCache(preferences, newDiskCache)
        return results
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val animes = buildEntryAnimes(MediaFirePreferences.getEntries(preferences))

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
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
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

            val entry = MediaFirePreferences.Entry(resolvedName, rawUrl, statusFilter?.state ?: SAnime.UNKNOWN)
            MediaFirePreferences.addEntry(preferences, entry.name, entry.url, entry.status)
            cachedAnimes = null

            val result = when (link) {
                is MediaFireLink.File -> listOf(buildSingleFileAnime(entry))
                is MediaFireLink.Folder -> buildFolderAnime(entry, link.key)
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
        StatusFilter(),
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
        is AnimeUrlRef.RootFile -> anime.apply {
            description = removeEntryHint(anime.title, ref.entryUrl)
            // El estado se lee siempre de las entradas guardadas para reflejar cambios hechos en Ajustes.
            MediaFirePreferences.findByUrl(preferences, ref.entryUrl)?.let { status = it.status }
        }
        is AnimeUrlRef.RootFolder -> anime.apply {
            description = removeEntryHint(anime.title, ref.entryUrl)
            MediaFirePreferences.findByUrl(preferences, ref.entryUrl)?.let { status = it.status }
        }
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

        return FilenameUtils.sortBySeasonAndEpisodeDescending(videoFiles, { it.filename }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    this.url = "file::${seasoned.item.quickkey}::${seasoned.item.filename}"
                    name = seasoned.display.name
                    episode_number = seasoned.display.episodeNumber
                    date_upload = runCatching {
                        dateFormat.parse(seasoned.item.created)?.time ?: 0L
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

    /**
     * Botón de acción en Ajustes. Este proyecto no ofrece la clase Preference simple,
     * así que se usa un interruptor que ejecuta la acción al pulsarlo y rechaza el
     * cambio (devuelve false) para quedarse siempre apagado. No pide confirmación:
     * se usa solo para acciones inofensivas (recargar cachés).
     */
    private fun actionPreference(
        screen: PreferenceScreen,
        prefKey: String,
        prefTitle: String,
        prefSummary: String,
        toastText: String,
        action: () -> Unit,
    ): SwitchPreferenceCompat = SwitchPreferenceCompat(screen.context).apply {
        key = prefKey
        title = prefTitle
        summary = prefSummary
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            action()
            Toast.makeText(screen.context, toastText, Toast.LENGTH_SHORT).show()
            false
        }
    }

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
                    "Para indicar el estado de emisión, agrega al final ::e (en emisión), " +
                    "::c (completado) o ::d (desconocido).\n" +
                    "Ejemplo: Mi anime::https://www.mediafire.com/folder/abc123::c\n\n" +
                    "Las carpetas marcadas como completadas se leen una sola vez y se guardan; si una cambia " +
                    "o se elimina, usa \"Refrescar enlaces guardados\".\n\n" +
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

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "mediafire_refresh_saved_links",
                prefTitle = "Refrescar enlaces guardados",
                prefSummary = "Las carpetas en emisión o desconocidas se leen al abrir Anikku; las completadas " +
                    "se guardan. Esto fuerza una nueva lectura de todas, completadas incluidas.",
                toastText = "Los enlaces se actualizarán al abrir el catálogo",
            ) {
                MediaFirePreferences.clearCompletedCache(preferences)
                cachedAnimes = null
            },
        )
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
}
