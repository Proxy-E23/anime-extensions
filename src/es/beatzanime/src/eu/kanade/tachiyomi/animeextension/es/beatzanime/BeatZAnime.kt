package eu.kanade.tachiyomi.animeextension.es.beatzanime

import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BeatZAnime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "BeatZ Anime"

    override val baseUrl = "https://www.beatz-anime.net"

    private val indexHost = "bz.beatz-anime.net"
    private val indexHttpUrl = "https://$indexHost".toHttpUrl()

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val showFilename: Boolean
        get() = BeatZAnimePreferences.showFilename(preferences)

    // ============================== Popular ===============================
    // El catálogo completo vive en /lista-animes/ sin paginación de servidor.

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/lista-animes/", headers)

    override fun popularAnimeSelector(): String = "div.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img.anime-poster")?.imgAttr()
        with(element.selectFirst("a.anime-poster-link")!!) {
            setUrlWithoutDomain(attr("abs:href"))
        }
        title = element.selectFirst("span.overlay-title-link")?.text().orEmpty()
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/index.php?pagina=$page"
        } else {
            "$baseUrl/"
        }

        return GET(url, headers)
    }
    override fun latestUpdatesSelector(): String = ".row > div:has(a.titulo-largo)"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")?.imgAttr()
        with(element.selectFirst("a.titulo-largo")!!) {
            setUrlWithoutDomain(attr("abs:href"))
            title = text()
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination > li.active + li:not(.disabled)"

    // =============================== Search ===============================
    // /lista-animes/ no filtra en el servidor: siempre devuelve el catálogo
    // completo, con los datos de filtro en atributos data-* de cada tarjeta,
    // y el sitio filtra en el navegador con JS. Como la extensión no ejecuta
    // JS, se sobrescribe getSearchAnime para replicar ese filtrado en Kotlin.

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/lista-animes/", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = null

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = searchAnimeRequest(page, query, filters)
        val document = client.newCall(request).execute().asJsoup()

        val source = filters.filterIsInstance<SourceFilter>().firstOrNull()?.getValue().orEmpty()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getValue().orEmpty()
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getValue().orEmpty()

        val normQuery = query.normalizeForCompare()
        val normSource = source.normalizeForCompare()
        val normStatus = status.normalizeForCompare()
        val normType = type.normalizeForCompare()

        val animes = document.select(searchAnimeSelector()).filter { element ->
            val name = element.attr("data-name").normalizeForCompare()
            val fuente = element.attr("data-fuente").normalizeForCompare()
            val estado = element.attr("data-estado").normalizeForCompare()
            val tipo = element.attr("data-tipo").normalizeForCompare()

            val matchQuery = normQuery.isEmpty() || name.contains(normQuery)
            val matchSource = normSource.isEmpty() || fuente.contains(normSource)
            val matchStatus = normStatus.isEmpty() || estado == normStatus
            val matchType = normType.isEmpty() || tipo == normType

            matchQuery && matchSource && matchStatus && matchType
        }.map { searchAnimeFromElement(it) }

        return AnimesPage(animes, false)
    }

    private fun String.normalizeForCompare(): String = this
        .lowercase()
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        .replace(Regex("\\p{Mn}+"), "")
        .trim()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SourceFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst(".row > div > img")?.imgAttr()
        genre = document.selectFirst("p.post-text span:has(b:contains(Generos))")?.ownText()
        status = document.selectFirst("div:has(>h5:contains(Estado)) a").parseStatus()
        description = buildString {
            document.selectFirst("p.post-text")?.textNodes()?.let {
                append(it.joinToString("\n\n") { it.text() })
            }
            append("\n\n")
            document.selectFirst("p.post-text span:has(b:contains(Sinónimos))")?.let {
                append("Sinónimos: ")
                append(it.ownText())
            }
        }.trim()
    }

    private fun Element?.parseStatus(): Int = when (this?.text()?.lowercase()) {
        "finalizado" -> SAnime.COMPLETED
        "en emisión", "en emsión" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<Pair<SEpisode, String>>()

        val onclickAttr = document.select("button[onclick*=$indexHost]").firstOrNull { btn ->
            val onclick = btn.attr("onclick")
            onclick.contains(indexHost) && !onclick.contains("/d/")
        }?.attr("onclick") ?: throw Exception("No se encontró el enlace a la carpeta de $indexHost")

        val folderUrl = Regex("""window\.open\('([^']+)'""").find(onclickAttr)?.groupValues?.get(1)
            ?: throw Exception("No se pudo extraer la URL de la carpeta")

        val basePath = "/" + folderUrl.toHttpUrl().pathSegments.joinToString("/")

        fun traverseFolder(path: String, relativePath: String, recursionDepth: Int = 0) {
            if (recursionDepth == 2) return

            val apiHeaders = headersBuilder().apply {
                add("Accept", "application/json, text/plain, */*")
                add("Host", indexHost)
                add(
                    "Referer",
                    indexHttpUrl.newBuilder()
                        .addPathSegments(path.removePrefix("/"))
                        .build()
                        .toString(),
                )
            }.build()

            val apiUrl = indexHttpUrl.newBuilder().apply {
                addPathSegment("api")
                addPathSegment("fs")
                addPathSegment("list")
            }.build()

            val jsonBody = """{"path":"${path.replace("\"", "\\\"")}","password":"","page":1,"per_page":0,"refresh":false}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl)
                .headers(apiHeaders)
                .post(jsonBody)
                .build()

            val data = client.newCall(request).execute().parseAs<IndexResponseDto>()

            data.data.content.forEach { item ->
                if (item.is_dir) {
                    traverseFolder("$path/${item.name}", item.name, recursionDepth + 1)
                } else {
                    val fileExt = item.name.substringAfterLast(".")
                    if (!SUPPORTED_FORMATS.any { it.equals(fileExt, true) }) return@forEach

                    val display = FilenameUtils.buildEpisodeDisplay(item.name, showFilename)
                    val episode = SEpisode.create().apply {
                        name = display.name
                        url = "$path/${item.name}"
                        episode_number = display.episodeNumber
                        scanlator = buildList {
                            if (relativePath != "") add(relativePath)
                            add(item.size.formatBytes())
                        }.joinToString(" • ")
                    }
                    episodeList.add(episode to item.name)
                }
            }
        }

        traverseFolder(basePath, "")

        return FilenameUtils.sortByEpisodeNumberDescending(episodeList) { it.second }.map { it.first }
    }

    @Serializable
    class IndexResponseDto(
        val data: DataDto,
    ) {
        @Serializable
        class DataDto(
            val content: List<ItemDto>,
        ) {
            @Serializable
            class ItemDto(
                val name: String,
                val size: Long,
                val is_dir: Boolean,
            )
        }
    }

    private fun Long.formatBytes(): String = when {
        this >= 1_000_000_000 -> "%.2f GB".format(this / 1_000_000_000.0)
        this >= 1_000_000 -> "%.2f MB".format(this / 1_000_000.0)
        this >= 1_000 -> "%.2f KB".format(this / 1_000.0)
        this > 1 -> "$this bytes"
        this == 1L -> "$this byte"
        else -> ""
    }

    // ============================ Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = BeatZAnimePreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val encodedPath = episode.url.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

        val url = indexHttpUrl.newBuilder().apply {
            addPathSegment("d")
        }.build().toString().removeSuffix("/") + encodedPath

        val refererPath = episode.url.substringBeforeLast("/") + "/"

        val videoHeaders = headersBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Referer", indexHttpUrl.newBuilder().addPathSegments(refererPath.removePrefix("/")).build().toString())
        }.build()

        return listOf(Video(url, "Video", url, videoHeaders))
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    companion object {
        private val SUPPORTED_FORMATS = listOf("mp4", "mkv")
    }
}
