package eu.kanade.tachiyomi.animeextension.es.wingzeroplus

import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

class WingZeroPlus :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Wing Zero Plus"
    override val baseUrl = "https://plus.wing-zero-network.org"
    override val lang = "es"
    override val supportsLatest = true

    private val gdExtractor by lazy { GoogleDriveExtractor(client, headers) }

    private val preferences by getPreferencesLazy()

    private val showFilename: Boolean
        get() = WingZeroPlusPreferences.showFilename(preferences)

    // ============================== Popular ===============================

    // El sitio pagina "series" de más antiguo a más reciente. Para mostrar lo más
    // nuevo primero, pedimos el total de páginas una vez y navegamos en reversa.
    // Se cachea porque no cambia dentro de la misma sesión de la extensión.
    private var popularTotalPages: Int? = null

    // Cachea cada página real ya parseada (clave = número de página real) para no
    // repetir la petición HTTP si el usuario navega hacia atrás en el mismo scroll.
    private val popularPageCache = mutableMapOf<Int, List<SAnime>>()

    override fun popularAnimeRequest(page: Int): Request {
        // No se usa: la lógica real vive en getPopularAnime. Se mantiene por
        // compatibilidad con la clase abstracta.
        return GET("$baseUrl/series?p=$page", headers)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // Si no conocemos el total de páginas todavía, lo obtenemos pidiendo la
        // página 1 del sitio y leyendo su paginador.
        val totalPages = popularTotalPages ?: run {
            val firstDocument = getDocumentWithRetry("$baseUrl/series?p=1")
            val total = parseLastPageNumber(firstDocument)
            popularTotalPages = total
            // La página 1 del sitio es la más antigua, que corresponde al último
            // "page" que pedirá Aniyomi: la cacheamos ya parseada para no repetirla.
            popularPageCache[1] = firstDocument.select(popularAnimeSelector())
                .filter { el -> el.selectFirst("a.uk-position-cover") != null }
                .map { el -> popularAnimeFromElement(el) }
                .filter { it.url.isNotBlank() }
            total
        }

        // Traducimos la página que pide Aniyomi a la página real del sitio,
        // recorrida de más reciente a más antigua.
        val realPage = totalPages - page + 1
        if (realPage < 1) return AnimesPage(emptyList(), false)

        val animesInSiteOrder = popularPageCache.getOrPut(realPage) {
            val document = getDocumentWithRetry("$baseUrl/series?p=$realPage")
            document.select(popularAnimeSelector())
                .filter { el -> el.selectFirst("a.uk-position-cover") != null }
                .map { el -> popularAnimeFromElement(el) }
                .filter { it.url.isNotBlank() }
        }

        // Dentro de cada página, más nuevo arriba, más viejo abajo.
        val animes = animesInSiteOrder.reversed()

        val hasNext = page < totalPages
        return AnimesPage(animes, hasNext)
    }

    // Lee el número de la última página del paginador de UIkit, p. ej.:
    // <li class="page-item"><a href="?p=7">7</a></li>
    private fun parseLastPageNumber(document: Document): Int {
        val pageLinks = document.select("ul.uk-pagination li.page-item a[href]")
        val activePage = document.selectFirst("ul.uk-pagination li.uk-active span")?.text()?.toIntOrNull() ?: 1
        val maxFromLinks = pageLinks.mapNotNull { link ->
            Regex("""[?&]p=(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: activePage
        return maxOf(activePage, maxFromLinks)
    }

    // Reintenta ante fallos de conexión transitorios (p. ej. errores de HTTP/2)
    // antes de rendirse.
    private suspend fun getDocumentWithRetry(url: String, maxAttempts: Int = 3): Document {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return client.newCall(GET(url, headers)).execute().asJsoup()
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts - 1) delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("No se pudo completar la petición a $url")
    }

    override fun popularAnimeSelector(): String = "div#tm-right-section div.uk-grid div.uk-margin-bottom"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("a.uk-position-cover") ?: return@apply
        url = anchor.attr("href").removePrefix(baseUrl)
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("h5.uk-panel-title")?.text() ?: ""
    }

    // Evitamos :has() por compatibilidad con versiones viejas de Jsoup; en su lugar
    // sobreescribimos popularAnimeParse y detectamos la siguiente página manualmente.
    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage {
        // No se usa: getPopularAnime maneja el flujo directamente.
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .filter { el -> el.selectFirst("a.uk-position-cover") != null }
            .map { el -> popularAnimeFromElement(el) }
            .filter { it.url.isNotBlank() }
        val hasNext = document.select("ul.uk-pagination li.uk-disabled a[href]").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    // "Recientes" toma la sección "Últimos episodios" de la home, que lista episodios
    // (una serie se repite por cada episodio reciente). Deduplicamos quedándonos con la
    // primera aparición de cada serie, que es su capítulo más reciente. No hay paginación
    // real, así que siempre devolvemos hasNextPage = false. Se cachea en memoria para no
    // repetir la petición a la home dentro de la misma sesión.
    private var latestAnimesCache: List<SAnime>? = null

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        latestAnimesCache?.let { return AnimesPage(it, false) }

        val document = getDocumentWithRetry(baseUrl)
        val animes = document.select(latestUpdatesSelector())
            .filter { el -> el.selectFirst("p#episode") != null } // descarta "Películas recientes"
            .map { el -> latestUpdatesFromElement(el) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }

        latestAnimesCache = animes
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    // La home también tiene "Películas recientes" con la misma clase contenedora, así que
    // distinguimos por la presencia de <p id="episode"> (las películas no lo tienen).
    override fun latestUpdatesSelector(): String = "div#tm-right-section div.uk-grid div.uk-width-1-3.uk-margin-bottom"

    // Cada tarjeta tiene dos <a class="uk-position-cover">: una al episodio y otra a la
    // serie. Nos interesa la de la serie para el listado de "Recientes".
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val animeAnchor = element.select("a.uk-position-cover")
            .firstOrNull { !it.attr("href").contains("watch=1") }
        url = animeAnchor?.attr("href")?.removePrefix(baseUrl) ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("h5.uk-panel-title")?.text()?.trim() ?: ""
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage {
        // No se usa: getLatestUpdates maneja el flujo directamente (incluyendo caché).
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector())
            .filter { el -> el.selectFirst("p#episode") != null } // descarta "Películas recientes"
            .map { el -> latestUpdatesFromElement(el) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        // No se usa: getSearchAnime maneja el flujo directamente.
        return popularAnimeParse(response)
    }

    // Cachés de las opciones de año/género leídas del <select> real de la web, separadas
    // por tipo de contenido porque /series y /movies tienen listas distintas. Se cargan en
    // segundo plano (ver init{}) sin bloquear ninguna pantalla.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class FilterOptions(
        val genres: List<Pair<String, String>>,
        val years: List<Pair<String, String>>,
    )

    private enum class FiltersState { NOT_FETCHED, FETCHING, FETCHED }

    private var seriesFilterOptions: FilterOptions? = null
    private var seriesFiltersState = FiltersState.NOT_FETCHED
    private var seriesFetchAttempts = 0

    private var moviesFilterOptions: FilterOptions? = null
    private var moviesFiltersState = FiltersState.NOT_FETCHED
    private var moviesFetchAttempts = 0

    init {
        fetchFilterOptions(isMovie = false)
    }

    // Lanza en segundo plano la carga de género/año para el tipo indicado, si todavía no
    // se cargó y no se agotaron los reintentos. Es seguro llamarla más de una vez.
    private fun fetchFilterOptions(isMovie: Boolean) {
        val currentState = if (isMovie) moviesFiltersState else seriesFiltersState
        val attempts = if (isMovie) moviesFetchAttempts else seriesFetchAttempts
        if (currentState != FiltersState.NOT_FETCHED || attempts >= 3) return

        if (isMovie) {
            moviesFiltersState = FiltersState.FETCHING
            moviesFetchAttempts++
        } else {
            seriesFiltersState = FiltersState.FETCHING
            seriesFetchAttempts++
        }

        scope.launch {
            try {
                val path = if (isMovie) "movies" else "series"
                val response = client.newCall(GET("$baseUrl/$path", headers)).execute()
                val document = response.asJsoup()

                fun parseSelectOptions(selectName: String): List<Pair<String, String>> {
                    val select = document.selectFirst("select[name=$selectName]") ?: return emptyList()
                    return select.select("option[value]")
                        .filter { it.attr("value").isNotBlank() }
                        .map { it.text().trim() to it.attr("value") }
                }

                val options = FilterOptions(
                    genres = parseSelectOptions("genre"),
                    years = parseSelectOptions("year"),
                )

                if (isMovie) {
                    moviesFilterOptions = options
                    moviesFiltersState = FiltersState.FETCHED
                } else {
                    seriesFilterOptions = options
                    seriesFiltersState = FiltersState.FETCHED
                }
            } catch (_: Exception) {
                if (isMovie) moviesFiltersState = FiltersState.NOT_FETCHED else seriesFiltersState = FiltersState.NOT_FETCHED
            }
        }
    }

    // Trae TODOS los resultados de un tipo (series o movies), recorriendo todas sus
    // páginas de una vez en orden de más nuevo a más viejo. Se usa solo para "Tipo = Todos",
    // donde no tiene sentido paginar dos catálogos con conteos independientes a la vez.
    private suspend fun fetchAllResults(isMovie: Boolean, query: String, genreValue: String?, yearValue: String?): List<SAnime> {
        val path = if (isMovie) "movies" else "series"
        fun buildUrl(realPage: Int): String = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("title", query)
            genreValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("genre", it) }
            yearValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("year", it) }
            addQueryParameter("p", realPage.toString())
        }.build().toString()

        val firstDocument = getDocumentWithRetry(buildUrl(1))
        val totalPages = parseLastPageNumber(firstDocument)

        val result = mutableListOf<SAnime>()
        for (realPage in totalPages downTo 1) {
            val document = if (realPage == 1) firstDocument else getDocumentWithRetry(buildUrl(realPage))
            result += document.select(searchAnimeSelector())
                .filter { el -> el.selectFirst("a.uk-position-cover") != null }
                .map { el -> searchAnimeFromElement(el) }
                .filter { it.url.isNotBlank() }
                .reversed()
        }
        return result
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val typeValue = typeFilter?.toUriPart() ?: "all"

        val genreValue = (filters.find { it is GenreFilter } as? GenreFilter)?.toUriPart()
        val yearValue = (filters.find { it is YearFilter } as? YearFilter)?.toUriPart()
        val hasActiveFilters = query.isNotBlank() || !genreValue.isNullOrBlank() || !yearValue.isNullOrBlank()

        // Dispara en segundo plano la carga de género/año de Películas, por si el usuario
        // cambió el Tipo y aún no se cargaron sus opciones. Las de Series ya se disparan
        // siempre desde init{}.
        if (typeValue == "movies" || typeValue == "all") fetchFilterOptions(isMovie = true)

        // Sin filtros, sin búsqueda y con Tipo = Series: es el catálogo "populares", así que
        // reutilizamos su misma lógica de orden invertido y caché.
        if (!hasActiveFilters && typeValue == "series") {
            return getPopularAnime(page)
        }

        // Tipo = Todos, sin búsqueda ni filtro: paginamos Series en el orden natural del
        // sitio y, en la primera página, agregamos también todas las películas existentes.
        if (typeValue == "all" && !hasActiveFilters) {
            val seriesDocument = getDocumentWithRetry("$baseUrl/series?p=$page")
            val seriesResults = seriesDocument.select(searchAnimeSelector())
                .filter { el -> el.selectFirst("a.uk-position-cover") != null }
                .map { el -> searchAnimeFromElement(el) }
                .filter { it.url.isNotBlank() }
            val seriesTotalPages = parseLastPageNumber(seriesDocument)
            val hasNext = page < seriesTotalPages

            if (page == 1) {
                val movieResults = fetchAllResults(isMovie = true, query = "", genreValue = null, yearValue = null)
                return AnimesPage(seriesResults + movieResults, hasNext)
            }
            return AnimesPage(seriesResults, hasNext)
        }

        // Tipo = Todos, con búsqueda/filtro activo: traemos series y películas completas
        // para esa búsqueda y las combinamos en una sola lista, sin paginación real.
        if (typeValue == "all") {
            if (page > 1) return AnimesPage(emptyList(), false)
            val seriesResults = fetchAllResults(isMovie = false, query, genreValue, yearValue)
            val movieResults = fetchAllResults(isMovie = true, query, genreValue, yearValue)
            return AnimesPage(seriesResults + movieResults, false)
        }

        val isMovie = typeValue == "movies"
        val path = if (isMovie) "movies" else "series"

        fun buildUrl(realPage: Int): String = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("title", query)
            genreValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("genre", it) }
            yearValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("year", it) }
            addQueryParameter("p", realPage.toString())
        }.build().toString()

        // Con filtros/búsqueda activos, el sitio también pagina de más antiguo a más
        // reciente, así que aplicamos el mismo criterio de total de páginas invertido.
        val firstDocument = getDocumentWithRetry(buildUrl(1))
        val totalPages = parseLastPageNumber(firstDocument)

        val realPage = totalPages - page + 1
        if (realPage < 1) return AnimesPage(emptyList(), false)

        val document = if (realPage == 1) {
            firstDocument
        } else {
            getDocumentWithRetry(buildUrl(realPage))
        }

        val animes = document.select(searchAnimeSelector())
            .filter { el -> el.selectFirst("a.uk-position-cover") != null }
            .map { el -> searchAnimeFromElement(el) }
            .filter { it.url.isNotBlank() }
            .reversed()

        val hasNext = page < totalPages
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // No se usa: getSearchAnime maneja el flujo directamente.
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val isMovie = typeFilter?.toUriPart() == "movies"
        val path = if (isMovie) "movies" else "series"
        return GET("$baseUrl/$path?p=$page", headers)
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
        val rawName = element.selectFirst("dt")?.text()?.trim() ?: "Episodio"
        val display = FilenameUtils.buildEpisodeDisplay(rawName, showFilename)
        val relUrl = element.selectFirst("a.uk-position-cover")?.attr("href") ?: ""
        return SEpisode.create().apply {
            name = display.name
            url = relUrl // se convierte a absoluta en episodeListParse
            episode_number = display.episodeNumber
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // Las películas no tienen lista de episodios: un solo "episodio" que
        // apunta a single-movie?watch=1 dentro de la misma URL de la película.
        if (anime.url.contains("/movie/")) {
            val movieUrl = baseUrl + anime.url
            return listOf(
                SEpisode.create().apply {
                    name = "Película"
                    url = "$movieUrl/single-movie?watch=1"
                    episode_number = 1F
                },
            )
        }
        val response = client.newCall(episodeListRequest(anime)).execute()
        return episodeListParse(response)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        // La URL de la serie es .../serie/7/claymore y el episodio tiene href relativo
        // "single-serie?watch=1&episode=106"; la URL absoluta correcta reemplaza el slug
        // por ese href: .../serie/7/single-serie?watch=1&episode=106
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

        return gdExtractor.videosFromUrl(fileId)
    }

    // ============================== Filters ===============================

    // Género y Año se cargan dinámicamente desde el <select> real de /series o /movies
    // (ver fetchFilterOptions), así que si el sitio agrega opciones nuevas aparecen solas.
    // La carga para "series" se dispara desde init{}; si todavía no terminó (o el usuario
    // cambió a Películas), se muestra un aviso para reintentar con "Restablecer".
    override fun getFilterList(): AnimeFilterList {
        fetchFilterOptions(isMovie = false)

        val genreOptions = seriesFilterOptions?.genres ?: emptyList()
        val yearOptions = seriesFilterOptions?.years ?: emptyList()

        val filters = mutableListOf<AnimeFilter<*>>(
            TypeFilter(),
            AnimeFilter.Header("Los filtros no funcionan junto con búsqueda de texto"),
        )

        if (seriesFilterOptions == null) {
            filters.add(AnimeFilter.Header("Presione 'Restablecer' para intentar cargar Género/Año"))
        } else {
            filters.add(GenreFilter(genreOptions))
            filters.add(YearFilter(yearOptions))
        }

        return AnimeFilterList(filters)
    }

    private class TypeFilter :
        UriPartFilter(
            "Tipo de contenido",
            arrayOf(
                Pair("Todos", "all"),
                Pair("Series", "series"),
                Pair("Películas", "movies"),
            ),
        )

    private class GenreFilter(dynamicOptions: List<Pair<String, String>>) :
        UriPartFilter(
            "Género",
            (listOf(Pair("Todos", "")) + dynamicOptions).toTypedArray(),
        )

    private class YearFilter(dynamicOptions: List<Pair<String, String>>) :
        UriPartFilter(
            "Año",
            // Más reciente primero, más antiguo al final.
            (listOf(Pair("Todos", "")) + dynamicOptions.sortedByDescending { it.first.toIntOrNull() ?: 0 })
                .toTypedArray(),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================ Preferencias ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = WingZeroPlusPreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del episodio.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )
    }
}
