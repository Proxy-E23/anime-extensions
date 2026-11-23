package eu.kanade.tachiyomi.animeextension.all.gofile

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.gofileextractor.GofileContent
import aniyomi.lib.gofileextractor.GofileExtractor
import aniyomi.lib.gofileextractor.GofileNode
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

class GofileSrc :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Gofile"
    override val baseUrl = "https://gofile.io"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by getPreferencesLazy()

    private var cachedAnimes: List<SAnime>? = null

    private val extractor by lazy { GofileExtractor(client, headers, baseUrl) }

    private fun shareUrl(id: String) = "$baseUrl/d/$id"

    private companion object {
        const val MAX_FOLDER_DEPTH = 10
    }

    // ── Formatos de SAnime.url ────────────────────────────────────────────────
    //  - "missing::<id>"                                          enlace eliminado, privado o inválido
    //  - "share::<id>"                                            raíz del enlace: un archivo, o una carpeta solo con videos
    //  - "folder::<id raíz>::<id carpeta>"                        subcarpeta hoja de un enlace
    //  - "single::<id raíz>::<id carpeta>::<id archivo>::<nombre>" video suelto dentro de una carpeta con subcarpetas
    //
    // Formato de SEpisode.url:
    //  - "file::<id carpeta>::<id archivo>::<nombre>"
    //    (si el enlace es un archivo suelto, la carpeta y el archivo tienen el mismo id)

    private fun rootIdFromAnimeUrl(url: String): String = url.substringAfter("::").substringBefore("::")

    private fun isMissing(url: String): Boolean = url.startsWith("missing::")

    private fun episodeUrl(parentId: String, fileId: String, filename: String) = "file::$parentId::$fileId::$filename"

    // ── Construcción de animes ────────────────────────────────────────────────

    private class Meta(val status: Int, val description: String)

    /** Texto en la descripción de cada entrada para poder eliminarla desde Ajustes → Eliminar enlace. */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    private fun newAnime(meta: Meta, title: String, url: String): SAnime = SAnime.create().apply {
        this.title = title
        this.url = url
        this.status = meta.status
        this.initialized = true
        this.description = meta.description
    }

    private fun missingAnime(entry: GofilePreferences.Entry, id: String): SAnime = SAnime.create().apply {
        title = "❌ ${entry.name} (Enlace eliminado, privado o inválido)"
        thumbnail_url = "https://http.cat/404"
        url = "missing::$id"
        status = SAnime.UNKNOWN
        description = "Este enlace fue eliminado, no es público, tiene contraseña o ya no es válido.\n\n" +
            removeEntryHint(entry.name, entry.url)
    }

    private fun folderUrl(rootId: String, folderId: String, depth: Int): String = if (depth == 0) "share::$rootId" else "folder::$rootId::$folderId"

    private fun singleUrl(rootId: String, parentId: String, file: GofileNode): String = "single::$rootId::$parentId::${file.id}::${file.name}"

    /**
     * Misma lógica que las extensiones de MediaFire y Drime:
     *  - Carpeta sin subcarpetas: un anime con sus videos como episodios.
     *  - Carpeta con subcarpetas: cada subcarpeta es un anime ("Padre - Subcarpeta")
     *    y cada video suelto de esa carpeta es un anime aparte.
     */
    private fun expandFolder(
        rootId: String,
        meta: Meta,
        content: GofileContent,
        folderName: String,
        parentTitle: String,
        depth: Int = 0,
    ): List<SAnime> {
        if (depth > MAX_FOLDER_DEPTH) return emptyList()

        val subFolders = content.children.filter { it.isFolder }
        val videoFiles = content.children.filter { it.isVideo }
        val results = mutableListOf<SAnime>()

        if (subFolders.isEmpty()) {
            if (videoFiles.isNotEmpty()) {
                val displayTitle = if (parentTitle.isBlank()) folderName else "$parentTitle - $folderName"
                results += newAnime(meta, displayTitle, folderUrl(rootId, content.root.id, depth))
            }
        } else {
            videoFiles.forEach { file ->
                val baseName = file.name.substringBeforeLast('.')
                val displayTitle = if (parentTitle.isBlank()) baseName else "$parentTitle - $baseName"
                results += newAnime(meta, displayTitle, singleUrl(rootId, content.root.id, file))
            }

            subFolders.forEach { sub ->
                val subContent = extractor.fetchContent(sub.id) ?: return@forEach
                val subTitle = if (parentTitle.isBlank()) folderName else parentTitle
                results += expandFolder(rootId, meta, subContent, sub.name, subTitle, depth + 1)
            }
        }

        return results
    }

    /** Convierte una entrada guardada en uno o varios animes. [content] es null si el enlace ya no existe. */
    private fun buildAnimes(entry: GofilePreferences.Entry, id: String, content: GofileContent?): List<SAnime> {
        if (content == null) return listOf(missingAnime(entry, id))

        val meta = Meta(entry.status, removeEntryHint(entry.name, entry.url))

        // Enlace a un solo archivo.
        if (!content.root.isFolder) {
            return if (content.root.isVideo) {
                listOf(newAnime(meta, entry.name, "share::$id"))
            } else {
                emptyList()
            }
        }

        return expandFolder(id, meta, content, folderName = entry.name, parentTitle = "")
    }

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun SAnime.toCached() = GofilePreferences.CachedAnime(title, url)

    /**
     * Los enlaces marcados como completados se guardan en disco y no se vuelven a
     * pedir; los demás (en emisión o desconocido) se leen una vez por sesión.
     */
    private fun buildLinkAnimes(entries: List<GofilePreferences.Entry>): List<SAnime> {
        val diskCache = GofilePreferences.readPublicCache(preferences)
        val newDiskCache = mutableMapOf<String, List<GofilePreferences.CachedAnime>>()
        val results = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val link = extractor.parseLink(entry.url) ?: return@forEach
            val meta = Meta(entry.status, removeEntryHint(entry.name, entry.url))
            val completed = entry.status == SAnime.COMPLETED
            // El nombre forma parte de la clave porque define los títulos ("Nombre - Subcarpeta").
            val cacheKey = "${link.id}::${entry.name}"

            val cached = if (completed) diskCache[cacheKey] else null
            if (cached != null) {
                newDiskCache[cacheKey] = cached
                cached.forEach { results += newAnime(meta, it.title, it.url) }
            } else {
                val content = extractor.fetchContent(link.id)
                val animes = buildAnimes(entry, link.id, content)
                results += animes
                // Un enlace caído (content == null) nunca se guarda en disco.
                if (completed && content != null && animes.isNotEmpty()) {
                    newDiskCache[cacheKey] = animes.map { it.toCached() }
                }
            }
        }

        // Poda: solo quedan en disco los enlaces que siguen guardados y completados.
        if (newDiskCache != diskCache) GofilePreferences.writePublicCache(preferences, newDiskCache)
        return results
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val animes = buildLinkAnimes(GofilePreferences.getEntries(preferences))
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
                ?: throw Exception("URL de Gofile inválida")

            // Evita guardar el mismo enlace dos veces con nombres distintos.
            GofilePreferences.findById(preferences, link.id)?.let { existing ->
                throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
            }

            val content = extractor.fetchContent(link.id)
                ?: throw Exception("El enlace no existe, fue eliminado, no es público o requiere contraseña")

            val resolvedName = nameFilter?.state?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (content.root.isFolder) content.root.name else content.root.name.substringBeforeLast('.')

            val entry = GofilePreferences.Entry(
                name = resolvedName,
                url = shareUrl(link.id),
                status = statusFilter?.state ?: SAnime.UNKNOWN,
            )

            // Se arman los animes antes de guardar: si no hay videos, no se guarda nada.
            val animes = buildAnimes(entry, link.id, content)
            if (animes.isEmpty()) {
                throw Exception("No se encontraron videos en ese enlace")
            }

            GofilePreferences.addEntry(preferences, entry.name, entry.url, entry.status)
            cachedAnimes = null

            return AnimesPage(animes, false)
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
        InfoFilter("Pega una URL de carpeta o archivo compartido de Gofile para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        StatusFilter(),
        SeparatorFilter(),
        InfoFilter("Deja la URL vacía para buscar por nombre"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request = GET(shareUrl(rootIdFromAnimeUrl(anime.url)))

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // La descripción y el estado se reconstruyen siempre desde las preferencias,
    // para reflejar cambios hechos a mano en Ajustes.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeUrl = anime.url

        return anime.apply {
            val id = rootIdFromAnimeUrl(animeUrl)
            val entry = GofilePreferences.findById(preferences, id)
            val displayName = entry?.name ?: title
            val entryUrl = entry?.url ?: shareUrl(id)

            if (isMissing(animeUrl)) {
                description = "Este enlace fue eliminado, no es público, tiene contraseña o ya no es válido.\n\n" +
                    removeEntryHint(displayName, entryUrl)
            } else {
                description = removeEntryHint(displayName, entryUrl)
                entry?.let { status = it.status }
            }
        }
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private val showFilename: Boolean
        get() = GofilePreferences.showFilename(preferences)

    private fun episodesFromContent(content: GofileContent): List<SEpisode> {
        val videoFiles = content.children.filter { it.isVideo }

        return FilenameUtils.sortBySeasonAndEpisodeDescending(videoFiles, { it.name }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    url = episodeUrl(content.root.id, seasoned.item.id, seasoned.item.name)
                    name = seasoned.display.name
                    episode_number = seasoned.display.episodeNumber
                    // La app usa estas fechas para estimar el próximo episodio
                    // de los animes marcados como "En emisión".
                    date_upload = seasoned.item.created * 1000L
                }
            }
    }

    private fun singleEpisode(node: GofileNode, parentId: String): SEpisode {
        val display = FilenameUtils.buildEpisodeDisplay(node.name, showFilename)
        return SEpisode.create().apply {
            url = episodeUrl(parentId, node.id, node.name)
            name = display.name
            episode_number = 1f
            date_upload = node.created * 1000L
        }
    }

    private fun looseVideoNode(id: String, filename: String) = GofileNode(
        id = id,
        name = filename,
        type = "file",
        mimetype = "video/",
        size = 0,
        created = 0,
        link = null,
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = anime.url
        if (isMissing(animeUrl)) return emptyList()

        return when {
            // Video suelto: el archivo viene completo en la URL, sin pedir nada a la red.
            animeUrl.startsWith("single::") -> {
                val parts = animeUrl.split("::", limit = 5)
                listOf(singleEpisode(looseVideoNode(parts[3], parts[4]), parts[2]))
            }

            // Subcarpeta hoja.
            animeUrl.startsWith("folder::") -> {
                val folderId = animeUrl.split("::", limit = 3)[2]
                val content = extractor.fetchContent(folderId) ?: return emptyList()
                episodesFromContent(content)
            }

            // Raíz (un archivo o una carpeta solo con videos).
            else -> {
                val content = extractor.fetchContent(rootIdFromAnimeUrl(animeUrl)) ?: return emptyList()
                when {
                    content.root.isFolder -> episodesFromContent(content)
                    content.root.isVideo -> listOf(singleEpisode(content.root, content.root.id))
                    else -> emptyList()
                }
            }
        }
    }

    // ── Hosters / Videos ──────────────────────────────────────────────────────
    // Cada episodio es un archivo de Gofile, así que hay un único hoster. El enlace de
    // descarga depende de la sesión de invitado, por eso se resuelve recién al abrir el
    // hoster y no al listar los episodios.

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("::", limit = 4)
        if (parts.size < 4) return emptyList()

        return listOf(
            Hoster(
                hosterUrl = parts[1],
                hosterName = "Gofile",
                internalData = "${parts[2]}::${parts[3]}",
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parentId = hoster.hosterUrl
        val data = hoster.internalData.split("::", limit = 2)
        if (parentId.isBlank() || data.size < 2) return emptyList()

        return withContext(Dispatchers.IO) { extractor.videoFromFile(parentId, data[0], data[1]) }
    }

    // ── Preferencias ──────────────────────────────────────────────────────────

    /**
     * Botón de acción en Ajustes. Este proyecto no ofrece la clase Preference simple,
     * así que se usa un interruptor que ejecuta la acción al pulsarlo y rechaza el
     * cambio (devuelve false) para quedarse siempre apagado.
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
            key = GofilePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
        }.also(screen::addPreference)

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "refresh_public_links",
                prefTitle = "Refrescar enlaces",
                prefSummary = "Los enlaces en emisión o desconocidos se actualizan al abrir Anikku; los completados " +
                    "se guardan. Esto fuerza una nueva lectura de todos, completados incluidos.",
                toastText = "Los enlaces se actualizarán al abrir el catálogo",
            ) {
                GofilePreferences.clearPublicCache(preferences)
                cachedAnimes = null
            },
        )

        val linkListPref = EditTextPreference(screen.context).apply {
            key = GofilePreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Gofile\n\n" +
                    "Para indicar el estado de emisión, agrega al final ::e (en emisión), " +
                    "::c (completado) o ::d (desconocido).\n" +
                    "Ejemplo: Mi anime::https://gofile.io/d/abc123::e\n\n" +
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
            key = GofilePreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de Gofile del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de Gofile de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la entrada equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = GofilePreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    linkListPref.text = updated

                    preferences.edit().putString(GofilePreferences.REMOVE_ENTRY_KEY, "").commit()
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
