package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.megaextractor.MegaLink
import aniyomi.lib.megaextractor.MegaNode
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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

class Mega :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MEGA"
    override val baseUrl = "https://mega.nz"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val extractor by lazy { MegaExtractor(client) }

    private var cachedAnimes: List<SAnime>? = null

    // Caché en memoria del árbol de cada carpeta ya listada (handle -> nodos
    // descifrados), para no volver a golpear la API ni redescifrar al
    // navegar entre catálogo, detalles y episodios de la misma carpeta.
    private val nodeCache = mutableMapOf<String, List<MegaNode>>()

    // Caché en memoria de metadata de archivo único (handle -> meta), por la
    // misma razón.
    private val singleFileMetaCache = mutableMapOf<String, MegaExtractor.SingleFileMeta>()

    private fun resolveSingleFileCached(link: MegaLink.File): MegaExtractor.SingleFileMeta = singleFileMetaCache.getOrPut(link.handle) {
        extractor.resolveSingleFile(link)
    }

    private val showFilename: Boolean
        get() = MegaPreferences.showFilename(preferences)

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val entries = MegaPreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val link = extractor.parseLink(entry.url)
            if (link == null) return@forEach

            when (link) {
                is MegaLink.File -> {
                    animeList += buildSingleFileAnime(entry.name, entry.url)
                }
                is MegaLink.Folder -> {
                    animeList += buildFolderAnime(entry.name, entry.url, link)
                }
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    /**
     * El nombre real de un archivo único se resuelve de forma perezosa en
     * getAnimeDetails, igual que hacen Drive/MediaFire.
     */
    private fun buildSingleFileAnime(entryName: String, entryUrl: String, sizeLabel: String? = null): SAnime = SAnime.create().apply {
        title = entryName
        url = MegaLinkData(entryUrl, "single", sizeLabel?.let { MegaLinkDataInfo(entryName, it) }).toJsonString()
        thumbnail_url = ""
        description = removeEntryHint(entryName, entryUrl)
    }

    /**
     * Se intenta listar la carpeta de una vez para detectar si fue eliminada
     * (entrada "fantasma" con instrucciones de borrado) o si tiene contenido
     * válido.
     */
    private fun buildFolderAnime(entryName: String, entryUrl: String, link: MegaLink.Folder): SAnime {
        val nodes = try {
            extractor.listFolder(link).also { nodeCache[link.handle] = it }
        } catch (e: Exception) {
            return SAnime.create().apply {
                title = "❌ $entryName (Carpeta eliminada o inválida)"
                thumbnail_url = "https://http.cat/404"
                url = MegaLinkData(
                    entryUrl,
                    "error404",
                    MegaLinkDataInfo(entryName, entryUrl),
                ).toJsonString()
                status = SAnime.UNKNOWN
                description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" + removeEntryHint(entryName, entryUrl)
            }
        }

        return SAnime.create().apply {
            title = entryName
            url = MegaLinkData(entryUrl, "multi").toJsonString()
            thumbnail_url = ""
            description = removeEntryHint(entryName, entryUrl)
        }
    }

    /**
     * Texto reutilizado en la descripción de cada entrada para poder
     * eliminarla desde Ajustes → Eliminar enlace (pegando el nombre o la
     * URL) sin tener que buscarla a mano en "Enlaces guardados".
     */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    // ── Búsqueda + Filtros (agregar nuevo enlace) ──────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlFilter = filters.filterIsInstance<UrlFilter>().firstOrNull()
        val nameFilter = filters.filterIsInstance<NameFilter>().firstOrNull()
        val rawUrl = urlFilter?.state?.trim() ?: ""

        if (rawUrl.isNotBlank()) {
            val link = extractor.parseLink(rawUrl)
                ?: throw Exception("URL de MEGA inválida")

            // Evita guardar la misma URL dos veces con nombres distintos.
            MegaPreferences.findByUrl(preferences, rawUrl)?.let { existing ->
                throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
            }

            var sizeLabel: String? = null
            val resolvedName = nameFilter?.state?.trim()?.takeIf { it.isNotBlank() }
                ?: when (link) {
                    is MegaLink.File -> {
                        val meta = runCatching { resolveSingleFileCached(link) }.getOrNull()
                        sizeLabel = meta?.size?.let { formatBytes(it) }
                        meta?.name ?: link.handle
                    }
                    is MegaLink.Folder -> link.handle
                }

            MegaPreferences.addEntry(preferences, resolvedName, rawUrl)
            cachedAnimes = null

            val anime = when (link) {
                is MegaLink.File -> buildSingleFileAnime(resolvedName, rawUrl, sizeLabel)
                is MegaLink.Folder -> buildFolderAnime(resolvedName, rawUrl, link)
            }
            return AnimesPage(listOf(anime), false)
        }

        val all = getPopularAnime(page)
        val results = if (query.isBlank()) all.animes else all.animes.filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(results, false)
    }

    override fun getFilterList() = AnimeFilterList(
        InfoFilter("Pega una URL de archivo o carpeta de MEGA para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        SeparatorFilter(),
        InfoFilter("Deja los campos vacíos para buscar por nombre entre tus enlaces guardados"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    // El botón "abrir en WebView" usa la URL que devuelve este método. Se
    // navega al link real de MEGA guardado en anime.url; si el parseo falla
    // o es una entrada "fantasma" (error404), se usa baseUrl como fallback.
    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = try {
            json.decodeFromString<MegaLinkData>(anime.url)
        } catch (e: Exception) {
            return GET(baseUrl)
        }
        return GET(parsed.url)
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = try {
            json.decodeFromString<MegaLinkData>(anime.url)
        } catch (e: Exception) {
            return anime
        }

        // Se reconstruye siempre (no solo al agregar el enlace) para que la
        // descripción con las instrucciones de "Eliminar enlace" se muestre
        // también en entradas que ya estaban guardadas antes de este campo.
        return when (parsed.type) {
            "error404" -> anime.apply {
                // anime.title trae el prefijo "❌ ... (Carpeta eliminada...)";
                // el nombre real para pegar en "Eliminar enlace" es el que
                // se guardó en info.title al crear la entrada.
                val realName = parsed.info?.title ?: anime.title
                description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" +
                    removeEntryHint(realName, parsed.url)
            }
            "single", "multi" -> anime.apply {
                description = removeEntryHint(anime.title, parsed.url)
            }
            else -> anime
        }
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parsed = json.decodeFromString<MegaLinkData>(anime.url)

        if (parsed.type == "error404") return emptyList()

        if (parsed.type == "single") {
            val link = extractor.parseLink(parsed.url) as? MegaLink.File ?: return emptyList()
            val meta = try {
                resolveSingleFileCached(link)
            } catch (e: Exception) {
                null
            }
            val display = FilenameUtils.buildEpisodeDisplay(meta?.name ?: "Video", showFilename)
            return listOf(
                SEpisode.create().apply {
                    name = display.name
                    scanlator = meta?.size?.let { formatBytes(it) } ?: (parsed.info?.size ?: "")
                    url = MegaEpisodeData(fileHandle = link.handle, fileKey = link.key, folderHandle = null).toJsonString()
                    episode_number = 1F
                    date_upload = -1L
                },
            )
        }

        val link = extractor.parseLink(parsed.url) as? MegaLink.Folder ?: return emptyList()
        val nodes = nodeCache[link.handle] ?: extractor.listFolder(link).also { nodeCache[link.handle] = it }

        if (nodes.isEmpty()) {
            throw Exception("MEGA no devolvió contenido para esta carpeta (posible link inválido o carpeta vacía)")
        }

        // Si el link apunta a un archivo específico dentro de la carpeta
        // (mega.nz/folder/H#K/file/subH), solo se expone ese archivo, no el
        // resto del contenido de la carpeta.
        val relevantNodes = if (link.subFileHandle != null) {
            nodes.filter { it.handle == link.subFileHandle }
        } else {
            nodes
        }

        // Orden delegado a FilenameUtils.sortByEpisodeNumberDescending: los
        // especiales (OP, ED, OVA, Special/Extra, etc.) van arriba de todo,
        // y luego los episodios numerados de mayor a menor.
        val videoNodes = FilenameUtils.sortByEpisodeNumberDescending(
            relevantNodes.filter { !it.isFolder && isVideoName(it.name) },
        ) { it.name }

        if (videoNodes.isEmpty()) {
            val noKey = relevantNodes.count { !it.isFolder && it.fileKey == null }
            val withKeyButNoName = relevantNodes.count { !it.isFolder && it.fileKey != null && it.name == "(nombre no disponible)" }
            val detail = when {
                noKey > 0 -> " ($noKey de ${relevantNodes.size} archivos sin key resoluble -- problema al descifrar la node key)"
                withKeyButNoName > 0 -> " ($withKeyButNoName de ${relevantNodes.size} archivos con key pero el nombre no descifró -- problema al descifrar el atributo)"
                else -> " (se listaron ${relevantNodes.size} nodos, pero ninguno con extensión de video reconocida)"
            }
            throw Exception("No se encontraron videos en esta carpeta de MEGA.$detail")
        }

        // Nombre y episode_number resueltos juntos por
        // FilenameUtils.buildEpisodeDisplay: nombre real del archivo (si
        // showFilename) o etiqueta genérica ("Episodio 24", "OP 2") según
        // categoría y número detectados.
        return videoNodes.map { node ->
            val display = FilenameUtils.buildEpisodeDisplay(node.name, showFilename)

            SEpisode.create().apply {
                name = display.name
                url = MegaEpisodeData(fileHandle = node.handle, fileKey = null, folderHandle = link.handle).toJsonString()
                episode_number = display.episodeNumber
                date_upload = node.timestamp
                scanlator = formatBytes(node.size)
            }
        }
    }

    private fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes bytes"
        else -> ""
    }

    // ── Video ────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val data = json.decodeFromString<MegaEpisodeData>(episode.url)

        // Archivo único (sin carpeta): resolver directo con su propia key.
        if (data.folderHandle == null) {
            val fileKey = data.fileKey ?: return emptyList()
            val link = MegaLink.File(handle = data.fileHandle, key = fileKey)
            val meta = resolveSingleFileCached(link)
            return extractor.videoFromSingleFile(meta)
        }

        // Archivo dentro de una carpeta: buscar el nodo ya cacheado (o
        // relistar la carpeta si el caché se perdió, p.ej. tras reiniciar
        // la app).
        val node = nodeCache[data.folderHandle]?.find { it.handle == data.fileHandle }
            ?: run {
                val entries = MegaPreferences.getEntries(preferences)
                val entry = entries.firstOrNull {
                    val link = extractor.parseLink(it.url)
                    link is MegaLink.Folder && link.handle == data.folderHandle
                } ?: return emptyList()

                val link = extractor.parseLink(entry.url) as? MegaLink.Folder ?: return emptyList()
                val nodes = extractor.listFolder(link)
                nodeCache[data.folderHandle] = nodes
                nodes.find { it.handle == data.fileHandle }
            } ?: return emptyList()

        return extractor.videoFromNode(node, folderHandle = data.folderHandle)
    }

    // ── Latest (no soportado) ──────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ── Utilidades de serialización ────────────────────────────────────────

    private fun MegaLinkData.toJsonString(): String = json.encodeToString(this)
    private fun MegaEpisodeData.toJsonString(): String = json.encodeToString(this)

    // ── Preferencias ──────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = MegaPreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = MegaPreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de MEGA\n\n" +
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
            key = MegaPreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de MEGA del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de MEGA de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo, o -- para una carpeta que funciona -- también " +
                    "el link que Anikku copia automáticamente al mantener presionado el ícono de WebView).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = MegaPreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    folderListPref.text = updated

                    preferences.edit().putString(MegaPreferences.REMOVE_ENTRY_KEY, "").commit()
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
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")
    }
}
