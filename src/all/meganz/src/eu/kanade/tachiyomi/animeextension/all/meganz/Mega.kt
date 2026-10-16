package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filenameutils.FilenameUtils
import aniyomi.lib.megaextractor.MegaApiException
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.megaextractor.MegaLink
import aniyomi.lib.megaextractor.MegaNode
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class Mega :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MEGA"
    override val baseUrl = "https://mega.nz"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val extractor by lazy { MegaExtractor(client) }

    private var cachedAnimes: List<SAnime>? = null

    // Caché en memoria del árbol de cada carpeta ya listada (handle -> nodos
    // descifrados), para no volver a golpear la API ni redescifrar al
    // navegar entre catálogo, detalles y episodios de la misma carpeta.
    private val nodeCache = mutableMapOf<String, List<MegaNode>>()

    // Caché en memoria de metadata de archivo único (handle -> meta), por la
    // misma razón.
    private val singleFileMetaCache = mutableMapOf<String, MegaExtractor.SingleFileMeta>()

    private fun resolveSingleFileCached(link: MegaLink.File): MegaExtractor.SingleFileMeta = singleFileMetaCache.getOrPut(link.handle) {
        extractor.resolveSingleFile(link)
    }

    /**
     * Intenta iniciar sesión y muestra el resultado por Toast. [pin] llega
     * en el mismo llamado que [password] (parseado del campo único
     * "contraseña::código" en Ajustes) para no depender de que algún estado
     * sobreviva entre dos campos separados -- eso fue justamente lo que
     * fallaba antes.
     *
     * El trabajo real (red + PBKDF2 de 100.000 vueltas) corre en un hilo
     * aparte: llamarlo directo desde el listener de la preferencia lo
     * dejaba corriendo en el hilo principal, lo que congelaba la UI unos
     * segundos en equipos de gama baja -- eso era el "error" real, no una
     * excepción. Los Toast se postean de vuelta al hilo principal con
     * Handler/Looper, igual que ya hace el campo de eliminar enlace más
     * abajo en este archivo.
     */
    private fun attemptLogin(context: Context, email: String, password: String, pin: String?) {
        if (email.isBlank()) {
            Toast.makeText(context, "Primero escribe tu email de MEGA arriba.", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isBlank()) {
            Toast.makeText(context, "Escribe tu contraseña de MEGA.", Toast.LENGTH_SHORT).show()
            return
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fun showToast(message: String, long: Boolean = false) {
            mainHandler.post { Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        }

        showToast(
            "Iniciando sesión con MEGA... Si tu cuenta pide resolver un desafío de seguridad, " +
                "esto puede demorar bastante (hasta un par de minutos) -- no cierres la app mientras tanto.",
            long = true,
        )

        Thread {
            try {
                extractor.login(email, password, pin)
                cachedAnimes = null // para que el Cloud Drive aparezca (o desaparezca) en el catálogo
                showToast("Sesión de MEGA iniciada como $email.")
            } catch (e: MegaApiException) {
                when (e.code) {
                    -26 -> showToast(
                        "Esta cuenta tiene verificación en dos pasos. Escribe de nuevo la contraseña arriba, " +
                            "agregando \"::\" y el código, por ejemplo: tucontraseña::123456",
                        long = true,
                    )
                    -9 -> showToast("Email o contraseña incorrectos.")
                    else -> showToast("Error al iniciar sesión (código ${e.code}).")
                }
            } catch (e: UnsupportedOperationException) {
                showToast(e.message ?: "Esta cuenta de MEGA no está soportada todavía.", long = true)
            } catch (e: Exception) {
                showToast("Error al iniciar sesión: ${e.message}")
            }
        }.start()
    }

    private val showFilename: Boolean
        get() = MegaPreferences.showFilename(preferences)

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val animeList = mutableListOf<SAnime>()

        if (extractor.isLoggedIn()) {
            animeList += buildCloudDriveAnimeList()
        }

        MegaPreferences.getEntries(preferences).forEach { entry ->
            val link = extractor.parseLink(entry.url)
            if (link == null) return@forEach

            when (link) {
                is MegaLink.File -> {
                    animeList += buildSingleFileAnime(entry.name, entry.url)
                }
                is MegaLink.Folder -> {
                    animeList += buildFolderAnime(entry.name, entry.url, link)
                }
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    /**
     * El nombre real de un archivo único se resuelve de forma perezosa en
     * getAnimeDetails, igual que hacen Drive/MediaFire.
     */
    private fun buildSingleFileAnime(entryName: String, entryUrl: String, sizeLabel: String? = null): SAnime = SAnime.create().apply {
        title = entryName
        url = MegaLinkData(entryUrl, "single", sizeLabel?.let { MegaLinkDataInfo(entryName, it) }).toJsonString()
        thumbnail_url = ""
        description = removeEntryHint(entryName, entryUrl)
    }

    /**
     * Se intenta listar la carpeta de una vez para detectar si fue eliminada
     * (entrada "fantasma" con instrucciones de borrado) o si tiene contenido
     * válido.
     */
    private fun buildFolderAnime(entryName: String, entryUrl: String, link: MegaLink.Folder): SAnime {
        val nodes = try {
            extractor.listFolder(link).also { nodeCache[link.handle] = it }
        } catch (e: Exception) {
            return SAnime.create().apply {
                title = "❌ $entryName (Carpeta eliminada o inválida)"
                thumbnail_url = "https://http.cat/404"
                url = MegaLinkData(
                    entryUrl,
                    "error404",
                    MegaLinkDataInfo(entryName, entryUrl),
                ).toJsonString()
                status = SAnime.UNKNOWN
                description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" + removeEntryHint(entryName, entryUrl)
            }
        }

        return SAnime.create().apply {
            title = entryName
            url = MegaLinkData(entryUrl, "multi").toJsonString()
            thumbnail_url = ""
            description = removeEntryHint(entryName, entryUrl)
        }
    }

    /**
     * Texto reutilizado en la descripción de cada entrada para poder
     * eliminarla desde Ajustes → Eliminar enlace (pegando el nombre o la
     * URL) sin tener que buscarla a mano en "Enlaces guardados".
     */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    /**
     * Entradas sintéticas del Cloud Drive de la cuenta logueada -- no vienen
     * de un link pegado a mano, así que a diferencia de [buildFolderAnime] si
     * falla el listado (sesión vencida, error de red) se omiten en silencio
     * en vez de mostrar una entrada de error: el usuario no las agregó a
     * propósito, y el estado real de la sesión ya se ve en Ajustes.
     *
     * Se genera una entrada por cada CARPETA DE PRIMER NIVEL del Cloud Drive
     * (antes se juntaba todo en una sola entrada "Mi Cloud Drive"), más una
     * entrada aparte para los videos que estén sueltos directo en la raíz
     * (fuera de cualquier carpeta), si los hay. Subcarpetas anidadas dentro
     * de una carpeta de primer nivel se aplanan: sus videos se listan como
     * episodios de esa misma entrada, sin crear más niveles.
     *
     * Una carpeta es "de primer nivel" si su padre no es, a su vez, otra
     * carpeta de la lista -- es decir, cuelga directo de la raíz del Cloud
     * Drive. No hace falta conocer el handle exacto de esa raíz (el nodo
     * tipo ROOT ya viene descartado desde MegaApi.listCloudDrive): alcanza
     * con ver que ningún nodo tiene ese handle como propio.
     */
    private fun buildCloudDriveAnimeList(): List<SAnime> {
        val email = extractor.loggedInEmail() ?: return emptyList()
        val nodes = try {
            extractor.listCloudDrive().also { nodeCache[CLOUD_DRIVE_HANDLE] = it }
        } catch (e: Exception) {
            return emptyList()
        }

        val folderHandles = nodes.filter { it.isFolder }.map { it.handle }.toSet()
        val topLevelFolders = nodes.filter { it.isFolder && it.parentHandle !in folderHandles }
        val rootLooseVideos = nodes.filter { !it.isFolder && it.parentHandle !in folderHandles && isVideoName(it.name) }

        val animeList = mutableListOf<SAnime>()

        topLevelFolders.forEach { folder ->
            animeList += SAnime.create().apply {
                title = "☁️ ${folder.name}"
                url = MegaLinkData("https://mega.nz/fm", "cloud_folder", folderHandle = folder.handle).toJsonString()
                thumbnail_url = ""
                description = "Carpeta de tu Cloud Drive de MEGA (cuenta: $email)."
            }
        }

        if (rootLooseVideos.isNotEmpty()) {
            animeList += SAnime.create().apply {
                title = "☁️ Mi Cloud Drive (raíz)"
                url = MegaLinkData("https://mega.nz/fm", "cloud").toJsonString()
                thumbnail_url = ""
                description = "Videos sueltos en la raíz de tu Cloud Drive de MEGA (cuenta: $email), " +
                    "fuera de cualquier carpeta."
            }
        }

        return animeList
    }

    // ── Búsqueda + Filtros (agregar nuevo enlace) ──────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlFilter = filters.filterIsInstance<UrlFilter>().firstOrNull()
        val nameFilter = filters.filterIsInstance<NameFilter>().firstOrNull()
        val rawUrl = urlFilter?.state?.trim() ?: ""

        if (rawUrl.isNotBlank()) {
            val link = extractor.parseLink(rawUrl)
                ?: throw Exception("URL de MEGA inválida")

            // Evita guardar la misma URL dos veces con nombres distintos.
            MegaPreferences.findByUrl(preferences, rawUrl)?.let { existing ->
                throw Exception("Este enlace ya está guardado como \"${existing.name}\"")
            }

            var sizeLabel: String? = null
            val resolvedName = nameFilter?.state?.trim()?.takeIf { it.isNotBlank() }
                ?: when (link) {
                    is MegaLink.File -> {
                        val meta = runCatching { resolveSingleFileCached(link) }.getOrNull()
                        sizeLabel = meta?.size?.let { formatBytes(it) }
                        meta?.name ?: link.handle
                    }
                    is MegaLink.Folder -> link.handle
                }

            MegaPreferences.addEntry(preferences, resolvedName, rawUrl)
            cachedAnimes = null

            val anime = when (link) {
                is MegaLink.File -> buildSingleFileAnime(resolvedName, rawUrl, sizeLabel)
                is MegaLink.Folder -> buildFolderAnime(resolvedName, rawUrl, link)
            }
            return AnimesPage(listOf(anime), false)
        }

        val all = getPopularAnime(page)
        val results = if (query.isBlank()) all.animes else all.animes.filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(results, false)
    }

    override fun getFilterList() = AnimeFilterList(
        InfoFilter("Pega una URL de archivo o carpeta de MEGA para agregarlo"),
        SeparatorFilter(),
        UrlFilter(),
        NameFilter(),
        SeparatorFilter(),
        InfoFilter("Deja los campos vacíos para buscar por nombre entre tus enlaces guardados"),
    )

    // ── Detalles ──────────────────────────────────────────────────────────────

    // El botón "abrir en WebView" usa la URL que devuelve este método. Se
    // navega al link real de MEGA guardado en anime.url; si el parseo falla
    // o es una entrada "fantasma" (error404), se usa baseUrl como fallback.
    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = try {
            json.decodeFromString<MegaLinkData>(anime.url)
        } catch (e: Exception) {
            return GET(baseUrl)
        }
        return GET(parsed.url)
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = try {
            json.decodeFromString<MegaLinkData>(anime.url)
        } catch (e: Exception) {
            return anime
        }

        // Se reconstruye siempre (no solo al agregar el enlace) para que la
        // descripción con las instrucciones de "Eliminar enlace" se muestre
        // también en entradas que ya estaban guardadas antes de este campo.
        return when (parsed.type) {
            "error404" -> anime.apply {
                // anime.title trae el prefijo "❌ ... (Carpeta eliminada...)";
                // el nombre real para pegar en "Eliminar enlace" es el que
                // se guardó en info.title al crear la entrada.
                val realName = parsed.info?.title ?: anime.title
                description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" +
                    removeEntryHint(realName, parsed.url)
            }
            "single", "multi" -> anime.apply {
                description = removeEntryHint(anime.title, parsed.url)
            }
            else -> anime
        }
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parsed = json.decodeFromString<MegaLinkData>(anime.url)

        if (parsed.type == "error404") return emptyList()

        if (parsed.type == "single") {
            val link = extractor.parseLink(parsed.url) as? MegaLink.File ?: return emptyList()
            val meta = try {
                resolveSingleFileCached(link)
            } catch (e: Exception) {
                null
            }
            val display = FilenameUtils.buildEpisodeDisplay(meta?.name ?: "Video", showFilename)
            return listOf(
                SEpisode.create().apply {
                    name = display.name
                    scanlator = meta?.size?.let { formatBytes(it) } ?: (parsed.info?.size ?: "")
                    url = MegaEpisodeData(fileHandle = link.handle, fileKey = link.key, folderHandle = null).toJsonString()
                    episode_number = 1F
                    date_upload = -1L
                },
            )
        }

        if (parsed.type == "cloud" || parsed.type == "cloud_folder") {
            val nodes = nodeCache[CLOUD_DRIVE_HANDLE] ?: extractor.listCloudDrive().also { nodeCache[CLOUD_DRIVE_HANDLE] = it }

            if (nodes.isEmpty()) {
                throw Exception("Tu Cloud Drive de MEGA está vacío (o no se pudo listar: revisa que la sesión siga activa en Ajustes).")
            }

            val folderHandles = nodes.filter { it.isFolder }.map { it.handle }.toSet()
            val nodesByHandle = nodes.associateBy { it.handle }

            // true si [node] cuelga -- directa o indirectamente, a través de
            // cualquier cantidad de subcarpetas -- de la carpeta [ancestorHandle].
            // Así se aplanan subcarpetas anidadas dentro de una carpeta de
            // primer nivel: da igual a cuántos niveles de profundidad esté
            // el archivo, mientras algún ancestro suyo sea esa carpeta.
            fun isDescendantOf(node: MegaNode, ancestorHandle: String): Boolean {
                var current: MegaNode? = node
                var hops = 0
                while (current != null && hops < 64) { // límite defensivo anti ciclos
                    val parent = current.parentHandle ?: return false
                    if (parent == ancestorHandle) return true
                    current = nodesByHandle[parent]
                    hops++
                }
                return false
            }

            val relevantNodes = if (parsed.type == "cloud_folder") {
                val folderHandle = parsed.folderHandle ?: return emptyList()
                nodes.filter { !it.isFolder && isDescendantOf(it, folderHandle) }
            } else {
                // "cloud": solo videos sueltos directo en la raíz, fuera de
                // cualquier carpeta (mismo criterio que buildCloudDriveAnimeList).
                nodes.filter { !it.isFolder && it.parentHandle !in folderHandles }
            }

            val videoNodes = relevantNodes.filter { isVideoName(it.name) }
            if (videoNodes.isEmpty()) {
                throw Exception(
                    if (parsed.type == "cloud_folder") {
                        "No se encontraron videos en esta carpeta de tu Cloud Drive."
                    } else {
                        "No se encontraron videos sueltos en la raíz de tu Cloud Drive."
                    },
                )
            }

            return FilenameUtils.sortBySeasonAndEpisodeDescending(videoNodes, { it.name }, showFilename)
                .map { seasoned ->
                    SEpisode.create().apply {
                        name = seasoned.display.name
                        url = MegaEpisodeData(fileHandle = seasoned.item.handle, fileKey = null, folderHandle = null, fromCloudDrive = true).toJsonString()
                        episode_number = seasoned.display.episodeNumber
                        date_upload = seasoned.item.timestamp
                        scanlator = formatBytes(seasoned.item.size)
                    }
                }
        }

        val link = extractor.parseLink(parsed.url) as? MegaLink.Folder ?: return emptyList()
        val nodes = nodeCache[link.handle] ?: extractor.listFolder(link).also { nodeCache[link.handle] = it }

        if (nodes.isEmpty()) {
            throw Exception("MEGA no devolvió contenido para esta carpeta (posible link inválido o carpeta vacía)")
        }

        // Si el link apunta a un archivo específico dentro de la carpeta
        // (mega.nz/folder/H#K/file/subH), solo se expone ese archivo, no el
        // resto del contenido de la carpeta.
        val relevantNodes = if (link.subFileHandle != null) {
            nodes.filter { it.handle == link.subFileHandle }
        } else {
            nodes
        }

        val videoNodes = relevantNodes.filter { !it.isFolder && isVideoName(it.name) }

        if (videoNodes.isEmpty()) {
            val noKey = relevantNodes.count { !it.isFolder && it.fileKey == null }
            val withKeyButNoName = relevantNodes.count { !it.isFolder && it.fileKey != null && it.name == "(nombre no disponible)" }
            val detail = when {
                noKey > 0 -> " ($noKey de ${relevantNodes.size} archivos sin key resoluble -- problema al descifrar la node key)"
                withKeyButNoName > 0 -> " ($withKeyButNoName de ${relevantNodes.size} archivos con key pero el nombre no descifró -- problema al descifrar el atributo)"
                else -> " (se listaron ${relevantNodes.size} nodos, pero ninguno con extensión de video reconocida)"
            }
            throw Exception("No se encontraron videos en esta carpeta de MEGA.$detail")
        }

        // Orden y nombre resueltos juntos por
        // FilenameUtils.sortBySeasonAndEpisodeDescending: los especiales (OP,
        // ED, OVA, Special/Extra, etc.) van arriba de todo, luego los
        // episodios numerados de mayor a menor; nombre real del archivo (si
        // showFilename) o etiqueta genérica ("Episodio 24", "OP 2") según
        // categoría y número detectados.
        return FilenameUtils.sortBySeasonAndEpisodeDescending(videoNodes, { it.name }, showFilename)
            .map { seasoned ->
                SEpisode.create().apply {
                    name = seasoned.display.name
                    url = MegaEpisodeData(fileHandle = seasoned.item.handle, fileKey = null, folderHandle = link.handle).toJsonString()
                    episode_number = seasoned.display.episodeNumber
                    date_upload = seasoned.item.timestamp
                    scanlator = formatBytes(seasoned.item.size)
                }
            }
    }

    private fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes bytes"
        else -> ""
    }

    // ── Video ────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val data = json.decodeFromString<MegaEpisodeData>(episode.url)

        if (data.fromCloudDrive) {
            val cached = nodeCache[CLOUD_DRIVE_HANDLE] ?: extractor.listCloudDrive().also { nodeCache[CLOUD_DRIVE_HANDLE] = it }
            val node = cached.find { it.handle == data.fileHandle } ?: return emptyList()
            return extractor.videoFromOwnFile(node)
        }

        // Archivo único (sin carpeta): resolver directo con su propia key.
        if (data.folderHandle == null) {
            val fileKey = data.fileKey ?: return emptyList()
            val link = MegaLink.File(handle = data.fileHandle, key = fileKey)
            val meta = resolveSingleFileCached(link)
            return extractor.videoFromSingleFile(meta)
        }

        // Archivo dentro de una carpeta: buscar el nodo ya cacheado (o
        // relistar la carpeta si el caché se perdió, p.ej. tras reiniciar
        // la app).
        val node = nodeCache[data.folderHandle]?.find { it.handle == data.fileHandle }
            ?: run {
                val entries = MegaPreferences.getEntries(preferences)
                val entry = entries.firstOrNull {
                    val link = extractor.parseLink(it.url)
                    link is MegaLink.Folder && link.handle == data.folderHandle
                } ?: return emptyList()

                val link = extractor.parseLink(entry.url) as? MegaLink.Folder ?: return emptyList()
                val nodes = extractor.listFolder(link)
                nodeCache[data.folderHandle] = nodes
                nodes.find { it.handle == data.fileHandle }
            } ?: return emptyList()

        return extractor.videoFromNode(node, folderHandle = data.folderHandle)
    }

    // ── Latest (no soportado) ──────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ── Utilidades de serialización ────────────────────────────────────────

    private fun MegaLinkData.toJsonString(): String = json.encodeToString(this)
    private fun MegaEpisodeData.toJsonString(): String = json.encodeToString(this)

    // ── Preferencias ──────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = MegaPreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )

        EditTextPreference(screen.context).apply {
            key = MegaPreferences.PREF_MEGA_EMAIL
            title = "Cuenta de MEGA (opcional) — Email"
            summary = extractor.loggedInEmail()?.let { "Sesión iniciada como: $it" }
                ?: "Sin sesión iniciada. Escribe tu email y después tu contraseña (abajo) para iniciar sesión."
            dialogTitle = "Email de MEGA"
            setDialogMessage(
                "Si inicias sesión, esta extensión y cualquier otra que use la misma librería " +
                    "descargan con la cuota de ancho de banda de tu cuenta en vez de la anónima -- " +
                    "útil sobre todo para video de mayor resolución o peso. Es opcional: sin sesión, " +
                    "todo sigue funcionando igual que antes.",
            )
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "meganz_account_password_input"
            title = "Contraseña de MEGA"
            summary = "Escribe aquí tu contraseña para iniciar sesión con el email de arriba. No se guarda: se usa una sola vez. " +
                "Si tu cuenta tiene verificación en dos pasos, agrega \"::\" y el código (ejemplo: tucontraseña::123456)."
            dialogTitle = "Contraseña de MEGA"
            setDialogMessage(
                "Se usa una sola vez para iniciar sesión. No queda guardada en ningún lado. Si tu cuenta " +
                    "tiene verificación en dos pasos activada, escribe la contraseña seguida de \"::\" y el " +
                    "código de verificación, por ejemplo: tucontraseña::123456",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val email = MegaPreferences.getSavedEmail(preferences)
                val raw = (newValue as String).trim()
                val hasCode = raw.contains("::")
                val password = if (hasCode) raw.substringBeforeLast("::") else raw
                val pin = if (hasCode) raw.substringAfterLast("::").trim().ifBlank { null } else null
                attemptLogin(screen.context, email, password, pin)
                false // ni la contraseña ni el código de 2FA se persisten
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "meganz_account_logout_button"
            title = "Cerrar sesión de MEGA"
            summary = "Cierra la sesión para esta y para todas las extensiones que usan la misma librería."
            dialogTitle = "Cerrar sesión de MEGA"
            setDialogMessage(
                "El cuadro de texto no se usa: toca Aceptar para cerrar sesión ahora, o Cancelar para no hacer nada.",
            )
            setOnPreferenceChangeListener { _, _ ->
                extractor.logout()
                nodeCache.remove(CLOUD_DRIVE_HANDLE)
                cachedAnimes = null
                Toast.makeText(screen.context, "Sesión de MEGA cerrada.", Toast.LENGTH_SHORT).show()
                false
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            // key dummy: no se persiste nada real, pero EditTextPreference
            // la requiere no-nula.
            key = "meganz_stop_proxy_button"
            title = "Detener servidor de MEGA"
            summary = "El servidor local que descifra y sirve los videos de MEGA se queda " +
                "corriendo en segundo plano mientras la app esté abierta (el costo de " +
                "dejarlo así es mínimo). Si prefieres apagarlo manualmente en vez de " +
                "esperar a cerrar la app, toca aquí. Si estás reproduciendo algo ahora " +
                "mismo, se cortará."
            setDialogTitle("Detener servidor de MEGA")
            setDialogMessage(
                "El cuadro de texto no se usa: toca Aceptar para detener el servidor " +
                    "ahora, o Cancelar para no hacer nada.",
            )
            // Se reutiliza EditTextPreference como confirmación
            // (Aceptar/Cancelar); el texto tecleado se ignora.
            // onPreferenceChangeListener solo dispara con Aceptar, y
            // retorna false para no persistir nada.
            setOnPreferenceChangeListener { _, _ ->
                extractor.shutdown()
                Toast.makeText(
                    screen.context,
                    "Servidor de MEGA detenido.",
                    Toast.LENGTH_SHORT,
                ).show()
                false
            }
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = MegaPreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de MEGA\n\n" +
                    "Para eliminar una entrada, borra la línea completa.\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        lateinit var removeEntryPref: EditTextPreference
        removeEntryPref = EditTextPreference(screen.context).apply {
            key = MegaPreferences.REMOVE_ENTRY_KEY
            title = "Eliminar enlace"
            summary = "Pega aquí el nombre o el link de MEGA del enlace que quieres quitar"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el nombre o la URL de MEGA de la entrada que quieres quitar (puedes copiarlos desde " +
                    "la descripción de esa entrada en el catálogo, o -- para una carpeta que funciona -- también " +
                    "el link que Anikku copia automáticamente al mantener presionado el ícono de WebView).\n\n" +
                    "Si el nombre pegado coincide con más de una entrada guardada, no se elimina ninguna " +
                    "(usa la URL en ese caso, para no borrar la equivocada).\n\n" +
                    "Para ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.",
            )
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val updated = MegaPreferences.removeEntryByLine(preferences, lineToRemove)
                    cachedAnimes = null
                    folderListPref.text = updated

                    preferences.edit().putString(MegaPreferences.REMOVE_ENTRY_KEY, "").commit()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        removeEntryPref.text = ""
                    }
                }
                true
            }
        }
        screen.addPreference(removeEntryPref)
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")

        // Key sentinela para nodeCache: un handle real de MEGA siempre tiene
        // 8 caracteres, así que este valor (más largo, con guion bajo) no
        // puede chocar con uno real.
        private const val CLOUD_DRIVE_HANDLE = "CLOUD_DRIVE_ROOT"
    }
}
