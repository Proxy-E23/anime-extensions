package eu.kanade.tachiyomi.animeextension.es.dantaliansubs

import android.app.Application
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.googledrivescraper.GoogleDriveScraper
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DantalianSubs :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "DantalianSubs"
    override val baseUrl = "https://dantaliansubs.moe"
    override val lang = "es"
    override val supportsLatest = true

    // El challenge de Cloudflare aparece en todo el sitio, no solo en un CDN de video
    // puntual (los videos viven en Google Drive), así que el interceptor va en el client
    // principal. La cookie resultante queda cacheada por Android hasta que expire.
    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(super.client))
        .build()

    private val preferences by getPreferencesLazy()

    private val showFilename: Boolean
        get() = DantalianSubsPreferences.showFilename(preferences)

    private val animeListUrl = "$baseUrl/anime/"

    private val gdScraper by lazy { GoogleDriveScraper(client, headers) }
    private val gdExtractor by lazy { GoogleDriveExtractor(client, headers) }

    // El "anime" es siempre la página de la serie. El sitio es WordPress con el plugin
    // Ultimate Post: /anime/ trae dos grids fijos en la misma página ("Anime en progreso"
    // y "Anime finalizado"), sin paginación real. Se usa "en progreso" como Recientes y
    // "finalizado" como Populares, ya que el sitio no expone un orden por fecha.

    // ============================ Caché de portadas ============================
    // Portadas guardadas en SharedPreferences para que sobrevivan al cierre de la app.
    // Se actualizan al abrir la serie (getAnimeDetails) y se usan primero al construir
    // el listado en Populares/Recientes.
    private val thumbnailPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_thumbnails", 0x0000)
    }

    private fun getCachedThumbnail(url: String): String? = thumbnailPrefs.getString(url, null)?.ifBlank { null }

    private fun cacheThumbnail(url: String, thumbnailUrl: String) {
        thumbnailPrefs.edit().putString(url, thumbnailUrl).apply()
    }

    // ============================ Caché de sesión ============================
    // Todo lo demás se cachea solo en memoria y se pierde al cerrar la app, evitando
    // repetir peticiones dentro de la misma sesión sin arrastrar datos viejos entre sesiones.
    private var popularCache: List<SAnime>? = null
    private var latestCache: List<SAnime>? = null
    private val detailsCache = mutableMapOf<String, SAnime>()
    private val episodesCache = mutableMapOf<String, List<SEpisode>>()
    private val animePageCache = mutableMapOf<String, Document>()

    // Evita descargar la página de la serie dos veces: getAnimeDetails y getEpisodeList
    // se llaman por separado, pero ambos usan el mismo Document.
    private fun fetchAnimePageDocument(anime: SAnime): Document = animePageCache.getOrPut(anime.url) {
        client.newCall(GET(baseUrl + anime.url, headers)).execute().asJsoup()
    }

    // Populares y Recientes viven en la misma página (/anime/), así que se descarga y
    // parsea una sola vez.
    private fun fetchAnimeListDocument(): Document = client.newCall(GET(animeListUrl, headers)).execute().asJsoup()

    // ============================== Popular =================================
    // Sección "Anime finalizado".

    override fun popularAnimeRequest(page: Int): Request = GET(animeListUrl, headers)

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val all = popularCache ?: run {
            val document = fetchAnimeListDocument()
            val list = parseFinishedSection(document)
            popularCache = list
            list
        }
        return AnimesPage(all, false)
    }

    private fun parseFinishedSection(document: Document): List<SAnime> {
        val heading = document.select("h2:matchesOwn((?i)anime finalizado)").firstOrNull()
        val container = heading?.nextElementSiblings()?.firstOrNull { it.selectFirst(".ultp-block-item") != null }
            ?: return emptyList()
        return container.select(".ultp-block-item").mapNotNull { animeFromGridItem(it) }
    }

    // Requeridos por la clase base; getPopularAnime maneja el parseo real (dos secciones
    // en la misma página no encajan con el selector simple de ParsedAnimeHttpSource).
    override fun popularAnimeSelector(): String = ".ultp-block-item"
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromGridItem(element) ?: SAnime.create()
    override fun popularAnimeNextPageSelector(): String? = null
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseFinishedSection(response.asJsoup()), false)

    // =============================== Latest =================================
    // Sección "Anime en progreso".

    override fun latestUpdatesRequest(page: Int): Request = GET(animeListUrl, headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val all = latestCache ?: run {
            val document = fetchAnimeListDocument()
            val list = parseInProgressSection(document)
            latestCache = list
            list
        }
        return AnimesPage(all, false)
    }

    private fun parseInProgressSection(document: Document): List<SAnime> {
        val heading = document.select("h2:matchesOwn((?i)anime en progreso)").firstOrNull()
        val container = heading?.nextElementSiblings()?.firstOrNull { it.selectFirst(".ultp-block-item") != null }
            ?: return emptyList()
        return container.select(".ultp-block-item").mapNotNull { animeFromGridItem(it) }
    }

    override fun latestUpdatesSelector(): String = ".ultp-block-item"
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromGridItem(element) ?: SAnime.create()
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(parseInProgressSection(response.asJsoup()), false)

    // Cada item de grid (Ultimate Post) trae link, título y miniatura de catálogo.
    // La miniatura cacheada, si existe, prevalece sobre la del catálogo: en las páginas
    // de serie la imagen suele ser de mejor calidad.
    private fun animeFromGridItem(element: Element): SAnime? {
        val link = element.selectFirst(".ultp-block-title a") ?: return null
        val url = link.attr("href").removePrefix(baseUrl)
        if (url.isBlank()) return null

        return SAnime.create().apply {
            this.url = url
            title = link.text().trim()
            thumbnail_url = getCachedThumbnail(url)
                ?: element.selectFirst(".ultp-block-image-content, .ultp-block-image img")?.attr("data-src")
                    ?.ifBlank { null }
        }
    }

    // =============================== Search ==================================
    // El sitio no tiene buscador propio útil para scraping; se filtra en memoria sobre
    // el catálogo combinado (finalizado + en progreso).
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(animeListUrl, headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = null

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val document = fetchAnimeListDocument()
        val all = (parseFinishedSection(document) + parseInProgressSection(document)).distinctBy { it.url }
        val filtered = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(filtered, false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================ Anime Details ==============================
    // Se sobreescribe getAnimeDetails (no solo animeDetailsParse) para cachear la
    // portada de forma persistente usando la url del anime.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        detailsCache[anime.url]?.let { return it }

        val document = fetchAnimePageDocument(anime)
        val details = animeDetailsParse(document)

        details.thumbnail_url?.let { cacheThumbnail(anime.url, it) }
        detailsCache[anime.url] = details
        return details
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.page-title")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("figure.wp-block-image img")?.attr("data-src")?.ifBlank { null }

        // El primer <p> del contenido trae la ficha técnica (Fuente/Estado/Episodios/
        // Codec/Resolución). Jsoup .text() normaliza los <br> como espacios, así que las
        // regex cortan en la siguiente etiqueta conocida en vez de en salto de línea.
        val plainInfo = document.selectFirst(".entry-content p")?.text().orEmpty()
        val estadoValue = REGEX_ESTADO.find(plainInfo)?.groupValues?.get(1)?.trim()
        val episodios = REGEX_EPISODIOS.find(plainInfo)?.groupValues?.get(1)?.trim()
        val codec = REGEX_CODEC.find(plainInfo)?.groupValues?.get(1)?.trim()
        val resolucion = REGEX_RESOLUCION.find(plainInfo)?.groupValues?.get(1)?.trim()

        status = when {
            estadoValue?.contains("finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
            estadoValue?.contains("progreso", ignoreCase = true) == true ||
                estadoValue?.contains("emisión", ignoreCase = true) == true ||
                estadoValue?.contains("emision", ignoreCase = true) == true -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            if (!episodios.isNullOrBlank()) appendLine("Episodios: $episodios")
            if (!codec.isNullOrBlank()) appendLine("Codec: $codec")
            if (!resolucion.isNullOrBlank()) appendLine("Resolución: $resolucion")
        }.trim().ifBlank { null }
    }

    // ============================ Episode List ================================
    // El sitio publica de dos formas según el estado de la serie: series finalizadas
    // (o con todos los episodios subidos) tienen un link único a una carpeta de Drive;
    // series en emisión tienen un link suelto por episodio. Se intenta primero la
    // carpeta y, si no aparece, se cae al parseo de links sueltos.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        episodesCache[anime.url]?.let { return it }

        val document = fetchAnimePageDocument(anime)

        val episodes = findDriveFolderLink(document)?.let { folderUrl ->
            gdScraper.scrapeEpisodes(folderUrl).map { ep ->
                val display = FilenameUtils.buildEpisodeDisplay(ep.name, showFilename)
                SEpisode.create().apply {
                    name = display.name
                    url = ep.url
                    episode_number = display.episodeNumber
                    date_upload = ep.dateUploadMillis
                }
            }
        } ?: parseLooseEpisodeLinks(document)

        if (episodes.isEmpty()) {
            throw Exception("No se encontraron episodios (ni carpeta ni links sueltos de Google Drive) para esta serie.")
        }

        val sorted = episodes.sortedByDescending { it.episode_number }
        episodesCache[anime.url] = sorted
        return sorted
    }

    // Busca un link a carpeta de Drive dentro del contenido, descartando el de subtítulos.
    private fun findDriveFolderLink(document: Document): String? = document.select(".entry-content a[href*=\"drive.google.com/drive/folders/\"]")
        .firstOrNull { link -> !SUBS_KEYWORD_REGEX.containsMatchIn(link.text()) }
        ?.attr("href")

    // Series en emisión: cada línea del párrafo trae "Episodio N: " seguido de un <a> a
    // Drive (archivo suelto) y opcionalmente un <a> a Torrent, que se ignora. Se recorren
    // los nodos del párrafo en orden para asociar cada número con el link que le sigue.
    private fun parseLooseEpisodeLinks(document: Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        document.select(".entry-content p").forEach { paragraph ->
            var pendingEpisodeNumber: Float? = null

            paragraph.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> {
                        EPISODIO_LABEL_REGEX.find(node.text())?.let {
                            pendingEpisodeNumber = it.groupValues[1].toFloatOrNull()
                        }
                    }
                    is Element -> {
                        if (node.tagName() == "a" && node.attr("href").contains("drive.google.com/file/d/")) {
                            val epNumber = pendingEpisodeNumber
                            if (epNumber != null) {
                                val display = FilenameUtils.buildEpisodeDisplay("Episodio $epNumber", showFilename)
                                episodes.add(
                                    SEpisode.create().apply {
                                        name = display.name
                                        url = node.attr("href")
                                        episode_number = epNumber
                                    },
                                )
                                pendingEpisodeNumber = null
                            }
                        }
                    }
                }
            }
        }

        return episodes
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links ===============================
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val fileId = extractDriveFileId(episode.url)
            ?: throw Exception("No se pudo interpretar el link de Google Drive para este episodio.")
        return gdExtractor.videosFromUrl(fileId)
    }

    // El id de Drive puede venir como "...uc?id=XXXX" (link de carpeta, ya normalizado
    // por GoogleDriveScraper) o como "...file/d/XXXX/view?..." (link suelto).
    private fun extractDriveFileId(url: String): String? = url.substringAfter("id=", "").ifBlank { null }
        ?: FILE_D_REGEX.find(url)?.groupValues?.get(1)?.ifBlank { null }

    // ============================ Preferencias ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = DantalianSubsPreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )
    }

    companion object {
        // Jsoup .text() colapsa los <br> a espacios, así que el párrafo llega como una
        // sola línea. Cada regex corta en la siguiente etiqueta conocida o fin de texto.
        private const val NEXT_LABEL = """(?=\s*(?:Fuente de video|Estado|Episodios|Codec|Resoluci[oó]n):|$)"""
        private val REGEX_ESTADO = Regex("""Estado:\s*(.+?)$NEXT_LABEL""", RegexOption.IGNORE_CASE)
        private val REGEX_EPISODIOS = Regex("""Episodios:\s*(.+?)$NEXT_LABEL""", RegexOption.IGNORE_CASE)
        private val REGEX_CODEC = Regex("""Codec:\s*(.+?)$NEXT_LABEL""", RegexOption.IGNORE_CASE)
        private val REGEX_RESOLUCION = Regex("""Resoluci[oó]n:\s*(.+?)$NEXT_LABEL""", RegexOption.IGNORE_CASE)
        private val SUBS_KEYWORD_REGEX = Regex("""subs?\b""", RegexOption.IGNORE_CASE)

        // "Episodio 1: ", "Episodio 12:", admite decimales tipo "Episodio 1.5:" por si acaso.
        private val EPISODIO_LABEL_REGEX = Regex("""Episodio\s+(\d+(?:\.\d+)?)\s*:""", RegexOption.IGNORE_CASE)

        // Extrae el id de un link "https://drive.google.com/file/d/XXXX/view?..."
        private val FILE_D_REGEX = Regex("""/file/d/([^/]+)""")
    }
}
