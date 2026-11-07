package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class Mega :
    AnimeHttpLegacySource(),
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

    // Preferencia del email: se guarda para repintar el estado de la sesión sin reabrir Ajustes.
    private var emailPref: EditTextPreference? = null

    // Verdadero si la última carga del Cloud Drive falló (posible sesión vencida); pinta el estado en amarillo.
    private var cloudDriveLoadFailed = false

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
                MegaPreferences.clearCloudCache(preferences)
                cloudDriveLoadFailed = false
                cachedAnimes = null // para que el Cloud Drive aparezca (o desaparezca) en el catálogo
                refreshSessionSummary()
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

        // Un fallo del Cloud Drive (sesión vencida, red) no debe tumbar los
        // enlaces guardados: se muestra un aviso y no se guarda el resultado
        // en memoria, para reintentar la próxima vez que se abra el catálogo.
        var cacheable = true
        if (MegaPreferences.showCloudDrive(preferences) && extractor.isLoggedIn()) {
            try {
                animeList += loadCloudDriveAnimes()
                cloudDriveLoadFailed = false
            } catch (e: Exception) {
                cloudDriveLoadFailed = true
                cacheable = false
                animeList += cloudDriveErrorAnime(e.message)
            }
        }

        animeList += buildEntryAnimes(MegaPreferences.getEntries(preferences))

        if (cacheable) cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    /**
     * Enlaces guardados. Las carpetas marcadas como completadas se comprueban
     * una sola vez y se recuerdan en disco; las demás (en emisión o
     * desconocido) se comprueban una vez por sesión. Los archivos únicos no
     * piden nada al construirse, así que no necesitan caché.
     */
    private fun buildEntryAnimes(entries: List<MegaPreferences.Entry>): List<SAnime> {
        val verified = MegaPreferences.readVerifiedFolders(preferences)
        val newVerified = mutableSetOf<String>()
        val results = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val link = extractor.parseLink(entry.url) ?: return@forEach

            when (link) {
                is MegaLink.File -> {
                    results += buildSingleFileAnime(entry.name, entry.url, status = entry.status)
                }

                is MegaLink.Folder -> {
                    val completed = entry.status == SAnime.COMPLETED
                    if (completed && entry.url in verified) {
                        newVerified += entry.url
                        results += folderAnime(entry)
                    } else {
                        val alive = isFolderAlive(link)
                        results += if (alive) folderAnime(entry) else deletedFolderAnime(entry)
                        // Una carpeta que falló (borrada o error de red) nunca se guarda como verificada.
                        if (completed && alive) newVerified += entry.url
                    }
                }
            }
        }

        // Poda: solo quedan en disco las carpetas que siguen guardadas y completadas.
        if (newVerified != verified) MegaPreferences.writeVerifiedFolders(preferences, newVerified)
        return results
    }

    /**
     * El nombre real de un archivo único se resuelve de forma perezosa en
     * getAnimeDetails, igual que hacen Drive/MediaFire.
     */
    private fun buildSingleFileAnime(
        entryName: String,
        entryUrl: String,
        sizeLabel: String? = null,
        status: Int = SAnime.UNKNOWN,
    ): SAnime = SAnime.create().apply {
        title = entryName
        url = MegaLinkData(entryUrl, "single", sizeLabel?.let { MegaLinkDataInfo(entryName, it) }).toJsonString()
        thumbnail_url = ""
        description = removeEntryHint(entryName, entryUrl)
        this.status = status
    }

    /**
     * Lista la carpeta para detectar si fue eliminada (entrada "fantasma" con
     * instrucciones de borrado) o si sigue viva. De paso deja los nodos en
     * [nodeCache] para el resto de la sesión.
     */
    private fun isFolderAlive(link: MegaLink.Folder): Boolean = try {
        extractor.listFolder(link).also { nodeCache[link.handle] = it }
        true
    } catch (e: Exception) {
        false
    }

    private fun folderAnime(entry: MegaPreferences.Entry): SAnime = SAnime.create().apply {
        title = entry.name
        url = MegaLinkData(entry.url, "multi").toJsonString()
        thumbnail_url = ""
        description = removeEntryHint(entry.name, entry.url)
        status = entry.status
    }

    private fun deletedFolderAnime(entry: MegaPreferences.Entry): SAnime = SAnime.create().apply {
        title = "❌ ${entry.name} (Carpeta eliminada o inválida)"
        thumbnail_url = "https://http.cat/404"
        url = MegaLinkData(
            entry.url,
            "error404",
            MegaLinkDataInfo(entry.name, entry.url),
        ).toJsonString()
        status = SAnime.UNKNOWN
        description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" + removeEntryHint(entry.name, entry.url)
    }

    private fun buildFolderAnime(entry: MegaPreferences.Entry, link: MegaLink.Folder): SAnime = if (isFolderAlive(link)) folderAnime(entry) else deletedFolderAnime(entry)

    /**
     * Texto reutilizado en la descripción de cada entrada para poder
     * eliminarla desde Ajustes → Eliminar enlace (pegando el nombre o la
     * URL) sin tener que buscarla a mano en "Enlaces guardados".
     */
    private fun removeEntryHint(entryName: String, entryUrl: String): String = "Para eliminar este enlace, copia el nombre (\"$entryName\") o la URL de abajo " +
        "y pégala en Ajustes → Eliminar enlace:\n\n$entryUrl"

    /**
     * Entradas sintéticas del Cloud Drive de la cuenta logueada -- no vienen
     * de un link pegado a mano. La estructura se lee de MEGA una sola vez y
     * se guarda en disco: después se muestra al instante, sin pedir nada,
     * hasta que se pulse "Recargar mi Cloud Drive" en Ajustes o cambie la
     * cuenta. Si la lectura falla, se lanza la excepción para que
     * [getPopularAnime] muestre un aviso y no guarde nada.
     */
    private fun loadCloudDriveAnimes(): List<SAnime> {
        val email = extractor.loggedInEmail() ?: return emptyList()

        MegaPreferences.readCloudCache(preferences, email)?.let { cached ->
            return cached.map { cloudAnime(it, email) }
        }

        val entries = buildCloudDriveEntries()
        // Un Drive vacío no se guarda: podría ser una lectura fallida y así se reintenta.
        if (entries.isNotEmpty()) MegaPreferences.writeCloudCache(preferences, email, entries)
        return entries.map { cloudAnime(it, email) }
    }

    /**
     * Se genera una entrada por cada CARPETA DE PRIMER NIVEL del Cloud Drive,
     * más una entrada aparte para los videos que estén sueltos directo en la
     * raíz (fuera de cualquier carpeta), si los hay. Subcarpetas anidadas
     * dentro de una carpeta de primer nivel se aplanan: sus videos se listan
     * como episodios de esa misma entrada, sin crear más niveles.
     *
     * Una carpeta es "de primer nivel" si su padre no es, a su vez, otra
     * carpeta de la lista -- es decir, cuelga directo de la raíz del Cloud
     * Drive. No hace falta conocer el handle exacto de esa raíz (el nodo
     * tipo ROOT ya viene descartado desde MegaApi.listCloudDrive): alcanza
     * con ver que ningún nodo tiene ese handle como propio.
     */
    private fun buildCloudDriveEntries(): List<MegaPreferences.CloudEntry> {
        val nodes = extractor.listCloudDrive().also { nodeCache[CLOUD_DRIVE_HANDLE] = it }

        val folderHandles = nodes.filter { it.isFolder }.map { it.handle }.toSet()
        val topLevelFolders = nodes.filter { it.isFolder && it.parentHandle !in folderHandles }
        val rootLooseVideos = nodes.filter { !it.isFolder && it.parentHandle !in folderHandles && isVideoName(it.name) }

        val entries = mutableListOf<MegaPreferences.CloudEntry>()

        topLevelFolders.forEach { folder ->
            entries += MegaPreferences.CloudEntry(
                title = "☁️ ${folder.name}",
                url = MegaLinkData("https://mega.nz/fm", "cloud_folder", folderHandle = folder.handle).toJsonString(),
                isRoot = false,
            )
        }

        if (rootLooseVideos.isNotEmpty()) {
            entries += MegaPreferences.CloudEntry(
                title = "☁️ Mi Cloud Drive (raíz)",
                url = MegaLinkData("https://mega.nz/fm", "cloud").toJsonString(),
                isRoot = true,
            )
        }

        return entries
    }

    private fun cloudAnime(entry: MegaPreferences.CloudEntry, email: String): SAnime = SAnime.create().apply {
        title = entry.title
        url = entry.url
        thumbnail_url = ""
        description = if (entry.isRoot) {
            "Videos sueltos en la raíz de tu Cloud Drive de MEGA (cuenta: $email), fuera de cualquier carpeta."
        } else {
            "Carpeta de tu Cloud Drive de MEGA (cuenta: $email)."
        }
    }

    private fun cloudDriveErrorAnime(message: String?): SAnime = SAnime.create().apply {
        title = "⚠️ Cloud Drive: no se pudo cargar"
        url = MegaLinkData("https://mega.nz/fm", "cloud_error").toJsonString()
        thumbnail_url = ""
        status = SAnime.UNKNOWN
        description = (message ?: "Error desconocido") +
            "\n\nRevisa que la sesión de MEGA siga activa en Ajustes de la extensión y vuelve a abrir el catálogo."
    }

    // ── Búsqueda + Filtros (agregar nuevo enlace) ──────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlFilter = filters.filterIsInstance<UrlFilter>().firstOrNull()
        val nameFilter = filters.filterIsInstance<NameFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
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

            val entry = MegaPreferences.Entry(resolvedName, rawUrl, statusFilter?.state ?: SAnime.UNKNOWN)
            MegaPreferences.addEntry(preferences, entry.name, entry.url, entry.status)
            cachedAnimes = null

            val anime = when (link) {
                is MegaLink.File -> buildSingleFileAnime(entry.name, entry.url, sizeLabel, entry.status)
                is MegaLink.Folder -> buildFolderAnime(entry, link)
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
        StatusFilter(),
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
                // El estado se lee siempre de las entradas guardadas para reflejar cambios hechos en Ajustes.
                MegaPreferences.findByUrl(preferences, parsed.url)?.let { status = it.status }
            }
            "cloud_error" -> anime.apply {
                description = "No se pudo cargar tu Cloud Drive.\n\n" +
                    "Revisa que la sesión de MEGA siga activa en Ajustes de la extensión y vuelve a abrir el catálogo."
            }
            else -> anime
        }
    }

    // ── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parsed = json.decodeFromString<MegaLinkData>(anime.url)

        if (parsed.type == "error404" || parsed.type == "cloud_error") return emptyList()

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
                // cualquier carpeta (mismo criterio que buildCloudDriveEntries).
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

    /**
     * Estado de la sesión para el campo del email, con color:
     *  - verde: hay una sesión guardada en el dispositivo;
     *  - amarillo: hay sesión, pero la última carga del Cloud Drive falló (puede haber vencido);
     *  - rojo: no hay sesión.
     * "Verde" significa que la sesión existe localmente: MEGA solo confirma que sigue válida
     * cuando se le hace una petición, por eso el amarillo se activa a partir de un fallo real.
     */
    private fun sessionSummary(): CharSequence {
        val email = extractor.loggedInEmail()
        return if (email != null) {
            val prefix = "Sesión iniciada como: "
            val warning = if (cloudDriveLoadFailed) {
                "\n⚠️ No se pudo cargar tu Cloud Drive: la sesión podría haber vencido. " +
                    "Si se repite, cierra sesión e inicia de nuevo."
            } else {
                ""
            }
            val color = if (cloudDriveLoadFailed) COLOR_SESSION_WARNING else COLOR_SESSION_OK
            SpannableString(prefix + email + warning).apply {
                setSpan(ForegroundColorSpan(color), prefix.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            val status = "Sin sesión iniciada."
            val help = " Escribe tu email y después tu contraseña (abajo) para iniciar sesión."
            SpannableString(status + help).apply {
                setSpan(ForegroundColorSpan(COLOR_SESSION_ERROR), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun refreshSessionSummary() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { emailPref?.summary = sessionSummary() }
    }

    /**
     * Botón de acción en Ajustes. Este proyecto no ofrece la clase Preference simple,
     * así que se usa un interruptor que ejecuta la acción al pulsarlo y rechaza el
     * cambio (devuelve false) para quedarse siempre apagado. No pide confirmación:
     * se usa solo para acciones inofensivas (recargar cachés, detener el servidor).
     */
    private fun actionPreference(
        screen: PreferenceScreen,
        prefKey: String,
        prefTitle: String,
        prefSummary: String,
        toastText: String,
        action: () -> Unit,
    ): SwitchPreferenceCompat = SwitchPreferenceCompat(screen.context).apply {
        key = prefKey
        title = prefTitle
        summary = prefSummary
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            action()
            Toast.makeText(screen.context, toastText, Toast.LENGTH_SHORT).show()
            false
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = MegaPreferences.PREF_SHOW_FILENAME,
            default = false,
            title = "Mostrar nombre del archivo",
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…",
        )

        val folderListPref = EditTextPreference(screen.context).apply {
            key = MegaPreferences.PREF_KEY
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de MEGA\n\n" +
                    "Para indicar el estado de emisión, agrega al final ::e (en emisión), " +
                    "::c (completado) o ::d (desconocido).\n" +
                    "Ejemplo: Mi anime::https://mega.nz/folder/abc#clave::c\n\n" +
                    "Las carpetas marcadas como completadas se comprueban una sola vez; si una se elimina " +
                    "o cambia, usa \"Refrescar enlaces guardados\".\n\n" +
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

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "meganz_refresh_saved_links",
                prefTitle = "Refrescar enlaces guardados",
                prefSummary = "Las carpetas en emisión o desconocidas se comprueban al abrir Anikku; las completadas " +
                    "se recuerdan. Esto fuerza una nueva comprobación de todas, completadas incluidas.",
                toastText = "Los enlaces se comprobarán al abrir el catálogo",
            ) {
                MegaPreferences.clearVerifiedFolders(preferences)
                cachedAnimes = null
            },
        )

        EditTextPreference(screen.context).apply {
            key = MegaPreferences.PREF_MEGA_EMAIL
            title = "Cuenta de MEGA (opcional) — Email"
            summary = sessionSummary()
            dialogTitle = "Email de MEGA"
            setDialogMessage(
                "Si inicias sesión, esta extensión y cualquier otra que use la misma librería " +
                    "descargan con la cuota de ancho de banda de tu cuenta en vez de la anónima -- " +
                    "útil sobre todo para video de mayor resolución o peso. Es opcional: sin sesión, " +
                    "todo sigue funcionando igual que antes.",
            )
            setDefaultValue("")
            emailPref = this
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

        SwitchPreferenceCompat(screen.context).apply {
            key = MegaPreferences.PREF_SHOW_CLOUD_DRIVE
            title = "Mostrar mi Cloud Drive en el catálogo"
            summary = "Solo se usa si hay una sesión de MEGA iniciada. Apagado: no se pide nada de tu cuenta al abrir el catálogo."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(
            actionPreference(
                screen = screen,
                prefKey = "meganz_reload_cloud_drive",
                prefTitle = "Recargar mi Cloud Drive",
                prefSummary = "Vuelve a leer tus carpetas la próxima vez que abras el catálogo. " +
                    "Úsalo si creaste, moviste o renombraste carpetas en tu Cloud Drive.",
                toastText = "Tu Cloud Drive se recargará al abrir el catálogo",
            ) {
                MegaPreferences.clearCloudCache(preferences)
                nodeCache.remove(CLOUD_DRIVE_HANDLE)
                cachedAnimes = null
            },
        )

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
                cloudDriveLoadFailed = false
                refreshSessionSummary()
                MegaPreferences.clearCloudCache(preferences)
                nodeCache.remove(CLOUD_DRIVE_HANDLE)
                cachedAnimes = null
                Toast.makeText(screen.context, "Sesión de MEGA cerrada.", Toast.LENGTH_SHORT).show()
                false
            }
        }.also(screen::addPreference)

        screen.addPreference(
            actionPreference(
                screen = screen,
                // Key nueva a propósito: la anterior ("meganz_stop_proxy_button") ya tenía un texto guardado
                // del antiguo cuadro de diálogo, y leerlo como booleano hacía crashear Ajustes.
                prefKey = "meganz_stop_proxy_action",
                prefTitle = "Detener servidor de MEGA",
                prefSummary = "El servidor local que descifra y sirve los videos de MEGA se queda " +
                    "corriendo en segundo plano mientras la app esté abierta (el costo de " +
                    "dejarlo así es mínimo). Si prefieres apagarlo manualmente en vez de " +
                    "esperar a cerrar la app, actívalo aquí. Si estás reproduciendo algo ahora " +
                    "mismo, se cortará.",
                toastText = "Servidor de MEGA detenido.",
            ) {
                extractor.shutdown()
            },
        )
    }

    companion object {
        private val COLOR_SESSION_OK = Color.parseColor("#4CAF50")
        private val COLOR_SESSION_WARNING = Color.parseColor("#FFC107")
        private val COLOR_SESSION_ERROR = Color.parseColor("#F44336")

        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts", "m2ts")

        // Key sentinela para nodeCache: un handle real de MEGA siempre tiene
        // 8 caracteres, así que este valor (más largo, con guion bajo) no
        // puede chocar con uno real.
        private const val CLOUD_DRIVE_HANDLE = "CLOUD_DRIVE_ROOT"
    }
}
