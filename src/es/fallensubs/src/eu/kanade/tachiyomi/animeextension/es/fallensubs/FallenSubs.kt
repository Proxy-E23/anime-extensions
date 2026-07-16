package eu.kanade.tachiyomi.animeextension.es.fallensubs

import android.app.Application
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.megaextractor.MegaLink
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FallenSubs : ParsedAnimeHttpSource() {

    override val name = "FallenSubs"
    override val baseUrl = "https://www.fallensubs.com"
    override val lang = "es"
    override val supportsLatest = true

    private val forumUrl = "$baseUrl/forum"

    private val extractor by lazy { MegaExtractor(client) }

    // El "anime" es siempre el topic del foro (index.php?topic=XXXX.0): ahí está todo lo que
    // necesitamos (portada, sinopsis, tabla de descargas).

    // ============================ Caché de portadas ============================
    // Portadas buenas (sacadas del topic en getAnimeDetails), guardadas en SharedPreferences
    // para que sobrevivan a cierres de la app. Sustituyen a la miniatura mala/pequeña de
    // action=series en cualquier listado (Populares, Búsqueda, Recientes).
    private val thumbnailPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_thumbnails", 0x0000)
    }

    // .ifBlank { null }: Jsoup .attr() y algún caché viejo pueden devolver "" en vez de null;
    // tratarlo como "ya resuelto" bloquearía la resolución para siempre.
    private fun getCachedThumbnail(url: String): String? = thumbnailPrefs.getString(url, null)?.ifBlank { null }

    private fun cacheThumbnail(url: String, thumbnailUrl: String) {
        thumbnailPrefs.edit().putString(url, thumbnailUrl).apply()
    }

    // Resuelve en paralelo la portada real de los animes que aún no estén en el caché
    // persistente (se decide por el caché, no por si thumbnail_url viene vacío: el catálogo
    // casi siempre trae *algo*, aunque sea la miniatura mala). Se usa en Populares y
    // Búsqueda. Si una petición falla, esa serie se queda con su miniatura original.
    private suspend fun resolveMissingThumbnails(list: List<SAnime>): List<SAnime> = coroutineScope {
        val pending = list.filter { it.url.isNotBlank() && getCachedThumbnail(it.url) == null }
        if (pending.isEmpty()) return@coroutineScope list

        val resolved = pending.map { anime ->
            async {
                anime.url to try {
                    getAnimeDetails(anime).thumbnail_url
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().toMap()

        list.map { anime ->
            val newThumbnail = resolved[anime.url]
            if (!newThumbnail.isNullOrBlank()) anime.apply { thumbnail_url = newThumbnail } else anime
        }
    }

    // ============================== Popular ================================
    // action=series no está paginado por el sitio (una sola página con todas las series), así
    // que la usamos como índice liviano y simulamos la paginación en memoria.
    private var popularCache: List<SAnime>? = null

    // Caché de sesión por página (se pierde al cerrar la app): evita repetir
    // resolveMissingThumbnails cada vez que se vuelve a ver la misma página.
    private val popularPageCache = mutableMapOf<Int, AnimesPage>()

    override fun popularAnimeRequest(page: Int): Request = GET("$forumUrl/index.php?action=series", headers)

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        popularPageCache[page]?.let { return it }

        val all = popularCache ?: run {
            val document = client.newCall(popularAnimeRequest(1)).execute().asJsoup()
            val list = document.select(popularAnimeSelector())
                .map { popularAnimeFromElement(it) }
                .filter { it.url.isNotBlank() }
            popularCache = list
            list
        }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= all.size) return AnimesPage(emptyList(), false).also { popularPageCache[page] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, all.size)
        val hasNext = toIndex < all.size
        val pageItems = resolveMissingThumbnails(all.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { popularPageCache[page] = it }
    }

    override fun popularAnimeSelector(): String = "ul#portfolio-list li"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val posterLink = element.selectFirst("a[href*=verficha]")
        // El link de "Descarga" va directo al topic; lo usamos en vez de "Ficha" para no
        // depender de esa página intermedia.
        val downloadLink = element.select("div#enlaces a[href*=topic]").firstOrNull()

        url = downloadLink?.attr("href")?.removePrefix(baseUrl) ?: ""
        title = posterLink?.attr("title")?.ifBlank { null }
            ?: posterLink?.selectFirst("img")?.attr("alt")
            ?: element.selectFirst("span")?.text()?.trim()
            ?: ""
        thumbnail_url = getCachedThumbnail(url) ?: posterLink?.selectFirst("img")?.attr("src")?.ifBlank { null }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // Requerido por la clase base; getPopularAnime maneja la paginación real.
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(response.asJsoup().select(popularAnimeSelector()).map { popularAnimeFromElement(it) }, false)

    // =============================== Latest =================================
    // La home publica un artículo por episodio cuyo link de "Descarga Directa" a veces
    // apunta a OTRO topic (el topic madre real de la serie), y el topic del artículo no
    // tiene tabla de descargas -> NoEpisodesException. En su lugar, usamos los listados de
    // temas del foro ("Proyectos Actuales" board=5.0 y "Proyectos Terminados" board=4.0):
    // cada fila ES el topic madre, uno por serie.
    //
    // El orden nativo de esos listados es "último comentario", no "última edición real". Para
    // acercarnos a esto último sin visitar todo el foro de una, solo resolvemos la fecha de
    // "Última Modificación" (vive dentro del topic) del bloque que corresponde a la página
    // pedida, en paralelo, y reordenamos ese bloque.
    private var latestCache: List<SAnime>? = null

    // Caché de sesión del resultado ya procesado (fecha de edición + portada resueltas) de
    // cada página, para no volver a descargar cada topic al re-visitar Recientes.
    private val latestPageCache = mutableMapOf<Int, AnimesPage>()

    override fun latestUpdatesRequest(page: Int): Request = GET("$forumUrl/index.php?board=5.0", headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        latestPageCache[page]?.let { return it }

        val all = latestCache ?: run {
            val currentDoc = client.newCall(GET("$forumUrl/index.php?board=5.0", headers)).execute().asJsoup()
            val finishedDoc = client.newCall(GET("$forumUrl/index.php?board=4.0", headers)).execute().asJsoup()
            val list = (parseBoardTopics(currentDoc) + parseBoardTopics(finishedDoc))
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.url }
            latestCache = list
            list
        }

        val fromIndex = (page - 1) * LATEST_PAGE_SIZE
        if (fromIndex >= all.size) return AnimesPage(emptyList(), false).also { latestPageCache[page] = it }
        val toIndex = minOf(fromIndex + LATEST_PAGE_SIZE, all.size)
        val hasNext = toIndex < all.size
        val reorderedPage = resolveAndReorderByLastEdit(all.subList(fromIndex, toIndex))
        return AnimesPage(reorderedPage, hasNext).also { latestPageCache[page] = it }
    }

    // Resuelve en paralelo la fecha de "Última Modificación" de cada tema del bloque y
    // reordena de más reciente a más antiguo (sin fecha o con error -> al final). De paso,
    // ya que se descarga el Document completo, también resuelve y cachea la portada si falta
    // (reusa animeDetailsParse en vez de hacer una segunda petición aparte).
    private suspend fun resolveAndReorderByLastEdit(page: List<SAnime>): List<SAnime> = coroutineScope {
        val resolved = page.map { anime ->
            async {
                try {
                    val document = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
                    val editedAt = parseSpanishDate(document.selectFirst("div.modified")?.text().orEmpty())

                    if (anime.thumbnail_url.isNullOrBlank()) {
                        val details = animeDetailsParse(document)
                        if (!details.thumbnail_url.isNullOrBlank()) {
                            cacheThumbnail(anime.url, details.thumbnail_url!!)
                            anime.thumbnail_url = details.thumbnail_url
                        }
                    }
                    anime to editedAt
                } catch (_: Exception) {
                    anime to 0L
                }
            }
        }.awaitAll()

        resolved.sortedByDescending { it.second }.map { it.first }
    }

    // La celda "lastpost" trae la fecha del último mensaje en español (ej. "Julio 11, 2026,
    // 09:00:20 am"); se usa para intercalar por fecha real los temas de ambos boards.
    private fun parseBoardTopics(document: Document): List<Pair<SAnime, Long>> = document.select("table.table_grid tr").mapNotNull { row ->
        val link = row.selectFirst("td.subject span.subject-title a[href*=topic]") ?: return@mapNotNull null
        val dateText = row.selectFirst("td.lastpost")?.ownText()?.trim().orEmpty()
        val anime = SAnime.create().apply {
            url = link.attr("href").removePrefix(baseUrl)
            title = link.text().trim()
            thumbnail_url = getCachedThumbnail(url)
        }
        if (anime.url.isBlank()) return@mapNotNull null
        anime to parseSpanishDate(dateText)
    }

    private fun parseSpanishDate(text: String): Long {
        val match = SPANISH_DATE_REGEX.find(text) ?: return 0L
        val (monthName, day, year, hour, minute, ampm) = match.destructured
        val month = SPANISH_MONTHS[monthName.lowercase()] ?: return 0L
        var hour24 = hour.toInt() % 12
        if (ampm.lowercase() == "pm") hour24 += 12
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year.toInt(), month, day.toInt(), hour24, minute.toInt(), 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    // Requeridos por la clase base; getLatestUpdates maneja la combinación real de boards.
    override fun latestUpdatesSelector(): String = "table.table_grid td.subject span.subject-title a[href*=topic]"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        url = element.attr("href").removePrefix(baseUrl)
        title = element.text().trim()
        thumbnail_url = getCachedThumbnail(url)
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(
        response.asJsoup().select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .distinctBy { it.url },
        false,
    )

    // =============================== Search ==================================
    // El buscador del foro requiere sesión/token para scraping cómodo; filtramos en memoria
    // sobre el listado completo de Populares (el catálogo total es de tamaño moderado).
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String? = null

    // Clave "query|page": evita mezclar resultados entre búsquedas distintas.
    private val searchPageCache = mutableMapOf<String, AnimesPage>()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cacheKey = "$query|$page"
        searchPageCache[cacheKey]?.let { return it }

        val all = popularCache ?: run {
            val document = client.newCall(popularAnimeRequest(1)).execute().asJsoup()
            val list = document.select(popularAnimeSelector())
                .map { popularAnimeFromElement(it) }
                .filter { it.url.isNotBlank() }
            popularCache = list
            list
        }

        val filtered = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= filtered.size) return AnimesPage(emptyList(), false).also { searchPageCache[cacheKey] = it }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filtered.size)
        val hasNext = toIndex < filtered.size
        val pageItems = resolveMissingThumbnails(filtered.subList(fromIndex, toIndex))
        return AnimesPage(pageItems, hasNext).also { searchPageCache[cacheKey] = it }
    }

    // Requerido por la clase base; getSearchAnime maneja el filtrado real.
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================ Anime Details ==============================
    // Se sobreescribe getAnimeDetails (no solo animeDetailsParse) para tener la url del
    // anime y así cachear su portada de forma persistente.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.newCall(animeDetailsRequest(anime)).execute().asJsoup()
        val details = animeDetailsParse(document)
        val thumbnail = details.thumbnail_url
        if (!thumbnail.isNullOrBlank()) cacheThumbnail(anime.url, thumbnail)
        return details
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h5 a[rel=nofollow]")?.text()?.trim() ?: ""
        // SMF marca con "bbc_img" la imagen insertada por el staff en el post; sin eso, un
        // simple selectFirst("img") podría agarrar un ícono de hoster o banner decorativo.
        thumbnail_url = document.selectFirst("div.inner img.bbc_img")?.attr("src")
            ?: document.selectFirst("div.inner img")?.attr("src")

        // El post mete todo en un solo bloque de texto sin separación por clases; recortamos
        // solo lo que va entre "SINOPSIS" y la siguiente sección en mayúsculas.
        val fullText = document.selectFirst("div.inner")?.text()?.trim().orEmpty()
        val sinopsisStart = fullText.uppercase().indexOf("SINOPSIS")
        description = if (sinopsisStart == -1) {
            null
        } else {
            val afterSinopsis = fullText.substring(sinopsisStart + "SINOPSIS".length).trim()
            val nextSectionMatch = SECTION_HEADER_REGEX.find(afterSinopsis.uppercase())
            (if (nextSectionMatch != null) afterSinopsis.substring(0, nextSectionMatch.range.first) else afterSinopsis)
                .trim()
                .ifBlank { null }
        }

        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==================================
    // Cada celda de la tabla de descargas (bbc_table) apunta a un archivo único de Mega
    // (1 episodio) o a una carpeta/"Batch" (se expande en 1 episodio por archivo de video).
    //
    // La URL de cada SEpisode codifica 3 datos separados por "|" (link de Mega original,
    // handle del nodo, nombre) para no tener que re-listar la carpeta en getVideoList.
    override fun episodeListSelector(): String = "table.bbc_table"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select(episodeListSelector()).forEach { table ->
            val headerCells = table.select("tr").firstOrNull()?.select("td, th") ?: return@forEach
            val qualityNames = headerCells.drop(1).map { it.text().trim() }
            // Solo la mejor resolución disponible (idealmente 1080p): ignoramos 720p y
            // menores. Columnas sin resolución reconocible (ej. "BD", "OP Credless") no se
            // descartan, para no perder contenido válido por accidente.
            val bestQuality = qualityNames.mapNotNull { extractResolution(it) }.maxOrNull()
            val allowedIndices = qualityNames.indices.filter { index ->
                val resolution = extractResolution(qualityNames[index])
                bestQuality == null || resolution == null || resolution == bestQuality
            }.toSet()

            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.isEmpty()) return@forEach
                val rowName = cells.first()?.text()?.trim().orEmpty().ifBlank { "Batch" }

                cells.drop(1).forEachIndexed { index, cell ->
                    if (index !in allowedIndices) return@forEachIndexed
                    val megaHref = cell.selectFirst("a[href*=mega.nz]")?.attr("href") ?: return@forEachIndexed
                    val quality = qualityNames.getOrNull(index) ?: "Descarga"
                    val link = extractor.parseLink(megaHref) ?: return@forEachIndexed

                    when (link) {
                        is MegaLink.Folder -> {
                            val nodes = try {
                                extractor.listFolder(link)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            nodes.filter { !it.isFolder }.forEach { node ->
                                episodes.add(
                                    SEpisode.create().apply {
                                        name = "${node.name} [$quality]"
                                        url = encodeEpisodeUrl(megaHref, node.handle, node.name)
                                        episode_number = FilenameUtils.extractEpisodeNumber(node.name) ?: 0F
                                    },
                                )
                            }
                        }
                        is MegaLink.File -> {
                            episodes.add(
                                SEpisode.create().apply {
                                    name = "$rowName [$quality]"
                                    url = encodeEpisodeUrl(megaHref, link.handle, rowName)
                                    episode_number = FilenameUtils.extractEpisodeNumber(rowName) ?: 0F
                                },
                            )
                        }
                    }
                }
            }
        }

        return FilenameUtils.sortByEpisodeNumberDescending(episodes) { it.name }
    }

    private fun extractResolution(qualityText: String): Int? = RESOLUTION_REGEX.find(qualityText)?.groupValues?.get(1)?.toIntOrNull()

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

    // ============================ Video Links ===============================
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url.isBlank()) throw Exception("URL de Mega no encontrada para este episodio.")
        val data = decodeEpisodeUrl(episode.url)

        val link = extractor.parseLink(data.megaHref)
            ?: throw Exception("No se pudo interpretar el link de Mega.")

        return when (link) {
            is MegaLink.Folder -> {
                val nodes = extractor.listFolder(link)
                val node = nodes.firstOrNull { it.handle == data.handle && !it.isFolder }
                    ?: throw Exception("No se encontró el archivo '${data.displayName}' dentro de la carpeta de Mega.")
                extractor.videoFromNode(node, folderHandle = link.handle)
            }
            is MegaLink.File -> {
                val meta = extractor.resolveSingleFile(link)
                extractor.videoFromSingleFile(meta)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val LATEST_PAGE_SIZE = 20
        private val SECTION_HEADER_REGEX = Regex("""(DATOS DE LA SERIE|FICHA TECNICA|SINOPSIS|STAFF FALLENSUBS)""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p?""")

        // Ejemplo real: "Julio 11, 2026, 09:00:20 am"
        private val SPANISH_DATE_REGEX = Regex(
            """(\p{L}+)\s+(\d{1,2}),\s+(\d{4}),\s+(\d{1,2}):(\d{2}):\d{2}\s+([ap]m)""",
            RegexOption.IGNORE_CASE,
        )
        private val SPANISH_MONTHS = mapOf(
            "enero" to 0, "febrero" to 1, "marzo" to 2, "abril" to 3,
            "mayo" to 4, "junio" to 5, "julio" to 6, "agosto" to 7,
            "septiembre" to 8, "octubre" to 9, "noviembre" to 10, "diciembre" to 11,
        )
    }
}
