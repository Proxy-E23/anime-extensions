package eu.kanade.tachiyomi.animeextension.es.wingzeroplus

import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WingZeroPlus : ParsedAnimeHttpSource() {

    override val name = "Wing Zero Plus"
    override val baseUrl = "https://plus.wing-zero-network.org"
    override val lang = "es"
    override val supportsLatest = false

    private val gdExtractor by lazy { GoogleDriveExtractor(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?p=$page", headers)

    override fun popularAnimeSelector(): String = "div#tm-right-section div.uk-grid div.uk-margin-bottom"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("a.uk-position-cover") ?: return@apply
        url = anchor.attr("href").removePrefix(baseUrl)
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("h5.uk-panel-title")?.text() ?: ""
    }

    // Evitamos :has() porque puede no estar soportado en versiones viejas de Jsoup.
    // En su lugar sobreescribimos popularAnimeParse y detectamos la siguiente página manualmente.
    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .filter { el -> el.selectFirst("a.uk-position-cover") != null }
            .map { el -> popularAnimeFromElement(el) }
            .filter { it.url.isNotBlank() }
        val hasNext = document.select("ul.uk-pagination li.uk-disabled a[href]").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    // supportsLatest = false, estos métodos no se invocan en runtime

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("title", query)
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> filter.toUriPart().takeIf { it.isNotBlank() }
                        ?.let { addQueryParameter("genre", it) }
                    is YearFilter -> filter.toUriPart().takeIf { it.isNotBlank() }
                        ?.let { addQueryParameter("year", it) }
                    else -> {}
                }
            }
            addQueryParameter("p", page.toString())
        }.build()
        return GET(url.toString(), headers)
    }

    // ============================ Anime Details ===========================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h2.uk-text-contrast.uk-text-bold")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.media-cover img")?.attr("src")
        description = document.selectFirst("p.uk-text-muted.uk-h4")?.text()
        status = SAnime.COMPLETED

        document.select("dl.uk-description-list-horizontal dt").forEach { dt ->
            val dd = dt.nextElementSibling() ?: return@forEach
            when {
                dt.text().contains("Géneros") ->
                    genre = dd.select("li").joinToString(", ") { it.text() }
                dt.text().contains("Estudios") ->
                    author = dd.select("li").joinToString(", ") { it.text() }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div#episodes div.episodes"

    override fun episodeFromElement(element: Element): SEpisode {
        val name = element.selectFirst("dt")?.text()?.trim() ?: "Episodio"
        val epNum = EP_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
        val relUrl = element.selectFirst("a.uk-position-cover")?.attr("href") ?: ""
        return SEpisode.create().apply {
            this.name = name
            url = relUrl // se convierte a absoluta en episodeListParse
            episode_number = epNum
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        // La URL de la serie es algo como:
        // https://plus.wing-zero-network.org/serie/7/claymore
        // El episodio tiene href relativo: "single-serie?watch=1&episode=106"
        // La URL absoluta correcta es:
        // https://plus.wing-zero-network.org/serie/7/single-serie?watch=1&episode=106
        val serieBaseUrl = response.request.url.toString()
            .substringBeforeLast("/") // quita el slug, deja .../serie/7

        val doc = response.asJsoup()
        val episodes = doc.select(episodeListSelector()).map { el ->
            episodeFromElement(el).also { ep ->
                if (ep.url.isNotBlank()) {
                    ep.url = "$serieBaseUrl/${ep.url}"
                }
            }
        }
        return episodes.reversed()
    }

    // ============================ Video Links =============================

    // Los tres métodos abstractos no se usan porque sobreescribimos getVideoList
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epUrl = episode.url.ifBlank {
            throw Exception("URL del episodio no encontrada.")
        }
        val doc = client.newCall(GET(epUrl, headers)).execute().asJsoup()

        val fileId = doc.select("a[download]")
            .map { it.attr("href") }
            .firstOrNull { it.contains("drive") && it.contains("id=") }
            ?.substringAfter("id=")?.substringBefore("&")
            ?: doc.selectFirst("iframe[src*='drive.google.com/file/d/']")
                ?.attr("src")
                ?.substringAfter("/file/d/")
                ?.substringBefore("/")
            ?: throw Exception("No se encontró enlace de Google Drive para este episodio.")

        // Cargamos la página de preview de GD para extraer la URL de stream firmada
        val previewUrl = "https://drive.google.com/file/d/$fileId/preview"
        val previewHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Referer", "https://drive.google.com/")
            .build()
        val previewResp = client.newCall(GET(previewUrl, previewHeaders)).execute()
        val previewBody = previewResp.body.string()

        // GD embebe la URL de stream en el HTML como "fmt_stream_map" o en un array de URLs
        // Buscamos patrones conocidos de URLs de stream de GD
        val streamUrl = Regex(""""(https://[^"]*googleusercontent\.com[^"]*\.(mp4|webm)[^"]*?)"""")
            .find(previewBody)?.groupValues?.get(1)
            ?: Regex(""""url":"(https://[^"]*googlevideo\.com[^"]*?)"""")
                .find(previewBody)?.groupValues?.get(1)?.replace("\\u003d", "=")?.replace("\\u0026", "&")
            ?: gdExtractor.videosFromUrl(fileId).firstOrNull()?.videoUrl
            ?: throw Exception("No se pudo obtener la URL de stream para este episodio.")

        return listOf(Video(streamUrl, "Video", streamUrl, previewHeaders))
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Los filtros no funcionan junto con búsqueda de texto"),
        GenreFilter(),
        YearFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Género",
            arrayOf(
                Pair("Todos", ""),
                Pair("Acción", "2"),
                Pair("Aventura", "3"),
                Pair("Ciencia ficción", "9"),
                Pair("Comedia", "18"),
                Pair("Deporte", "13"),
                Pair("Drama", "1"),
                Pair("Ecchi", "8"),
                Pair("Fantasía", "4"),
                Pair("Horror", "20"),
                Pair("Magia", "16"),
                Pair("Mecha", "5"),
                Pair("Misterio", "7"),
                Pair("Psicológico", "12"),
                Pair("Romance", "17"),
                Pair("Shounen", "15"),
                Pair("Sobrenatural", "6"),
                Pair("Suspenso", "19"),
                Pair("Vida escolar", "14"),
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "Año",
            arrayOf(
                Pair("Todos", ""),
                Pair("1979", "1979"),
                Pair("1989", "1989"),
                Pair("1992", "1992"),
                Pair("1993", "1993"),
                Pair("1995", "1995"),
                Pair("1996", "1996"),
                Pair("1997", "1997"),
                Pair("1999", "1999"),
                Pair("2006", "2006"),
                Pair("2007", "2007"),
                Pair("2009", "2009"),
                Pair("2010", "2010"),
                Pair("2013", "2013"),
                Pair("2015", "2015"),
                Pair("2016", "2016"),
                Pair("2017", "2017"),
                Pair("2019", "2019"),
                Pair("2020", "2020"),
                Pair("2021", "2021"),
                Pair("2022", "2022"),
                Pair("2023", "2023"),
                Pair("2024", "2024"),
                Pair("2025", "2025"),
                Pair("2026", "2026"),
            ),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        private val EP_NUMBER_REGEX = Regex(
            """Episodio\s+0*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
