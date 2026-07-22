package aniyomi.lib.googledrivescraper

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.commonEmptyRequestBody
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ProtocolException
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Scraping de carpetas/archivos de Google Drive: listado de contenido,
 * recorrido recursivo de episodios, catálogo (subcarpetas de primer nivel)
 * y detalles (portada + `details.json`).
 *
 * Evolución/fusión de la extensión Google Drive y de la librería
 * `googledriveepisodes` -- ver el README de este módulo para más contexto.
 *
 * No resuelve el video final de un episodio; para eso se usa
 * `googledriveextractor` (GoogleDriveExtractor), que se mantiene aparte
 * porque también lo usan extensiones que no recorren carpetas de Drive.
 *
 * Dos niveles de funciones:
 * - Bajo nivel ([listFolderItems], [fetchFileName], [fetchFileMetadata]):
 *   piezas sueltas para extensiones con su propio criterio de nombres,
 *   episodios o catálogo.
 * - Alto nivel ([scrapeEpisodes], [scrapeCatalogFolders], [scrapeFolderDetails]):
 *   recorrido completo ya armado, para extensiones "tal cual" como Google Drive.
 */
class GoogleDriveScraper(private val client: OkHttpClient, private val headers: Headers) {

    private val driveHeaders = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", client.cookieHeaderFor("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // ============================ Bajo nivel ===============================

    /**
     * Igual que [listFolderItems], pero exponiendo si la carpeta no existe
     * (404 real) en vez de fusionar ese caso con cualquier otro error de
     * red -- útil para mostrarle al usuario "esta carpeta fue eliminada"
     * solo cuando corresponde.
     */
    fun listFolderItemsResult(folderId: String, pageToken: String?): FolderResult {
        val docResult = fetchFolderDocumentResult(folderId)
        val driveDocument = when (docResult) {
            is DocumentResult.Success -> docResult.document
            DocumentResult.NotFound -> return FolderResult.NotFound
            DocumentResult.NetworkError -> return FolderResult.NetworkError
        }
        val key = extractKey(driveDocument) ?: ""

        val response = client.newCall(
            createBatchPost(driveDocument, defaultGetRequest(folderId, pageToken ?: "", key), keyOverride = key),
        ).execute()

        val parsed = response.parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }
        val items = parsed.items?.map { it.toScrapedItem() } ?: emptyList()

        return FolderResult.Success(FolderPage(items, parsed.nextPageToken))
    }

    /**
     * Lista el contenido de UNA carpeta (un solo nivel, sin recursión),
     * con los shortcuts de Drive ya resueltos a su item real. Para
     * recorrer subcarpetas hay que volver a llamar esta función con el
     * `id` de cada carpeta encontrada.
     *
     * Si necesitas distinguir "la carpeta ya no existe" de "hubo un error
     * de red", usa [listFolderItemsResult] en su lugar.
     */
    fun listFolderItems(folderId: String, pageToken: String?): FolderPage? = (listFolderItemsResult(folderId, pageToken) as? FolderResult.Success)?.page

    /**
     * Nombre real de un archivo suelto de Drive, leído del `<title>` de su
     * página pública -- sin autenticación, sin batch API. Más liviano que
     * [fetchFileMetadata] cuando solo hace falta el nombre.
     */
    fun fetchFileName(fileId: String): String? = try {
        val doc = client.newCall(
            GET("https://drive.google.com/file/d/$fileId/view", headers = driveHeaders),
        ).execute().asJsoup()
        fileNameFromDocument(doc)
    } catch (e: Exception) {
        null
    }

    /**
     * Metadatos completos (nombre, tamaño, fecha de modificación) de un
     * archivo suelto de Drive por su ID, sin necesitar la carpeta que lo
     * contiene. Si solo hace falta el nombre, [fetchFileName] es más
     * liviana y no requiere autenticación.
     *
     * ESTADO ACTUAL (sin resolver): la vía autenticada de abajo (key +
     * SAPISIDHASH + batch API, con fallback a la key de `/drive/my-drive`)
     * no está confirmada -- en pruebas reales el tamaño y la fecha
     * siguieron llegando vacíos incluso con sesión iniciada. Por eso esta
     * función cae directo al nombre real (vía [fetchFileName], que sí está
     * confirmado que funciona) sin insistir con la parte autenticada. El
     * código de la vía autenticada se deja tal cual para retomarlo más
     * adelante -- ver notas al final de este archivo.
     */
    fun fetchFileMetadata(fileId: String): FileMetadata? = fetchFileName(fileId)?.let { FileMetadata(it, null, null) }

    /**
     * Vía autenticada para [fetchFileMetadata], actualmente sin usar -- ver
     * el docblock de esa función para el motivo. Se deja implementada para
     * retomarla más adelante en vez de perder el trabajo ya hecho.
     */
    @Suppress("unused")
    private fun fetchFileMetadataAuthenticated(fileId: String): FileMetadata? {
        val fileDocument = try {
            client.newCall(
                GET("https://drive.google.com/file/d/$fileId/view", headers = driveHeaders),
            ).execute().asJsoup()
        } catch (a: ProtocolException) {
            return fetchFileName(fileId)?.let { FileMetadata(it, null, null) }
        }

        val fallbackName = fileNameFromDocument(fileDocument)

        val myDriveDocument by lazy { fetchMyDriveDocument() }
        val keySourceDocument = if (extractKey(fileDocument) != null) fileDocument else myDriveDocument
        val key = keySourceDocument?.let { extractKey(it) }
        if (key == null || keySourceDocument == null) {
            return fallbackName?.let { FileMetadata(it, null, null) }
        }

        val response = try {
            client.newCall(
                createBatchPost(keySourceDocument, singleFileMetadataReq(fileId, key), keyOverride = key),
            ).execute()
        } catch (e: Exception) {
            return fallbackName?.let { FileMetadata(it, null, null) }
        }

        val parsed = try {
            response.parseAs<SingleFileResponse> { JSON_REGEX.find(it)!!.groupValues[1] }
        } catch (e: Exception) {
            return fallbackName?.let { FileMetadata(it, null, null) }
        }

        return FileMetadata(
            title = parsed.title,
            fileSize = parsed.fileSize?.toLongOrNull() ?: parsed.quotaBytesUsed?.toLongOrNull(),
            modifiedDateMillis = parsed.modifiedDate?.let { date -> runCatching { dateFormat.parse(date)?.time }.getOrNull() },
        )
    }

    /** Página raíz de Drive, usada como fuente alterna del key de la API cuando la del archivo/carpeta no lo trae. */
    private fun fetchMyDriveDocument(): Document? = try {
        client.newCall(
            GET("https://drive.google.com/drive/my-drive", headers = driveHeaders),
        ).execute().asJsoup()
    } catch (e: Exception) {
        null
    }

    // ============================ Alto nivel ================================

    /**
     * Recorre recursivamente una carpeta de Drive y arma la lista de
     * episodios (nombre real, posición, fecha, tamaño, path).
     *
     * [ScrapedEpisode.name] es SIEMPRE el nombre real del archivo -- esta
     * librería no decide si mostrar "Episodio N" o el nombre real, eso es
     * decisión del consumidor (típicamente vía
     * `FilenameUtils.buildEpisodeDisplay(scraped.name, showFilename)`).
     *
     * [ScrapedEpisode.episodeNumber] es la POSICIÓN dentro de la carpeta,
     * no el número detectado en el nombre del archivo -- esta librería no
     * depende de `filenameutils` a propósito, para mantenerlas desacopladas.
     *
     * [startPosition]/[stopPosition] filtran por posición original mientras
     * se recorre; el ordenamiento final (especiales arriba, episodios de
     * mayor a menor) queda a criterio del consumidor.
     */
    fun scrapeEpisodes(
        folderUrl: String,
        maxRecursionDepth: Int = 2,
        startPosition: Int? = null,
        stopPosition: Int? = null,
    ): List<ScrapedEpisode> {
        val episodeList = mutableListOf<ScrapedEpisode>()

        fun traverse(url: String, path: String, recursionDepth: Int) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = FOLDER_ID_REGEX.find(url)?.groupValues?.get(1) ?: return
            var pageToken: String? = ""
            var counter = 1

            while (pageToken != null) {
                val page = listFolderItems(folderId, pageToken) ?: return

                page.items.forEach { item ->
                    if (item.isVideo) {
                        if (startPosition != null && maxRecursionDepth == 1 && counter < startPosition) {
                            counter++
                            return@forEach
                        }
                        if (stopPosition != null && maxRecursionDepth == 1 && counter > stopPosition) return

                        val size = item.fileSize?.let { formatBytes(it) } ?: ""

                        episodeList.add(
                            ScrapedEpisode(
                                name = item.title,
                                url = "https://drive.google.com/uc?id=${item.id}",
                                episodeNumber = counter.toFloat(),
                                dateUploadMillis = item.modifiedDateMillis ?: -1L,
                                sizeLabel = if (path.isBlank()) size else "/$path" + if (size.isNotBlank()) " • $size" else "",
                                path = path,
                            ),
                        )
                        counter++
                    }

                    if (item.isFolder) {
                        traverse(
                            "https://drive.google.com/drive/folders/${item.id}",
                            if (path.isEmpty()) item.title else "$path/${item.title}",
                            recursionDepth + 1,
                        )
                    }
                }

                pageToken = page.nextPageToken
            }
        }

        traverse(folderUrl, "", 0)
        return episodeList
    }

    /**
     * Lista las subcarpetas de primer nivel de una carpeta raíz, para
     * usarlas como catálogo (una entrada de catálogo = una subcarpeta).
     */
    fun scrapeCatalogFolders(rootFolderUrl: String): List<ScrapedFolder> {
        val folderId = FOLDER_ID_REGEX.find(rootFolderUrl)?.groupValues?.get(1) ?: return emptyList()
        val folders = mutableListOf<ScrapedFolder>()
        var pageToken: String? = ""

        while (pageToken != null) {
            val page = listFolderItems(folderId, pageToken) ?: break
            folders.addAll(page.items.filter { it.isFolder }.map { ScrapedFolder(it.id, it.title) })
            pageToken = page.nextPageToken
        }

        return folders
    }

    /**
     * Busca la portada (imagen llamada "cover") y el archivo
     * `details.json` dentro de una carpeta, y devuelve lo encontrado. El
     * nombre del anime NO se incluye a propósito: el nombre que el usuario
     * le dio a la entrada guardada siempre debe prevalecer sobre lo que
     * diga `details.json` (ver README).
     */
    fun scrapeFolderDetails(folderUrl: String): ScrapedDetails? {
        val folderId = FOLDER_ID_REGEX.find(folderUrl)?.groupValues?.get(1) ?: return null
        val driveDocument = fetchFolderDocument(folderId) ?: return null
        val key = extractKey(driveDocument) ?: ""

        val coverResponse = client.newCall(
            createBatchPost(driveDocument, searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)(folderId, "", key), keyOverride = key),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        val coverUrl = coverResponse.items?.firstOrNull()?.let { "https://drive.google.com/uc?id=${it.id}" }

        val detailsResponse = client.newCall(
            createBatchPost(driveDocument, searchReqWithType(folderId, "details.json", "")(folderId, "", key), keyOverride = key),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        val detailsItem = detailsResponse.items?.firstOrNull()
            ?: return ScrapedDetails(coverUrl, null, null, null, null, null)

        val details = fetchDetailsJson(detailsItem.id) ?: return ScrapedDetails(coverUrl, null, null, null, null, null)

        return ScrapedDetails(
            coverUrl = coverUrl,
            author = details.author,
            artist = details.artist,
            description = details.description,
            genre = details.genre?.joinToString(", "),
            status = details.status,
        )
    }

    // ============================= Internos =================================

    private sealed class DocumentResult {
        data class Success(val document: Document) : DocumentResult()
        data object NotFound : DocumentResult()
        data object NetworkError : DocumentResult()
    }

    /**
     * Obtiene el documento HTML de una carpeta, distinguiendo un 404 real
     * (código HTTP) de cualquier otro fallo de red -- el código HTTP es
     * más confiable que buscar "Error 404" en el <title>, que puede variar
     * según el idioma de la cuenta de Google.
     */
    private fun fetchFolderDocumentResult(folderId: String): DocumentResult {
        val response = try {
            client.newCall(
                GET("https://drive.google.com/drive/folders/$folderId", headers = driveHeaders),
            ).execute()
        } catch (a: ProtocolException) {
            return DocumentResult.NetworkError
        }

        if (response.code == 404) {
            response.close()
            return DocumentResult.NotFound
        }

        return DocumentResult.Success(response.asJsoup())
    }

    private fun fileNameFromDocument(document: Document): String? = document.selectFirst("title")?.text()
        ?.removeSuffix(" - Google Drive")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun fetchFolderDocument(folderId: String): Document? = (fetchFolderDocumentResult(folderId) as? DocumentResult.Success)?.document

    private fun extractKey(document: Document): String? {
        val keyScript = document.select("script").firstOrNull { script -> KEY_REGEX.find(script.data()) != null }?.data() ?: return null
        return KEY_REGEX.find(keyScript)?.groupValues?.get(1)
    }

    private fun extractDriveVersion(document: Document): String {
        val versionScript = document.select("script").firstOrNull { script -> VERSION_REGEX.find(script.data()) != null }?.data() ?: ""
        return VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
    }

    /**
     * Arma y ejecuta la petición batch autenticada contra
     * `clients6.google.com/batch/drive/v2internal`, firmando con
     * SAPISIDHASH -- mecanismo compartido por [listFolderItems],
     * [fetchFileMetadata] y [scrapeFolderDetails].
     */
    private fun createBatchPost(document: Document, requestUrl: String, keyOverride: String? = null): okhttp3.Request {
        val key = keyOverride ?: extractKey(document) ?: ""
        val driveVersion = extractDriveVersion(document)
        val sapisid = client.sapisidFor("https://drive.google.com")

        val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", client.cookieHeaderFor("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun fetchDetailsJson(detailsFileId: String): DetailsJson? {
        val newPostHeaders = driveHeaders.newBuilder().apply {
            add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            set("Host", "drive.usercontent.google.com")
            add("Origin", "https://drive.google.com")
            add("Referer", "https://drive.google.com/")
            add("X-Drive-First-Party", "DriveWebUi")
            add("X-Json-Requested", "true")
        }.build()

        val newPostUrl = "https://drive.usercontent.google.com/uc?id=$detailsFileId&authuser=0&export=download"

        val newResponse = try {
            client.newCall(
                POST(newPostUrl, headers = newPostHeaders, body = commonEmptyRequestBody),
            ).execute().parseAs<DownloadResponse> { JSON_REGEX.find(it)!!.groupValues[1] }
        } catch (e: Exception) {
            return null
        }

        val downloadHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Connection", "keep-alive")
            add("Cookie", client.cookieHeaderFor("https://drive.usercontent.google.com"))
            add("Host", "drive.usercontent.google.com")
        }.build()

        return try {
            client.newCall(GET(newResponse.downloadUrl, headers = downloadHeaders)).execute().parseAs<DetailsJson>()
        } catch (e: Exception) {
            null
        }
    }

    private fun PostResponse.ResponseItem.toScrapedItem(): ScrapedItem {
        val isShortcut = mimeType == "application/vnd.google-apps.shortcut"
        val isVideo = mimeType.startsWith("video") || (isShortcut && shortcutDetails?.targetMimeType?.startsWith("video") == true)
        val isFolder = mimeType.endsWith(".folder") || (isShortcut && shortcutDetails?.targetMimeType?.endsWith(".folder") == true)
        val resolvedId = if (isShortcut) shortcutDetails?.targetId ?: id else id

        return ScrapedItem(
            id = resolvedId,
            title = title,
            isVideo = isVideo,
            isFolder = isFolder,
            fileSize = fileSize?.toLongOrNull(),
            modifiedDateMillis = modifiedDate?.let { date -> runCatching { dateFormat.parse(date)?.time }.getOrNull() },
        )
    }

    companion object {
        private val FOLDER_ID_REGEX = Regex("""/folders/([\w-]{28,})""")
        private val KEY_REGEX = Regex(""""(\w{39})"""")
        private val VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private val JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="
    }
}

/*
 * NOTA PENDIENTE: tamaño y fecha para archivos sueltos (fetchFileMetadata)
 *
 * Estado actual: fetchFileMetadata solo resuelve el nombre real (vía
 * fetchFileName, confirmado que funciona). Tamaño y fecha quedan vacíos
 * para un archivo agregado directo (a diferencia de un archivo encontrado
 * dentro de una carpeta, donde sí llegan bien vía listFolderItems).
 *
 * Ya probado y descartado (o no confirmado) en esta sesión:
 * - extractKey sobre el documento de /file/d/<id>/view: la página del
 *   archivo suelto no siempre trae el script con la key de 39 caracteres
 *   que sí trae la página de una carpeta.
 * - Fallback a extraer esa key desde /drive/my-drive (con sesión iniciada
 *   vía WebView): implementado en fetchFileMetadataAuthenticated, pero en
 *   pruebas reales tamaño y fecha siguieron sin llegar.
 * - Fallback de fileSize a quotaBytesUsed (espacio en la cuota del
 *   usuario) en la respuesta de la API: agregado al DTO y a la query, pero
 *   no se pudo confirmar si realmente resuelve el problema, porque la
 *   petición autenticada en sí parece no estar completando bien antes de
 *   llegar a ese punto.
 *
 * Sin confirmar cuál es el punto exacto de falla -- no se agregaron logs
 * para diagnosticarlo en esta sesión. Cosas a testear en una próxima:
 * - Loggear si extractKey(fileDocument) y extractKey(myDriveDocument)
 *   devuelven algo o null, para saber si el problema es no encontrar la
 *   key en ninguna de las dos páginas.
 * - Si sí hay key, loggear el código de respuesta y el body crudo de la
 *   petición batch (createBatchPost) para ver si Drive responde con error
 *   o con un JSON que simplemente no trae fileSize/quotaBytesUsed.
 * - Probar /drive/shared-with-me como tercera fuente de key (mismo
 *   mecanismo que /drive/my-drive, sin confirmar si cambia algo).
 * - Alternativa de menor esfuerzo: usar la fecha de la entrada guardada
 *   por el usuario (o de la carpeta padre si existe) como esta la usa
 *   CollectedNotes con la fecha de la nota del sitio, en vez de perseguir
 *   la fecha real de Drive para archivos sueltos.
 */
