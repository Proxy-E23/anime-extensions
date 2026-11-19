package eu.kanade.tachiyomi.animeextension.es.gakuensai

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.mediafireextractor.MediaFireExtractor
import aniyomi.lib.mediafireextractor.MediaFireLink
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GakuensaiFansub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Gakuensai Fansub"
    override val baseUrl = "https://www.gakuensai.xyz"
    override val lang = "es"
    override val supportsLatest = true

    // El sitio ahora es un foro SMF: todo cuelga de index.php. La misma página de proyectos
    // trae las pestañas SFW ("Proyectos") y NSFW ("FAP") dentro del mismo HTML.
    private val homeUrl = "$baseUrl/index.php"
    private val projectsUrl = "$baseUrl/index.php?action=projects"

    // Los enlaces de descarga (sa=enlaces) son redirecciones del propio sitio hacia el host
    // real, así que se piden sin seguir la redirección para leer el destino.
    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private val extractor by lazy {
        MediaFireExtractor(client, headers, "https://www.mediafire.com")
    }

    private val preferences by getPreferencesLazy()

    private val showFilename: Boolean
        get() = GakuensaiPreferences.showFilename(preferences)

    private val nsfwMode: String
        get() = GakuensaiPreferences.nsfwMode(preferences)

    private fun passesNsfwFilter(isNsfwEntry: Boolean): Boolean = when (nsfwMode) {
        GakuensaiPreferences.NSFW_MODE_ONLY -> isNsfwEntry
        GakuensaiPreferences.NSFW_MODE_SHOW_ALL -> true
        else -> !isNsfwEntry
    }

    // Los listados crudos se comparten entre sesiones de filtro distintas: nunca se muta un
    // SAnime cacheado directamente (perdería sus marcas internas), siempre se copia.
    private fun SAnime.withCleanTitle(): SAnime = SAnime.create().also { copy ->
        copy.url = url
        copy.title = stripInternalMarks(title)
        copy.thumbnail_url = thumbnail_url
        copy.genre = genre
        copy.description = description
        copy.status = status
    }

    // La sección (Activos/Finalizados/Otros) va codificada en el título con un marcador
    // invisible, igual que NSFW_MARK. Se retira en withCleanTitle() antes de mostrar el título.
    private fun sectionOf(anime: SAnime): String? = Regex("""\u0001([^\u0001]+)\u0001""").find(anime.title)?.groupValues?.get(1)

    private fun stripInternalMarks(title: String): String = title.removePrefix(NSFW_MARK).replace(Regex("""\u0001[^\u0001]+\u0001"""), "")

    // ============================ Caché de portadas ============================
    // El listado solo trae un banner horizontal (1240x350); la portada vertical buena está
    // en la página de detalle de cada serie. Se cachea de forma persistente para no volver a
    // pedirla en cada sesión.
    private val thumbnailPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_thumbnails", 0x0000)
    }

    private fun getCachedThumbnail(url: String): String? = thumbnailPrefs.getString(url, null)?.ifBlank { null }

    private fun cacheThumbnail(url: String, thumbnailUrl: String) {
        thumbnailPrefs.edit().putString(url, thumbnailUrl).apply()
    }

    // Resuelve en paralelo la portada vertical de las series que aún no estén en caché y
    // aplica la portada cacheada a TODAS las entradas de la lista. Las entradas vienen del
    // catálogo, que se armó con el banner del listado antes de que existiera la portada en
    // caché, así que hay que reemplazarlo también en las que ya estaban cacheadas.
    private suspend fun resolveMissingThumbnails(list: List<SAnime>): List<SAnime> = coroutineScope {
        val pending = list.filter { it.url.isNotBlank() && getCachedThumbnail(it.url) == null }

        val resolved = pending.map { anime ->
            async(Dispatchers.IO) {
                anime.url to try {
                    val document = client.get("$baseUrl${anime.url}", headers).useAsJsoup()
                    parseAnimeDetails(document).thumbnail_url
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().toMap()

        resolved.forEach { (url, thumbnail) ->
            if (!thumbnail.isNullOrBlank()) cacheThumbnail(url, thumbnail)
        }

        list.map { anime ->
            val thumbnail = getCachedThumbnail(anime.url)
            if (thumbnail != null) anime.apply { thumbnail_url = thumbnail } else anime
        }
    }

    // Índice título normalizado -> URL de proyecto, usado como plan B cuando el slug de
    // categoría del home no coincide con el slug real del proyecto.
    private var projectIndexCache: Map<String, String>? = null

    private fun normalizeTitle(title: String): String = title
        .lowercase()
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), "")

    private suspend fun getProjectIndex(): Map<String, String> {
        projectIndexCache?.let { return it }

        val index = getFullCatalog().associate { anime ->
            normalizeTitle(stripInternalMarks(anime.title)) to anime.url
        }
        projectIndexCache = index
        return index
    }

    // URLs de proyecto confirmadas como NSFW (vienen de /fap/). Se usa en Recientes en vez
    // del ribbon del post individual, que puede faltar en algún post aislado.
    private suspend fun getNsfwProjectUrls(): Set<String> = getFullCatalog().filter { it.title.startsWith(NSFW_MARK) }.map { it.url }.toSet()

    // ================================ Popular ================================
    // /proyectos/ no está paginado por el sitio; se usa como catálogo completo y se pagina
    // en memoria, respetando el orden Activos -> Finalizados.
    private var popularCache: List<SAnime>? = null

    // Caché de sesión por página: mientras Anikku no se cierre, no se repiten peticiones al
    // volver a visitar la misma página de Populares.
    private val popularPageCache = mutableMapOf<Int, AnimesPage>()

    override fun popularAnimeRequest(page: Int): Request = GET(projectsUrl, headers)

    // Catálogo crudo completo (SFW + NSFW, sin filtrar todavía). Una sola petición: ambas
    // pestañas vienen en el mismo HTML. Si falla se propaga el error (no se cachea vacío).
    private suspend fun getFullCatalog(): List<SAnime> = popularCache ?: run {
        val document = client.get(projectsUrl, headers).useAsJsoup()
        parseProjectsListing(document).also { if (it.isNotEmpty()) popularCache = it }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        popularPageCache[page]?.let { return it }

        val all = getFullCatalog()
        // "Otros" (Videos musicales, etc.) queda en el catálogo crudo para que el filtro de
        // categoría pueda mostrarlo si se pide explícitamente, pero Populares solo muestra
        // Activos/Finalizados por defecto.
        val filtered = all.filter { passesNsfwFilter(it.title.startsWith(NSFW_MARK)) && sectionOf(it) != SECTION_OTHER }
            .map { it.withCleanTitle() }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= filtered.size) return AnimesPage(emptyList(), false).also { popularPageCache[page] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filtered.size)
        val hasNext = toIndex < filtered.size
        val pageItems = resolveMissingThumbnails(filtered.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { popularPageCache[page] = it }
    }

    // Se marca el título con prefijos invisibles para saber, sin volver a pedir la página,
    // la sección y si la entrada es NSFW. Se retiran en withCleanTitle().
    private fun parseProjectsListing(document: Document): List<SAnime> {
        val result = mutableListOf<SAnime>()
        document.select("div.fichas-tab-panel").forEach { panel ->
            val isNsfwPanel = panel.attr("data-tab-panel") == "nsfw"
            panel.select("h3.fichas-section").forEach { header ->
                val sectionName = normalizeSection(header.text())
                val container = header.nextElementSibling() ?: return@forEach
                container.select(seriesCardSelector()).forEach { card ->
                    result += seriesCardToSAnime(card, isNsfwSection = isNsfwPanel, section = sectionName)
                }
            }
        }
        return result
    }

    private fun normalizeSection(raw: String): String {
        val text = raw.trim()
        return listOf(SECTION_ONGOING, SECTION_COMPLETED, SECTION_OTHER)
            .firstOrNull { text.startsWith(it, ignoreCase = true) } ?: text
    }

    private fun seriesCardSelector(): String = "div.ficha-card"

    private fun seriesCardToSAnime(element: Element, isNsfwSection: Boolean, section: String): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
        url = link?.attr("href")?.removePrefix(baseUrl).orEmpty()
        val cleanTitle = element.attr("title").trim().ifBlank { null }
            ?: link?.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: link?.selectFirst("div.ficha-text p")?.text()?.trim().orEmpty()
        title = (if (isNsfwSection) NSFW_MARK else "") + sectionMark(section) + cleanTitle
        thumbnail_url = getCachedThumbnail(url) ?: link?.selectFirst("img")?.attr("src")
        genre = if (isNsfwSection) "NSFW" else null
    }

    private fun sectionMark(section: String) = "\u0001$section\u0001"

    // Requerido por la clase base; getPopularAnime maneja la paginación real.
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseProjectsListing(response.asJsoup()), false)

    // =============================== Latest / Recientes ===============================
    // La portada lista un artículo por cada publicación (capítulo, volumen, etc.), 8 por
    // página (?articles=0, 8, 16...). Cada uno trae la "categoría" (board) y el título del
    // post; con eso se busca el proyecto en el catálogo y se consolida a una entrada por
    // serie, conservando el orden (más reciente primero). Se leen solo las primeras páginas.
    private var latestCache: List<SAnime>? = null
    private val latestPageCache = mutableMapOf<Int, AnimesPage>()

    private data class HomePost(val boardName: String, val postTitle: String)

    private fun homePageUrl(page: Int): String = if (page <= 1) homeUrl else "$homeUrl?articles=${(page - 1) * HOME_ARTICLES_PER_PAGE}"

    override fun latestUpdatesRequest(page: Int): Request = GET(homePageUrl(page), headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        latestPageCache[page]?.let { return it }

        val all = latestCache ?: buildLatestList().also { if (it.isNotEmpty()) latestCache = it }

        val filtered = all.filter { passesNsfwFilter(it.title.startsWith(NSFW_MARK)) }
            .map { it.withCleanTitle() }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= filtered.size) return AnimesPage(emptyList(), false).also { latestPageCache[page] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filtered.size)
        val hasNext = toIndex < filtered.size
        val pageItems = resolveMissingThumbnails(filtered.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { latestPageCache[page] = it }
    }

    private suspend fun buildLatestList(): List<SAnime> = coroutineScope {
        val catalog = getFullCatalog()
        val index = getProjectIndex()
        val catalogByUrl = catalog.associateBy { it.url }

        val posts = (1..LATEST_HOME_PAGES).map { homePage ->
            async(Dispatchers.IO) {
                runCatching { parseHomePosts(client.get(homePageUrl(homePage), headers).useAsJsoup()) }
                    .getOrElse { emptyList() }
            }
        }.awaitAll().flatten()

        posts.mapNotNull { matchProjectUrl(it, index) }
            .distinct()
            .mapNotNull { catalogByUrl[it] }
    }

    private fun parseHomePosts(document: Document): List<HomePost> = document.select("div.sp_article_modern_card").mapNotNull { card ->
        val postTitle = card.selectFirst("h3.sp_article_modern_title a")?.text()?.trim() ?: return@mapNotNull null
        val boardName = card.selectFirst("div.sp_article_modern_board a")?.text()?.trim().orEmpty()
        HomePost(boardName, postTitle)
    }

    // 1) El título del post empieza con el nombre del proyecto (gana el más largo, así
    //    "... Naru II - 12" no cae en "... Naru"). 2) Plan B: la categoría del post coincide
    //    con el proyecto o está contenida en su nombre ("Koe no Katachi" -> "Eiga Koe no Katachi").
    private fun matchProjectUrl(post: HomePost, index: Map<String, String>): String? {
        val postKey = normalizeTitle(post.postTitle)
        index.entries
            .filter { (key, _) -> key.isNotEmpty() && postKey.startsWith(key) }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }

        val boardKey = normalizeTitle(post.boardName)
        if (boardKey.isEmpty()) return null
        index[boardKey]?.let { return it }
        return index.entries
            .filter { (key, _) -> key.contains(boardKey) }
            .minByOrNull { it.key.length }
            ?.value
    }

    // Requerido por la clase base; getLatestUpdates maneja la consolidación real.
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    // =============================== Search ==================================
    // No hay buscador dedicado; se filtra en memoria sobre el catálogo completo.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    private val searchPageCache = mutableMapOf<String, AnimesPage>()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val section = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedSection
        val cacheKey = "$query|$section|$page"
        searchPageCache[cacheKey]?.let { return it }

        val all = getFullCatalog()
        val nsfwFiltered = all.filter { passesNsfwFilter(it.title.startsWith(NSFW_MARK)) }
            // "Todas" (section == null) se comporta como Populares: incluye Activos y
            // Finalizados, pero no Otros a menos que se elija explícitamente.
            .filter { if (section != null) sectionOf(it) == section else sectionOf(it) != SECTION_OTHER }
            .map { it.withCleanTitle() }
        val filtered = if (query.isBlank()) nsfwFiltered else nsfwFiltered.filter { it.title.contains(query, ignoreCase = true) }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= filtered.size) return AnimesPage(emptyList(), false).also { searchPageCache[cacheKey] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filtered.size)
        val hasNext = toIndex < filtered.size
        val pageItems = resolveMissingThumbnails(filtered.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { searchPageCache[cacheKey] = it }
    }

    // Requerido por la clase base; getSearchAnime maneja el filtrado real.
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Se sobreescribe getAnimeDetails (no solo animeDetailsParse) porque hace falta la url
    // del anime para cachear la portada, resolver el estado por sesión, y anteponer el
    // aviso de "solo torrent" a la descripción cuando corresponda.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.get("$baseUrl${anime.url}", headers).useAsJsoup()
        val details = parseAnimeDetails(document)

        val thumbnail = details.thumbnail_url
        if (!thumbnail.isNullOrBlank()) cacheThumbnail(anime.url, thumbnail)

        ensureStatuses(document)
        details.status = statusCache[anime.url] ?: SAnime.UNKNOWN

        if (isTorrentOnly(document)) {
            details.description = TORRENT_ONLY_NOTICE + details.description.orEmpty()
        }

        return details
    }

    // Filas dt/dd de los paneles de la ficha (datos técnicos, staff).
    private fun tableRows(document: Document, panelSelector: String): List<Pair<String, String>> = document.select("$panelSelector div.ficha-table-row").mapNotNull { row ->
        val key = row.selectFirst("dt")?.text()?.trim().orEmpty()
        val value = row.selectFirst("dd")?.text()?.trim().orEmpty()
        if (key.isBlank() && value.isBlank()) null else key to value
    }

    override fun animeDetailsParse(response: Response): SAnime = parseAnimeDetails(response.asJsoup())

    private fun parseAnimeDetails(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.ficha-hero-title")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("div.ficha-hero-poster-frame img")?.attr("src")
        description = document.selectFirst("div.ficha-hero-synopsis")?.text()?.trim().orEmpty()

        val staff = tableRows(document, "section.ficha-staff")
            .joinToString("\n") { (key, value) -> "$key: $value" }
        if (staff.isNotBlank()) {
            description = (description.orEmpty() + "\n\n" + staff).trim()
        }

        val technical = tableRows(document, "section.ficha-tech").toMap()
        genre = technical["Fuente"]

        // El estado real se completa en getAnimeDetails, que sí tiene la url del anime.
        status = SAnime.UNKNOWN
    }

    // Caché de sesión: sección (Activos/Finalizados) por URL de proyecto.
    private val statusCache = mutableMapOf<String, Int>()

    private fun rememberStatuses(document: Document) {
        document.select("div.fichas-tab-panel h3.fichas-section").forEach { header ->
            val status = when (normalizeSection(header.text())) {
                SECTION_ONGOING -> SAnime.ONGOING
                SECTION_COMPLETED -> SAnime.COMPLETED
                else -> return@forEach
            }
            val container = header.nextElementSibling() ?: return@forEach
            container.select(seriesCardSelector()).forEach { element ->
                val href = element.selectFirst("a[href]")?.attr("href")?.removePrefix(baseUrl)
                if (!href.isNullOrBlank()) statusCache[href] = status
            }
        }
    }

    private suspend fun ensureStatuses(fallback: Document) {
        if (statusCache.isNotEmpty()) return
        rememberStatuses(runCatching { client.get(projectsUrl, headers).useAsJsoup() }.getOrElse { fallback })
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    // ============================== Episode list ==============================
    // Los episodios ya vienen en el HTML de la ficha (sección "Descargas"). Cada botón apunta
    // a un enlace del propio sitio (sa=enlaces) que redirige al host real (MediaFire/torrent).
    // Se sobreescribe getEpisodeList entero; el resto de hooks quedan inutilizados.
    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    private fun isVideoFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun downloadEpisodes(document: Document): List<Element> = document.select("section.ficha-downloads article.ficha-download-episode")

    private fun mediafireEnlaces(document: Document): List<String> = downloadEpisodes(document)
        .flatMap { episode -> episode.select("a.btn-mediafire[href]").map { it.attr("href") } }

    // Hay bloques de descarga pero ninguno con MediaFire (solo torrent).
    private fun isTorrentOnly(document: Document): Boolean = downloadEpisodes(document).isNotEmpty() && mediafireEnlaces(document).isEmpty()

    // Caché de sesión: enlace del sitio -> URL real de MediaFire.
    private val downloadLinkCache = mutableMapOf<String, String>()

    // Se usa execute() a propósito: los helpers de keiyoushi.network lanzan error ante un 302.
    private fun resolveDownloadLink(enlaceUrl: String): String? {
        downloadLinkCache[enlaceUrl]?.let { return it }

        val resolved = try {
            noRedirectClient.newCall(GET(enlaceUrl, headers)).execute().use { response ->
                val location = response.header("Location")
                when {
                    !location.isNullOrBlank() -> response.request.url.resolve(location)?.toString()
                    response.isSuccessful -> MEDIAFIRE_URL_REGEX.find(
                        response.body.string().replace("&amp;", "&").replace("\\/", "/"),
                    )?.value
                    else -> {
                        Log.e(name, "Enlace $enlaceUrl devolvió HTTP ${response.code}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Fallo al resolver el enlace $enlaceUrl", e)
            null
        }

        if (resolved != null && "mediafire.com" in resolved) {
            downloadLinkCache[enlaceUrl] = resolved
            return resolved
        }
        Log.e(name, "El enlace $enlaceUrl no llevó a MediaFire: $resolved")
        return null
    }

    private data class LooseFile(val quickkey: String, val filename: String)

    // MediaFire entrega la fecha de creación como "yyyy-MM-dd HH:mm:ss" en UTC. Solo viene al
    // listar carpetas; los archivos sueltos no la traen y quedan sin fecha (0).
    private val createdDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get("$baseUrl${anime.url}", headers).useAsJsoup()
        ensureStatuses(document)

        val enlaces = mediafireEnlaces(document)
        if (enlaces.isEmpty()) return emptyList()

        val mediafireLinks = coroutineScope {
            enlaces.map { enlace -> async(Dispatchers.IO) { resolveDownloadLink(enlace) } }.awaitAll()
        }.filterNotNull().distinct()

        // Si ningún enlace se pudo resolver (fallo de red/servidor, ver Logcat) no se devuelve
        // nada y se puede reintentar en la próxima consulta.
        if (mediafireLinks.isEmpty()) return emptyList()

        val hasFolder = mediafireLinks.any { it.contains("/folder/") }

        if (hasFolder) {
            // Prioridad carpetas: se ignoran los archivos sueltos y se expanden todas las
            // carpetas, uniendo su contenido de video (una serie puede repartir sus
            // episodios en varias carpetas).
            val videoFiles = mediafireLinks.filter { it.contains("/folder/") }
                .mapNotNull { (extractor.parseLink(it) as? MediaFireLink.Folder)?.key }
                .flatMap { key -> runCatching { extractor.listFiles(key) }.getOrElse { emptyList() } }
                .filter { isVideoFile(it.filename) }

            return FilenameUtils.sortBySeasonAndEpisodeDescending(videoFiles, { it.filename }, showFilename)
                .map { seasoned ->
                    SEpisode.create().apply {
                        url = "file::${seasoned.item.quickkey}::${seasoned.item.filename}"
                        name = seasoned.display.name
                        episode_number = seasoned.display.episodeNumber
                        date_upload = createdDateFormat.tryParse(seasoned.item.created)
                    }
                }
        }

        // Sin carpetas: cada botón es 1 episodio suelto con su propio link /file/.
        val looseFiles = coroutineScope {
            mediafireLinks.filter { it.contains("/file/") }.map { href ->
                async(Dispatchers.IO) {
                    val link = extractor.parseLink(href) as? MediaFireLink.File ?: return@async null
                    val filename = link.filename ?: extractor.resolveFileName(link.quickkey, hint = null)
                    LooseFile(link.quickkey, filename)
                }
            }.awaitAll().filterNotNull()
        }

        return FilenameUtils.sortBySeasonAndEpisodeDescending(looseFiles, { it.filename }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    url = "file::${seasoned.item.quickkey}::${seasoned.item.filename}"
                    name = seasoned.display.name
                    episode_number = seasoned.display.episodeNumber
                    date_upload = 0L
                }
            }
    }

    // ============================== Hosters / Video list ==============================
    // Cada episodio es un archivo de MediaFire, así que hay un único hoster. La URL directa
    // caduca, por eso se resuelve recién al abrir el hoster y no al listar los episodios.
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("::")
        if (parts.size < 3) return emptyList()
        return listOf(
            Hoster(
                hosterUrl = parts[1],
                hosterName = "MediaFire",
                internalData = parts[2],
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val quickkey = hoster.hosterUrl
        val filename = hoster.internalData
        if (quickkey.isBlank() || filename.isBlank()) return emptyList()

        val directUrl = withContext(Dispatchers.IO) { extractor.resolveDirectVideoUrl(quickkey, filename) }
        return listOf(
            Video(
                videoUrl = directUrl,
                videoTitle = filename.substringBeforeLast('.'),
                headers = headers.newBuilder().set("Referer", "https://www.mediafire.com/").build(),
            ),
        )
    }

    // ============================== Filtros ==============================
    // El sitio no ofrece filtros reales de género/año; el único que aporta algo es la
    // categoría de /proyectos/. Solo aplica al buscar.
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        InfoFilter("El sitio no tiene géneros ni años: solo divide sus series en estas categorías."),
    )

    // ============================== Preferencias ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = GakuensaiPreferences.PREF_SHOW_FILENAME,
            title = "Mostrar nombre real del archivo",
            summary = "Muestra el nombre real del archivo de MediaFire en lugar de \"Episodio X\".",
            default = false,
        )

        screen.addListPreference(
            key = GakuensaiPreferences.PREF_NSFW_MODE,
            title = "NSFW",
            summary = "Controla si se muestra contenido +18 en Populares y Recientes.",
            entries = listOf("Mostrar todo", "Solo contenido SFW", "Solo contenido NSFW"),
            entryValues = listOf(
                GakuensaiPreferences.NSFW_MODE_SHOW_ALL,
                GakuensaiPreferences.NSFW_MODE_HIDE,
                GakuensaiPreferences.NSFW_MODE_ONLY,
            ),
            default = GakuensaiPreferences.NSFW_MODE_HIDE,
        )
    }

    // Al cambiar el modo NSFW, los resultados ya paginados en esta sesión quedan obsoletos
    // (se filtraron con el modo anterior); se limpian para que se repaginen con el modo
    // nuevo sin reiniciar Anikku. El catálogo crudo no se toca, solo el filtro aplicado.
    //
    // SharedPreferences solo retiene una referencia débil al listener: se guarda como
    // propiedad de la clase para que el recolector de basura no lo elimine.
    private val nsfwModeChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == GakuensaiPreferences.PREF_NSFW_MODE) {
                popularPageCache.clear()
                latestPageCache.clear()
                searchPageCache.clear()
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(nsfwModeChangeListener)
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val HOME_ARTICLES_PER_PAGE = 8
        private const val LATEST_HOME_PAGES = 4
        private val MEDIAFIRE_URL_REGEX = Regex("""https?://(?:www\.)?mediafire\.com/[^\s"'<>\\]+""")
        private const val NSFW_MARK = "\u0000NSFW\u0000"
        const val SECTION_ONGOING = "Activos"
        const val SECTION_COMPLETED = "Finalizados"
        const val SECTION_OTHER = "Otros"
        private val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")
        private const val TORRENT_ONLY_NOTICE =
            "⚠️ Esta serie solo está en torrent. Esta extensión aún no soporta torrents: " +
                "visualízala en WebView, copia el nombre del torrent y búscalo usando la extensión Nyaa.\n\n"
    }
}
