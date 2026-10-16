package eu.kanade.tachiyomi.animeextension.all.nyaatorrent

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.cancellation.CancellationException

class NyaaTorrent(extName: String, private val extURL: String, private val extId: Int) :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = extName

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, extURL)?.trim().takeIf { it?.isNotEmpty() ?: false } ?: extURL
    }

    override val lang = "all"

    private val preferences by getPreferencesLazy()

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Shared ===============================
    private val animeSelector = "table.torrent-list tbody tr"
    private val animeNextPageSelector = "ul.pagination a[rel='next']"
    private val videoExtensions = setOf("mkv", "mp4", "avi", "ogm", "wmv", "ts", "m2ts", "flv")

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("td:nth-child(2) a").attr("href"))
        anime.title = element.select("td:nth-child(2) a:not(.comments)").attr("title")
        return anime
    }

    // ======================== Resolution filtering =========================
    // Detecta menciones de resolución en un título o nombre de archivo.
    // Devuelve el set de resoluciones detectadas usando las claves internas
    // ("4k", "1080p", "720p", "480p") sin importar la variante textual usada
    // (ej. "2160p" y "4K" mapean ambos a "4k").
    private val resolutionRegexes: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(2160p|4k|uhd)\b""") to "4k",
        Regex("""(?i)\b1080p\b""") to "1080p",
        Regex("""(?i)\b720p\b""") to "720p",
        Regex("""(?i)\b480p\b""") to "480p",
    )

    private fun detectResolutions(text: String): Set<String> = resolutionRegexes.mapNotNullTo(mutableSetOf()) { (regex, key) ->
        key.takeIf { regex.containsMatchIn(text) }
    }

    private fun ignoredResolutions(): Set<String> = preferences.getStringSet(PREF_IGNORED_RES_KEY, emptySet()) ?: emptySet()

    // Un torrent se oculta por completo solo si TODAS las resoluciones que
    // menciona su título están ignoradas. Si el título es mixto (ej. batch
    // "1080p+720p") y solo una de las dos está ignorada, el torrent se
    // conserva: el filtrado fino de esa resolución concreta ocurre luego a
    // nivel de archivo/episodio dentro del torrent.
    private fun isAnimeFullyIgnored(title: String): Boolean {
        val ignored = ignoredResolutions()
        if (ignored.isEmpty()) return false
        val detected = detectResolutions(title)
        if (detected.isEmpty()) return false
        return detected.all { it in ignored }
    }

    // Nota: se probó también un filtro a nivel de archivo/episodio individual
    // (útil para batches mixtos con varias resoluciones), pero el SDK
    // (SAnime) no expone si el anime ya está en la biblioteca, así que no es
    // posible aplicar ese filtro solo a descubrimiento sin arriesgar afectar
    // también las actualizaciones de anime ya guardado. Por eso el filtrado
    // se queda únicamente a nivel de torrent (ver isAnimeFullyIgnored).

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page&s=seeders&o=desc")
    }

    override fun popularAnimeSelector(): String = animeSelector
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)
    override fun popularAnimeNextPageSelector(): String = animeNextPageSelector

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = super.popularAnimeParse(response)
        val filtered = page.animes.filterNot { isAnimeFullyIgnored(it.title) }
        return AnimesPage(filtered, page.hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page")
    }

    override fun latestUpdatesSelector(): String = animeSelector
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = animeNextPageSelector

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = super.latestUpdatesParse(response)
        val filtered = page.animes.filterNot { isAnimeFullyIgnored(it.title) }
        return AnimesPage(filtered, page.hasNextPage)
    }

    // ====================== Título del sintético (group) ======================
    // El título es lo que Aniyomi usa para buscar en los trackers, así que debe ser el nombre de la
    // serie y nada más: sin etiquetas de grupo, resolución, códec ni operadores de búsqueda.
    // 1) Se toma del primer resultado de la búsqueda (el nombre real de la publicación).
    // 2) Solo se acepta si contiene todas las palabras de la búsqueda; si no (o si la petición
    //    falla), se usa el texto de la búsqueda ya limpio.
    private val queryExclusionRegex = Regex("""(?:^|\s)-(?:"[^"]*"|\S+)""")
    private val queryBracketRegex = Regex("""\[[^\]]*]""")
    private val torrentTagRegex = Regex("""\[[^\]]*]|\{[^}]*\}|\([^)]*\)""")
    private val nonWordRegex = Regex("""[^\p{L}\p{N}]+""")

    // Grupos conocidos: solo se descartan si van al principio de la búsqueda.
    private val knownGroupNames = setOf("erai-raws", "subsplease", "judas", "ember", "toonshub", "yameii")

    // Palabras técnicas que no forman parte del nombre de la serie.
    private val queryNoiseWords = setOf(
        "480p", "540p", "576p", "720p", "1080p", "1440p", "2160p", "4k", "uhd",
        "avc", "x264", "h264", "h.264", "hevc", "x265", "h265", "h.265", "av1", "10bit", "8bit",
        "web-dl", "webdl", "webrip", "bluray", "blu-ray", "bdrip",
        "aac", "flac", "eac3", "ac3", "opus",
        "multisub", "multisubs", "multi-sub", "multi-subs", "eng-subs", "dual-audio", "batch",
    )

    // Texto de la búsqueda sin exclusiones (-hevc, -"season 2"), etiquetas [..], comillas,
    // grupo inicial ni palabras técnicas.
    private fun cleanGroupQuery(query: String): String {
        val words = query
            .replace(queryExclusionRegex, " ")
            .replace(queryBracketRegex, " ")
            .replace("\"", " ")
            .split(whitespaceRegex)
            .filter { it.isNotEmpty() }
        val first = words.firstOrNull()?.lowercase()
        val withoutGroup = if (first != null && first in knownGroupNames) words.drop(1) else words
        return withoutGroup.filterNot { it.lowercase() in queryNoiseWords }.joinToString(" ")
    }

    // Nombre de la serie a partir del título de un torrent:
    // "[Erai-raws] Kusuriya no Hitorigoto 3rd Season - 01v2 [1080p ...]" -> "Kusuriya no Hitorigoto 3rd Season".
    private fun seriesTitleFromTorrentTitle(torrentTitle: String): String {
        val withoutTags = torrentTitle.replace(torrentTagRegex, " ").replace(whitespaceRegex, " ").trim()
        var series = withoutTags
        for (pattern in releasePatterns) {
            val match = pattern.find(withoutTags) ?: continue
            series = withoutTags.substring(0, match.range.first)
            break
        }
        return series.trim().trimEnd('-', ' ').trim()
    }

    private fun titleMatchesQuery(title: String, query: String): Boolean {
        val haystack = title.lowercase()
        return query.lowercase().split(nonWordRegex).filter { it.isNotEmpty() }.all { haystack.contains(it) }
    }

    private fun chooseGroupTitle(query: String, firstResultTitle: String?): String {
        val fromQuery = cleanGroupQuery(query).ifEmpty { query.trim() }
        val fromResults = firstResultTitle?.let { seriesTitleFromTorrentTitle(it) }.orEmpty()
        return if (fromResults.isNotEmpty() && titleMatchesQuery(fromResults, fromQuery)) fromResults else fromQuery
    }

    private suspend fun groupTitle(query: String, encodedQuery: String, categoryParam: String): String = try {
        val searchUrl = "$baseUrl/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=1"
        val document = client.newCall(GET(searchUrl)).awaitSuccess().use { it.asJsoup() }
        val firstTitle = document.selectFirst("$animeSelector td:nth-child(2) a:not(.comments)")?.attr("title")
        chooseGroupTitle(query, firstTitle)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        chooseGroupTitle(query, null)
    }

    // ============================ Búsqueda por ID ============================
    // ID suelto: solo dígitos, 6 o más (para no chocar con títulos numéricos como "86" o "100").
    private val bareIdRegex = Regex("""^\d{6,}$""")

    // Enlace compartido de Nyaa: https://nyaa.si/view/2170358
    private val viewUrlRegex = Regex("""^https?://[^/\s]+/+view/(\d+)""")

    private fun extractTorrentId(query: String): String? {
        val text = query.trim()
        if (bareIdRegex.matches(text)) return text
        return viewUrlRegex.find(text)?.groupValues?.get(1)
    }

    // ================== Group: versiones de un capítulo (01 / 01v2) ==================
    // Modos de agrupación (solo aplican a group):
    //  - GROUP_MODE_ALL (0): comportamiento original, se cargan todos los torrents.
    //  - GROUP_MODE_LATEST (1): si un capítulo tiene varias versiones (01, 01v2...), se queda la más alta.
    //  - GROUP_MODE_ORIGINAL (2): se queda la versión original (la más baja) e ignora las nuevas.
    // Dos torrents son "el mismo capítulo" si tienen el mismo número de episodio y el resto del
    // título es idéntico (sin el CRC). Si cambia cualquier otra cosa (resolución, audio, grupo) se
    // conservan ambos. Los torrents sin número de episodio claro (batches) nunca se descartan.
    private data class GroupRequest(val query: String, val mode: Int)

    private data class ReleaseInfo(val episode: Float, val version: Int, val key: String)

    private val crcRegex = Regex("""\[[0-9A-Fa-f]{8}]""")
    private val whitespaceRegex = Regex("""\s+""")
    private val releasePatterns = listOf(
        // "Título - 01v2 [1080p ...]" / "Título - 12.5 (1080p)"
        Regex("""\s-\s(\d{1,4}(?:\.\d)?)(?:[vV](\d+))?(?=\s|\[|\(|$)"""),
        // "Título S01E05v2"
        Regex("""\b[Ss]\d{1,2}[Ee](\d{1,4})(?:[vV](\d+))?(?![A-Za-z0-9])"""),
    )

    // Un prefijo numerado (group1:/group2:) manda sobre el panel de filtros.
    // "group:" y la casilla "Agrupar resultados" usan el modo elegido en "Ajustes de agrupación".
    private fun parseGroupRequest(query: String, filters: AnimeFilterList): GroupRequest? {
        val panelMode = filters.filterIsInstance<GroupModeFilter>().firstOrNull()?.state ?: GROUP_MODE_ALL
        val shouldGroup = filters.filterIsInstance<GroupFilter>().firstOrNull()?.state == true && query.isNotEmpty()
        return when {
            query.startsWith(PREFIX_GROUP_LATEST) ->
                GroupRequest(query.removePrefix(PREFIX_GROUP_LATEST).trim(), GROUP_MODE_LATEST)
            query.startsWith(PREFIX_GROUP_ORIGINAL) ->
                GroupRequest(query.removePrefix(PREFIX_GROUP_ORIGINAL).trim(), GROUP_MODE_ORIGINAL)
            query.startsWith(PREFIX_GROUP) ->
                GroupRequest(query.removePrefix(PREFIX_GROUP).trim(), panelMode)
            shouldGroup -> GroupRequest(query, panelMode)
            else -> null
        }
    }

    private fun groupModeFromUrl(url: String): Int = url.substringAfter("gmode=", "").substringBefore("&").toIntOrNull()
        ?.takeIf { it in GROUP_MODE_LATEST..GROUP_MODE_ORIGINAL }
        ?: GROUP_MODE_ALL

    private fun groupModeLabel(mode: Int): String? = when (mode) {
        GROUP_MODE_LATEST -> "Modo: priorizar la versión más alta de cada capítulo (v2 sobre v1)."
        GROUP_MODE_ORIGINAL -> "Modo: ignorar nuevas versiones de cada capítulo (se queda la original)."
        else -> null
    }

    // Extrae (número de episodio, versión, clave del resto del título) de un título de torrent.
    // La clave es el título sin el CRC y con el número+versión sustituidos por un marcador.
    private fun parseReleaseInfo(title: String): ReleaseInfo? {
        for (pattern in releasePatterns) {
            val match = pattern.find(title) ?: continue
            val episode = match.groupValues[1].toFloatOrNull() ?: continue
            val version = match.groupValues[2].toIntOrNull() ?: 1
            val key = title.replaceRange(match.range, " # ")
                .replace(crcRegex, "")
                .lowercase()
                .replace(whitespaceRegex, " ")
                .trim()
            return ReleaseInfo(episode, version, key)
        }
        return null
    }

    private fun <T> applyVersionMode(items: List<T>, mode: Int, titleOf: (T) -> String): List<T> {
        if (mode == GROUP_MODE_ALL) return items
        val infos = items.map { parseReleaseInfo(titleOf(it)) }
        val chosenVersion = HashMap<Pair<String, Float>, Int>()
        for (info in infos) {
            if (info == null) continue
            val id = info.key to info.episode
            val current = chosenVersion[id]
            chosenVersion[id] = when {
                current == null -> info.version
                mode == GROUP_MODE_LATEST -> maxOf(current, info.version)
                else -> minOf(current, info.version)
            }
        }
        return items.filterIndexed { index, _ ->
            val info = infos[index] ?: return@filterIndexed true
            chosenVersion[info.key to info.episode] == info.version
        }
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val groupRequest = parseGroupRequest(query, filters)
        val looseTorrentId = extractTorrentId(query)
        return when {
            query.startsWith(PREFIX_SEARCH) -> {
                val id = query.removePrefix(PREFIX_SEARCH).trim()
                client.newCall(GET("$baseUrl/view/$id"))
                    .awaitSuccess()
                    .use { searchAnimeByIdParse(it, id) }
            }
            looseTorrentId != null -> {
                // ID suelto (2170358) o enlace de Nyaa (https://nyaa.si/view/2170358).
                // Si ese torrent no existe, se cae a la búsqueda normal con el mismo texto.
                val response = client.newCall(GET("$baseUrl/view/$looseTorrentId")).await()
                if (response.isSuccessful) {
                    searchAnimeByIdParse(response, looseTorrentId)
                } else {
                    response.close()
                    super.getSearchAnime(page, query, filters)
                }
            }
            query.startsWith(PREFIX_DBATCH) -> {
                val parts = query.removePrefix(PREFIX_DBATCH).split(":")
                val id = parts[0]
                val rangeSpec = parts.getOrNull(1)
                client.newCall(GET("$baseUrl/view/$id"))
                    .awaitSuccess()
                    .use { response -> searchAnimeByDbatchParse(response, id, rangeSpec) }
            }
            groupRequest != null -> {
                val realQuery = groupRequest.query
                val encodedQuery = URLEncoder.encode(realQuery, "UTF-8")
                val categoryParam = if (extId == 1) "1_0" else "1_1"
                // gmode solo viaja en la URL cuando no es "agrupar todo": así las URLs de los
                // sintéticos ya guardados (sin gmode) siguen idénticas y se comportan igual.
                val modeParam = if (groupRequest.mode != GROUP_MODE_ALL) "&gmode=${groupRequest.mode}" else ""
                val modeLabel = groupModeLabel(groupRequest.mode)?.let { "\n\n$it" }.orEmpty()
                val seriesTitle = groupTitle(realQuery, encodedQuery, categoryParam)
                val anime = SAnime.create().apply {
                    title = seriesTitle
                    url = "/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=1&grouped=1$modeParam"
                    thumbnail_url = NyaaCovers.groupUrl(groupRequest.mode)
                    description = "Búsqueda agrupada: \"$realQuery\"\n\nTodos los episodios de todos los torrents que coincidan con esta búsqueda.$modeLabel"
                }
                AnimesPage(listOf(anime), false)
            }
            else -> super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response, torrentId: String): AnimesPage {
        val document = response.use { it.asJsoup() }
        // animeDetailsParse no rellena url ni title, y en Aniyomi son obligatorios
        // (sin ellos el resultado falla con "lateinit property url has not been initialized").
        val details = animeDetailsParse(document).apply {
            url = "/view/$torrentId"
            title = document.select("h3.panel-title").text().ifEmpty { "Torrent $torrentId" }
        }
        return AnimesPage(listOf(details), false)
    }

    private fun parseRangeSpec(rangeSpec: String, videoFiles: List<VideoFile>): List<VideoFile> {
        return try {
            val segments = rangeSpec.split("--")
            val rangePart = segments.first()
            val excluded = segments.drop(1).mapNotNull { it.toIntOrNull() }.toSet()
            val rangeBounds = rangePart.split("-")
            val from = rangeBounds.getOrNull(0)?.toIntOrNull() ?: return emptyList()
            val to = rangeBounds.getOrNull(1)?.toIntOrNull() ?: return emptyList()
            videoFiles.filter { it.index in from..to && it.index !in excluded }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchAnimeByDbatchParse(
        response: Response,
        torrentId: String,
        rangeSpec: String? = null,
    ): AnimesPage {
        val document = response.asJsoup()
        val magnet = document.selectFirst("a[href^='magnet:']")?.attr("href").orEmpty()
        if (magnet.isEmpty()) return AnimesPage(emptyList(), false)

        val videoFiles = extractVideoFiles(document)
        if (videoFiles.isEmpty()) return AnimesPage(emptyList(), false)

        val torrentTitle = document.select("h3.panel-title").text()

        if (rangeSpec != null) {
            val filteredFiles = parseRangeSpec(rangeSpec, videoFiles)
            if (filteredFiles.isEmpty()) return AnimesPage(emptyList(), false)
            val indices = filteredFiles.joinToString(",") { it.index.toString() }
            val anime = SAnime.create().apply {
                title = torrentTitle
                url = "/view/$torrentId?dbatch=$indices&range=$rangeSpec"
                thumbnail_url = NyaaCovers.batchUrl()
                description = "Parte de: $torrentTitle\nRango: $rangeSpec"
            }
            return AnimesPage(listOf(anime), false)
        }

        data class GroupKey(val parent: String, val sub: String)
        val groups = videoFiles.groupBy { GroupKey(it.parentFolder, it.subFolder) }

        val animes = if (groups.size == 1) {
            videoFiles.map { file ->
                val fileName = file.element.ownText().trim().substringBeforeLast(".")
                SAnime.create().apply {
                    title = "[${file.index}] $fileName"
                    url = "/view/$torrentId?dbatch=${file.index}"
                    thumbnail_url = NyaaCovers.batchUrl()
                    description = "Parte de: $torrentTitle"
                }
            }
        } else {
            groups.map { (key, files) ->
                val indices = files.joinToString(",") { it.index.toString() }
                val label = when {
                    key.parent.isNotEmpty() && key.sub.isNotEmpty() ->
                        "${key.parent} / ${key.sub}"
                    key.parent.isNotEmpty() -> key.parent
                    key.sub.isNotEmpty() -> key.sub
                    else -> torrentTitle
                }
                SAnime.create().apply {
                    title = label
                    url = "/view/$torrentId?dbatch=$indices"
                    thumbnail_url = NyaaCovers.batchUrl()
                    description = "Parte de: $torrentTitle"
                }
            }
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val groupFilter = filters.filterIsInstance<GroupFilter>().firstOrNull()
        val shouldGroup = groupFilter?.state == true && query.isNotEmpty()
        val cleanQuery = when {
            shouldGroup -> query
            query.startsWith(PREFIX_GROUP) -> query.removePrefix(PREFIX_GROUP)
            else -> query
        }
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        var sortParam = "id"
        var sortDirection = "desc"
        var filterParam = "0"
        var categoryParam = if (extId == 1) "1_0" else "1_1"
        filters.forEach { filter ->
            when (filter) {
                is SortList -> {
                    sortParam = availableSorts[filter.state?.index ?: 0].id
                    sortDirection = if (filter.state?.ascending == true) "asc" else "desc"
                }
                is FilterList -> filterParam = availableFilters[filter.state].id
                is CategoriesList -> categoryParam = availableCategories[filter.state].id
                else -> {}
            }
        }
        val groupedParam = if (shouldGroup) "&grouped=1" else ""
        return GET("$baseUrl/?f=$filterParam&c=$categoryParam&s=$sortParam&o=$sortDirection&q=$encodedQuery&p=$page$groupedParam")
    }

    override fun searchAnimeSelector() = animeSelector
    override fun searchAnimeFromElement(element: Element) = animeFromElement(element)
    override fun searchAnimeNextPageSelector() = animeNextPageSelector

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = super.searchAnimeParse(response)
        val filtered = page.animes.filterNot { isAnimeFullyIgnored(it.title) }
        return AnimesPage(filtered, page.hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val location = document.location()
        val isGrouped = location.contains("grouped=1")
        val isDbatch = location.contains("dbatch=")

        if (isGrouped) {
            val groupMode = groupModeFromUrl(location)
            val rows = applyVersionMode(document.select("table.torrent-list tbody tr"), groupMode) {
                it.select("td:nth-child(2) a:not(.comments)").attr("title")
            }
            val totalSeeders = rows.sumOf {
                it.select("td:nth-child(6)").text().trim().toIntOrNull() ?: 0
            }
            val totalLeechers = rows.sumOf {
                it.select("td:nth-child(7)").text().trim().toIntOrNull() ?: 0
            }
            val totalSize = sumSizeTexts(
                rows.mapNotNull { it.select("td:nth-child(4)").text().trim().takeIf { s -> s.isNotEmpty() } },
            )
            anime.genre = "↑ Seeders: $totalSeeders, ↓ Leechers: $totalLeechers, Tamaño: $totalSize, Group"
            anime.thumbnail_url = NyaaCovers.groupUrl(groupMode)

            // Extraer query de la URL para mostrarlo en la descripción
            val query = location.substringAfter("&q=").substringBefore("&")
                .replace("+", " ").let { URLDecoder.decode(it, "UTF-8") }
            val modePrefix = when (groupMode) {
                GROUP_MODE_LATEST -> PREFIX_GROUP_LATEST
                GROUP_MODE_ORIGINAL -> PREFIX_GROUP_ORIGINAL
                else -> PREFIX_GROUP
            }
            val modeLabel = groupModeLabel(groupMode)?.let { "\n\n$it" }.orEmpty()
            anime.description = "$modePrefix$query$modeLabel"
        } else {
            val torrentId = location
                .substringAfter("/view/")
                .substringBefore("/")
                .substringBefore("#")
                .substringBefore("?")

            val filesize = document.select("div.panel-body > div:nth-child(4) > div:nth-child(2)").text()
            val seedersText = document.select("div.panel-body > div:nth-child(2) > div:nth-child(4)").text()
            val leechersText = document.select("div.panel-body > div:nth-child(3) > div:nth-child(4) > span").text()
            anime.genre = "↑ Seeders: $seedersText, ↓ Leechers: $leechersText, Tamaño: $filesize"

            if (isDbatch) {
                val queryParams = location.substringAfter("?", "")
                    .split("&")
                    .associate { param ->
                        val key = param.substringBefore("=")
                        val value = param.substringAfter("=", "")
                        key to value
                    }
                val rangeParam = queryParams["range"] ?: ""
                val prefix = if (rangeParam.isNotEmpty()) {
                    "dbatch:$torrentId:$rangeParam"
                } else {
                    "dbatch:$torrentId"
                }
                anime.description = prefix
                anime.genre = "${anime.genre}, Batch"
                anime.thumbnail_url = NyaaCovers.batchUrl()
            } else {
                val desc = document.select("#torrent-description").text()
                anime.description = buildString {
                    if (torrentId.isNotEmpty()) append("ID: $torrentId\n\n")
                    append(desc)
                }
                anime.author = document.select("a[title=user]").text()
                val imageRegex = Regex(
                    """\b(http|https)?:\S+(?:jpg|png|gif|bmp|webp|tiff|jpeg)(?!\.html)\b""",
                    RegexOption.IGNORE_CASE,
                )
                imageRegex.find(desc)?.let { anime.thumbnail_url = it.value }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================
    private fun extractSeedersLeechers(document: Document): String {
        val seeders = document.select("div.panel-body > div:nth-child(2) > div:nth-child(4)").text()
        val leechers = document.select("div.panel-body > div:nth-child(3) > div:nth-child(4) > span").text()
        return "↑$seeders ↓$leechers"
    }

    // Convierte tamaños tipo "364.9 MiB" o "1.4 GiB" a MiB, suma, y formatea.
    private fun sumSizeTexts(sizes: List<String>): String {
        val totalMiB = sizes.sumOf { raw ->
            val parts = raw.trim().split(" ")
            val value = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            when (parts.getOrNull(1)?.uppercase()) {
                "GIB" -> value * 1024.0
                "MIB" -> value
                "KIB" -> value / 1024.0
                else -> 0.0
            }
        }
        return if (totalMiB >= 1024.0) {
            "%.2f GiB".format(totalMiB / 1024.0)
        } else {
            "%.1f MiB".format(totalMiB)
        }
    }

    override fun episodeListSelector() = "div.torrent-file-list ul li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return when {
            url.contains("grouped=1") -> episodeListGroupedParse(url)
            url.contains("dbatch=") -> episodeListDbatchParse(response)
            else -> episodeListSingleParseHtml(response)
        }
    }

    private fun episodeListDbatchParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val magnet = document.selectFirst("a[href^='magnet:']")?.attr("href").orEmpty()
        if (magnet.isEmpty()) return emptyList()

        val torrentId = response.request.url.toString()
            .substringAfter("/view/")
            .substringBefore("/")
            .substringBefore("#")
            .substringBefore("?")

        val rawDbatch = response.request.url.queryParameter("dbatch") ?: return emptyList()
        val allowedIndices = rawDbatch.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

        val torrentDate = parseDate(
            document.select("div.panel-body > div:nth-child(1) > div:nth-child(4)").text(),
        )
        val useFilename = preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)
        val videoFiles = extractVideoFiles(document).filter { it.index in allowedIndices }

        if (videoFiles.isEmpty()) return emptyList()

        // allowedIndices viene en términos de índice-HTML (de searchAnimeByDbatchParse),
        // así que el filtro de arriba es correcto. Solo el index en la url
        // necesita resolverse al índice real del .torrent.
        val realIndexMap = if (torrentId.isNotEmpty()) {
            resolveRealIndices(videoFiles, torrentId)
        } else {
            emptyMap()
        }

        val unknownFolderMap = mutableMapOf<String, Int>()
        var partCounter = 1
        videoFiles
            .map { it.parentFolder }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { folder ->
                if (extractShortFolderName(folder).isEmpty()) {
                    unknownFolderMap[folder] = partCounter++
                }
            }

        val episodeCounterByFolder = mutableMapOf<String, Int>()
        val extraCounterByFolder = mutableMapOf<String, Int>()

        val episodes = videoFiles.mapIndexed { localIndex, file ->
            val folderKey = file.parentFolder
            val isExtra = file.subFolder.isNotEmpty()
            val counter = if (isExtra) extraCounterByFolder else episodeCounterByFolder
            val current = (counter[folderKey] ?: 0) + 1
            counter[folderKey] = current

            val fileName = file.element.ownText().trim()
            val fileSize = file.element.select("span.file-size").text()
            val streamIndex = realIndexMap[file.index] ?: file.index

            SEpisode.create().apply {
                name = if (useFilename) {
                    fileName
                } else {
                    if (isExtra) "Extra $current" else "Episodio $current"
                }
                url = "$magnet&index=$streamIndex"
                episode_number = (localIndex + 1).toFloat()
                scanlator = fileSize
                date_upload = torrentDate
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun episodeListGroupedParse(groupedUrl: String): List<SEpisode> {
        val encodedQuery = groupedUrl.substringAfter("&q=").substringBefore("&")
        val categoryParam = groupedUrl.substringAfter("&c=").substringBefore("&")

        // Torrents encontrados (título, URL de detalle y seeders/leechers de la fila de la lista).
        // La regla de versiones se aplica al final, sobre todas las páginas juntas, porque 01 y
        // 01v2 pueden caer en páginas distintas.
        val candidates = mutableListOf<GroupTorrent>()
        var page = 1
        var hasNextPage = true
        while (hasNextPage) {
            val searchUrl = "$baseUrl/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=$page"
            val searchDoc = client.newCall(GET(searchUrl)).execute().use { it.asJsoup() }
            searchDoc.select(animeSelector).forEach { row ->
                val a = row.selectFirst("td:nth-child(2) a:not(.comments)") ?: return@forEach
                val href = a.attr("href")
                val title = a.attr("title")
                if (href.isNotEmpty() && !isAnimeFullyIgnored(title)) {
                    val seeders = row.select("td:nth-child(6)").text()
                    val leechers = row.select("td:nth-child(7)").text()
                    val stats = if (seeders.isNotEmpty() && leechers.isNotEmpty()) "↑$seeders ↓$leechers" else null
                    candidates.add(GroupTorrent(title, "$baseUrl$href", stats))
                }
            }
            hasNextPage = searchDoc.selectFirst(animeNextPageSelector) != null
            page++
            if (page > 10) break
        }

        val groupMode = groupModeFromUrl(groupedUrl)
        val torrents = applyVersionMode(candidates, groupMode) { it.title }

        val allEpisodes = mutableListOf<SEpisode>()
        for (torrent in torrents) {
            try {
                allEpisodes.addAll(groupTorrentEpisodes(torrent))
            } catch (e: Exception) {
                // página inaccesible, se omite
            }
        }

        // En group los torrents sueltos llegan con su número real de episodio (fuera de group llevan
        // 1f para evitar "missing items" al abrirlos individualmente; con todos en 1f la playlist del
        // reproductor se rompería). Los que no se pudieron leer (NO_NUMBER) toman su posición.
        allEpisodes.forEachIndexed { index, ep ->
            if (ep.episode_number == NO_NUMBER) ep.episode_number = (allEpisodes.size - index).toFloat()
        }

        return allEpisodes.sortedByDescending { it.episode_number }
    }

    // ============ Nombres y números de episodio (librería filenameutils) ============
    // filenameutils lee el número de episodio, la categoría (OP, ED, OVA, Movie, Special...), la
    // versión (v2) y el nombre a mostrar. Lo único que se hace antes de pasarle el nombre es
    // descartar el título del episodio: en "Show - 008 - Título.mkv" o "Show - S01E08 - Título.mkv"
    // lo que va tras el número puede traer otros números ("The 300-Limit", "100 Meters") que la
    // librería tomaría por el número de episodio.
    private val episodeTitleCutRegex = Regex("""(?i)\s-\s\d{1,4}(?:\.\d)?(?:v\d+)?(?=\s-\s)|\bs\d{1,2}e\d{1,4}(?:v\d+)?(?=\s*-\s)""")

    // Tras el recorte se añade una extensión: la librería toma el último punto por la extensión y,
    // sin ella, "Show - 05.5" perdería el ".5".
    private fun normalizeFileName(fileName: String): String = episodeTitleCutRegex.find(fileName)
        ?.let { fileName.substring(0, it.range.last + 1) + ".mkv" }
        ?: fileName

    private fun episodeDisplay(fileName: String): FilenameUtils.EpisodeDisplay = FilenameUtils.buildEpisodeDisplay(normalizeFileName(fileName), showFilename = false)

    // Número leído del nombre, o null si no se pudo leer.
    private fun readableNumber(display: FilenameUtils.EpisodeDisplay): Float? = display.episodeNumber.takeIf { it != NO_NUMBER }

    // "v2 · " solo para versiones corregidas, en la línea del scanlator: el nombre queda limpio.
    private fun versionPrefix(fileName: String): String = FilenameUtils.extractVersionNumber(fileName)?.takeIf { it >= 2 }?.let { "v$it · " }.orEmpty()

    // ============================== Folder-aware file extraction ==============================

    private data class VideoFile(
        val index: Int,
        val element: Element,
        val parentFolder: String,
        val subFolder: String,
    )

    private val folderKeywordRegex = Regex(
        """(season|part|cour|temporada|s)[\s._-]*(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractShortFolderName(rawName: String): String {
        val match = folderKeywordRegex.find(rawName) ?: return ""
        val keyword = match.groupValues[1].lowercase().let { kw ->
            when {
                kw == "s" || kw == "season" -> "Temporada"
                kw == "part" -> "Parte"
                kw == "cour" -> "Cour"
                kw == "temporada" -> "Temporada"
                else -> kw.replaceFirstChar { it.uppercase() }
            }
        }
        val number = match.groupValues[2].trimStart('0').ifEmpty { "1" }
        return "$keyword $number"
    }

    // ================== Caché de torrents ya abiertos ==================
    // El contenido de un torrent no cambia nunca: su infohash es el hash del contenido, así que si
    // algo cambia es otro torrent con otro ID (por eso existen las versiones v2/v3). Por eso la
    // página de un torrent ya abierto se guarda y, en los refrescos siguientes de un sintético,
    // solo se abren los torrents nuevos. Se guarda el HTML original (no los episodios ya
    // formateados) para que los cambios de nombre o de lógica de parseo se sigan aplicando; los
    // seeders/leechers se leen siempre frescos de la lista de resultados. El caché vive en
    // cacheDir: Android puede vaciarlo y, en ese caso, los torrents simplemente se vuelven a pedir.
    private data class GroupTorrent(val title: String, val url: String, val stats: String?)

    private val torrentCacheDir: File by lazy {
        File(applicationContext.cacheDir, "nyaatorrent_v$CACHE_VERSION").apply { mkdirs() }
    }

    private fun torrentIdFromUrl(url: String): String = url
        .substringAfter("/view/")
        .substringBefore("/")
        .substringBefore("#")
        .substringBefore("?")

    // Solo IDs numéricos: evita nombres de archivo raros si la URL no tiene el formato esperado.
    private fun cacheFile(torrentId: String, kind: String): File? = if (torrentId.isNotEmpty() && torrentId.all { it.isDigit() }) {
        File(torrentCacheDir, "$torrentId.$kind.gz")
    } else {
        null
    }

    private fun readCache(torrentId: String, kind: String): String? = try {
        cacheFile(torrentId, kind)?.takeIf { it.exists() }?.let { file ->
            file.setLastModified(System.currentTimeMillis()) // los más usados se conservan (LRU)
            GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(torrentId: String, kind: String, content: String) {
        try {
            val file = cacheFile(torrentId, kind) ?: return
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(content) }
            pruneCache()
        } catch (e: Exception) {
            // sin caché, el torrent se vuelve a pedir
        }
    }

    private fun pruneCache() {
        val files = torrentCacheDir.listFiles() ?: return
        if (files.size <= CACHE_MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - CACHE_MAX_FILES).forEach { it.delete() }
    }

    private fun readCachedRealOrder(torrentId: String): List<String>? = readCache(torrentId, "order")
        ?.split(CACHE_SEPARATOR)
        ?.takeIf { it.isNotEmpty() && it.first().isNotEmpty() }

    private fun writeCachedRealOrder(torrentId: String, order: List<String>) {
        if (order.isNotEmpty()) writeCache(torrentId, "order", order.joinToString(CACHE_SEPARATOR))
    }

    // Episodios de un torrent dentro de group: usa la página guardada si existe y, si no, la pide.
    private fun groupTorrentEpisodes(torrent: GroupTorrent): List<SEpisode> {
        val torrentId = torrentIdFromUrl(torrent.url)

        readCache(torrentId, "html")?.let { cachedHtml ->
            val cachedEpisodes = episodeListSingleParseDocument(
                Jsoup.parse(cachedHtml, torrent.url),
                torrentId,
                includeSeedersLeechers = true,
                liveStats = torrent.stats,
                forGroup = true,
            )
            if (cachedEpisodes.isNotEmpty()) return cachedEpisodes
        }

        val html = client.newCall(GET(torrent.url)).execute().use { it.body.string() }
        val episodes = episodeListSingleParseDocument(
            Jsoup.parse(html, torrent.url),
            torrentId,
            includeSeedersLeechers = true,
            liveStats = torrent.stats,
            forGroup = true,
        )
        // Solo se guardan páginas válidas (con episodios): una página de error no debe quedar en caché.
        if (episodes.isNotEmpty()) writeCache(torrentId, "html", html)
        return episodes
    }

    // ===================== Real file order (bencoding) =====================
    // nyaa.si lista los archivos alfabéticamente en el HTML, pero el motor de
    // torrent-streaming necesita el índice real de info.files del .torrent.
    // Se descarga el .torrent vía HTTP plano y se decodifica el bencoding
    // manualmente para mapear nombre→índice real sin tocar el servicio torrent.

    private class BencodeDecoder(private val data: ByteArray) {
        var pos = 0

        fun decode(): Any = when (data[pos].toInt().toChar()) {
            'i' -> decodeInt()
            'l' -> decodeList()
            'd' -> decodeDict()
            else -> decodeString()
        }

        private fun decodeInt(): Long {
            pos++ // skip 'i'
            val end = indexOf('e'.code.toByte())
            val value = String(data, pos, end - pos, Charsets.US_ASCII).toLong()
            pos = end + 1
            return value
        }

        private fun decodeString(): ByteArray {
            val colon = indexOf(':'.code.toByte())
            val length = String(data, pos, colon - pos, Charsets.US_ASCII).toInt()
            val start = colon + 1
            val result = data.copyOfRange(start, start + length)
            pos = start + length
            return result
        }

        private fun decodeList(): List<Any> {
            pos++ // skip 'l'
            val result = mutableListOf<Any>()
            while (data[pos].toInt().toChar() != 'e') {
                result.add(decode())
            }
            pos++ // skip 'e'
            return result
        }

        private fun decodeDict(): Map<ByteArray, Any> {
            pos++ // skip 'd'
            val result = LinkedHashMap<ByteArray, Any>()
            while (data[pos].toInt().toChar() != 'e') {
                val key = decodeString()
                val value = decode()
                result[key] = value
            }
            pos++ // skip 'e'
            return result
        }

        private fun indexOf(target: Byte): Int {
            var i = pos
            while (data[i] != target) i++
            return i
        }
    }

    // Orden real de archivos del .torrent. Un torrent no cambia nunca, así que el resultado se
    // guarda en caché y el .torrent solo se descarga una vez.
    private fun fetchRealFileOrder(torrentId: String): List<String>? {
        readCachedRealOrder(torrentId)?.let { return it }
        val order = downloadRealFileOrder(torrentId) ?: return null
        writeCachedRealOrder(torrentId, order)
        return order
    }

    @Suppress("UNCHECKED_CAST")
    private fun downloadRealFileOrder(torrentId: String): List<String>? {
        return try {
            val torrentBytes = client.newCall(GET("$baseUrl/download/$torrentId.torrent"))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    response.body.bytes()
                }

            val decoded = BencodeDecoder(torrentBytes).decode() as? Map<ByteArray, Any> ?: return null
            val infoKey = decoded.keys.firstOrNull { it.toString(Charsets.UTF_8) == "info" } ?: return null
            val info = decoded[infoKey] as? Map<ByteArray, Any> ?: return null

            val filesKey = info.keys.firstOrNull { it.toString(Charsets.UTF_8) == "files" }
            if (filesKey != null) {
                val files = info[filesKey] as? List<Any> ?: return null
                files.mapNotNull { fileEntry ->
                    val fileDict = fileEntry as? Map<ByteArray, Any> ?: return@mapNotNull null
                    val pathKey = fileDict.keys.firstOrNull { it.toString(Charsets.UTF_8) == "path" }
                    val pathList = fileDict[pathKey] as? List<Any> ?: return@mapNotNull null
                    val pathParts = pathList.mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
                    pathParts.lastOrNull()
                }
            } else {
                val nameKey = info.keys.firstOrNull { it.toString(Charsets.UTF_8) == "name" } ?: return null
                val name = (info[nameKey] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
                listOf(name)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveRealIndices(videoFiles: List<VideoFile>, torrentId: String): Map<Int, Int> {
        val realOrder = fetchRealFileOrder(torrentId) ?: return emptyMap()

        // Archivos con nombre repetido en distintas carpetas (ej. "NCED01.mkv"
        // en Season 1/Extras y Season 2/Extras) se emparejan por ORDEN DE
        // OCURRENCIA: la N-ésima aparición en el HTML con la N-ésima en el
        // .torrent, evitando que indexOf() siempre devuelva la primera.
        val realOrderQueueByName = mutableMapOf<String, ArrayDeque<Int>>()
        realOrder.forEachIndexed { realIndex, name ->
            realOrderQueueByName.getOrPut(name) { ArrayDeque() }.addLast(realIndex)
        }

        val result = mutableMapOf<Int, Int>()
        for (file in videoFiles) {
            val fileName = file.element.ownText().trim()
            val queue = realOrderQueueByName[fileName] ?: continue
            val realIndex = queue.removeFirstOrNull() ?: continue
            result[file.index] = realIndex
        }
        return result
    }

    private fun extractVideoFiles(document: Document): List<VideoFile> {
        val result = mutableListOf<VideoFile>()
        var globalIndex = 0

        val rootUl = document.selectFirst("div.torrent-file-list > ul") ?: return result

        fun getChildUl(li: Element): Element? = li.children().firstOrNull { it.tagName() == "ul" }

        fun isRootFolder(li: Element): Boolean = getChildUl(li)?.hasAttr("data-show") == true

        fun processFile(li: Element, parent: String, sub: String) {
            val fileName = li.ownText().trim()
            val ext = fileName.substringAfterLast(".").lowercase()
            if (ext in videoExtensions) {
                result.add(VideoFile(globalIndex, li, parent, sub))
            }
            globalIndex++
        }

        fun processSubFolderChildren(ul: Element, parent: String, sub: String) {
            for (li in ul.children()) {
                val childUl = getChildUl(li)
                if (childUl != null) {
                    for (leaf in childUl.children()) {
                        processFile(leaf, parent, sub)
                    }
                } else {
                    processFile(li, parent, sub)
                }
            }
        }

        fun processFolderChildren(ul: Element, parent: String) {
            for (li in ul.children()) {
                val childUl = getChildUl(li)
                if (childUl != null) {
                    val subName = li.selectFirst("a.folder")?.text()?.trim() ?: li.ownText().trim()
                    processSubFolderChildren(childUl, parent, subName)
                } else {
                    processFile(li, parent, "")
                }
            }
        }

        for (li in rootUl.children()) {
            val childUl = getChildUl(li)
            if (childUl != null && isRootFolder(li)) {
                for (child in childUl.children()) {
                    val childChildUl = getChildUl(child)
                    if (childChildUl != null) {
                        val folderName = child.selectFirst("a.folder")?.text()?.trim() ?: child.ownText().trim()
                        processFolderChildren(childChildUl, folderName)
                    } else {
                        processFile(child, "", "")
                    }
                }
            } else if (childUl != null) {
                val folderName = li.selectFirst("a.folder")?.text()?.trim() ?: li.ownText().trim()
                processFolderChildren(childUl, folderName)
            } else {
                processFile(li, "", "")
            }
        }

        return result
    }

    private fun buildEpisodeName(
        file: VideoFile,
        episodeNumber: Int,
        isExtra: Boolean,
        useFilename: Boolean,
        unknownFolderMap: Map<String, Int>,
        episodeLabel: String? = null,
    ): String {
        val fileName = file.element.ownText().trim()

        val parentLabel = if (file.parentFolder.isEmpty()) {
            ""
        } else {
            val short = extractShortFolderName(file.parentFolder)
            if (short.isNotEmpty()) {
                short
            } else {
                if (useFilename) {
                    file.parentFolder
                } else {
                    val partN = unknownFolderMap[file.parentFolder] ?: 1
                    "Parte $partN"
                }
            }
        }

        val subLabel = if (file.subFolder.isEmpty()) {
            ""
        } else {
            if (useFilename) file.subFolder else "Extras"
        }

        val prefix = when {
            parentLabel.isNotEmpty() && subLabel.isNotEmpty() -> "$parentLabel/$subLabel: "
            parentLabel.isNotEmpty() -> "$parentLabel: "
            subLabel.isNotEmpty() -> "$subLabel: "
            else -> ""
        }

        return if (useFilename) {
            "$prefix$fileName"
        } else {
            val label = if (isExtra) "Extra $episodeNumber" else (episodeLabel ?: "Episodio $episodeNumber")
            "$prefix$label"
        }
    }

    private fun episodeListSingleParseHtml(response: Response, includeSeedersLeechers: Boolean = false): List<SEpisode> {
        val document = response.asJsoup()
        return episodeListSingleParseDocument(
            document,
            torrentIdFromUrl(response.request.url.toString()),
            includeSeedersLeechers,
        )
    }

    // liveStats: seeders/leechers frescos (de la fila de la lista) para cuando la página viene del caché.
    private fun episodeListSingleParseDocument(
        document: Document,
        torrentId: String,
        includeSeedersLeechers: Boolean = false,
        liveStats: String? = null,
        forGroup: Boolean = false,
    ): List<SEpisode> {
        val magnet = document.selectFirst("a[href^='magnet:']")?.attr("href").orEmpty()
        if (magnet.isEmpty()) return emptyList()

        val torrentDate = parseDate(
            document.select("div.panel-body > div:nth-child(1) > div:nth-child(4)").text(),
        )
        val seedersLeechers = if (includeSeedersLeechers) " ${liveStats ?: extractSeedersLeechers(document)}" else ""
        val useFilename = preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)
        val videoFiles = extractVideoFiles(document)

        if (videoFiles.isEmpty()) return emptyList()

        // Caso especial: un solo archivo sin carpetas
        if (videoFiles.size == 1 && videoFiles[0].parentFolder.isEmpty() && videoFiles[0].subFolder.isEmpty()) {
            val singleFile = videoFiles[0]
            val fileName = singleFile.element.ownText().trim()
                .takeIf { it.isNotEmpty() }
                ?: document.select("h3.panel-title").text()
            val fileSize = singleFile.element.select("span.file-size").text()
                .ifEmpty { document.select("div.panel-body > div:nth-child(4) > div:nth-child(2)").text() }
            val display = episodeDisplay(fileName)
            return listOf(
                SEpisode.create().apply {
                    name = if (useFilename) fileName else display.name
                    url = magnet
                    // episode_number = 1f para evitar "missing items" cuando se abre individualmente.
                    // En group se usa el número real (o NO_NUMBER si no se pudo leer).
                    episode_number = if (forGroup) (readableNumber(display) ?: NO_NUMBER) else 1f
                    scanlator = "${versionPrefix(fileName)}$fileSize$seedersLeechers"
                    date_upload = torrentDate
                },
            )
        }

        // El índice HTML puede diferir del índice real del .torrent si el
        // uploader no añadió los archivos en orden alfabético. Se resuelve
        // descargando el .torrent; si falla, cae al índice HTML como fallback.
        val realIndexMap = if (torrentId.isNotEmpty()) {
            resolveRealIndices(videoFiles, torrentId)
        } else {
            emptyMap()
        }

        val unknownFolderMap = mutableMapOf<String, Int>()
        var partCounter = 1
        videoFiles
            .map { it.parentFolder }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { folder ->
                if (extractShortFolderName(folder).isEmpty()) {
                    unknownFolderMap[folder] = partCounter++
                }
            }

        // Orden de aparición de carpetas en el HTML para agrupar temporadas
        // en la lista final (más reciente arriba) sin que temporadas con
        // numeración propia (ej. S01E26 y S02E26 → ambas "26") se intercalen.
        val folderAppearanceOrder = videoFiles
            .map { it.parentFolder }
            .distinct()
            .withIndex()
            .associate { (i, folder) -> folder to i }

        val episodeCounterByFolder = mutableMapOf<String, Int>()
        val extraCounterByFolder = mutableMapOf<String, Int>()

        val episodes = videoFiles.map { file ->
            val folderKey = file.parentFolder
            val isExtra = file.subFolder.isNotEmpty()

            val fileName = file.element.ownText().trim()
            val display = if (isExtra) null else episodeDisplay(fileName)
            // Archivos que la librería reconoce como OP, ED, OVA, Movie o Special (número 0): llevan
            // su propia etiqueta y no cuentan como episodio al numerar la carpeta.
            val hasOwnLabel = display != null && display.episodeNumber == 0f

            val counter = if (isExtra) extraCounterByFolder else episodeCounterByFolder
            val current = if (hasOwnLabel) 0 else (counter[folderKey] ?: 0) + 1
            if (!hasOwnLabel) counter[folderKey] = current

            // Extras: eje negativo (nunca choca con episodios reales al
            // ordenar descendente) pero proporcional a current para que
            // el extra de mayor número quede primero (Extra 4, 3, 2, 1).
            val realEpNumber = if (isExtra) {
                current.toFloat() - 1000f
            } else {
                display?.let { readableNumber(it) } ?: file.index.toFloat()
            }

            // Los episodios se nombran por su posición en la carpeta (Temporada 2: Episodio 1),
            // aunque los archivos traigan numeración absoluta (026, 027...).
            val epName = buildEpisodeName(file, current, isExtra, useFilename, unknownFolderMap, if (hasOwnLabel) display?.name else null)
            val fileSize = file.element.select("span.file-size").text()
            val streamIndex = realIndexMap[file.index] ?: file.index
            val seasonOrder = folderAppearanceOrder[folderKey] ?: 0

            val episode = SEpisode.create().apply {
                name = epName
                url = "$magnet&index=$streamIndex"
                episode_number = realEpNumber
                scanlator = "${versionPrefix(fileName)}$fileSize$seedersLeechers"
                date_upload = torrentDate
            }
            // Los extras y los archivos con etiqueta propia (OP, ED, OVA...) van aparte de los episodios.
            Triple(episode, seasonOrder, isExtra || hasOwnLabel)
        }

        // Temporadas de la más nueva a la más antigua; dentro de cada una, primero los extras y luego
        // los episodios (cada grupo en orden descendente). Con el orden descendente de la app, cada
        // temporada muestra sus extras arriba, pegados al último episodio; con el ascendente, los
        // extras quedan al final de su temporada.
        return episodes
            .sortedWith(
                compareByDescending<Triple<SEpisode, Int, Boolean>> { it.second }
                    .thenByDescending { it.third }
                    .thenByDescending { it.first.episode_number },
            )
            .map { it.first }
    }

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val magnetUrl = episode.url
        return listOf(Video(magnetUrl, episode.name, magnetUrl))
    }

    override fun videoListSelector() = throw Exception("Not used")
    override fun videoFromElement(element: Element) = throw Exception("Not used")
    override fun videoUrlParse(response: okhttp3.Response) = throw Exception("Not used")

    // ========================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Enlace de dominio personalizado"
            dialogTitle = "Enlace de dominio personalizado"
            dialogMessage = "ej. https://nyaa.si/"
            setOnPreferenceChangeListener { _, newValue ->
                val trimmedValue = (newValue as String).trim()
                if (trimmedValue.isBlank()) {
                    preferences.edit().putString(key, extURL).apply()
                    Toast.makeText(screen.context, "URL predeterminada restaurada. Reinicia la app para aplicar el nuevo ajuste.", Toast.LENGTH_LONG).show()
                } else {
                    preferences.edit().putString(key, trimmedValue).apply()
                    Toast.makeText(screen.context, "Reinicia la app para aplicar el nuevo ajuste.", Toast.LENGTH_LONG).show()
                }
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Solo mostrar nombre del archivo"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "No mostrará la ruta completa del episodio."
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_IGNORED_RES_KEY
            title = "Ignorar resoluciones"
            summary = "Oculta resultados nuevos que coincidan con las resoluciones seleccionadas."
            entries = RESOLUTION_ENTRIES
            entryValues = RESOLUTION_VALUES
            setDefaultValue(emptySet<String>())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ==============================
    private data class Sort(val name: String, val id: String)
    private class SortList(availableSorts: Array<String>) : AnimeFilter.Sort("Ordenar", availableSorts, Selection(0, false))
    private val availableSorts = arrayOf(
        Sort("Fecha", "id"),
        Sort("Seeders", "seeders"),
        Sort("Leechers", "leechers"),
        Sort("Descargas", "downloads"),
    )

    private data class Filter(val name: String, val id: String) {
        override fun toString() = name
    }
    private class FilterList(availableFilters: Array<String>) : AnimeFilter.Select<String>("Filtro", availableFilters)
    private val availableFilters = arrayOf(
        Filter("Sin filtro", "0"),
        Filter("Sin remakes", "1"),
        Filter("Solo confiables", "2"),
    )

    private data class Category(val name: String, val id: String) {
        override fun toString() = name
    }
    private class CategoriesList(availableCategories: Array<String>) : AnimeFilter.Select<String>("Categoría", availableCategories)
    private val availableCategories = if (extId == 1) {
        listOf(
            Category("Todas", "1_0"),
            Category("Video musical de anime", "1_1"),
            Category("Traducido al inglés", "1_2"),
            Category("No traducido al inglés", "1_3"),
            Category("Raw", "1_4"),
        )
    } else {
        listOf(
            Category("Anime", "1_1"),
            Category("Real Life", "2_2"),
        )
    }

    private class GroupFilter : AnimeFilter.CheckBox("Agrupar resultados")

    private class GroupModeFilter :
        AnimeFilter.Select<String>(
            "Ajustes de agrupación",
            arrayOf("Agrupar todo", "Priorizar versión más alta", "Ignorar nuevas versiones"),
        )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortList(availableSorts.map { it.name }.toTypedArray()),
        FilterList(availableFilters.map { it.name }.toTypedArray()),
        CategoriesList(availableCategories.map { it.name }.toTypedArray()),
        GroupFilter(),
        GroupModeFilter(),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val PREFIX_GROUP = "group:"
        const val PREFIX_GROUP_LATEST = "group1:"
        const val PREFIX_GROUP_ORIGINAL = "group2:"
        const val PREFIX_DBATCH = "dbatch:"

        private const val GROUP_MODE_ALL = 0
        private const val GROUP_MODE_LATEST = 1
        private const val GROUP_MODE_ORIGINAL = 2

        // Caché de torrents: subir CACHE_VERSION si cambia el HTML de Nyaa o el parseo lo exige.
        private const val CACHE_VERSION = 1
        private const val CACHE_MAX_FILES = 400
        private const val CACHE_SEPARATOR = "\u001F"

        // Marca "sin número" (la misma que usa filenameutils para nombres que no se pudieron leer).
        private const val NO_NUMBER = -9999f

        private const val PREF_DOMAIN_KEY = "domain"
        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false
        private const val PREF_IGNORED_RES_KEY = "ignored_resolutions"

        private val RESOLUTION_ENTRIES = arrayOf("4K", "1080p", "720p", "480p")
        private val RESOLUTION_VALUES = arrayOf("4k", "1080p", "720p", "480p")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        }
    }
}
