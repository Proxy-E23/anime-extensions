package eu.kanade.tachiyomi.animeextension.es.vampirnofansub

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

class VampirNoFansub : ParsedAnimeHttpSource() {

    override val name = "Vampir no Fansub"
    override val baseUrl = "https://vampirnofansubs2.wordpress.com"
    override val lang = "es"
    override val supportsLatest = true

    // ============================== Popular ================================
    // El catálogo "popular" real de este fansub son las páginas estáticas
    // /animes-bd/ (series) y /animes-bd-2/ (películas), que listan TODO lo
    // publicado con portada. No hay paginación real: es una sola página larga.

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-bd/", headers)

    override fun popularAnimeSelector(): String = "div.entry-content figure.wp-caption"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("a") ?: return@apply
        setUrlWithoutDomain(anchor.attr("abs:href"))
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:src").takeIf { it.isNotBlank() }
                ?: img.attr("abs:data-orig-file")
        }
        title = element.selectFirst("figcaption strong")?.text()?.trim() ?: ""
    }

    // Todo viene en una sola página: no hay siguiente.
    override fun popularAnimeNextPageSelector(): String? = null

    // ============================== Latest ==================================
    // "Últimos" = portada del blog (entradas recientes con paginación /page/N/).

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) GET(baseUrl, headers) else GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesSelector(): String = "article.post"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("h2.entry-title a") ?: return@apply
        setUrlWithoutDomain(anchor.attr("abs:href"))
        title = anchor.text().trim()
        thumbnail_url = element.selectFirst("a.entry-thumbnail img")?.let { img ->
            img.attr("abs:src").takeIf { it.isNotBlank() }
                ?: img.attr("abs:data-orig-file")
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = "div.nav-previous a"

    // ============================== Search ==================================
    // Búsqueda nativa de WordPress (?s=), y si además hay texto y el filtro de
    // Tipo está activo, filtramos localmente el catálogo estático por título.

    override fun searchAnimeSelector(): String = latestUpdatesSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = latestUpdatesFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                if (page > 1) addPathSegment("page").addPathSegment(page.toString())
            }.build()
            return GET(url, headers)
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val path = if (typeFilter?.toUriPart() == "movies") "animes-bd-2" else "animes-bd"
        return GET("$baseUrl/$path/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Si la respuesta es una de las páginas estáticas de catálogo, usamos ese parser.
        if (document.select(popularAnimeSelector()).isNotEmpty() && document.select(searchAnimeSelector()).isEmpty()) {
            return popularAnimeParse(response)
        }
        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
        val hasNext = searchAnimeNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        InfoFilter("El filtro de Tipo solo aplica cuando la búsqueda está vacía."),
    )

    // ============================== Details =================================
    //
    // IMPORTANTE — cómo funciona esta extensión ahora mismo:
    // Este fansub protege sus enlaces de descarga (MediaFire) detrás de
    // ouo.io, que a su vez está protegido por un Cloudflare Turnstile que
    // NO se puede resolver de forma automática (ni con OkHttp, ni con un
    // WebView invisible controlado por código): Cloudflare exige una
    // interacción humana real, y no hay ninguna API pública en el SDK de
    // Tachiyomi/Aniyomi que permita a una extensión abrir su propio WebView
    // y recibir de vuelta la URL a la que el usuario navegó manualmente.
    //
    // Por eso esta extensión NO intenta resolver nada automáticamente.
    // Funciona únicamente como catálogo: la sinopsis incluye el enlace
    // directo a la carpeta contenedora de MediaFire en 1080p (vía ouo.io)
    // como un link real, para que el usuario lo toque, lo resuelva
    // manualmente en su navegador (recomendado: uno con soporte de
    // bypass de acortadores, p.ej. Firefox + FastForward), y luego use la
    // URL de MediaFire resultante en una extensión de MediaFire aparte.

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val content = document.selectFirst("div.entry-content")

        title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("""\s*\[.*?]\s*"""), " ")
            ?.replace(Regex("""\s*\(\d+/(\d+|\?+)\)\s*"""), " ")
            ?.trim()
            ?: ""

        thumbnail_url = content?.selectFirst("img")?.let { img ->
            img.attr("abs:src").takeIf { it.isNotBlank() } ?: img.attr("abs:data-orig-file")
        }

        // La sinopsis está entre el encabezado "SINOPSIS" y el siguiente <hr>.
        val paragraphs = content?.select("p") ?: emptyList()
        val sinopsisIndex = paragraphs.indexOfFirst { it.text().contains("SINOPSIS", ignoreCase = true) }
        val baseDescription = if (sinopsisIndex != -1 && sinopsisIndex + 1 < paragraphs.size) {
            paragraphs[sinopsisIndex + 1].text().trim()
        } else {
            // Fallback: primer párrafo largo del post.
            paragraphs.map { it.text().trim() }.firstOrNull { it.length > 40 } ?: ""
        }

        val folderLink1080 = content?.let { findOuoFolderLink1080p(it) }

        description = buildString {
            if (folderLink1080 != null) {
                append("⚠️ Este fansub protege sus descargas con ouo.io (Cloudflare). ")
                append("Esta extensión NO resuelve el enlace automáticamente: toca el link de abajo, ")
                append("resuélvelo en tu navegador (recomendado: uno con bypass de acortadores, ")
                append("p.ej. Firefox + FastForward) y usa la URL de MediaFire resultante en tu ")
                append("extensión de MediaFire.\n\n")
                append("🔗 Carpeta 1080p: $folderLink1080\n\n")
                append("---\n\n")
            } else {
                append("⚠️ No se encontró un enlace de carpeta contenedora (1080p) en esta publicación. ")
                append("Es posible que aún no se haya publicado, o que solo existan enlaces por episodio. ")
                append("Abre la publicación completa en WebView para revisar manualmente.\n\n")
                append("---\n\n")
            }
            append(baseDescription)
        }

        // Bloque INFORMACIÓN suele venir en un <pre> con líneas "CLAVE: valor".
        val infoBlock = content?.selectFirst("pre")?.text() ?: ""
        val genreLine = Regex("""GÉNERO:\s*(.+)""").find(infoBlock)?.groupValues?.get(1)?.trim()
        genre = genreLine

        val chaptersLine = Regex("""C?APÍTULOS:\s*(\d+)\s*/\s*(\d+|\?+)""").find(infoBlock)
        status = when {
            chaptersLine == null -> SAnime.UNKNOWN
            chaptersLine.groupValues[2].contains("?") -> SAnime.ONGOING
            chaptersLine.groupValues[1] == chaptersLine.groupValues[2] -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }

        initialized = true
    }

    /**
     * Busca, dentro del contenido del post, el enlace de ouo.io correspondiente
     * a la carpeta contenedora (no a episodios sueltos) en su versión 1080p.
     *
     * Estrategia:
     *  1. Localiza el elemento que contiene el texto "CARPETA CONTENEDORA"
     *     (indicador de que a partir de ahí vienen los links de la carpeta
     *     completa, no de episodios individuales).
     *  2. Entre los elementos posteriores a ese punto, busca enlaces a
     *     ouo.io cuyo texto visible, o el alt/title de una imagen hija,
     *     contenga "1080".
     *  3. Si no se localiza el marcador "CARPETA CONTENEDORA" (posts viejos
     *     con otro formato), se hace un fallback: se toma el ÚLTIMO enlace
     *     ouo.io del post que cumpla el criterio de "1080", asumiendo que
     *     los enlaces de la carpeta contenedora suelen ir al final.
     */
    private fun findOuoFolderLink1080p(content: Element): String? {
        val allElements = content.children()
        val markerIndex = allElements.indexOfFirst {
            it.text().contains("CARPETA CONTENEDORA", ignoreCase = true)
        }

        fun linkIs1080(a: Element): Boolean {
            val linkText = a.text()
            if (linkText.contains("1080", ignoreCase = true)) return true
            val imgAlt = a.selectFirst("img")?.let { img ->
                img.attr("alt") + " " + img.attr("data-image-title")
            } ?: ""
            return imgAlt.contains("1080", ignoreCase = true)
        }

        if (markerIndex != -1) {
            val candidateLinks = allElements.subList(markerIndex, allElements.size)
                .flatMap { it.select("a[href*=ouo.io]") }
            candidateLinks.firstOrNull { linkIs1080(it) }?.let { return it.attr("abs:href") }
            // Si no hay ninguno marcado como 1080 tras el marcador, devolvemos
            // el primer enlace ouo.io que aparezca ahí (mejor que nada).
            candidateLinks.firstOrNull()?.let { return it.attr("abs:href") }
        }

        // Fallback sin marcador: último enlace ouo.io del post marcado como 1080.
        val allOuoLinks = content.select("a[href*=ouo.io]")
        return allOuoLinks.lastOrNull { linkIs1080(it) }?.attr("abs:href")
    }

    // ============================== Episodes ================================
    //
    // Esta extensión funciona solo como catálogo (ver nota en animeDetailsParse):
    // no genera episodios reales de descarga, ya que no es posible resolver
    // ouo.io automáticamente. Se deja un único episodio informativo que, al
    // intentar reproducirse, explica cómo proceder manualmente.

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = "info::${response.request.url}"
            name = "ℹ️ Ver sinopsis: enlace de descarga manual (ouo.io)"
            episode_number = 1f
        },
    )

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================== Videos ==================================

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw Exception(
        "Esta extensión no descarga videos automáticamente. " +
            "Revisa la sinopsis de la serie: ahí encontrarás el enlace de ouo.io " +
            "a la carpeta de MediaFire (1080p). Resuélvelo manualmente en tu navegador " +
            "y usa la URL resultante en tu extensión de MediaFire.",
    )
}
