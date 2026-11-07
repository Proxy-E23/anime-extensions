package eu.kanade.tachiyomi.animeextension.es.elabismodedarkrai

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.megaextractor.MegaLink
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ElAbismoDeDarkrai :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "El Abismo de Darkrai"
    override val baseUrl = "https://sites.google.com/view/elabismodedarkrai"
    override val lang = "es"
    override val supportsLatest = false

    // Las miniaturas viven en el mismo Google Sites que las páginas y, al llenarse
    // la pantalla, el servidor rechaza o corta parte de las peticiones (la app
    // muestra el ícono de imagen rota hasta que se reabre). Solo para las URLs de
    // miniaturas:
    // 1. Reintentos (capa externa): se repiten las que fallan, incluidas las
    //    descargas que se cortan a mitad (ese error ocurre al leer la imagen, por
    //    eso la caché va por dentro de esta capa).
    // 2. Caché en disco propia: una miniatura ya descargada se sirve desde el
    //    archivo y no vuelve a pedirse, ni siquiera tras cerrar y reabrir la app.
    // 3. Se espacian las peticiones que sí van a la red.
    // El resto del tráfico (páginas, MEGA) no se toca.
    override val client: OkHttpClient by lazy {
        val thumbnailDir = File(Injekt.get<Application>().cacheDir, "source_${id}_thumbs")
        super.client.newBuilder()
            .rateLimit(permits = THUMBNAIL_REQUESTS_PER_SECOND, period = 1.seconds) { isThumbnailUrl(it) }
            .addInterceptor(ThumbnailRetryInterceptor())
            .addInterceptor(ThumbnailDiskCacheInterceptor(thumbnailDir))
            .build()
    }

    private val extractor by lazy { MegaExtractor(client) }

    private val preferences by getPreferencesLazy()

    private val showFilename: Boolean
        get() = DarkraiPreferences.showFilename(preferences)

    // ============================ Caché de miniaturas ===========================
    // Persistente entre sesiones/reinicios de la app (SharedPreferences propias,
    // no la caché de sesión de más abajo). Clave = url del anime (el link de
    // MEGA, único por entrada), valor = thumbnail_url visto la última vez. Si el
    // sitio cambia la miniatura de una entrada, se actualiza sola la próxima vez
    // que esa categoría se vuelva a descargar (no hace falta invalidar a mano).
    private val thumbnailPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_thumbnails", Context.MODE_PRIVATE)
    }

    private fun getCachedThumbnail(animeUrl: String): String? = thumbnailPrefs.getString(animeUrl, null)?.ifBlank { null }

    // Una sola escritura por página descargada (antes era una por cada card).
    private fun cacheThumbnails(thumbnails: Map<String, String>) {
        if (thumbnails.isEmpty()) return
        thumbnailPrefs.edit().apply { thumbnails.forEach { (url, thumb) -> putString(url, thumb) } }.apply()
    }

    // Google Sites sirve cada imagen a 1280 px de ancho (sufijo "=w1280"). Con
    // cientos de cards cargando a la vez, el servidor corta peticiones y la app
    // muestra el ícono de imagen rota. Pidiendo un tamaño de miniatura el peso
    // baja muchísimo. Si algún día las portadas se vieran mal, poner
    // THUMBNAIL_WIDTH = 0 desactiva el redimensionado.
    private fun normalizeThumbnail(src: String?): String? {
        val url = src?.trim()?.ifBlank { null } ?: return null
        if (url.startsWith("data:")) return null
        if (THUMBNAIL_WIDTH <= 0) return url
        return url.replace(WIDTH_SUFFIX_REGEX, "=w$THUMBNAIL_WIDTH")
    }

    private fun imageSrc(img: Element?): String? = normalizeThumbnail(img?.attr("src")?.ifBlank { null } ?: img?.attr("data-src"))

    // ============================ Categorías del sitio ==========================
    // El sitio no tiene capítulos: cada "card" de cada categoría YA es un link
    // directo (envuelto en un redirect de Google) hacia un archivo o carpeta de
    // MEGA. No hay fechas ni orden cronológico -> todo cae en Populares, y la
    // categoría real se elige con un filtro.

    private data class Category(val label: String, val path: String)

    private val categories = listOf(
        Category("Anime", "recuerdos-anime"),
        Category("Manga / Manhwa", "recuerdos-manga"),
        Category("Películas", "recuerdos-pelis"),
        Category("Juegos", "recuerdos-juegos"),
        Category("Congelador", "congelador-recuerdos"),
        Category("Eventos especiales", "eventos-especiales"),
    )

    // Un href interno que apunta al slug de una de las categorías de arriba
    // (ej. "/view/elabismodedarkrai/recuerdos-manga") no es una serie con
    // sub-páginas, es un link cruzado a otra categoría completa del sitio.
    private fun isCategoryLink(href: String): Boolean {
        val slug = href.substringAfterLast('/').substringBefore('?')
        return categories.any { it.path == slug }
    }

    // ============================== Popular ================================
    // Dos niveles de caché, igual que en el resto de tus extensiones:
    // - Sesión (en memoria, categoryCache/allCategoriesCache): vive mientras el
    //   proceso de la app no se reinicie. Evita re-descargar y re-parsear las
    //   páginas HTML de las 6 categorías en cada scroll/tab dentro de la misma
    //   sesión.
    // - Permanente (thumbnailPrefs, más arriba): sobrevive a reinicios de la
    //   app. Guarda solo la miniatura por entrada; si el sitio la cambia, se
    //   actualiza sola la próxima vez que esa categoría se descargue de nuevo
    //   (sesión nueva).
    // Populares combina las 6 categorías en una sola lista; el filtro de
    // categoría solo acota en Buscar.
    // synchronizedMap: getPopularAnime ahora dispara las 6 categorías en
    // paralelo, así que puede haber escrituras concurrentes sobre este mapa
    // (cada corrutina usa su propia key, pero un HashMap plano no garantiza
    // integridad interna con escrituras simultáneas).
    private val categoryCache = Collections.synchronizedMap(mutableMapOf<String, List<SAnime>>())
    private var allCategoriesCache: List<SAnime>? = null

    private fun currentCategoryPath(filters: AnimeFilterList): String? {
        val filter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        return filter?.getValue()?.ifBlank { null }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/${categories.first().path}", headers)

    // La lista completa ya está en memoria; se entrega en bloques para que la app
    // no pida cientos de miniaturas de golpe (las pide al hacer scroll).
    private fun List<SAnime>.paginate(page: Int): AnimesPage {
        val from = (page - 1) * PAGE_SIZE
        if (from >= size) return AnimesPage(emptyList(), false)
        val to = minOf(from + PAGE_SIZE, size)
        return AnimesPage(subList(from, to), to < size)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = loadAllCategories().paginate(page)

    private suspend fun loadAllCategories(): List<SAnime> {
        allCategoriesCache?.let { return it }

        // Las 6 categorías son requests independientes entre sí, así que se
        // piden todas en paralelo en vez de una por una: el tiempo total pasa
        // a ser el de la más lenta de las 6, no la suma de las 6.
        val combined = coroutineScope {
            categories.map { category ->
                async { category to getAnimeForCategory(category.path) }
            }.awaitAll().flatMap { (category, entries) ->
                entries.map { anime -> anime.apply { description = "Categoría: ${category.label}" } }
            }
        }.distinctBy { it.url }
        allCategoriesCache = combined
        return combined
    }

    private suspend fun getAnimeForCategory(categoryPath: String): List<SAnime> {
        categoryCache[categoryPath]?.let { return it }

        val document = client.newCall(GET("$baseUrl/$categoryPath", headers)).execute().asJsoup()
        val list = parseCategoryDocument(document)
        categoryCache[categoryPath] = list
        return list
    }

    // Cada "card" del listado de Google Sites es un bloque "div.oKdM2c" con la
    // imagen enlazada adentro; el título real vive en el bloque HERMANO
    // siguiente (div.oKdM2c sin la clase extra "Kzv0Me"), no dentro del mismo
    // contenedor. Ambos se resuelven a partir del <a> con la imagen.
    //
    // El título puede venir de varias formas (todas con la clase "zfr3Q"):
    // - un <p> normal, a veces partido en varios <span> ("D" + "r. Stone");
    // - un <h1> (encabezado), que es lo que usa el sitio en varias entradas
    //   (Darwin Incident, World Is Dancing, Yani Neko...); antes solo se
    //   buscaba "p.zfr3Q", por eso esas salían como "Sin título";
    // - varios elementos seguidos ("Sentenced to" + "be a Hero"), que se unen
    //   con un espacio.
    private fun titleBlockOf(imageBlock: Element): Element? {
        var container: Element? = imageBlock
        while (container != null && !container.hasClass("oKdM2c")) {
            container = container.parent()
        }
        return container?.nextElementSibling()
    }

    // El texto de un link incrustado en el título (ej. "(OVAS)" en "Konosuba
    // (OVAS)") no es parte del nombre de la card: es otro link de descarga y se
    // trata aparte en findInlineLinks().
    private fun findCardTitle(imageBlock: Element): String? {
        val titleBlock = titleBlockOf(imageBlock) ?: return null
        return titleBlock.select(".zfr3Q")
            .map { element -> element.clone().apply { select("a").remove() }.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(EMOJI_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .ifBlank { null }
    }

    // Links de MEGA escritos dentro del texto del título (ej. "(OVAS)"). Se
    // devuelven como (texto del link, href). Se ignora el ícono de "copiar
    // vínculo" de los encabezados (no tiene texto).
    private fun findInlineLinks(imageBlock: Element): List<Pair<String, String>> {
        val titleBlock = titleBlockOf(imageBlock) ?: return emptyList()
        return titleBlock.select(".zfr3Q a[href]").mapNotNull { a ->
            val label = a.text().trim()
            val href = a.attr("href")
            if (label.isBlank() || href.startsWith("#")) null else label to href
        }
    }

    // Convierte los links incrustados de una card en entradas propias, con el
    // título "<card> <texto del link>" (ej. "Konosuba (OVAS)").
    private fun inlineLinkEntries(
        imageBlock: Element,
        cardTitle: String?,
        thumbnail: String?,
        thumbnails: MutableMap<String, String>,
        mapTitle: (String) -> String = { it },
    ): List<SAnime> = findInlineLinks(imageBlock).mapNotNull { (label, href) ->
        val megaUrl = resolveGoogleRedirect(href)?.takeIf { it.contains("mega.nz") } ?: return@mapNotNull null
        if (thumbnail != null) thumbnails[megaUrl] = thumbnail
        SAnime.create().apply {
            title = mapTitle(listOfNotNull(cardTitle, label).joinToString(" "))
            thumbnail_url = thumbnail ?: getCachedThumbnail(megaUrl)
            url = megaUrl
        }
    }

    private fun parseCategoryDocument(document: Document): List<SAnime> {
        val results = mutableListOf<SAnime>()
        val thumbnails = mutableMapOf<String, String>()
        // href de la sub-página -> título de la card que la enlaza en la categoría
        // (suele ser el nombre real y completo de la serie, mejor que el <title>
        // abreviado de la propia sub-página).
        val subPages = linkedMapOf<String, String?>()

        document.select(popularAnimeSelector()).forEach { card ->
            val link = card.selectFirst("a")
            val img = card.selectFirst("img.CENy8b")
            val titleText = findCardTitle(card)

            val href = link?.attr("href")

            when {
                href.isNullOrBlank() -> return@forEach
                // Sub-página interna del propio sitio (una serie con varios enlaces
                // de descarga adentro, ej. temporadas): se resuelve aparte.
                // Se excluyen los slugs de las propias categorías (recuerdos-anime,
                // recuerdos-manga, etc.): a veces aparecen como link cruzado dentro
                // de otra categoría, y no son una serie sino la categoría completa;
                // tratarlas como sub-página anteponía "recuerdos manga - " a cada
                // card de esa categoría (ej. "recuerdos manga - Sousou no Frieren").
                (href.startsWith("/view/") || href.contains("sites.google.com/view/elabismodedarkrai")) &&
                    !isCategoryLink(href) -> {
                    if (href !in subPages) subPages[href] = titleText
                }
                // Card con el redirect de Google envolviendo un link de MEGA directo.
                else -> {
                    val megaUrl = resolveGoogleRedirect(href)
                    if (megaUrl != null && megaUrl.contains("mega.nz")) {
                        val liveThumbnail = imageSrc(img)
                        if (liveThumbnail != null) thumbnails[megaUrl] = liveThumbnail
                        results.add(
                            SAnime.create().apply {
                                title = titleText ?: "Sin título"
                                thumbnail_url = liveThumbnail ?: getCachedThumbnail(megaUrl)
                                url = megaUrl
                            },
                        )
                        results.addAll(inlineLinkEntries(card, titleText, liveThumbnail, thumbnails))
                    }
                }
            }
        }

        // Las sub-páginas se resuelven aparte porque cada una puede contener
        // varias entradas de MEGA (ej. varias temporadas de un mismo anime).
        subPages.forEach { (subHref, cardTitle) ->
            results.addAll(resolveSubPage(subHref, cardTitle, thumbnails))
        }

        cacheThumbnails(thumbnails)
        // El mismo link de MEGA puede aparecer dos veces; la url es el
        // identificador de la entrada en la app, así que se deja una sola.
        return results.distinctBy { it.url }
    }

    private fun resolveSubPage(href: String, categoryCardTitle: String?, thumbnails: MutableMap<String, String>): List<SAnime> {
        val fullUrl = if (href.startsWith("http")) href else "https://sites.google.com$href"
        val document = try {
            client.newCall(GET(fullUrl, headers)).execute().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val parentTitle = categoryCardTitle
            ?: document.selectFirst("title")?.text()?.substringAfter(" - ")?.trim()

        val entries = mutableListOf<SAnime>()
        document.select(popularAnimeSelector()).forEach { card ->
            val link = card.selectFirst("a") ?: return@forEach
            val img = card.selectFirst("img.CENy8b")
            val href2 = link.attr("href")
            if (href2.isBlank() || href2.startsWith("/view/")) return@forEach

            val megaUrl = resolveGoogleRedirect(href2) ?: return@forEach
            if (!megaUrl.contains("mega.nz")) return@forEach

            val titleText = findCardTitle(card)
            val liveThumbnail = imageSrc(img)
            if (liveThumbnail != null) thumbnails[megaUrl] = liveThumbnail

            entries.add(
                SAnime.create().apply {
                    title = buildEntryTitle(parentTitle, titleText)
                    thumbnail_url = liveThumbnail ?: getCachedThumbnail(megaUrl)
                    url = megaUrl
                },
            )
            entries.addAll(
                inlineLinkEntries(card, titleText, liveThumbnail, thumbnails) { buildEntryTitle(parentTitle, it) },
            )
        }
        return entries
    }

    // Dentro de una sub-página, el nombre de la serie solo se antepone cuando la
    // card NO se explica sola: "S1", "Temporada 2", "Parte 2", "OVAs",
    // "Especiales", "Película"... (ej. "Spy x Family - S2"). Si la card ya tiene
    // nombre propio ("FATE/ZERO", "La Desaparición de Nagato Yuki") se deja tal
    // cual; antes se le pegaba siempre el nombre de la página ("Fate Series -
    // FATE/ZERO", "Haruhi - La Desaparición de Nagato Yuki").
    private fun buildEntryTitle(parentTitle: String?, cardTitle: String?): String {
        val card = cardTitle?.ifBlank { null }
        val parent = parentTitle?.ifBlank { null }
        if (parent == null) return card ?: "Sin título"
        if (card == null) return parent
        if (card.contains(parent, ignoreCase = true)) return card
        return if (isGenericTitle(card)) "$parent - $card" else card
    }

    // Genérico = al quitar numeración/temporada/"película"/"ova"... no queda un
    // nombre (menos de 3 letras).
    private fun isGenericTitle(title: String): Boolean = title.replace(GENERIC_TOKENS_REGEX, "").replace(NON_LETTER_REGEX, "").length < 3

    // El sitio enlaza vía https://www.google.com/url?q=<url-encoded>&sa=D&... en
    // vez de al link de MEGA directo. Importante: el chequeo de redirect va
    // PRIMERO, porque el propio "mega.nz" url-encoded (%2Fmega.nz%2F...)
    // contiene el substring literal "mega.nz" sin decodificar, y un chequeo
    // "ya es directo" hecho antes devolvería el href sin decodificar.
    private fun resolveGoogleRedirect(href: String): String? {
        if (href.contains("google.com/url")) {
            val qParam = href.substringAfter("q=", "").substringBefore("&")
            if (qParam.isBlank()) return null
            return try {
                URLDecoder.decode(qParam, "UTF-8")
            } catch (_: Exception) {
                null
            }
        }
        if (href.contains("mega.nz")) return href
        return null
    }

    // Solo los bloques de imagen cuyo link es el de la card (clase "fqo2vd").
    // Filtrar solo por ":has(a)" no alcanza: el bloque de texto/título comparte
    // la clase "tyJCtd baZpAe" y también puede tener un <a> adentro (el ícono de
    // "copiar vínculo" de los encabezados, o un link dentro del texto como
    // "(OVAS)"), lo que generaba entradas fantasma "Sin título".
    override fun popularAnimeSelector(): String = "div.tyJCtd.baZpAe:has(a.fqo2vd)"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create()

    override fun popularAnimeNextPageSelector(): String? = null

    // ============================= Latest updates ============================
    // supportsLatest = false: estos métodos no se invocan nunca, pero la clase
    // abstracta los exige igual.

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/${categories.first().path}", headers)
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Búsqueda ================================
    // No hay buscador real en el sitio: se busca por título entre lo ya
    // cargado de la categoría elegida en el filtro.

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/${categories.first().path}", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create()
    override fun searchAnimeNextPageSelector(): String? = null

    // Sin categoría elegida en el filtro ("Todas"): se busca en el listado
    // combinado de Populares. Con categoría elegida, se acota a esa sola.
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val categoryPath = currentCategoryPath(filters)
        val all = if (categoryPath == null) loadAllCategories() else getAnimeForCategory(categoryPath)
        val results = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }
        return results.paginate(page)
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(listOf("Todas" to "") + categories.map { it.label to it.path }),
    )

    // ============================== Detalles =================================

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ============================== Episodios =================================
    // Cada "anime" ES un link de MEGA. Si es una carpeta, cada archivo de video
    // dentro se expone como un episodio; si es un archivo único, hay un solo
    // episodio.

    // Nunca se invocan: getEpisodeList() está sobreescrito abajo y no pasa por
    // el flujo de parseo por selector/elemento.
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val link = extractor.parseLink(anime.url)
            ?: throw Exception("No se pudo interpretar el link de MEGA.")

        return when (link) {
            is MegaLink.File -> {
                val meta = try {
                    extractor.resolveSingleFile(link)
                } catch (_: Exception) {
                    null
                }
                val display = FilenameUtils.buildEpisodeDisplay(meta?.name ?: anime.title, showFilename)
                listOf(
                    SEpisode.create().apply {
                        name = display.name
                        url = encodeEpisodeUrl(anime.url, link.handle, meta?.name ?: anime.title)
                        episode_number = 1F
                    },
                )
            }
            // La API de MEGA devuelve TODOS los niveles de la carpeta compartida
            // en una sola llamada (no solo el primer nivel), cada nodo con su
            // parentHandle. Series como Dr. Stone no tienen videos sueltos en la
            // raíz, sino subcarpetas (Especiales, S2, S2 P2, S4...); por eso se
            // recorre el árbol completo y cada video se nombra con la ruta de
            // subcarpetas a las que pertenece, para distinguir de cuál
            // temporada/tanda es.
            is MegaLink.Folder -> {
                val nodes = try {
                    extractor.listFolder(link)
                } catch (e: Exception) {
                    throw Exception("No se pudo abrir la carpeta de MEGA (puede estar eliminada o el link ya no ser válido).")
                }

                val videoNodes = nodes.filter { !it.isFolder && isVideoName(it.name) }
                if (videoNodes.isEmpty()) {
                    throw Exception("No se encontraron videos en esta carpeta de MEGA (ni en sus subcarpetas).")
                }

                val folderByHandle = nodes.filter { it.isFolder }.associateBy { it.handle }

                // Sube por la cadena de padres hasta quedarse sin una carpeta
                // conocida en este listado (es decir, hasta llegar a la raíz
                // del propio link compartido), acumulando los nombres en
                // orden desde la raíz hacia la subcarpeta más profunda.
                fun folderPathFor(startParentHandle: String?): String {
                    val segments = mutableListOf<String>()
                    var current = startParentHandle
                    val visited = mutableSetOf<String>()
                    while (current != null && folderByHandle.containsKey(current) && current !in visited) {
                        visited.add(current)
                        val folder = folderByHandle.getValue(current)
                        segments.add(0, folder.name)
                        current = folder.parentHandle
                    }
                    return segments.joinToString(" / ")
                }

                FilenameUtils.sortBySeasonAndEpisodeDescending(videoNodes, { it.name }, showFilename)
                    .map { seasoned ->
                        val folderPath = folderPathFor(seasoned.item.parentHandle)
                        val displayName = if (folderPath.isBlank()) seasoned.display.name else "$folderPath - ${seasoned.display.name}"
                        SEpisode.create().apply {
                            name = displayName
                            url = encodeEpisodeUrl(anime.url, seasoned.item.handle, seasoned.item.name)
                            episode_number = seasoned.display.episodeNumber
                            date_upload = seasoned.item.timestamp
                        }
                    }
            }
        }
    }

    private fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun encodeEpisodeUrl(megaHref: String, handle: String, displayName: String): String = "$megaHref|$handle|$displayName"

    private data class EpisodeUrlData(val megaHref: String, val handle: String, val displayName: String)

    private fun decodeEpisodeUrl(episodeUrl: String): EpisodeUrlData {
        val parts = episodeUrl.split("|", limit = 3)
        return EpisodeUrlData(
            megaHref = parts.getOrElse(0) { "" },
            handle = parts.getOrElse(1) { "" },
            displayName = parts.getOrElse(2) { "" },
        )
    }

    // ================================ Video ===================================

    override fun videoUrlParse(response: okhttp3.Response): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url.isBlank()) throw Exception("URL de MEGA no encontrada para este episodio.")
        val data = decodeEpisodeUrl(episode.url)

        val link = extractor.parseLink(data.megaHref)
            ?: throw Exception("No se pudo interpretar el link de MEGA.")

        return when (link) {
            is MegaLink.Folder -> {
                val nodes = extractor.listFolder(link)
                val node = nodes.firstOrNull { it.handle == data.handle && !it.isFolder }
                    ?: throw Exception("No se encontró el archivo '${data.displayName}' dentro de la carpeta de MEGA.")
                extractor.videoFromNode(node, folderHandle = link.handle)
            }
            is MegaLink.File -> {
                val meta = extractor.resolveSingleFile(link)
                extractor.videoFromSingleFile(meta)
            }
        }
    }

    // ============================ Preferencias ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = DarkraiPreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")

        private const val PAGE_SIZE = 30
        private const val THUMBNAIL_REQUESTS_PER_SECOND = 10
        private const val THUMBNAIL_WIDTH = 500

        private val WIDTH_SUFFIX_REGEX = Regex("=w\\d+$")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Cs}]")
        private val NON_LETTER_REGEX = Regex("[^\\p{L}]")
        private val GENERIC_TOKENS_REGEX = Regex(
            "(?iu)\\b(?:s\\d+|t\\d+|temporada|temp|season|parte|part|cour|pel[ií]culas?|movies?|ovas?|oavs?|especiales|especial|specials?|final|completo|completa|\\d+(?:st|nd|rd|th)?|[ivx]+)\\b",
        )
    }
}

private fun isThumbnailUrl(url: HttpUrl): Boolean = url.host == "sites.google.com" && url.encodedPath.contains("sitesv-images")

// Caché en disco SOLO de miniaturas (una imagen por archivo, nombre = hash de la
// URL). Vive en la caché de la app: sobrevive a cerrar/reabrir Anikku y se vacía
// con "Borrar caché". Si el sitio reemplaza una imagen, su URL cambia y se
// descarga la nueva; además cada archivo caduca a los 30 días y el tamaño total
// se mantiene bajo un tope borrando primero lo más viejo.
private class ThumbnailDiskCacheInterceptor(private val dir: File) : Interceptor {
    private val writes = AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || !isThumbnailUrl(request.url)) return chain.proceed(request)

        val key = hash(request.url.toString())
        val imageFile = File(dir, key)
        val typeFile = File(dir, "$key.type")

        readCached(request, imageFile, typeFile)?.let { return it }

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        val body = response.body
        val type = body.contentType()
        val bytes = body.bytes()
        if (bytes.isNotEmpty() && type?.type == "image") {
            store(imageFile, typeFile, bytes, type.toString())
        }
        return response.newBuilder().body(bytes.toResponseBody(type)).build()
    }

    private fun readCached(request: Request, imageFile: File, typeFile: File): Response? = try {
        if (imageFile.exists() && System.currentTimeMillis() - imageFile.lastModified() <= MAX_AGE_MS) {
            val bytes = imageFile.readBytes()
            val type = typeFile.takeIf { it.exists() }?.readText()?.toMediaTypeOrNull()
            if (bytes.isEmpty()) {
                null
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody(type))
                    .build()
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun store(imageFile: File, typeFile: File, bytes: ByteArray, type: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${imageFile.name}.tmp")
            tmp.writeBytes(bytes)
            typeFile.writeText(type)
            if (!tmp.renameTo(imageFile)) tmp.delete()
            if (writes.incrementAndGet() % EVICT_EVERY_WRITES == 0) evictIfNeeded()
        } catch (_: Exception) {
            // Sin espacio o sin permisos: simplemente no se guarda.
        }
    }

    private fun evictIfNeeded() {
        val images = dir.listFiles { file -> !file.name.endsWith(".type") && !file.name.endsWith(".tmp") } ?: return
        var total = images.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in images.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 8 / 10) break
            total -= file.length()
            file.delete()
            File(dir, "${file.name}.type").delete()
        }
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_BYTES = 80L * 1024 * 1024
        const val EVICT_EVERY_WRITES = 25
    }
}

// Reintenta con espera creciente las miniaturas que el servidor rechaza (429,
// 403, 5xx) o corta a media descarga. Si la app cancela la imagen (por ejemplo
// al hacer scroll rápido), deja de reintentar al instante.
private class ThumbnailRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isThumbnailUrl(request.url)) return chain.proceed(request)

        var attempt = 0
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || response.code !in RETRYABLE_CODES || attempt >= MAX_RETRIES) return response
                response.close()
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES || chain.call().isCanceled()) throw e
            }
            attempt++
            try {
                Thread.sleep(RETRY_BASE_DELAY_MS * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Canceled", e)
            }
        }
    }

    private companion object {
        val RETRYABLE_CODES = setOf(403, 408, 429, 500, 502, 503, 504)
        const val MAX_RETRIES = 5
        const val RETRY_BASE_DELAY_MS = 600L
    }
}
