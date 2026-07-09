package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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

    // Cache en memoria de árboles de carpeta ya listados (handle de carpeta
    // -> nodos descifrados), para no tener que volver a golpear la API +
    // descifrar todo el árbol cada vez que se navega entre catálogo,
    // detalles y episodios de la misma carpeta.
    private val nodeCache = mutableMapOf<String, List<MegaNode>>()

    // Cache en memoria de metadata de archivo único (handle -> meta), por la
    // misma razón: getAnimeDetails y getEpisodeList antes pedían cada uno su
    // propia resolución por separado, duplicando la llamada de red a MEGA.
    private val singleFileMetaCache = mutableMapOf<String, MegaExtractor.SingleFileMeta>()

    private fun resolveSingleFileCached(link: MegaLink.File): MegaExtractor.SingleFileMeta = singleFileMetaCache.getOrPut(link.handle) {
        extractor.resolveSingleFile(link)
    }

    private val showFilename: Boolean
        get() = MegaPreferences.showFilename(preferences)

    // ── Catálogo ──────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val entries = MegaPreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
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
     * Para un archivo único, no hace falta listar nada: se resuelve el
     * nombre real de forma perezosa en getAnimeDetails, igual que hacen
     * Drive/MediaFire con sus entradas "single". Si ya se conoce el tamaño
     * (p.ej. porque se acaba de agregar el enlace) se guarda en `info.size`
     * para mostrarlo luego en el episodio, igual que hace Drive.
     */
    private fun buildSingleFileAnime(entryName: String, entryUrl: String, sizeLabel: String? = null): SAnime = SAnime.create().apply {
        title = entryName
        url = MegaLinkData(entryUrl, "single", sizeLabel?.let { MegaLinkDataInfo(entryName, it) }).toJsonString()
        thumbnail_url = ""
    }

    /**
     * Para una carpeta, se intenta listar de una vez para detectar si fue
     * eliminada (entrada "fantasma" con instrucciones de borrado, igual que
     * en Drive/MediaFire) o si tiene contenido válido.
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
                description = "Esta carpeta fue eliminada o el link ya no es válido.\n\n" +
                    "Para eliminarla, copia la línea de abajo y pégala en Ajustes → Eliminar enlace:\n\n" +
                    "$entryName::$entryUrl"
            }
        }

        return SAnime.create().apply {
            title = entryName
            url = MegaLinkData(entryUrl, "multi").toJsonString()
            thumbnail_url = ""
        }
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

    // El botón "abrir en WebView" (icono de globo) usa la URL que devuelve
    // animeDetailsRequest. Estaba hardcodeado a GET(baseUrl), que siempre
    // manda a la página principal de mega.nz en vez de al link guardado por
    // el usuario (bug real reportado: el botón nunca abría el enlace de la
    // entrada). Se parsea anime.url (el MegaLinkData serializado) para
    // recuperar el link real de MEGA y navegar ahí. Si el parseo falla o es
    // una entrada "fantasma" (error404), se cae de vuelta al baseUrl como
    // antes, para no romper ese caso.
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

        if (parsed.type == "error404") return anime
        if (parsed.type != "single") return anime

        // No se toca anime.title aquí: para un link de archivo único, el
        // título ya viene bien seteado desde buildSingleFileAnime() con el
        // nombre que el usuario le dio a la entrada al guardar el enlace
        // (p.ej. "Aobuta"). Sobrescribirlo con meta.name (el nombre real del
        // archivo en MEGA, p.ej. "[HaibaneSubs] Seishun Buta...mkv") hacía
        // que ese nombre largo apareciera como TÍTULO de la entrada en vez
        // de como nombre del episodio (que es donde sí corresponde: ver
        // getEpisodeList, showFilename). No hace falta resolver el archivo
        // aquí -- getEpisodeList ya lo hace y usa el resultado donde
        // corresponde -- así que se evita una llamada de red redundante.
        return anime
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
            return listOf(
                SEpisode.create().apply {
                    name = if (showFilename) (meta?.name ?: "Video") else "Episodio 1"
                    scanlator = meta?.size?.let { formatBytes(it) } ?: (parsed.info?.size ?: "")
                    url = MegaEpisodeData(fileHandle = link.handle, fileKey = link.key, folderHandle = null).toJsonString()
                    episode_number = 1F
                    date_upload = -1L
                },
            )
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

        // Orden: primero los especiales (OP, ED, OVA, Special, Extra -- en
        // ese orden entre sí, y por número descendente dentro de cada tipo),
        // y luego los episodios numerados de mayor a menor (24, 23, ..., 1,
        // 0 al fondo). Comparar como número (no como texto) evita que "9"
        // quede "después" de "13" por ser mayor como string.
        //
        // Los especiales van ARRIBA DE TODO -- no solo los que no tienen
        // ningún número, sino cualquiera detectado por palabra clave (OP/ED/
        // OVA/Special/Extra), aunque su nombre también incluya un número
        // (p.ej. "OP2.mkv") -- porque si un OP/ED numerado se mezclara con
        // los episodios reales por su número, ocuparía o desplazaría el
        // puesto de un episodio real ante trackers como AniList/MAL.
        val videoNodes = relevantNodes.filter { !it.isFolder && isVideoName(it.name) }
            .sortedWith(
                compareByDescending<MegaNode> { classifySpecialEpisode(it.name) != null }
                    .thenBy { classifySpecialEpisode(it.name)?.type?.sortOrder ?: Int.MAX_VALUE }
                    .thenByDescending { classifySpecialEpisode(it.name)?.number ?: extractEpisodeNumber(it.name) ?: Int.MIN_VALUE },
            )

        if (videoNodes.isEmpty()) {
            val noKey = relevantNodes.count { !it.isFolder && it.fileKey == null }
            val withKeyButNoName = relevantNodes.count { !it.isFolder && it.fileKey != null && it.name == "(nombre no disponible)" }
            val detail = when {
                noKey > 0 -> " ($noKey de ${relevantNodes.size} archivos sin key resoluble -- problema al descifrar la node key)"
                withKeyButNoName > 0 -> " ($withKeyButNoName de ${relevantNodes.size} archivos con key pero el nombre no descifró -- problema al descifrar el atributo)"
                else -> " (se listaron ${relevantNodes.size} nodos, pero ninguno con extensión de video reconocida)"
            }
            // DIAGNÓSTICO TEMPORAL: se agrega un fragmento del JSON crudo de
            // la API al propio mensaje de error, para poder verlo en pantalla
            // sin necesitar logcat/adb. Quitar una vez resuelto el bug.
            val rawSnippet = extractor.lastRawFolderResponse()?.take(500) ?: "(sin respuesta capturada)"
            throw Exception("No se encontraron videos en esta carpeta de MEGA.$detail\n\nRAW: $rawSnippet")
        }

        return videoNodes.map { node ->
            val special = classifySpecialEpisode(node.name)
            val realNumber = special?.number ?: extractEpisodeNumber(node.name)

            SEpisode.create().apply {
                name = if (showFilename) {
                    node.name
                } else if (special != null) {
                    // "OP", "OP 2", "ED", "OVA 1", etc. -- con el número real
                    // si el archivo lo trae, sin número si no lo trae.
                    special.number?.let { "${special.type.label} $it" } ?: special.type.label
                } else {
                    // Episodio numerado normal: se usa el número REAL
                    // extraído del nombre (24, 23...), no la posición en la
                    // lista -- si se usara el índice, con la lista ya en
                    // orden descendente el primer episodio (24) terminaría
                    // etiquetado como "Episodio 1".
                    "Episodio ${realNumber ?: "?"}"
                }
                url = MegaEpisodeData(fileHandle = node.handle, fileKey = null, folderHandle = link.handle).toJsonString()
                // episode_number es lo que usan los trackers (AniList/MAL)
                // para hacer match. Los especiales usan un rango negativo
                // propio por tipo, para no chocar entre sí ni con los
                // episodios reales (siempre positivos, o 0 si existe un
                // episodio 0). Sin esto, un OP/ED se contaría como si fuera
                // el episodio 1 y desincronizaría el seguimiento del usuario.
                episode_number = when {
                    special != null -> (special.type.baseOffset - (special.number ?: 0)).toFloat()
                    realNumber != null -> realNumber.toFloat()
                    else -> -9999F
                }
                date_upload = node.timestamp
                scanlator = formatBytes(node.size)
            }
        }
    }

    private enum class SpecialEpisodeType(val label: String, val sortOrder: Int, val baseOffset: Int) {
        OP("OP", 0, -1000),
        ED("ED", 1, -2000),
        OVA("OVA", 2, -3000),
        SPECIAL("Special", 3, -4000),
        EXTRA("Extra", 4, -5000),
    }

    private data class SpecialEpisodeInfo(val type: SpecialEpisodeType, val number: Int?)

    // Detecta si un nombre de archivo corresponde a un opening, ending, OVA,
    // especial o extra (en vez de un episodio numerado normal), y extrae su
    // número si lo trae (p.ej. "OP2.mkv" -> OP, 2; "NCED.mkv" -> ED, null).
    //
    // El lookahead (?=\d|\s|$|[^a-zA-Z0-9]) después de OP/ED/OVA es necesario
    // porque \b (límite de palabra) NO separa una letra de un dígito pegado:
    // "OP2" no tiene boundary entre "P" y "2", así que \bOP\b nunca matchea
    // ahí y el archivo caía por error como episodio numerado normal. Este
    // lookahead exige que a "OP" le siga un dígito, un espacio, el final del
    // nombre, o un símbolo -- pero NUNCA otra letra -- así "OPTIMUS" sigue
    // sin dispararlo (ahí sigue "T", una letra).
    private fun classifySpecialEpisode(name: String): SpecialEpisodeInfo? {
        val withoutExt = name.substringBeforeLast('.')
        val type = when {
            Regex("""(?i)\bNCOP\b|\bOP(?=\d|\s|$|[^a-zA-Z0-9])|opening""").containsMatchIn(withoutExt) -> SpecialEpisodeType.OP
            Regex("""(?i)\bNCED\b|\bED(?=\d|\s|$|[^a-zA-Z0-9])|ending""").containsMatchIn(withoutExt) -> SpecialEpisodeType.ED
            Regex("""(?i)\bOVA(?=\d|\s|$|[^a-zA-Z0-9])""").containsMatchIn(withoutExt) -> SpecialEpisodeType.OVA
            Regex("""(?i)\bespecial(es)?\b|\bspecial\b""").containsMatchIn(withoutExt) -> SpecialEpisodeType.SPECIAL
            Regex("""(?i)\bextra(s)?\b""").containsMatchIn(withoutExt) -> SpecialEpisodeType.EXTRA
            else -> return null
        }
        // Número pegado o cercano a la palabra clave (p.ej. "OP2", "OP 2",
        // "ED-01"), buscando el último grupo de dígitos del nombre igual que
        // extractEpisodeNumber, ya que openings/endings casi siempre traen el
        // número (si lo tienen) al final del nombre, antes de la extensión.
        val number = Regex("""(\d+)\s*$""").find(withoutExt)?.groupValues?.get(1)?.toIntOrNull()
        return SpecialEpisodeInfo(type, number)
    }

    private fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    // Toma el último grupo de dígitos del nombre (antes de la extensión, si
    // es posible) como número de episodio. Ejemplos:
    //   "[HaibaneSubs] Ragna Crimson - 13.mkv" -> 13
    //   "Episodio 09.mkv" -> 9
    //   "[HaibaneSubs] Seishun Buta Yarou...[1080p AVC E-AC3].mkv" -> null
    //     (el "1080" que aparece ahí NO cuenta: solo se buscan números que
    //     estén inmediatamente antes de la extensión de archivo, ya que un
    //     número de resolución/codec en medio del nombre no es el episodio)
    private fun extractEpisodeNumber(name: String): Int? {
        val withoutExt = name.substringBeforeLast('.')
        val match = Regex("""(\d+)\s*$""").find(withoutExt) ?: return null
        return match.groupValues[1].toIntOrNull()
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
        SwitchPreferenceCompat(screen.context).apply {
            key = MegaPreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
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
            summary = "Pega aquí el link de MEGA, o la línea Nombre::URL de una carpeta eliminada"
            dialogTitle = "Eliminar enlace"
            setDialogMessage(
                "Pega el link de MEGA de la entrada que quieres quitar (por ejemplo, el que Anikku copia " +
                    "automáticamente al mantener presionado el ícono de WebView), o -- si la entrada da " +
                    "error -- la línea Nombre::URL completa desde su descripción.\n\n" +
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
    }
}
