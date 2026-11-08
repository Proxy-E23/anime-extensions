package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.googledrivescraper.FolderResult
import aniyomi.lib.googledrivescraper.GoogleDriveScraper
import aniyomi.lib.googledrivescraper.ScrapedDetails
import aniyomi.lib.googledrivescraper.ScrapedItem
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class GoogleDrive :
    AnimeHttpLegacySource(),
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

    // Fila de ajustes con el estado de la sesión de Google, y si la última carga de Mi Drive falló.
    private var sessionPref: SwitchPreferenceCompat? = null
    private var cloudLoadFailed = false

    // Caché en memoria de detalles ya resueltos por entrada, para no repetir
    // las llamadas de red de getAnimeDetails al reabrir la misma entrada.
    // Guarda solo lo que viene de Drive (portada y details.json): el estado y el
    // aviso de "Eliminar enlace" se calculan en cada apertura desde las preferencias.
    private val detailsCache = mutableMapOf<String, ScrapedDetails>()

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

    /** Resultado de armar una entrada guardada. [storable]: se puede guardar en disco si está completada. */
    private class EntryBuild(val animes: List<SAnime>, val storable: Boolean, val failed: Boolean)

    /** [cacheable] es falso si alguna entrada falló, para reintentar la próxima vez que se abra el catálogo. */
    private class CatalogBuild(val animes: List<SAnime>, val cacheable: Boolean)

    private fun catalogAnime(title: String, url: String, status: Int): SAnime = SAnime.create().apply {
        this.title = title
        this.url = url
        this.status = status
        thumbnail_url = ""
    }

    private fun catalogAnime(title: String, data: LinkData, status: Int): SAnime = catalogAnime(title, data.toJsonString(), status)

    private fun missingAnime(entry: GoogleDrivePreferences.Entry): SAnime = SAnime.create().apply {
        title = "❌ ${entry.name} (Carpeta eliminada)"
        thumbnail_url = "https://http.cat/404"
        url = LinkData(entry.url, "error404", LinkDataInfo(entry.name, entry.url)).toJsonString()
        status = SAnime.UNKNOWN
        description = "Esta carpeta fue eliminada o ya no está disponible.\n\n" + removeEntryHint(entry.name, entry.url)
    }

    /** Entrada visible cuando una carpeta no se pudo leer (red, sesión vencida...); no se guarda en ninguna caché. */
    private fun failedBuild(entry: GoogleDrivePreferences.Entry, message: String?): EntryBuild {
        val anime = SAnime.create().apply {
            title = "⚠️ ${entry.name} (No se pudo cargar)"
            url = LinkData(entry.url, "error", LinkDataInfo(entry.name, entry.url)).toJsonString()
            status = SAnime.UNKNOWN
            description = (message ?: "Error desconocido") +
                "\n\nRevisa tu conexión y que la sesión de Google siga iniciada; luego cierra y vuelve a abrir la extensión.\n\n" +
                removeEntryHint(entry.name, entry.url)
        }
        return EntryBuild(listOf(anime), storable = false, failed = true)
    }

    /**
     * Lee una carpeta guardada y la convierte en animes:
     *  - Sin subcarpetas y con videos en la primera página: un solo anime (la propia carpeta).
     *  - Con subcarpetas: cada subcarpeta y cada video suelto es un anime.
     * Si cualquier página falla no se devuelve nada parcial, para no guardar en disco una lista incompleta.
     */
    private fun buildFolderEntry(entry: GoogleDrivePreferences.Entry, folderId: String, recurDepth: String): EntryBuild {
        val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"
        val animes = mutableListOf<SAnime>()

        try {
            var pageToken: String? = ""
            // Solo la primera página decide si la raíz es un solo anime
            // (todo video, sin subcarpetas) o un catálogo de subcarpetas.
            var firstPage = true

            while (pageToken != null) {
                when (val result = scraper.listFolderItemsResult(folderId, pageToken)) {
                    is FolderResult.NotFound -> return EntryBuild(listOf(missingAnime(entry)), storable = false, failed = false)
                    FolderResult.NetworkError -> return failedBuild(entry, "No se pudo conectar con Google Drive.")
                    is FolderResult.Success -> {
                        val folders = result.page.items.filter { it.isFolder }
                        val videos = result.page.items.filter { it.isVideo }

                        if (firstPage && folders.isEmpty() && videos.isNotEmpty()) {
                            return EntryBuild(
                                listOf(catalogAnime(entry.name, LinkData(folderUrl, "multi"), entry.status)),
                                storable = true,
                                failed = false,
                            )
                        }

                        folders.forEach { item ->
                            animes += catalogAnime(
                                item.title,
                                LinkData("https://drive.google.com/drive/folders/${item.id}$recurDepth", "multi"),
                                entry.status,
                            )
                        }
                        videos.forEach { item ->
                            animes += catalogAnime(
                                item.title,
                                LinkData(
                                    "https://drive.google.com/uc?id=${item.id}",
                                    "single",
                                    LinkDataInfo(item.title, item.fileSize?.let { formatBytes(it) } ?: ""),
                                ),
                                entry.status,
                            )
                        }

                        pageToken = result.page.nextPageToken
                        firstPage = false
                    }
                }
            }
        } catch (e: Exception) {
            return failedBuild(entry, e.message)
        }

        return EntryBuild(animes, storable = animes.isNotEmpty(), failed = false)
    }

    /**
     * Enlaces guardados. Las entradas marcadas como completadas se guardan en disco y no se
     * vuelven a pedir (ni se comprueba si siguen existiendo); las demás (en emisión o
     * desconocido) se leen una vez por sesión. Los archivos sueltos no piden nada al
     * construirse, así que no necesitan caché. La lista de episodios siempre se lee en vivo
     * al abrir la entrada.
     */
    private fun buildEntryAnimes(entries: List<GoogleDrivePreferences.Entry>): CatalogBuild {
        val diskCache = GoogleDrivePreferences.readCompletedCache(preferences)
        val newDiskCache = mutableMapOf<String, List<GoogleDrivePreferences.CachedAnime>>()
        val results = mutableListOf<SAnime>()
        val built = mutableListOf<Pair<GoogleDrivePreferences.Entry, List<SAnime>>>()
        var cacheable = true

        entries.forEach { entry ->
            val fileMatch = DRIVE_FILE_REGEX.matchEntire(entry.url)

            val animes = if (fileMatch != null) {
                val fileId = fileMatch.groups["id1"]?.value ?: fileMatch.groups["id2"]!!.value
                listOf(
                    catalogAnime(
                        entry.name,
                        LinkData("https://drive.google.com/uc?id=$fileId", "single", LinkDataInfo(entry.name, "")),
                        entry.status,
                    ),
                )
            } else {
                val match = DRIVE_FOLDER_REGEX.matchEntire(entry.url) ?: return@forEach
                val folderId = match.groups["id"]!!.value
                val recurDepth = match.groups["depth"]?.value ?: ""

                // El nombre forma parte de la clave porque define el título de las entradas de una sola carpeta.
                val cacheKey = "${entry.url}::${entry.name}"
                val completed = entry.status == SAnime.COMPLETED

                val cached = if (completed) diskCache[cacheKey] else null
                if (cached != null) {
                    newDiskCache[cacheKey] = cached
                    cached.map { catalogAnime(it.title, it.url, entry.status) }
                } else {
                    val build = buildFolderEntry(entry, folderId, recurDepth)
                    if (build.failed) cacheable = false
                    // Una carpeta caída, con error o vacía nunca se guarda en disco.
                    if (completed && build.storable) {
                        newDiskCache[cacheKey] = build.animes.map { GoogleDrivePreferences.CachedAnime(it.title, it.url) }
                    }
                    build.animes
                }
            }

            results += animes
            built += entry to animes
        }

        // Poda: solo quedan en disco las entradas que siguen guardadas y completadas.
        if (newDiskCache != diskCache) GoogleDrivePreferences.writeCompletedCache(preferences, newDiskCache)
        updateOwners(entries, built)

        return CatalogBuild(results, cacheable)
    }

    /** Actualiza el mapa "id de carpeta/archivo -> entrada guardada" (ver GoogleDrivePreferences.readOwners). */
    private fun updateOwners(
        entries: List<GoogleDrivePreferences.Entry>,
        built: List<Pair<GoogleDrivePreferences.Entry, List<SAnime>>>,
    ) {
        val old = GoogleDrivePreferences.readOwners(preferences)
        val savedUrls = entries.map { it.url }.toSet()
        val owners = old.filterValues { it in savedUrls }.toMutableMap()

        built.forEach { (entry, animes) ->
            animes.forEach { anime ->
                val itemUrl = runCatching { json.decodeFromString<LinkData>(anime.url).url }.getOrNull() ?: return@forEach
                itemKey(itemUrl)?.let { owners[it] = entry.url }
            }
        }

        if (owners != old) GoogleDrivePreferences.writeOwners(preferences, owners)
    }

    /** Entrada guardada a la que pertenece un anime del catálogo, o null si no se puede determinar. */
    private fun resolveEntry(itemUrl: String): GoogleDrivePreferences.Entry? {
        GoogleDrivePreferences.findByUrl(preferences, itemUrl)?.let { return it }
        val key = itemKey(itemUrl) ?: return null
        val ownerUrl = GoogleDrivePreferences.readOwners(preferences)[key] ?: return null
        return GoogleDrivePreferences.findByUrl(preferences, ownerUrl)
    }

    // ------------------------------- Mi Drive -------------------------------

    /**
     * Mi Drive: una entrada por cada carpeta de primer nivel de la cuenta, más una sola
     * entrada aparte para los videos sueltos en la raíz (fuera de cualquier carpeta), si
     * los hay; todas con el prefijo de nube. Es una sola petición, y el resultado se
     * guarda en disco hasta que se pulse "Recargar mi Drive" en Ajustes.
     */
    private fun buildCloudEntries(): List<GoogleDrivePreferences.CachedAnime> {
        if (!scraper.isLoggedIn()) {
            throw IllegalStateException("No hay una sesión de Google iniciada.")
        }

        val items = mutableListOf<ScrapedItem>()
        var accountEmail: String? = null
        var pageToken: String? = ""
        while (pageToken != null) {
            val page = scraper.listMyDriveRoot(pageToken)
            items += page.items
            if (accountEmail == null) accountEmail = page.accountEmail
            pageToken = page.nextPageToken
        }

        // Se guarda para mostrarlo en Ajustes (la lectura de preferencias no puede usar la red).
        accountEmail?.let { GoogleDrivePreferences.setAccountEmail(preferences, it) }

        val folders = items.filter { it.isFolder }.map { item ->
            val data = LinkData(
                "https://drive.google.com/drive/folders/${item.id}",
                CLOUD_MULTI,
                LinkDataInfo(item.title, ""),
            )
            GoogleDrivePreferences.CachedAnime("$CLOUD_PREFIX${item.title}", data.toJsonString())
        }
        // Los videos sueltos se agrupan en una sola entrada; sus episodios se leen al abrirla.
        val rootEntry = if (items.any { it.isVideo }) {
            val data = LinkData("https://drive.google.com/drive/my-drive", CLOUD_ROOT, LinkDataInfo(CLOUD_ROOT_NAME, ""))
            listOf(GoogleDrivePreferences.CachedAnime("$CLOUD_PREFIX$CLOUD_ROOT_NAME", data.toJsonString()))
        } else {
            emptyList()
        }

        return folders + rootEntry
    }

    /** Nombre con el que el usuario identifica un elemento de Mi Drive en "Estado de emisión de mis carpetas". */
    private fun cloudStatusName(data: LinkData, fallback: String): String {
        val raw = data.info?.title ?: return fallback
        return if (data.type == CLOUD_SINGLE) raw.substringBeforeLast('.') else raw
    }

    private fun cloudAnime(cached: GoogleDrivePreferences.CachedAnime): SAnime {
        val status = runCatching { json.decodeFromString<LinkData>(cached.url) }.getOrNull()
            ?.let { GoogleDrivePreferences.driveStatusFor(preferences, cloudStatusName(it, cached.title)) }
            ?: SAnime.UNKNOWN
        return catalogAnime(cached.title, cached.url, status)
    }

    private fun loadCloudAnimes(): List<SAnime> {
        val cached = GoogleDrivePreferences.readCloudCache(preferences)
            ?: buildCloudEntries().also { if (it.isNotEmpty()) GoogleDrivePreferences.writeCloudCache(preferences, it) }
        return cached.map { cloudAnime(it) }
    }

    private fun cloudErrorAnime(message: String?): SAnime = SAnime.create().apply {
        title = "⚠️ Mi Drive: no se pudo cargar"
        url = LinkData(baseUrl, "error", LinkDataInfo("Mi Drive", "")).toJsonString()
        status = SAnime.UNKNOWN
        description = (message ?: "Error desconocido") +
            "\n\nRevisa que tengas la sesión de Google iniciada en el WebView de la extensión y vuelve a abrir el catálogo. " +
            "Si no quieres ver tu Drive aquí, desactiva \"Mostrar mi Drive en el catálogo\" en Ajustes."
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        migrateLegacyPrefs()

        val animeList = mutableListOf<SAnime>()
        var cacheable = true

        val entries = GoogleDrivePreferences.getEntries(preferences)
        if (entries.isNotEmpty()) {
            val build = buildEntryAnimes(entries)
            animeList += build.animes
            if (!build.cacheable) cacheable = false
        }

        // Un fallo de Mi Drive (sesión vencida, red) no debe tumbar los enlaces guardados:
        // se muestra un aviso y no se guarda en caché para reintentar la próxima vez.
        if (GoogleDrivePreferences.showDrive(preferences)) {
            try {
                animeList += loadCloudAnimes()
                cloudLoadFailed = false
            } catch (e: Exception) {
                cacheable = false
                cloudLoadFailed = true
                animeList += cloudErrorAnime(e.message)
            }
            refreshSessionSummary()
        }

        if (cacheable) cachedAnimes = animeList
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
        val urlFilter = filterList.filterIsInstance<UrlFilter>().firstOrNull()
        val nameFilter = filterList.filterIsInstance<NameFilter>().firstOrNull()
        val statusFilter = filterList.filterIsInstance<StatusFilter>().firstOrNull()

        val pastedUrl = urlFilter?.state?.trim().orEmpty()
        val status = statusFilter?.state ?: SAnime.UNKNOWN

        return if (pastedUrl.isNotBlank()) {
            val folderMatch = DRIVE_FOLDER_REGEX.matchEntire(pastedUrl)

            if (folderMatch != null) {
                val folderId = folderMatch.groups["id"]!!.value
                val recurDepth = folderMatch.groups["depth"]?.value ?: ""
                val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"

                GoogleDrivePreferences.findByUrl(preferences, folderUrl)?.let { existing ->
                    throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
                }

                val entryName = nameFilter?.state?.trim()?.takeIf { it.isNotBlank() }
                    ?: folderMatch.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                    ?: folderId

                GoogleDrivePreferences.addEntry(preferences, entryName, folderUrl, status)
                cachedAnimes = null
                detailsCache.clear()

                addSinglePage(folderUrl, entryName, status)
            } else {
                val fileMatch = DRIVE_FILE_REGEX.matchEntire(pastedUrl)
                    ?: throw Exception("URL de Google Drive inválida")

                val fileId = fileMatch.groups["id1"]?.value ?: fileMatch.groups["id2"]!!.value
                val fileUrl = "https://drive.google.com/uc?id=$fileId"

                GoogleDrivePreferences.findByUrl(preferences, fileUrl)?.let { existing ->
                    throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
                }

                val entryName = nameFilter?.state?.trim()?.takeIf { it.isNotBlank() }
                    ?: fileMatch.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                    ?: fileId

                GoogleDrivePreferences.addEntry(preferences, entryName, fileUrl, status)
                cachedAnimes = null
                detailsCache.clear()

                addSinglePage(fileUrl, entryName, status, isFile = true)
            }
        } else if (query.isNotBlank()) {
            val all = getPopularAnime(page)
            AnimesPage(all.animes.filter { it.title.contains(query, ignoreCase = true) }, false)
        } else {
            getPopularAnime(page)
        }
    }

    // ============================== FILTERS ===============================

    override fun getFilterList() = AnimeFilterList(
        InfoFilter("Pega una URL de carpeta o archivo de Google Drive para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        StatusFilter(),
        SeparatorFilter(),
        InfoFilter("Deja la URL vacía para buscar por nombre"),
    )

    // =========================== Anime Details ============================

    private fun LinkData.isError(): Boolean = type == "error404" || type == "error"

    private fun LinkData.isSingle(): Boolean = type == "single" || type == CLOUD_SINGLE

    private fun LinkData.isCloud(): Boolean = type == CLOUD_MULTI || type == CLOUD_SINGLE || type == CLOUD_ROOT

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return GET(baseUrl, headers = headers)
        }
        if (parsed.isError()) return GET(baseUrl, headers = headers)
        return GET(parsed.url, headers = headers)
    }

    // El estado y la descripción se reconstruyen siempre desde las preferencias,
    // para reflejar cambios hechos a mano en Ajustes. Lo que viene de Drive (portada y
    // details.json) se pide al abrir la entrada, así que lo completado también se actualiza.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return anime
        }

        if (parsed.isError()) return anime

        // Estado elegido por el usuario (null si no se puede determinar) y aviso para la descripción.
        val userStatus: Int?
        val hint: String
        if (parsed.isCloud()) {
            userStatus = GoogleDrivePreferences.driveStatusFor(preferences, cloudStatusName(parsed, anime.title))
            hint = when (parsed.type) {
                CLOUD_ROOT -> "Videos sueltos en la raíz de tu Google Drive, fuera de cualquier carpeta."
                CLOUD_SINGLE -> "Archivo de tu cuenta de Google Drive."
                else -> "Carpeta de tu cuenta de Google Drive."
            }
        } else {
            val entry = resolveEntry(parsed.url)
            userStatus = entry?.status
            hint = removeEntryHint(entry?.name ?: anime.title, entry?.url ?: parsed.url)
        }

        // Un archivo suelto (o la entrada de videos sueltos de la raíz) no tiene carpeta
        // propia donde buscar details.json ni cover.
        if (parsed.isSingle() || parsed.type == CLOUD_ROOT) {
            return anime.apply {
                description = hint
                userStatus?.let { status = it }
            }
        }

        // anime.url completa como key: dos entradas pueden apuntar al mismo
        // folderId (mismo link agregado dos veces con nombres distintos).
        val cacheKey = anime.url

        val details = detailsCache[cacheKey]
            ?: runCatching { scraper.scrapeFolderDetails(parsed.url) }.getOrNull()?.also { detailsCache[cacheKey] = it }
            ?: return anime.apply {
                description = hint
                userStatus?.let { status = it }
            }

        return anime.apply {
            // El nombre lo elige el usuario al guardar el enlace y nunca lo
            // sobrescribe details.json (que puede traer algo genérico como "Folder").
            details.coverUrl?.let { thumbnail_url = it }
            details.author?.let { author = it }
            details.artist?.let { artist = it }
            details.genre?.let { genre = it }

            // El estado elegido por el usuario manda; details.json solo completa cuando es "desconocido".
            val detailsStatus = details.status?.let { it.toIntOrNull() ?: SAnime.UNKNOWN }
            val chosen = userStatus?.takeIf { it != SAnime.UNKNOWN }
            status = chosen ?: detailsStatus ?: userStatus ?: status

            // Se reconstruye desde details.description, no desde anime.description:
            // Aniyomi/Anikku persiste ese campo entre sesiones y puede traer un hint
            // ya escrito de una carga anterior, acumulándose en cada carga.
            description = (details.description?.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") + hint
        }
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

        if (parsed.isError()) return emptyList()

        if (parsed.isSingle()) {
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

        // Videos sueltos en la raíz de Mi Drive: se leen en vivo al abrir la entrada.
        if (parsed.type == CLOUD_ROOT) {
            val videos = mutableListOf<ScrapedItem>()
            var pageToken: String? = ""
            while (pageToken != null) {
                val page = scraper.listMyDriveRoot(pageToken)
                videos += page.items.filter { it.isVideo }
                pageToken = page.nextPageToken
            }

            if (videos.isEmpty()) {
                throw Exception("No se encontraron videos sueltos en la raíz de tu Drive.")
            }

            val showFilename = GoogleDrivePreferences.showFilename(preferences)
            return FilenameUtils.sortBySeasonAndEpisodeDescending(videos, { it.title }, showFilename)
                .map { seasoned ->
                    SEpisode.create().apply {
                        name = seasoned.display.name
                        url = "https://drive.google.com/uc?id=${seasoned.item.id}"
                        episode_number = seasoned.display.episodeNumber
                        date_upload = seasoned.item.modifiedDateMillis ?: -1L
                        scanlator = seasoned.item.fileSize?.let { formatBytes(it) } ?: ""
                    }
                }
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
        return FilenameUtils.sortBySeasonAndEpisodeDescending(scraped, { it.name }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    name = seasoned.display.name
                    url = seasoned.item.url
                    episode_number = seasoned.display.episodeNumber
                    date_upload = seasoned.item.dateUploadMillis
                    scanlator = seasoned.item.sizeLabel
                }
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))

    // ============================= Utilities ==============================

    private fun addSinglePage(url: String, entryName: String, status: Int, isFile: Boolean = false): AnimesPage {
        val anime = if (isFile) {
            catalogAnime(entryName, LinkData(url, "single", LinkDataInfo(entryName, "")), status)
        } else {
            val match = DRIVE_FOLDER_REGEX.matchEntire(url) ?: throw Exception("URL de Google Drive inválida")
            val recurDepth = match.groups["depth"]?.value ?: ""

            catalogAnime(
                entryName,
                LinkData("https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth", "multi"),
                status,
            )
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
        // Tipos de LinkData para los elementos de Mi Drive (nunca se usan en entradas guardadas).
        private const val CLOUD_MULTI = "cloud_multi"
        private const val CLOUD_SINGLE = "cloud_single"
        private const val CLOUD_ROOT = "cloud_root"
        private const val CLOUD_ROOT_NAME = "Mi Drive (raíz)"

        private val COLOR_SESSION_OK = 0xFF4CAF50.toInt()
        private val COLOR_SESSION_WARNING = 0xFFFFC107.toInt()
        private val COLOR_SESSION_ERROR = 0xFFF44336.toInt()
        private const val CLOUD_PREFIX = "☁️ "

        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<n>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )

        // Ej.: .../file/d/<id>/view?usp=sharing o .../uc?id=<id>
        private val DRIVE_FILE_REGEX = Regex(
            """(?<n>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/(?:file\/d\/(?<id1>[\w-]{25,})(?:\/[^?]*)?|uc\?(?:[^&]*&)*id=(?<id2>[\w-]{25,}))(?:[?&][^;]*)?${'$'}""",
        )

        // Id de la carpeta o del archivo dentro de una URL del catálogo.
        private val ITEM_ID_REGEX = Regex("""(?:/folders/|[?&]id=)([\w-]{25,})""")

        private fun itemKey(url: String): String? = ITEM_ID_REGEX.find(url)?.groupValues?.get(1)
    }

    // ============================== Settings ==============================

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

    /**
     * Estado de la sesión de Google, con color:
     *  - verde: hay una sesión guardada en el dispositivo;
     *  - amarillo: hay sesión, pero la última carga de Mi Drive falló (puede haber vencido);
     *  - rojo: no hay sesión.
     * "Verde" significa que la cookie de sesión existe localmente: Google solo confirma que
     * sigue vigente cuando se le hace una petición, por eso el amarillo sale de un fallo real.
     * El correo se conoce solo después de haber cargado Mi Drive al menos una vez.
     */
    private fun sessionSummary(): CharSequence {
        if (!scraper.isLoggedIn()) {
            val status = "Sin sesión de Google iniciada."
            return SpannableString("$status Inicia sesión desde el WebView de la extensión.").apply {
                setSpan(ForegroundColorSpan(COLOR_SESSION_ERROR), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val email = GoogleDrivePreferences.accountEmail(preferences)
        val warning = if (cloudLoadFailed) {
            "\n⚠️ No se pudo cargar tu Mi Drive: la sesión podría haber vencido. Si se repite, cierra sesión e inicia de nuevo."
        } else {
            ""
        }
        val color = if (cloudLoadFailed) COLOR_SESSION_WARNING else COLOR_SESSION_OK

        return if (email != null) {
            val prefix = "Sesión iniciada como: "
            SpannableString(prefix + email + warning).apply {
                setSpan(ForegroundColorSpan(color), prefix.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            val status = "Sesión de Google detectada."
            val help = " El correo aparecerá después de cargar Mi Drive (activa \"Mostrar mi Drive en el catálogo\" y abre el catálogo)."
            SpannableString(status + help + warning).apply {
                setSpan(ForegroundColorSpan(color), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun refreshSessionSummary() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { sessionPref?.summary = sessionSummary() }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Fila informativa: es un interruptor que nunca se queda encendido (ver actionPreference).
        // Al pulsarla solo vuelve a calcular el texto.
        SwitchPreferenceCompat(screen.context).apply {
            key = "session_status"
            title = "Cuenta de Google"
            summary = sessionSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshSessionSummary()
                false
            }
        }.also {
            sessionPref = it
            screen.addPreference(it)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = GoogleDrivePreferences.PREF_SHOW_DRIVE
            title = "Mostrar mi Drive en el catálogo"
            summary = "Muestra con ☁️ las carpetas de tu cuenta de Google (requiere haber iniciado sesión en el WebView)."
            setDefaultValue(false)
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
                    "Úsalo si creaste, moviste o renombraste carpetas, o si cambiaste de cuenta de Google.",
                toastText = "Mi Drive se recargará al abrir el catálogo",
            ) {
                GoogleDrivePreferences.clearCloudCache(preferences)
                // El correo se vuelve a detectar con la próxima carga (por si cambió la cuenta).
                GoogleDrivePreferences.setAccountEmail(preferences, null)
                cachedAnimes = null
                refreshSessionSummary()
            },
        )

        EditTextPreference(screen.context).apply {
            key = GoogleDrivePreferences.PREF_DRIVE_STATUS
            title = "Estado de emisión de mis carpetas"
            summary = "Marca carpetas de Mi Drive como en emisión o completadas"
            dialogTitle = "Estado de emisión de mis carpetas"
            setDialogMessage(
                "Una línea por carpeta de la raíz de tu Drive (sin el ☁️):\n\n" +
                    "Nombre de la carpeta::e   (en emisión)\n" +
                    "Nombre de la carpeta::c   (completado)\n" +
                    "Nombre de la carpeta::d   (desconocido)\n\n" +
                    "Para ver los cambios, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = GoogleDrivePreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Google Drive\n\n" +
                    "Para indicar el estado de emisión, agrega al final ::e (en emisión), " +
                    "::c (completado) o ::d (desconocido).\n" +
                    "Ejemplo: Mi anime::https://drive.google.com/drive/folders/abc123::c\n\n" +
                    "Las entradas marcadas como completadas se leen una sola vez y se guardan; si una cambia " +
                    "o se elimina, usa \"Recargar enlaces guardados\".\n\n" +
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

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "refresh_saved_links",
                prefTitle = "Recargar enlaces guardados",
                prefSummary = "Las entradas en emisión o desconocidas se leen al abrir Anikku; las completadas " +
                    "se guardan. Esto fuerza una nueva lectura de todas, completadas incluidas.",
                toastText = "Los enlaces se actualizarán al abrir el catálogo",
            ) {
                GoogleDrivePreferences.clearCompletedCache(preferences)
                cachedAnimes = null
                detailsCache.clear()
            },
        )

        SwitchPreferenceCompat(screen.context).apply {
            key = GoogleDrivePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
