package eu.kanade.tachiyomi.animeextension.es.elabismodedarkrai

import android.app.Application
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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class ElAbismoDeDarkrai :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "El Abismo de Darkrai"
    override val baseUrl = "https://sites.google.com/view/elabismodedarkrai"
    override val lang = "es"
    override val supportsLatest = false

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
        Injekt.get<Application>().getSharedPreferences("source_${id}_thumbnails", 0x0000)
    }

    private fun getCachedThumbnail(animeUrl: String): String? = thumbnailPrefs.getString(animeUrl, null)?.ifBlank { null }

    private fun cacheThumbnail(animeUrl: String, thumbnailUrl: String) {
        thumbnailPrefs.edit().putString(animeUrl, thumbnailUrl).apply()
    }

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
    private val categoryCache = mutableMapOf<String, List<SAnime>>()
    private var allCategoriesCache: List<SAnime>? = null

    private fun currentCategoryPath(filters: AnimeFilterList): String? {
        val filter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        return filter?.getValue()?.ifBlank { null }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/${categories.first().path}", headers)

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        allCategoriesCache?.let { return AnimesPage(it, false) }

        val combined = mutableListOf<SAnime>()
        categories.forEach { category ->
            val entries = getAnimeForCategory(category.path).animes
            combined.addAll(
                entries.map { anime ->
                    anime.apply { description = "Categoría: ${category.label}" }
                },
            )
        }
        allCategoriesCache = combined
        return AnimesPage(combined, false)
    }

    private suspend fun getAnimeForCategory(categoryPath: String): AnimesPage {
        val cached = categoryCache[categoryPath]
        if (cached != null) return AnimesPage(cached, false)

        val document = client.newCall(GET("$baseUrl/$categoryPath", headers)).execute().asJsoup()
        val list = parseCategoryDocument(document)
        categoryCache[categoryPath] = list
        return AnimesPage(list, false)
    }

    // Cada "card" del listado de Google Sites es un bloque "div.oKdM2c" con la
    // imagen enlazada adentro; el título real vive en el bloque HERMANO
    // siguiente ("div.oKdM2c" sin la clase extra "Kzv0Me"), no dentro del mismo
    // contenedor. Ambos se resuelven a partir del <a> con la imagen.
    private fun findCardTitle(imageBlock: Element): String? {
        var container: Element? = imageBlock
        while (container != null && !container.hasClass("oKdM2c")) {
            container = container.parent()
        }
        val titleBlock = container?.nextElementSibling()
        // El título puede venir partido en varios <span> dentro del mismo <p>
        // (ej. "D" + "r. Stone"), así que se toma el texto completo del <p>,
        // no solo el primer <span>.
        return titleBlock?.selectFirst("p.zfr3Q")?.text()?.trim()
    }

    private fun parseCategoryDocument(document: Document): List<SAnime> {
        val results = mutableListOf<SAnime>()
        val subPages = mutableListOf<String>()

        document.select(popularAnimeSelector()).forEach { card ->
            val link = card.selectFirst("a")
            val img = card.selectFirst("img.CENy8b")
            val titleText = findCardTitle(card)

            val href = link?.attr("href")

            when {
                href.isNullOrBlank() -> return@forEach
                // Sub-página interna del propio sitio (una serie con varios enlaces
                // de descarga adentro, ej. temporadas): se resuelve aparte.
                href.startsWith("/view/") || href.contains("sites.google.com/view/elabismodedarkrai") -> {
                    subPages.add(href)
                }
                // Card con el redirect de Google envolviendo un link de MEGA directo.
                else -> {
                    val megaUrl = resolveGoogleRedirect(href)
                    if (megaUrl != null && megaUrl.contains("mega.nz")) {
                        val liveThumbnail = img?.attr("src")?.ifBlank { null }
                        if (liveThumbnail != null) cacheThumbnail(megaUrl, liveThumbnail)
                        results.add(
                            SAnime.create().apply {
                                title = titleText?.ifBlank { null } ?: "Sin título"
                                thumbnail_url = liveThumbnail ?: getCachedThumbnail(megaUrl)
                                url = megaUrl
                            },
                        )
                    }
                }
            }
        }

        // Las sub-páginas se resuelven aparte porque cada una puede contener
        // varias entradas de MEGA (ej. varias temporadas de un mismo anime).
        subPages.distinct().forEach { subHref ->
            results.addAll(resolveSubPage(subHref))
        }

        return results
    }

    private fun resolveSubPage(href: String): List<SAnime> {
        val fullUrl = if (href.startsWith("http")) href else "https://sites.google.com$href"
        val document = try {
            client.newCall(GET(fullUrl, headers)).execute().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val parentTitle = document.selectFirst("title")?.text()
            ?.substringAfter(" - ")?.trim()

        val entries = mutableListOf<SAnime>()
        document.select(popularAnimeSelector()).forEach { card ->
            val link = card.selectFirst("a") ?: return@forEach
            val img = card.selectFirst("img.CENy8b")
            val href2 = link.attr("href")
            if (href2.isBlank() || href2.startsWith("/view/")) return@forEach

            val megaUrl = resolveGoogleRedirect(href2) ?: return@forEach
            if (!megaUrl.contains("mega.nz")) return@forEach

            val titleText = findCardTitle(card)
            val liveThumbnail = img?.attr("src")?.ifBlank { null }
            if (liveThumbnail != null) cacheThumbnail(megaUrl, liveThumbnail)

            entries.add(
                SAnime.create().apply {
                    title = listOfNotNull(parentTitle, titleText).joinToString(" - ").ifBlank { titleText ?: "Sin título" }
                    thumbnail_url = liveThumbnail ?: getCachedThumbnail(megaUrl)
                    url = megaUrl
                },
            )
        }
        return entries
    }

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

    // Solo los bloques de imagen que además tienen un link <a> adentro (el
    // mismo selector matchea también el bloque de texto/título si no se
    // filtra por :has(a), ya que ambos comparten la clase "tyJCtd baZpAe").
    override fun popularAnimeSelector(): String = "div.tyJCtd.baZpAe:has(a)"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create()

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseCategoryDocument(response.asJsoup()), false)

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
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // Sin categoría elegida en el filtro ("Todas"): se busca en el listado
    // combinado de Populares. Con categoría elegida, se acota a esa sola.
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val categoryPath = currentCategoryPath(filters)
        val all = if (categoryPath == null) {
            getPopularAnime(page).animes
        } else {
            getAnimeForCategory(categoryPath).animes
        }
        val results = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(results, false)
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(listOf("Todas" to "") + categories.map { it.label to it.path }),
    )

    // ============================== Detalles =================================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ============================== Episodios =================================
    // Cada "anime" ES un link de MEGA. Si es una carpeta, cada archivo de video
    // dentro se expone como un episodio; si es un archivo único, hay un solo
    // episodio.

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

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

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

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
    }
}
