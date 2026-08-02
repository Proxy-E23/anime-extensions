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
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GakuensaiFansub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Gakuensai Fansub"
    override val baseUrl = "https://www.gakuensai.xyz"
    override val lang = "es"
    override val supportsLatest = true

    private val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
    private val projectsUrl = "$baseUrl/proyectos"
    private val nsfwUrl = "$baseUrl/fap"

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

    // Resuelve en paralelo la portada vertical de las series que aún no estén en caché.
    private suspend fun resolveMissingThumbnails(list: List<SAnime>): List<SAnime> = coroutineScope {
        val pending = list.filter { it.url.isNotBlank() && getCachedThumbnail(it.url) == null }
        if (pending.isEmpty()) return@coroutineScope list

        val resolved = pending.map { anime ->
            async {
                anime.url to try {
                    val document = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
                    animeDetailsParse(document).thumbnail_url
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().toMap()

        list.map { anime ->
            val newThumbnail = resolved[anime.url]
            if (!newThumbnail.isNullOrBlank()) {
                cacheThumbnail(anime.url, newThumbnail)
                anime.apply { thumbnail_url = newThumbnail }
            } else {
                anime
            }
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

    // Catálogo crudo completo (SFW + NSFW, sin filtrar todavía).
    private suspend fun getFullCatalog(): List<SAnime> = popularCache ?: run {
        val sfw = runCatching {
            parseProjectsListing(client.newCall(popularAnimeRequest(1)).execute().asJsoup(), isNsfwListing = false)
        }.getOrElse { emptyList() }
        val nsfw = runCatching {
            parseProjectsListing(client.newCall(GET(nsfwUrl, headers)).execute().asJsoup(), isNsfwListing = true)
        }.getOrElse { emptyList() }
        (sfw + nsfw).also { popularCache = it }
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
    private fun parseProjectsListing(document: Document, isNsfwListing: Boolean): List<SAnime> {
        val sections = document.select("div.entry > div")
        val ordered = mutableListOf<SAnime>()

        listOf(SECTION_ONGOING, SECTION_COMPLETED, SECTION_OTHER).forEach { sectionName ->
            val header = sections.firstOrNull { it.selectFirst("h2")?.text()?.trim()?.startsWith(sectionName) == true }
            val container = header?.nextElementSibling() ?: return@forEach
            container.select(seriesCardSelector()).forEach { element ->
                ordered += seriesCardToSAnime(element, isNsfwSection = isNsfwListing, section = sectionName)
            }
        }
        return ordered
    }

    private fun seriesCardSelector(): String = "div.la-serie"

    private fun seriesCardToSAnime(element: Element, isNsfwSection: Boolean, section: String): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
        url = link?.attr("href")?.removePrefix(baseUrl).orEmpty()
        val cleanTitle = link?.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null } ?: element.attr("title").trim()
        title = (if (isNsfwSection) NSFW_MARK else "") + sectionMark(section) + cleanTitle
        thumbnail_url = getCachedThumbnail(url) ?: link?.selectFirst("img")?.attr("src")
        genre = if (isNsfwSection) "NSFW" else null
    }

    private fun sectionMark(section: String) = "\u0001$section\u0001"

    override fun popularAnimeSelector(): String = seriesCardSelector()

    // Nunca se invoca en la práctica; getPopularAnime maneja el flujo real.
    override fun popularAnimeFromElement(element: Element): SAnime = seriesCardToSAnime(element, isNsfwSection = false, section = SECTION_ONGOING)

    override fun popularAnimeNextPageSelector(): String? = null

    // Requerido por la clase base; getPopularAnime maneja la paginación real.
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseProjectsListing(response.asJsoup(), isNsfwListing = false), false)

    // =============================== Latest / Recientes ===============================
    // La home publica un post por cada capítulo. Se consolida a una sola entrada por serie,
    // quedándose con la fecha del post más reciente.
    private var latestCache: List<SAnime>? = null
    private val latestPageCache = mutableMapOf<Int, AnimesPage>()

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        latestPageCache[page]?.let { return it }

        val all = latestCache ?: run {
            val document = client.newCall(latestUpdatesRequest(1)).execute().asJsoup()
            val list = parseHomeConsolidated(document)
            latestCache = list
            list
        }

        val filtered = all.filter { passesNsfwFilter(it.title.startsWith(NSFW_MARK)) }
            .map { it.withCleanTitle() }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= filtered.size) return AnimesPage(emptyList(), false).also { latestPageCache[page] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filtered.size)
        val hasNext = toIndex < filtered.size
        val pageItems = resolveMissingThumbnails(filtered.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { latestPageCache[page] = it }
    }

    // Cada post trae: link al post, categorías (la última es la más específica), timestamp
    // UNIX en el atributo utime, y un ribbon NSFW opcional.
    private data class HomePost(val projectUrl: String, val title: String, val timestamp: Long, val isNsfw: Boolean)

    private suspend fun parseHomeConsolidated(document: Document): List<SAnime> {
        val posts = document.select("div.card-index").mapNotNull { card ->
            val categoryLinks = card.select("div.post-categories a[href*=/category/]")
            val mostSpecificHref = categoryLinks.lastOrNull()?.attr("href") ?: return@mapNotNull null
            val slug = mostSpecificHref.trimEnd('/').substringAfterLast('/')
            val title = card.selectFirst("h5 a")?.text()?.trim() ?: return@mapNotNull null
            val timestamp = card.selectFirst("time[utime]")?.attr("utime")?.toLongOrNull() ?: 0L
            val isNsfw = card.selectFirst("div.ribbon-wrapper, div.ribbon") != null

            val basePath = if (isNsfw) "/fap" else "/proyectos"
            HomePost(projectUrl = "$basePath/$slug/", title = title, timestamp = timestamp, isNsfw = isNsfw)
        }

        val index = getProjectIndex()
        val knownProjectUrls = index.values.toSet()
        val nsfwProjectUrls = getNsfwProjectUrls()

        // Si la URL construida a partir del slug de categoría coincide con una URL real
        // conocida se usa tal cual; si no, plan B por título normalizado (el slug de
        // categoría no siempre coincide con el slug real del proyecto).
        val grouped = posts.groupBy { post ->
            if (post.projectUrl in knownProjectUrls) {
                post.projectUrl
            } else {
                val normalized = normalizeTitle(post.title)
                index.entries.firstOrNull { (key, _) -> normalized.contains(key) || key.contains(normalized) }?.value
                    ?: post.projectUrl
            }
        }

        return grouped.map { (projectUrl, groupPosts) ->
            val latest = groupPosts.maxByOrNull { it.timestamp } ?: groupPosts.first()
            // El índice NSFW real (de qué listado vino la URL) manda sobre el ribbon del
            // post, que puede faltar en algún post aislado.
            val isNsfw = if (projectUrl in nsfwProjectUrls) {
                true
            } else if (projectUrl in knownProjectUrls) {
                false
            } else {
                latest.isNsfw
            }
            SAnime.create().apply {
                url = projectUrl
                title = (if (isNsfw) NSFW_MARK else "") + seriesTitleFromPostTitle(latest.title)
                thumbnail_url = getCachedThumbnail(projectUrl)
            }
        }.sortedByDescending { anime ->
            grouped.entries.first { it.key == anime.url }.value.maxOf { it.timestamp }
        }
    }

    // El título del post suele ser "Serie – NN" o "Serie – NN y MM"; se recorta el sufijo de
    // episodio para mostrar el nombre limpio de la serie.
    private fun seriesTitleFromPostTitle(postTitle: String): String = postTitle.substringBefore(" – ").substringBefore(" [").trim().ifBlank { postTitle }

    override fun latestUpdatesSelector(): String = "div.card-index h5 a"
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        url = element.attr("href").removePrefix(baseUrl)
        title = element.text().trim()
    }
    override fun latestUpdatesNextPageSelector(): String? = null

    // Requerido por la clase base; getLatestUpdates maneja la consolidación real.
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    // =============================== Search ==================================
    // No hay buscador dedicado; se filtra en memoria sobre el catálogo completo.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)
    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = null

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
        val document = client.newCall(animeDetailsRequest(anime)).execute().asJsoup()
        val details = animeDetailsParse(document)

        val thumbnail = details.thumbnail_url
        if (!thumbnail.isNullOrBlank()) cacheThumbnail(anime.url, thumbnail)

        if (statusCache.isEmpty()) {
            rememberStatuses(runCatching { client.newCall(GET(projectsUrl, headers)).execute().asJsoup() }.getOrElse { document })
        }
        details.status = statusCache[anime.url] ?: SAnime.UNKNOWN

        // Solo-torrent se sabe recién tras consultar el AJAX de episodios; se reutiliza
        // torrentOnlyCache si getEpisodeList ya corrió antes en esta sesión. Si la consulta
        // AJAX falla, NO se marca como torrent-only para evitar un falso positivo.
        val isTorrentOnly = torrentOnlyCache[anime.url] ?: run {
            val releaseIds = document.select("button.accordion-project-button[data-release-id]")
                .mapNotNull { it.attr("data-release-id").ifBlank { null } }
            if (releaseIds.isEmpty()) {
                false
            } else {
                val cardsByRelease = releaseIds.map { it to fetchReleaseCards(it, "$baseUrl${anime.url}") }
                val allQueriesFailed = cardsByRelease.all { (_, cards) -> cards.isEmpty() }
                val hasMediafire = cardsByRelease.any { (_, cards) -> cards.any { it.mediafireHrefs.isNotEmpty() } }
                if (allQueriesFailed) {
                    false
                } else {
                    val result = !hasMediafire
                    torrentOnlyCache[anime.url] = result
                    result
                }
            }
        }
        if (isTorrentOnly) {
            details.description = TORRENT_ONLY_NOTICE + details.description.orEmpty()
        }

        return details
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("div.hero-poster-wrapper img")?.attr("src")
        description = document.select("div.sinopsis-inner").text().trim()

        val staff = document.select("div.gakuensai-info-card--staff dt, div.gakuensai-info-card--staff dd")
            .chunked(2)
            .joinToString("\n") { pair -> "${pair.getOrNull(0)?.text().orEmpty()}: ${pair.getOrNull(1)?.text().orEmpty()}" }
        if (staff.isNotBlank()) {
            description = (description.orEmpty() + "\n\n" + staff).trim()
        }

        val technical = document.select("div.gakuensai-info-card--technical dt, div.gakuensai-info-card--technical dd")
            .chunked(2)
            .associate { pair -> pair.getOrNull(0)?.text()?.trim().orEmpty() to pair.getOrNull(1)?.text()?.trim().orEmpty() }
        genre = technical["Fuente"]

        // El estado real se completa en getAnimeDetails, que sí tiene la url del anime.
        status = SAnime.UNKNOWN
    }

    // Caché de sesión: sección (Activos/Finalizados) por URL de proyecto.
    private val statusCache = mutableMapOf<String, Int>()

    private fun rememberStatuses(document: Document) {
        val sections = document.select("div.entry > div")
        listOf(SECTION_ONGOING to SAnime.ONGOING, SECTION_COMPLETED to SAnime.COMPLETED).forEach { (sectionName, status) ->
            val header = sections.firstOrNull { it.selectFirst("h2")?.text()?.trim()?.startsWith(sectionName) == true }
            val container = header?.nextElementSibling() ?: return@forEach
            container.select(seriesCardSelector()).forEach { element ->
                val href = element.selectFirst("a[href]")?.attr("href")?.removePrefix(baseUrl)
                if (!href.isNullOrBlank()) statusCache[href] = status
            }
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    // ============================== Episode list ==============================
    // Los episodios llegan por AJAX (JSON con HTML embebido), no en el HTML de la página.
    // Se sobreescribe getEpisodeList entero; el resto de hooks quedan inutilizados.
    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun isVideoFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    // "Release card" = 1 tarjeta del acordeón AJAX; puede ser 1 episodio suelto o un batch
    // con uno o más enlaces mediafire (carpetas o archivos).
    private data class ReleaseCard(val label: String, val mediafireHrefs: List<String>)

    // Caché de sesión por release_id, así getAnimeDetails y getEpisodeList no repiten la
    // misma petición AJAX.
    private val releaseCardsCache = mutableMapOf<String, List<ReleaseCard>>()

    private fun fetchReleaseCards(releaseId: String, refererUrl: String): List<ReleaseCard> {
        releaseCardsCache[releaseId]?.let { return it }

        val body = FormBody.Builder()
            .add("action", "gakuensai_load_release_downloads")
            .add("release_id", releaseId)
            .build()
        // El sitio devuelve HTTP 403 si la petición no trae Referer ni X-Requested-With,
        // como las manda un navegador real. OkHttp no los añade por su cuenta.
        val ajaxHeaders = headers.newBuilder()
            .set("Referer", refererUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Origin", baseUrl)
            .build()
        val request = Request.Builder().url(ajaxUrl).post(body).headers(ajaxHeaders).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(name, "Fallo de red al pedir release_id=$releaseId", e)
            return emptyList()
        }

        if (!response.isSuccessful) {
            Log.e(name, "AJAX release_id=$releaseId devolvió HTTP ${response.code}")
            response.close()
            return emptyList()
        }

        val bodyString = response.body.string()
        val json = try {
            JSONObject(bodyString)
        } catch (e: Exception) {
            Log.e(name, "Respuesta AJAX release_id=$releaseId no es JSON válido: ${bodyString.take(300)}", e)
            return emptyList()
        }

        if (!json.optBoolean("success", false)) {
            Log.e(name, "AJAX release_id=$releaseId respondió success=false: $bodyString")
            return emptyList()
        }

        val html = json.optJSONObject("data")?.optString("html").orEmpty()
        if (html.isBlank()) {
            Log.e(name, "AJAX release_id=$releaseId respondió sin html en data")
            return emptyList()
        }

        val cards = Jsoup.parse(html).select("div.gakuensai-release-card").map { card ->
            val label = card.selectFirst("h5")?.text()?.trim().orEmpty()
            val mediafireHrefs = card.select("a[href*=mediafire.com]").map { it.attr("href") }
            ReleaseCard(label, mediafireHrefs)
        }
        releaseCardsCache[releaseId] = cards
        return cards
    }

    // Marca si la última consulta de un anime resultó ser solo-torrent (sin ningún link de
    // MediaFire), para que getAnimeDetails anteponga el aviso a la descripción.
    private val torrentOnlyCache = mutableMapOf<String, Boolean>()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        if (statusCache.isEmpty()) {
            rememberStatuses(
                runCatching { client.newCall(GET(projectsUrl, headers)).execute().asJsoup() }.getOrElse { document },
            )
        }

        val releaseIds = document.select("button.accordion-project-button[data-release-id]")
            .mapNotNull { it.attr("data-release-id").ifBlank { null } }
        if (releaseIds.isEmpty()) {
            torrentOnlyCache[anime.url] = false
            return emptyList()
        }

        val cardsByRelease = releaseIds.map { it to fetchReleaseCards(it, "$baseUrl${anime.url}") }
        val allQueriesFailed = cardsByRelease.all { (_, cards) -> cards.isEmpty() }
        val allCards = cardsByRelease.flatMap { (_, cards) -> cards }
        val allMediafireHrefs = allCards.flatMap { it.mediafireHrefs }

        if (allQueriesFailed) {
            // Fallo de red/servidor (ver Logcat). No se cachea nada ni se marca torrent-only
            // para poder reintentar en la próxima consulta.
            return emptyList()
        }

        if (allMediafireHrefs.isEmpty()) {
            torrentOnlyCache[anime.url] = true
            return emptyList()
        }
        torrentOnlyCache[anime.url] = false

        val hasFolder = allMediafireHrefs.any { it.contains("/folder/") }

        if (hasFolder) {
            // Prioridad carpetas: se ignoran los archivos sueltos y se expanden todas las
            // carpetas, uniendo su contenido de video (una serie puede repartir sus
            // episodios en varias carpetas).
            val videoFiles = allMediafireHrefs.filter { it.contains("/folder/") }
                .mapNotNull { (extractor.parseLink(it) as? MediaFireLink.Folder)?.key }
                .flatMap { key -> runCatching { extractor.listFiles(key) }.getOrElse { emptyList() } }
                .filter { isVideoFile(it.filename) }

            return FilenameUtils.sortBySeasonAndEpisodeDescending(videoFiles, { it.filename }, showFilename)
                .map { seasoned ->
                    SEpisode.create().apply {
                        url = "file::${seasoned.item.quickkey}::${seasoned.item.filename}"
                        name = seasoned.display.name
                        episode_number = seasoned.display.episodeNumber
                        date_upload = 0L
                    }
                }
        }

        // Sin carpetas: cada card es 1 episodio suelto con su propio link /file/.
        data class LooseFile(val quickkey: String, val filename: String)

        val looseFiles = allCards.mapNotNull { card ->
            val fileHref = card.mediafireHrefs.firstOrNull { it.contains("/file/") } ?: return@mapNotNull null
            val link = extractor.parseLink(fileHref) as? MediaFireLink.File ?: return@mapNotNull null
            val filename = link.filename ?: extractor.resolveFileName(link.quickkey, hint = null)
            LooseFile(link.quickkey, filename)
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

    // ============================== Video list ==============================
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("::")
        if (parts.size < 3) return emptyList()
        val quickkey = parts[1]
        val filename = parts[2]
        return extractor.videoFromFile(quickkey, filename)
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
