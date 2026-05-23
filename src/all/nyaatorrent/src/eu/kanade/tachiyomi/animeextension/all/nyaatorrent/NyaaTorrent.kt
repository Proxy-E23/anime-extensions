package eu.kanade.tachiyomi.animeextension.all.nyaatorrent

import android.widget.Toast
import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException
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
    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("td:nth-child(2) a").attr("href"))
        anime.title = element.select("td:nth-child(2) a:not(.comments)").attr("title")
        return anime
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page&s=seeders&o=desc")
    }

    override fun popularAnimeSelector(): String = animeSelector
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)
    override fun popularAnimeNextPageSelector(): String = animeNextPageSelector

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page")
    }

    override fun latestUpdatesSelector(): String = animeSelector
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = animeNextPageSelector

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = when {
        query.startsWith(PREFIX_SEARCH) -> {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/view/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }
        query.startsWith(PREFIX_GROUP) -> {
            val realQuery = query.removePrefix(PREFIX_GROUP)
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

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = if (query.startsWith(PREFIX_GROUP)) query.removePrefix(PREFIX_GROUP) else query
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
        return GET("$baseUrl/?f=$filterParam&c=$categoryParam&s=$sortParam&o=$sortDirection&q=$encodedQuery&p=$page")
    }

    override fun searchAnimeSelector() = animeSelector
    override fun searchAnimeFromElement(element: Element) = animeFromElement(element)
    override fun searchAnimeNextPageSelector() = animeNextPageSelector

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val category = document.select("div.panel-body > div:nth-child(1) > div:nth-child(2)").text()
        val seeders = document.select("div.panel-body > div:nth-child(2) > div:nth-child(4)").text()
        val leechers = document.select("div.panel-body > div:nth-child(3) > div:nth-child(4) > span").text()
        val filesize = document.select("div.panel-body > div:nth-child(4) > div:nth-child(2)").text()
        val genre = mutableListOf<String>()
        genre.add("Category: $category")
        genre.add("Seeders: $seeders")
        genre.add("Leechers: $leechers")
        genre.add("File Size: $filesize")
        anime.genre = genre.joinToString(", ")
        val desc = document.select("#torrent-description").text()
        anime.description = desc
        anime.author = document.select("a[title=user]").text()
        val imageRegex = Regex("""\b(http|https)?:\S+(?:jpg|png|gif|bmp|webp|tiff|jpeg)(?!\.html)\b""", RegexOption.IGNORE_CASE)
        val match = imageRegex.find(desc)
        if (match != null) {
            anime.thumbnail_url = match.value
        }
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.torrent-file-list ul li li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        if (url.contains("grouped=1")) {
            return episodeListGroupedParse(url)
        }
        return episodeListSingleParse(response)
    }

    private fun episodeListGroupedParse(groupedUrl: String): List<SEpisode> {
        val rawQuery = groupedUrl.substringAfter("&q=").substringBefore("&")
        val categoryParam = groupedUrl.substringAfter("&c=").substringBefore("&")
        val encodedQuery = rawQuery

        val torrentDetailUrls = mutableListOf<String>()
        var page = 1
        var hasNextPage = true
        while (hasNextPage) {
            val searchUrl = "$baseUrl/?f=0&c=$categoryParam&s=id&o=desc&q=$encodedQuery&p=$page"
            val searchDoc = client.newCall(GET(searchUrl)).execute().use { it.asJsoup() }
            searchDoc.select("$animeSelector td:nth-child(2) a:not(.comments)").forEach { a ->
                val href = a.attr("href")
                if (href.isNotEmpty()) torrentDetailUrls.add("$baseUrl$href")
            }
            hasNextPage = searchDoc.selectFirst(animeNextPageSelector) != null
            page++
            if (page > 10) break
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for (detailUrl in torrentDetailUrls) {
            try {
                val detailResponse = client.newCall(GET(detailUrl)).execute()
                val episodes = episodeListSingleParse(detailResponse)
                allEpisodes.addAll(episodes)
            } catch (e: Exception) {
                // Skip dead/broken torrents
            }
        }

        var episodeNumber = allEpisodes.size.toFloat()
        allEpisodes.forEach { ep ->
            ep.episode_number = episodeNumber
            if (!preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                ep.name = "Episodio ${episodeNumber.toInt()}"
            }
            episodeNumber--
        }
        return allEpisodes
    }

    private fun episodeListSingleParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val torrentFile = "$baseUrl${document.selectFirst("div.panel-footer a")?.attr("href").orEmpty()}"
        val torrentDate = parseDate(document.select("div.panel-body > div:nth-child(1) > div:nth-child(4)").text())
        return try {
            val torrent = TorrentUtils.getTorrentInfo(torrentFile, "torrent")
            val torrentIndexed = torrent.files
            val torrentTracker = torrent.trackers.filter { it.trim().isNotEmpty() }.joinToString("") { "&tr=$it" }
            val torrentMagnet = "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}"

            var episodeNumber = 1F
            torrentIndexed
                .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in validExtensions }
                .map {
                    SEpisode.create().apply {
                        name = if (preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                            it.path.trim().split('/').last()
                        } else {
                            "Episodio ${episodeNumber.toInt()}"
                        }
                        url = "$torrentMagnet$torrentTracker&index=${it.indexFile}"
                        episode_number = episodeNumber++
                        scanlator = convertBytesToReadable(it.size)
                        date_upload = torrentDate
                    }
                }.reversed()
                .toMutableList()
        } catch (e: SocketTimeoutException) {
            throw Exception("Dead Torrent \uD83D\uDE35")
        }
    }

    private val validExtensions = setOf("mp4", "mov", "avi", "wmv", "mkv", "flv", "webm", "ogg", "mpeg", "mpg", "mts", "vob", "ts")

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    private fun convertBytesToReadable(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0
        return when {
            gigabytes >= 1 -> String.format("%.2f GB", gigabytes)
            megabytes >= 1 -> String.format("%.2f MB", megabytes)
            else -> String.format("%.2f KB", kilobytes)
        }
    }

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = listOf(Video(episode.url, episode.name, episode.url))

    override fun videoListSelector() = throw Exception("Not used")
    override fun videoFromElement(element: Element) = throw Exception("Not used")
    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    // ========================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Custom Domain Link"
            dialogTitle = "Custom Domain Link"
            dialogMessage = "eg. https://nyaa.si/"
            setOnPreferenceChangeListener { _, newValue ->
                val trimmedValue = (newValue as String).trim()
                if (trimmedValue.isBlank()) {
                    preferences.edit().putString(key, extURL).apply()
                    Toast.makeText(screen.context, "Default URL restored. Restart App to apply new setting.", Toast.LENGTH_LONG).show()
                } else {
                    preferences.edit().putString(key, trimmedValue).apply()
                    Toast.makeText(screen.context, "Restart App to apply new setting.", Toast.LENGTH_LONG).show()
                }
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will not display full path of episode."
        }.also(screen::addPreference)
    }

    // ============================== Filters ==============================
    private data class Sort(val name: String, val id: String)
    private class SortList(availableSorts: Array<String>) : AnimeFilter.Sort("Sort", availableSorts, Selection(0, false))
    private val availableSorts = arrayOf(
        Sort("Date", "id"),
        Sort("Seeders", "seeders"),
        Sort("Leechers", "leechers"),
        Sort("Downloads", "downloads"),
    )

    private data class Filter(val name: String, val id: String) {
        override fun toString() = name
    }
    private class FilterList(availableFilters: Array<String>) : AnimeFilter.Select<String>("Filter", availableFilters)
    private val availableFilters = arrayOf(
        Filter("No filter", "0"),
        Filter("No remakes", "1"),
        Filter("Trusted only", "2"),
    )

    private data class Category(val name: String, val id: String) {
        override fun toString() = name
    }
    private class CategoriesList(availableCategories: Array<String>) : AnimeFilter.Select<String>("Category", availableCategories)
    private val availableCategories = if (extId == 1) {
        listOf(
            Category("All", "1_0"),
            Category("Anime Music Video", "1_1"),
            Category("English-translated", "1_2"),
            Category("Non-English-translated", "1_3"),
            Category("Raw", "1_4"),
        )
    } else {
        listOf(
            Category("Anime", "1_1"),
            Category("Real Life", "2_2"),
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortList(availableSorts.map { it.name }.toTypedArray()),
        FilterList(availableFilters.map { it.name }.toTypedArray()),
        CategoriesList(availableCategories.map { it.name }.toTypedArray()),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val PREFIX_GROUP = "group:"

        private const val PREF_DOMAIN_KEY = "domain"
        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        }
    }
}
//trigger build