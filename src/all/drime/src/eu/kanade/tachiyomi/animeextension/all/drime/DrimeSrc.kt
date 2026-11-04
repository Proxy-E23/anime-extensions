package eu.kanade.tachiyomi.animeextension.all.drime

import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.drimeextractor.DrimeExtractor
import aniyomi.lib.drimeextractor.DrimeNode
import aniyomi.lib.drimeextractor.DrimeShare
import aniyomi.lib.filenameutils.FilenameUtils
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
import java.util.TimeZone

class DrimeSrc :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Drime"
    override val baseUrl = "https://app.drime.cloud"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Drime entrega las fechas en ISO 8601 UTC ("2026-10-07T03:15:54.000000Z");
    // se usan solo los primeros 19 caracteres.
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var cachedAnimes: List<SAnime>? = null

    private val extractor by lazy { DrimeExtractor(client, headers, baseUrl) }

    private fun parseDate(raw: String): Long = runCatching { dateFormat.parse(raw.take(19))?.time ?: 0L }.getOrElse { 0L }

    private fun shareUrl(hash: String) = "$baseUrl/drive/s/$hash"

    private companion object {
        const val MAX_FOLDER_DEPTH = 10
    }

    // ── Formatos de SAnime.url ────────────────────────────────────────────────
    // Enlaces públicos:
    //  - "missing::<hash>"                                       enlace eliminado o inválido
    //  - "share::<hash>"                                         raíz del enlace: un archivo, o una carpeta solo con videos
    //  - "folder::<hash>::<hash carpeta>"                        subcarpeta hoja de un enlace
    //  - "single::<hash>::<id enlace>::<hash archivo>::<nombre>" video suelto dentro de una carpeta con subcarpetas
    // Mi Drive (token):
    //  - "drive::<hash carpeta>"                                 carpeta hoja de la cuenta
    //  - "dsingle::<hash archivo>::<nombre>"                     video suelto dentro de una carpeta con subcarpetas
    //  - "error::drive"                                          aviso cuando no se pudo cargar Mi Drive
    //
    // Formatos de SEpisode.url:
    //  - "file::<hash>::<id enlace>::<hash archivo>::<nombre>"   enlace público
    //  - "dfile::<hash archivo>::<nombre>"                       Mi Drive

    private fun hashFromAnimeUrl(url: String): String = url.substringAfter("::").substringBefore("::")

    private fun isMissing(url: String): Boolean = url.startsWith("missing::")

    private fun isError(url: String): Boolean = url.startsWith("error::")

    private fun isDriveUrl(url: String): Boolean = url.startsWith("drive::") || url.startsWith("dsingle::")

    private fun episodeUrl(shareHash: String, linkId: Long, entryHash: String, filename: String) = "file::$shareHash::$linkId::$entryHash::$filename"

    private fun requireToken(): String = DrimePreferences.getToken(preferences)
        .ifBlank { throw Exception("Configura tu token de Drime en Ajustes de la extensión") }

    // ── Navegación de carpetas (común a enlaces públicos y Mi Drive) ──────────

    private class Listing(val folder: DrimeNode, val children: List<DrimeNode>, val owner: String?)

    private class Meta(val status: Int, val description: String)

    private interface FolderBrowser {
        /** Contenido de una subcarpeta, o null si ya no existe. */
        fun list(folder: DrimeNode): Listing?
        fun folderUrl(folder: DrimeNode, depth: Int): String
        fun singleUrl(file: DrimeNode): String
    }

    private fun publicBrowser(share: DrimeShare): FolderBrowser = object : FolderBrowser {
        override fun list(folder: DrimeNode): Listing? = extractor.fetchShare(share.hash, folder.hash)
            ?.let { Listing(it.root, it.children, it.owner) }

        override fun folderUrl(folder: DrimeNode, depth: Int): String = if (depth == 0) "share::${share.hash}" else "folder::${share.hash}::${folder.hash}"

        override fun singleUrl(file: DrimeNode): String = "single::${share.hash}::${share.linkId}::${file.hash}::${file.name}"
    }

    private fun driveBrowser(token: String): FolderBrowser = object : FolderBrowser {
        override fun list(folder: DrimeNode): Listing? = extractor.listDrive(token, folder.hash)
            ?.let { Listing(it.root ?: folder, it.children, null) }

        override fun folderUrl(folder: DrimeNode, depth: Int): String = "drive::${folder.hash}"

        override fun singleUrl(file: DrimeNode): String = "dsingle::${file.hash}::${file.name}"
    }

    // ── Construcción de animes ────────────────────────────────────────────────

    /** Texto en la descripción de cada entrada para poder eliminarla desde Ajustes → Eliminar enlace. */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    private fun newAnime(meta: Meta, title: String, url: String, owner: String?): SAnime = SAnime.create().apply {
        this.title = title
        this.url = url
        this.status = meta.status
        this.author = owner
        this.initialized = true
        this.description = meta.description
    }

    private fun missingAnime(entry: DrimePreferences.Entry, hash: String): SAnime = SAnime.create().apply {
        title = "❌ ${entry.name} (Enlace eliminado, privado o inválido)"
        thumbnail_url = "https://http.cat/404"
        url = "missing::$hash"
        status = SAnime.UNKNOWN
        description = "Este enlace fue eliminado, está protegido o ya no es válido.\n\n" +
            removeEntryHint(entry.name, entry.url)
    }

    private fun driveErrorAnime(message: String?): SAnime = SAnime.create().apply {
        title = "⚠️ Mi Drive: no se pudo cargar"
        url = "error::drive"
        status = SAnime.UNKNOWN
        description = (message ?: "Error desconocido") +
            "\n\nRevisa tu token en Ajustes de la extensión y vuelve a abrir el catálogo."
    }

    /**
     * Misma lógica que la extensión de MediaFire:
     *  - Carpeta sin subcarpetas: un anime con sus videos como episodios.
     *  - Carpeta con subcarpetas: cada subcarpeta es un anime ("Padre - Subcarpeta")
     *    y cada video suelto de esa carpeta es un anime aparte.
     */
    private fun expandFolder(
        browser: FolderBrowser,
        meta: Meta,
        listing: Listing,
        folderName: String,
        parentTitle: String,
        depth: Int = 0,
    ): List<SAnime> {
        if (depth > MAX_FOLDER_DEPTH) return emptyList()

        val subFolders = listing.children.filter { it.isFolder }
        val videoFiles = listing.children.filter { it.isVideo }
        val results = mutableListOf<SAnime>()

        if (subFolders.isEmpty()) {
            if (videoFiles.isNotEmpty()) {
                val displayTitle = if (parentTitle.isBlank()) folderName else "$parentTitle - $folderName"
                results += newAnime(meta, displayTitle, browser.folderUrl(listing.folder, depth), listing.owner)
            }
        } else {
            videoFiles.forEach { file ->
                val baseName = file.name.substringBeforeLast('.')
                val displayTitle = if (parentTitle.isBlank()) baseName else "$parentTitle - $baseName"
                results += newAnime(meta, displayTitle, browser.singleUrl(file), listing.owner)
            }

            subFolders.forEach { sub ->
                val subListing = browser.list(sub) ?: return@forEach
                val subTitle = if (parentTitle.isBlank()) folderName else parentTitle
                results += expandFolder(browser, meta, subListing, sub.name, subTitle, depth + 1)
            }
        }

        return results
    }

    /** Convierte una entrada guardada en uno o varios animes. [share] es null si el enlace ya no existe. */
    private fun buildAnimes(entry: DrimePreferences.Entry, hash: String, share: DrimeShare?): List<SAnime> {
        if (share == null) return listOf(missingAnime(entry, hash))

        val meta = Meta(entry.status, removeEntryHint(entry.name, entry.url))

        // Enlace a un solo archivo.
        if (!share.root.isFolder) {
            return if (share.root.isVideo) {
                listOf(newAnime(meta, entry.name, "share::$hash", share.owner))
            } else {
                emptyList()
            }
        }

        val listing = Listing(share.root, share.children, share.owner)
        return expandFolder(publicBrowser(share), meta, listing, folderName = entry.name, parentTitle = "")
    }

    private fun buildAnimes(entry: DrimePreferences.Entry): List<SAnime> {
        val link = extractor.parseLink(entry.url) ?: return emptyList()
        return buildAnimes(entry, link.hash, extractor.fetchShare(link.hash))
    }

    private fun driveMeta(rootName: String) = Meta(
        status = DrimePreferences.driveStatusFor(preferences, rootName),
        description = "Carpeta de tu cuenta de Drime.",
    )

    /**
     * Mi Drive: cada carpeta de la raíz se expande con la misma lógica que un enlace
     * público; los videos sueltos de la raíz son animes aparte.
     */
    private fun buildDriveAnimes(token: String): List<SAnime> {
        val rootListing = extractor.listDrive(token, null) ?: return emptyList()
        val browser = driveBrowser(token)
        val results = mutableListOf<SAnime>()

        rootListing.children.filter { it.isVideo }.forEach { file ->
            val baseName = file.name.substringBeforeLast('.')
            results += newAnime(driveMeta(baseName), baseName, browser.singleUrl(file), null)
        }

        rootListing.children.filter { it.isFolder }.forEach { folder ->
            val listing = browser.list(folder) ?: return@forEach
            results += expandFolder(browser, driveMeta(folder.name), listing, folder.name, parentTitle = "")
        }

        return results
    }

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun SAnime.toCached() = DrimePreferences.CachedAnime(title, url, author)

    /**
     * Enlaces públicos. Los marcados como completados se guardan en disco y no se
     * vuelven a pedir; los demás (en emisión o desconocido) se leen una vez por sesión.
     */
    private fun buildLinkAnimes(entries: List<DrimePreferences.Entry>): List<SAnime> {
        val diskCache = DrimePreferences.readPublicCache(preferences)
        val newDiskCache = mutableMapOf<String, List<DrimePreferences.CachedAnime>>()
        val results = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val link = extractor.parseLink(entry.url) ?: return@forEach
            val meta = Meta(entry.status, removeEntryHint(entry.name, entry.url))
            val completed = entry.status == SAnime.COMPLETED
            // El nombre forma parte de la clave porque define los títulos ("Nombre - Subcarpeta").
            val cacheKey = "${link.hash}::${entry.name}"

            val cached = if (completed) diskCache[cacheKey] else null
            if (cached != null) {
                newDiskCache[cacheKey] = cached
                cached.forEach { results += newAnime(meta, it.title, it.url, it.author) }
            } else {
                val share = extractor.fetchShare(link.hash)
                val animes = buildAnimes(entry, link.hash, share)
                results += animes
                // Un enlace caído (share == null) nunca se guarda en disco.
                if (completed && share != null && animes.isNotEmpty()) {
                    newDiskCache[cacheKey] = animes.map { it.toCached() }
                }
            }
        }

        // Poda: solo quedan en disco los enlaces que siguen guardados y completados.
        if (newDiskCache != diskCache) DrimePreferences.writePublicCache(preferences, newDiskCache)
        return results
    }

    /** Mi Drive: se lee del disco; solo se reconstruye si no hay caché o se pide "Recargar mi Drive". */
    private fun loadDriveAnimes(token: String): List<SAnime> {
        DrimePreferences.readDriveCache(preferences)?.let { cached ->
            return cached.map { newAnime(driveMeta(it.title), it.title, it.url, it.author) }
        }

        val animes = buildDriveAnimes(token)
        DrimePreferences.writeDriveCache(preferences, animes.map { it.toCached() })
        return animes
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val linkAnimes = buildLinkAnimes(DrimePreferences.getEntries(preferences))

        // Un fallo de Mi Drive (token inválido, red) no debe tumbar los enlaces públicos:
        // se muestra un aviso y no se guarda en caché para reintentar la próxima vez.
        var cacheable = true
        val token = DrimePreferences.getToken(preferences)
        val driveAnimes = if (token.isNotBlank() && DrimePreferences.showDrive(preferences)) {
            try {
                loadDriveAnimes(token)
            } catch (e: Exception) {
                cacheable = false
                listOf(driveErrorAnime(e.message))
            }
        } else {
            emptyList()
        }

        val animes = linkAnimes + driveAnimes
        if (cacheable) cachedAnimes = animes
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
                ?: throw Exception("URL de Drime inválida")

            // Evita guardar el mismo enlace dos veces con nombres distintos.
            DrimePreferences.findByHash(preferences, link.hash)?.let { existing ->
                throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
            }

            val share = extractor.fetchShare(link.hash)
                ?: throw Exception("El enlace no existe, fue eliminado o requiere contraseña")

            val resolvedName = nameFilter?.state?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (share.root.isFolder) share.root.name else share.root.name.substringBeforeLast('.')

            val entry = DrimePreferences.Entry(
                name = resolvedName,
                url = shareUrl(link.hash),
                status = statusFilter?.state ?: SAnime.UNKNOWN,
            )

            // Se arman los animes antes de guardar: si no hay videos, no se guarda nada.
            val animes = buildAnimes(entry, link.hash, share)
            if (animes.isEmpty()) {
                throw Exception("No se encontraron videos en ese enlace")
            }

            DrimePreferences.addEntry(preferences, entry.name, entry.url, entry.status)
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
        InfoFilter("Pega una URL de carpeta o archivo compartido de Drime para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        StatusFilter(),
        SeparatorFilter(),
        InfoFilter("Deja la URL vacía para buscar por nombre"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request = GET(
        when {
            isDriveUrl(anime.url) || isError(anime.url) -> "$baseUrl/drive"
            else -> shareUrl(hashFromAnimeUrl(anime.url))
        },
    )

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // La descripción y el estado se reconstruyen siempre desde las preferencias,
    // para reflejar cambios hechos a mano en Ajustes.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeUrl = anime.url

        return anime.apply {
            when {
                isError(animeUrl) -> Unit

                isDriveUrl(animeUrl) -> {
                    description = "Carpeta de tu cuenta de Drime."
                    status = DrimePreferences.driveStatusFor(preferences, title)
                }

                else -> {
                    val hash = hashFromAnimeUrl(animeUrl)
                    val entry = DrimePreferences.findByHash(preferences, hash)
                    val displayName = entry?.name ?: title
                    val entryUrl = entry?.url ?: shareUrl(hash)

                    if (isMissing(animeUrl)) {
                        description = "Este enlace fue eliminado, está protegido o ya no es válido.\n\n" +
                            removeEntryHint(displayName, entryUrl)
                    } else {
                        description = removeEntryHint(displayName, entryUrl)
                        entry?.let { status = it.status }
                    }
                }
            }
        }
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun episodesFromChildren(children: List<DrimeNode>, urlOf: (DrimeNode) -> String): List<SEpisode> {
        val videoFiles = children.filter { it.isVideo }

        return FilenameUtils.sortBySeasonAndEpisodeDescending(videoFiles, { it.name }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    url = urlOf(seasoned.item)
                    name = seasoned.display.name
                    episode_number = seasoned.display.episodeNumber
                    // La app usa estas fechas para estimar el próximo episodio
                    // de los animes marcados como "En emisión".
                    date_upload = parseDate(seasoned.item.created)
                }
            }
    }

    private fun singleEpisode(node: DrimeNode, url: String): SEpisode {
        val display = FilenameUtils.buildEpisodeDisplay(node.name, showFilename)
        return SEpisode.create().apply {
            this.url = url
            name = display.name
            episode_number = 1f
            date_upload = parseDate(node.created)
        }
    }

    private fun looseVideoNode(hash: String, filename: String) = DrimeNode(
        id = 0,
        hash = hash,
        name = filename,
        type = "video",
        extension = filename.substringAfterLast('.', "").lowercase(),
        size = 0,
        created = "",
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = anime.url
        if (isMissing(animeUrl) || isError(animeUrl)) return emptyList()

        return when {
            // Mi Drive: carpeta hoja.
            animeUrl.startsWith("drive::") -> {
                val listing = extractor.listDrive(requireToken(), hashFromAnimeUrl(animeUrl)) ?: return emptyList()
                episodesFromChildren(listing.children) { "dfile::${it.hash}::${it.name}" }
            }

            // Mi Drive: video suelto (el archivo viene completo en la URL, sin pedir nada a la red).
            animeUrl.startsWith("dsingle::") -> {
                val parts = animeUrl.split("::", limit = 3)
                listOf(singleEpisode(looseVideoNode(parts[1], parts[2]), "dfile::${parts[1]}::${parts[2]}"))
            }

            // Enlace público: video suelto.
            animeUrl.startsWith("single::") -> {
                val parts = animeUrl.split("::", limit = 5)
                val node = looseVideoNode(parts[3], parts[4])
                listOf(singleEpisode(node, episodeUrl(parts[1], parts[2].toLong(), node.hash, node.name)))
            }

            // Enlace público: subcarpeta hoja.
            animeUrl.startsWith("folder::") -> {
                val hash = hashFromAnimeUrl(animeUrl)
                val folderHash = animeUrl.split("::", limit = 3)[2]
                val share = extractor.fetchShare(hash, folderHash) ?: return emptyList()
                episodesFromChildren(share.children) { episodeUrl(share.hash, share.linkId, it.hash, it.name) }
            }

            // Enlace público: raíz (un archivo o una carpeta solo con videos).
            else -> {
                val share = extractor.fetchShare(hashFromAnimeUrl(animeUrl)) ?: return emptyList()
                when {
                    share.root.isFolder -> episodesFromChildren(share.children) {
                        episodeUrl(share.hash, share.linkId, it.hash, it.name)
                    }
                    share.root.isVideo -> listOf(
                        singleEpisode(share.root, episodeUrl(share.hash, share.linkId, share.root.hash, share.root.name)),
                    )
                    else -> emptyList()
                }
            }
        }
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url.startsWith("dfile::")) {
            val parts = episode.url.split("::", limit = 3)
            return extractor.videoFromDriveFile(requireToken(), parts[1], parts[2])
        }

        val parts = episode.url.split("::", limit = 5)
        val shareHash = parts[1]
        val linkId = parts[2].toLong()
        val entryHash = parts[3]
        val filename = parts[4]
        return extractor.videoFromFile(shareHash, linkId, entryHash, filename)
    }

    private val showFilename: Boolean
        get() = DrimePreferences.showFilename(preferences)

    // ── Preferencias ──────────────────────────────────────────────────────────

    private fun tokenSummary(token: String) = if (token.isBlank()) "No configurado" else "Configurado (oculto)"

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
            key = DrimePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = DrimePreferences.PREF_TOKEN
            title = "Token de API de Drime (Mi Drive)"
            summary = tokenSummary(DrimePreferences.getToken(preferences))
            dialogTitle = "Token de API de Drime"
            setDialogMessage(
                "Para ver las carpetas de tu cuenta en el catálogo:\n\n" +
                    "1. Entra a Drime → Configuración → Developers.\n" +
                    "2. En \"API access tokens\" pulsa \"Create token\" y elige el permiso \"Read Only\".\n" +
                    "3. Copia el token (se muestra una sola vez) y pégalo aquí.\n\n" +
                    "El token no caduca y puedes borrarlo desde Drime cuando quieras.\n\n" +
                    "Para ver los cambios, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { pref, newValue ->
                cachedAnimes = null
                // Otra cuenta (o token) implica otro Drive: la caché anterior ya no sirve.
                DrimePreferences.clearDriveCache(preferences)
                pref.summary = tokenSummary((newValue as String).trim())
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DrimePreferences.PREF_SHOW_DRIVE
            title = "Mostrar mi Drive en el catálogo"
            summary = "Solo se usa si hay un token configurado."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = DrimePreferences.PREF_DRIVE_STATUS
            title = "Estado de emisión de mis carpetas"
            summary = "Marca carpetas de Mi Drive como en emisión o completadas"
            dialogTitle = "Estado de emisión de mis carpetas"
            setDialogMessage(
                "Una línea por carpeta de la raíz de tu Drive:\n\n" +
                    "Nombre de la carpeta::e   (en emisión)\n" +
                    "Nombre de la carpeta::c   (completado)\n" +
                    "Nombre de la carpeta::d   (desconocido)\n\n" +
                    "Se aplica a todos los animes que salgan de esa carpeta, incluidas sus subcarpetas.\n\n" +
                    "Para ver los cambios, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "reload_drive",
                prefTitle = "Recargar mi Drive",
                prefSummary = "Vuelve a leer tus carpetas la próxima vez que abras el catálogo. " +
                    "Úsalo si creaste, moviste o renombraste carpetas en tu Drive.",
                toastText = "Mi Drive se recargará al abrir el catálogo",
            ) {
                DrimePreferences.clearDriveCache(preferences)
                cachedAnimes = null
            },
        )

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "refresh_public_links",
                prefTitle = "Refrescar enlaces públicos",
                prefSummary = "Los enlaces en emisión o desconocidos se actualizan al abrir Anikku; los completados " +
                    "se guardan. Esto fuerza una nueva lectura de todos, completados incluidos.",
                toastText = "Los enlaces se actualizarán al abrir el catálogo",
            ) {
                DrimePreferences.clearPublicCache(preferences)
                cachedAnimes = null
            },
        )

        val linkListPref = EditTextPreference(screen.context).apply {
            key = DrimePreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Drime\n\n" +
                    "Para indicar el estado de emisión, agrega al final ::e (en emisión), " +
                    "::c (completado) o ::d (desconocido).\n" +
                    "Ejemplo: Mi anime::https://app.drime.cloud/drive/s/abc123::e\n\n" +
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
            key = DrimePreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de Drime del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de Drime de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la entrada equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = DrimePreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    linkListPref.text = updated

                    preferences.edit().putString(DrimePreferences.REMOVE_ENTRY_KEY, "").commit()
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
