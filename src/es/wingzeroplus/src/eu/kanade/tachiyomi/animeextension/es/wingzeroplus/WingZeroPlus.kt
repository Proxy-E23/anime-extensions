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

class WingZeroPlus : ParsedAnimeHttpSource() {

    override val name = "Wing Zero Plus"
    override val baseUrl = "https://plus.wing-zero-network.org"
    override val lang = "es"
    override val supportsLatest = true

    private val gdExtractor by lazy { GoogleDriveExtractor(client, headers) }

    // ============================== Popular ===============================

    // Por defecto mostramos Series en "popular". El filtro de Tipo permite cambiar a Películas
    // desde la búsqueda (Aniyomi aplica los filtros incluso sin texto de búsqueda).
    private var contentType = "series"

    // El sitio pagina "series" del más antiguo (página 1) al más reciente (última página).
    // Para mostrar lo más nuevo primero necesitamos saber cuántas páginas hay en total y
    // pedirlas en orden inverso. Cacheamos el total una vez conocido para no recalcularlo
    // en cada página (se resetea si el sitio cambia entre sesiones de la app).
    private var popularTotalPages: Int? = null

    // Cachea en memoria la lista de animes ya parseada de cada página REAL del sitio
    // (clave = número de página real, ya invertida). Así, si el usuario hace scroll hacia
    // arriba y abajo dentro de la misma sesión, no se repite la petición HTTP a una página
    // que ya se descargó antes. Se pierde si la extensión se recarga (nueva instancia).
    private val popularPageCache = mutableMapOf<Int, List<SAnime>>()

    override fun popularAnimeRequest(page: Int): Request {
        // No se usa directamente: la lógica real vive en getPopularAnime, que también
        // necesita hacer una petición extra para conocer el total de páginas.
        // Se mantiene por compatibilidad con la clase abstracta.
        return GET("$baseUrl/series?p=$page", headers)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // Si ya sabemos el total de páginas, no hace falta la petición extra: solo pedimos
        // la página real que corresponde. Si es la primera vez que se llama (no sabemos aún
        // el total), tenemos que pedir la página 1 del sitio (?p=1) para leer la paginación
        // y contar cuántas páginas hay en total.
        val totalPages = popularTotalPages ?: run {
            val firstDocument = getDocumentWithRetry("$baseUrl/series?p=1")
            val total = parseLastPageNumber(firstDocument)
            popularTotalPages = total
            // Esa respuesta de ?p=1 es la página MÁS ANTIGUA del sitio: la cacheamos ya
            // parseada, porque corresponde al ÚLTIMO "page" que Aniyomi pida al llegar al
            // final del scroll (page == totalPages), y así evitamos pedirla dos veces.
            popularPageCache[1] = firstDocument.select(popularAnimeSelector())
                .filter { el -> el.selectFirst("a.uk-position-cover") != null }
                .map { el -> popularAnimeFromElement(el) }
                .filter { it.url.isNotBlank() }
            total
        }

        // Traducimos la página que pide Aniyomi (1, 2, 3...) a la página real del sitio,
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

        // Dentro de cada página, más nuevo arriba / más viejo abajo.
        val animes = animesInSiteOrder.reversed()

        val hasNext = page < totalPages
        return AnimesPage(animes, hasNext)
    }

    // Lee el número de la última página del paginador de UIkit, p. ej.:
    // <li class="page-item"><a href="?p=7">7</a></li>
    // Si no hay paginación (solo 1 página), devuelve 1.
    private fun parseLastPageNumber(document: Document): Int {
        val pageLinks = document.select("ul.uk-pagination li.page-item a[href]")
        val activePage = document.selectFirst("ul.uk-pagination li.uk-active span")?.text()?.toIntOrNull() ?: 1
        val maxFromLinks = pageLinks.mapNotNull { link ->
            Regex("""[?&]p=(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: activePage
        return maxOf(activePage, maxFromLinks)
    }

    // Ejecuta una petición GET y la reintenta ante fallos de conexión transitorios
    // (p. ej. errores de HTTP/2 como TYPE_GOAWAY / -902) antes de rendirse. Se usa en las
    // peticiones de listado porque, al pedir 2 páginas seguidas para calcular el orden
    // invertido, aumenta la chance de toparse con este tipo de error de conexión puntual.
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

    // Evitamos :has() porque puede no estar soportado en versiones viejas de Jsoup.
    // En su lugar sobreescribimos popularAnimeParse y detectamos la siguiente página manualmente.
    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage {
        // No se usa: getPopularAnime maneja todo el flujo directamente.
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .filter { el -> el.selectFirst("a.uk-position-cover") != null }
            .map { el -> popularAnimeFromElement(el) }
            .filter { it.url.isNotBlank() }
        val hasNext = document.select("ul.uk-pagination li.uk-disabled a[href]").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    // "Recientes" toma la sección "Últimos episodios" de la home. Esa sección lista
    // EPISODIOS (se repite la misma serie una vez por cada episodio reciente que tenga),
    // así que acá deduplicamos quedándonos con la primera aparición de cada serie: como la
    // lista ya viene ordenada de más reciente a más antiguo, la primera aparición de una
    // serie es su capítulo más reciente. No hay paginación real: es una lista fija y corta,
    // así que siempre devolvemos hasNextPage = false.
    //
    // Se cachea en memoria (como hacen googledrive/mediafire en este mismo repo) para no
    // volver a pedir la home cada vez que se abre la pestaña "Recientes" dentro de la misma
    // sesión de la extensión.
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

    // OJO: la home también tiene una sección "Películas recientes" con la MISMA clase
    // contenedora (div.uk-width-1-3.uk-margin-bottom), así que no alcanza con esa clase para
    // distinguirlas. La marca inequívoca de "Últimos episodios" es el <p id="episode"> dentro
    // de la tarjeta (las películas no lo tienen); filtramos por su presencia en el parse,
    // evitando el selector CSS :has() por consistencia con el resto de la extensión.
    override fun latestUpdatesSelector(): String = "div#tm-right-section div.uk-grid div.uk-width-1-3.uk-margin-bottom"

    // Cada tarjeta de "Últimos episodios" tiene DOS <a class="uk-position-cover">: una que
    // apunta al episodio (.../single-serie?watch=1&episode=N) y otra que apunta a la serie
    // (.../serie/ID/slug). Nos interesa esta última para el listado de "Recientes".
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val animeAnchor = element.select("a.uk-position-cover")
            .firstOrNull { !it.attr("href").contains("watch=1") }
        url = animeAnchor?.attr("href")?.removePrefix(baseUrl) ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("h5.uk-panel-title")?.text()?.trim() ?: ""
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage {
        // No se usa: getLatestUpdates maneja todo el flujo directamente (incluyendo caché).
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
        // No se usa: getSearchAnime maneja todo el flujo directamente.
        return popularAnimeParse(response)
    }

    // Cachés en memoria de las opciones de año/género leídas del <select> real de la web,
    // separadas por tipo de contenido porque /series y /movies tienen listas distintas.
    // Se cargan en segundo plano (ver init{} más abajo) apenas se instancia la extensión,
    // sin bloquear ninguna pantalla; así, si el sitio agrega un año o género nuevo, aparece
    // solo la próxima vez que se abra el filtro, sin tocar el código.
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

    // Lanza en segundo plano (sin bloquear) la carga de género/año para el tipo indicado,
    // si todavía no se cargaron y no se agotaron los reintentos. Se puede volver a invocar
    // sin problema: si ya está en curso o ya cargó, no hace nada.
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

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val isMovie = typeFilter?.toUriPart() == "movies"
        val path = if (isMovie) "movies" else "series"

        // Dispara en segundo plano (no bloqueante) la carga de género/año para este tipo,
        // por si el usuario cambió a Películas y aún no se cargaron sus opciones.
        fetchFilterOptions(isMovie)

        val genreValue = (filters.find { it is GenreFilter } as? GenreFilter)?.toUriPart()
        val yearValue = (filters.find { it is YearFilter } as? YearFilter)?.toUriPart()
        val hasActiveFilters = query.isNotBlank() || !genreValue.isNullOrBlank() || !yearValue.isNullOrBlank()

        fun buildUrl(realPage: Int): String = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("title", query)
            genreValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("genre", it) }
            yearValue?.takeIf { it.isNotBlank() }?.let { addQueryParameter("year", it) }
            addQueryParameter("p", realPage.toString())
        }.build().toString()

        // Sin filtros, sin búsqueda de texto y con Tipo = Series: es exactamente el
        // catálogo "populares", reutilizamos la misma lógica de orden invertido global
        // (incluye la precarga de filtros y el caché de total de páginas de populares).
        if (!hasActiveFilters && !isMovie) {
            return getPopularAnime(page)
        }

        // Con filtros/búsqueda activos, el sitio también pagina de más antiguo a más
        // reciente, así que aplicamos el mismo criterio: total real de páginas primero,
        // luego navegamos desde la última hacia atrás.
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
        // No se usa: getSearchAnime maneja todo el flujo directamente.
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
        val name = element.selectFirst("dt")?.text()?.trim() ?: "Episodio"
        val epNum = EP_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
        val relUrl = element.selectFirst("a.uk-position-cover")?.attr("href") ?: ""
        return SEpisode.create().apply {
            this.name = name
            url = relUrl // se convierte a absoluta en episodeListParse
            episode_number = epNum
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

    // Género y Año se cargan dinámicamente desde el <select> real de /series o /movies
    // (ver fetchFilterOptions), así que sus opciones no están hardcodeadas: si el sitio
    // agrega un año o género nuevo, aparecerá solo, sin tocar el código.
    //
    // La carga para "series" se dispara en segundo plano desde init{}, apenas se instancia
    // la extensión, así que normalmente ya está lista para cuando el usuario abre el panel
    // de filtros. Si aún no terminó (o el usuario cambia a Películas, cuyas opciones recién
    // se disparan en la primera búsqueda de ese tipo), se muestra un aviso para reintentar
    // presionando "Restablecer" en el panel de filtros.
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

    companion object {
        private val EP_NUMBER_REGEX = Regex(
            """Episodio\s+0*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
