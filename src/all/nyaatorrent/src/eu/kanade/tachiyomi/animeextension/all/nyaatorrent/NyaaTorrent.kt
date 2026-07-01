package eu.kanade.tachiyomi.animeextension.all.nyaatorrent

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class NyaaTorrent(extName: String, private val extURL: String, private val extId: Int) :
    ParsedAnimeHttpSource(),
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

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val shouldGroup = filters.filterIsInstance<GroupFilter>().firstOrNull()?.state == true && query.isNotEmpty()
        return when {
            query.startsWith(PREFIX_SEARCH) -> {
                val id = query.removePrefix(PREFIX_SEARCH)
                client.newCall(GET("$baseUrl/view/$id"))
                    .awaitSuccess()
                    .use(::searchAnimeByIdParse)
            }
            query.startsWith(PREFIX_DBATCH) -> {
                val parts = query.removePrefix(PREFIX_DBATCH).split(":")
                val id = parts[0]
                val rangeSpec = parts.getOrNull(1)
                client.newCall(GET("$baseUrl/view/$id"))
                    .awaitSuccess()
                    .use { response -> searchAnimeByDbatchParse(response, id, rangeSpec) }
            }
            shouldGroup || query.startsWith(PREFIX_GROUP) -> {
                val realQuery = if (query.startsWith(PREFIX_GROUP)) query.removePrefix(PREFIX_GROUP) else query
                val encodedQuery = URLEncoder.encode(realQuery, "UTF-8")
                val categoryParam = if (extId == 1) "1_0" else "1_1"
                val anime = SAnime.create().apply {
                    title = "\uD83D\uDDC2 $realQuery"
                    url = "/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=1&grouped=1"
                    thumbnail_url = null
                    description = "Búsqueda agrupada: \"$realQuery\"\n\nTodos los episodios de todos los torrents que coincidan con esta búsqueda."
                }
                AnimesPage(listOf(anime), false)
            }
            else -> super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
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
                thumbnail_url = null
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
                    thumbnail_url = null
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
                    thumbnail_url = null
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
            val rows = document.select("table.torrent-list tbody tr")
            val totalSeeders = rows.sumOf {
                it.select("td:nth-child(6)").text().trim().toIntOrNull() ?: 0
            }
            val totalLeechers = rows.sumOf {
                it.select("td:nth-child(7)").text().trim().toIntOrNull() ?: 0
            }
            val totalSize = sumSizeTexts(
                rows.mapNotNull { it.select("td:nth-child(4)").text().trim().takeIf { s -> s.isNotEmpty() } },
            )
            anime.genre = "↑ Seeders: $totalSeeders, ↓ Leechers: $totalLeechers, Tamaño: $totalSize"

            // Extraer query de la URL para mostrarlo en la descripción
            val query = location.substringAfter("&q=").substringBefore("&")
                .replace("+", " ").let { URLDecoder.decode(it, "UTF-8") }
            anime.description = "group:$query"
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

        val torrentDetailUrls = mutableListOf<String>()
        var page = 1
        var hasNextPage = true
        while (hasNextPage) {
            val searchUrl = "$baseUrl/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=$page"
            val searchDoc = client.newCall(GET(searchUrl)).execute().use { it.asJsoup() }
            searchDoc.select("$animeSelector td:nth-child(2) a:not(.comments)").forEach { a ->
                val href = a.attr("href")
                val title = a.attr("title")
                if (href.isNotEmpty() && !isAnimeFullyIgnored(title)) {
                    torrentDetailUrls.add("$baseUrl$href")
                }
            }
            hasNextPage = searchDoc.selectFirst(animeNextPageSelector) != null
            page++
            if (page > 10) break
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for (detailUrl in torrentDetailUrls) {
            try {
                val detailResponse = client.newCall(GET(detailUrl)).execute()
                allEpisodes.addAll(episodeListSingleParseHtml(detailResponse, includeSeedersLeechers = true))
            } catch (e: Exception) {
                // página inaccesible, se omite
            }
        }

        // Normalizar episode_number para que la playlist del reproductor funcione correctamente.
        // Los torrents sueltos llegan con episode_number = 1f (para evitar "missing items"
        // cuando se abren individualmente). En group: eso rompe la playlist porque todos
        // los episodios tienen el mismo número interno. Se extrae el número real del nombre.
        val useFilename = preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)
        allEpisodes.forEachIndexed { index, ep ->
            val epNumber = extractEpisodeNumber(ep.name) ?: (allEpisodes.size - index).toFloat()
            ep.episode_number = epNumber
            if (!useFilename) {
                ep.name = "Episodio ${epNumber.toInt()}"
            }
        }

        return allEpisodes.sortedByDescending { it.episode_number }
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun fetchRealFileOrder(torrentId: String): List<String>? {
        return try {
            val torrentBytes = client.newCall(GET("$baseUrl/download/$torrentId.torrent"))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.bytes()
                } ?: return null

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
            val label = if (isExtra) "Extra $episodeNumber" else "Episodio $episodeNumber"
            "$prefix$label"
        }
    }

    private fun episodeListSingleParseHtml(response: Response, includeSeedersLeechers: Boolean = false): List<SEpisode> {
        val document = response.asJsoup()
        val magnet = document.selectFirst("a[href^='magnet:']")?.attr("href").orEmpty()
        if (magnet.isEmpty()) return emptyList()

        val torrentId = response.request.url.toString()
            .substringAfter("/view/")
            .substringBefore("/")
            .substringBefore("#")
            .substringBefore("?")

        val torrentDate = parseDate(
            document.select("div.panel-body > div:nth-child(1) > div:nth-child(4)").text(),
        )
        val seedersLeechers = if (includeSeedersLeechers) " ${extractSeedersLeechers(document)}" else ""
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
            val realNumber = extractEpisodeNumber(fileName) ?: 1f
            return listOf(
                SEpisode.create().apply {
                    name = if (useFilename) fileName else "Episodio ${realNumber.toInt()}"
                    url = magnet
                    // episode_number = 1f para evitar "missing items" cuando se abre individualmente.
                    // En group: este valor se sobreescribe con el número real en episodeListGroupedParse.
                    episode_number = 1f
                    scanlator = "$fileSize$seedersLeechers"
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

            val counter = if (isExtra) extraCounterByFolder else episodeCounterByFolder
            val current = (counter[folderKey] ?: 0) + 1
            counter[folderKey] = current

            val fileName = file.element.ownText().trim()
            // Extras: eje negativo (nunca choca con episodios reales al
            // ordenar descendente) pero proporcional a current para que
            // el extra de mayor número quede primero (Extra 4, 3, 2, 1).
            val realEpNumber = if (isExtra) {
                current.toFloat() - 1000f
            } else {
                extractEpisodeNumber(fileName) ?: file.index.toFloat()
            }

            val epName = buildEpisodeName(file, current, isExtra, useFilename, unknownFolderMap)
            val fileSize = file.element.select("span.file-size").text()
            val streamIndex = realIndexMap[file.index] ?: file.index
            val seasonOrder = folderAppearanceOrder[folderKey] ?: 0

            SEpisode.create().apply {
                name = epName
                url = "$magnet&index=$streamIndex"
                episode_number = realEpNumber
                scanlator = "$fileSize$seedersLeechers"
                date_upload = torrentDate
            } to seasonOrder
        }

        return episodes
            .sortedWith(
                compareByDescending<Pair<SEpisode, Int>> { it.second }
                    .thenByDescending { it.first.episode_number },
            )
            .map { it.first }
    }

    private fun extractEpisodeNumber(fileName: String): Float? {
        val patterns = listOf(
            Regex("""[Ee]pisodio\s+(\d+)"""),
            Regex("""[-\s](\d{1,4})v\d"""),
            Regex("""[-\s](\d{1,2})_(\d)\s*[\[\(]"""),
            Regex("""[-\s]0*(\d{1,2})\s*[\[\(]"""),
            Regex("""[Ee][Pp]?(\d{1,4})"""),
            Regex("""[-\s](\d{1,4})[\s\.(]"""),
            Regex("""_(\d{1,4})_"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(fileName) ?: continue
            if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                val whole = match.groupValues[1].toFloatOrNull() ?: continue
                val decimal = match.groupValues[2].toFloatOrNull() ?: continue
                return whole + decimal / 10f
            }
            return match.groupValues[1].toFloatOrNull()
        }
        return null
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
    override fun videoUrlParse(document: Document) = throw Exception("Not used")

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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortList(availableSorts.map { it.name }.toTypedArray()),
        FilterList(availableFilters.map { it.name }.toTypedArray()),
        CategoriesList(availableCategories.map { it.name }.toTypedArray()),
        GroupFilter(),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val PREFIX_GROUP = "group:"
        const val PREFIX_DBATCH = "dbatch:"

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
