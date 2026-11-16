package eu.kanade.tachiyomi.animeextension.all.anigram

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.filenameutils.FilenameUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * AniGram: biblioteca de anime guardada en Telegram.
 * Ajustes: credenciales de Telegram, enlace de un mensaje del tema "Catalogo" y opciones de nombres.
 */
class AniGram :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniGram"
    override val baseUrl = "https://telegram.org"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences by getPreferencesLazy()
    private val app by lazy { Injekt.get<Application>() }

    private val showFilename: Boolean
        get() = preferences.getBoolean("show_filename", false)

    private fun aviso(texto: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, texto, Toast.LENGTH_LONG).show() }
    }

    /** Arranca TDLib si ya hay credenciales guardadas. Devuelve el estado actual. */
    private fun asegurarCliente(): String {
        val id = preferences.getString("api_id", "")?.trim()?.toIntOrNull()
        val hash = preferences.getString("api_hash", "")?.trim().orEmpty()
        if (id == null || hash.isEmpty()) return "FALTAN api_id / api_hash en los ajustes"
        return try {
            TdSession.iniciar(app, id, hash)
            TdSession.esperarEstado()
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun exigirListo() {
        val estado = asegurarCliente()
        if (estado != "Ready") throw Exception("TDLib no esta listo. Estado: $estado. Revisa los ajustes de AniGram")
    }

    private fun enlaceCatalogo(): String {
        val enlace = preferences.getString("catalogo", "").orEmpty().trim()
        if (enlace.isEmpty()) throw Exception("Falta el enlace del catalogo en los ajustes de AniGram")
        return enlace
    }

    private fun enSegundoPlano(accion: () -> Unit) {
        Thread {
            try {
                accion()
            } catch (t: Throwable) {
                aviso("Error: ${t.message}")
            }
        }.start()
    }

    // ---------- ajustes ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        fun campo(clave: String, titulo: String, ayuda: String, alCambiar: (String) -> Unit) {
            EditTextPreference(ctx).apply {
                key = clave
                this.title = titulo
                summary = ayuda
                dialogTitle = titulo
                setDefaultValue("")
                setOnPreferenceChangeListener { _, nuevo ->
                    alCambiar(nuevo as String)
                    true
                }
            }.also(screen::addPreference)
        }

        // Los "botones" son interruptores que ejecutan la accion y vuelven a quedar apagados
        fun boton(clave: String, titulo: String, ayuda: String, accion: () -> Unit) {
            SwitchPreferenceCompat(ctx).apply {
                key = clave
                this.title = titulo
                summary = ayuda
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, _ ->
                    enSegundoPlano(accion)
                    false
                }
            }.also(screen::addPreference)
        }

        fun dialogo(titulo: String, texto: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle(titulo)
                        .setMessage(texto)
                        .setPositiveButton("Cerrar", null)
                        .show()
                } catch (t: Throwable) {
                    Toast.makeText(app, texto.take(300), Toast.LENGTH_LONG).show()
                }
            }
        }

        SwitchPreferenceCompat(ctx).apply {
            key = "show_filename"
            title = "Mostrar nombre del archivo"
            summary = "Activado: nombre real del archivo.\nDesactivado: \"Episodio 1\", \"Episodio 2\"..."
            setDefaultValue(false)
        }.also(screen::addPreference)

        campo("catalogo", "Enlace del catalogo", "Enlace de un mensaje del tema Catalogo") { }

        campo("api_id", "1. api_id", "Numero de my.telegram.org") { v ->
            val hash = preferences.getString("api_hash", "").orEmpty().trim()
            val id = v.trim().toIntOrNull()
            if (id != null && hash.isNotEmpty()) {
                enSegundoPlano {
                    TdSession.iniciar(app, id, hash)
                    aviso("Estado: ${TdSession.esperarEstado()}")
                }
            }
        }
        campo("api_hash", "2. api_hash", "Cadena de my.telegram.org") { v ->
            val id = preferences.getString("api_id", "").orEmpty().trim().toIntOrNull()
            if (id != null && v.trim().isNotEmpty()) {
                enSegundoPlano {
                    TdSession.iniciar(app, id, v.trim())
                    aviso("Estado: ${TdSession.esperarEstado()}")
                }
            }
        }
        campo("telefono", "3. Telefono", "Con codigo de pais, ej. +52...") { v ->
            enSegundoPlano {
                TdSession.enviarTelefono(v)
                Thread.sleep(2500)
                aviso("Estado: ${TdSession.estado} (el codigo llega a tu Telegram)")
            }
        }
        campo("codigo", "4. Codigo", "El codigo que te llego a Telegram") { v ->
            enSegundoPlano {
                TdSession.enviarCodigo(v)
                Thread.sleep(2500)
                aviso("Estado: ${TdSession.estado}")
            }
        }
        campo("password", "5. Contrasena 2FA", "Solo si tienes verificacion en dos pasos") { v ->
            enSegundoPlano {
                TdSession.enviarPassword(v)
                Thread.sleep(2500)
                aviso("Estado: ${TdSession.estado}")
            }
        }

        boton("btn_estado", "Ver estado de TDLib", "Pulsa para ver el estado actual") {
            aviso("Estado: ${asegurarCliente()}")
        }
        boton("btn_novedades", "Buscar novedades", "Lee solo lo nuevo de cada tema y agrega los animes nuevos") {
            val estado = asegurarCliente()
            val texto = if (estado != "Ready") {
                "TDLib no esta listo. Estado: $estado"
            } else {
                try {
                    preparar()
                    iniciarServidor()
                    val enlace = enlaceCatalogo()
                    if (Catalogo.copia(enlace) == null) {
                        Catalogo.cargarTodo(enlace)
                        Catalogo.ultimoResumen
                    } else {
                        Catalogo.actualizar(enlace) + "\n\n" + Catalogo.ultimoResumen
                    }
                } catch (t: Throwable) {
                    "ERROR: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            dialogo("Novedades", texto)
        }
        boton(
            "btn_recargar",
            "Recargar catalogo completo",
            "Borra la copia guardada y lo lee todo de nuevo (por si editaste o borraste portadas)",
        ) {
            val estado = asegurarCliente()
            val texto = if (estado != "Ready") {
                "TDLib no esta listo. Estado: $estado"
            } else {
                try {
                    preparar()
                    iniciarServidor()
                    Catalogo.invalidar()
                    Catalogo.cargarTodo(enlaceCatalogo())
                    Catalogo.ultimoResumen
                } catch (t: Throwable) {
                    "ERROR: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            dialogo("Catalogo", texto)
        }
        boton("btn_tam", "Ver tamano de cache", "Cuanto ocupa lo descargado por TDLib") {
            asegurarCliente()
            aviso("Cache TDLib -> ${TdSession.tamanoCache()}")
        }
        boton("btn_limpiar", "Limpiar cache", "Borra lo descargado de los videos usados en esta sesion") {
            asegurarCliente()
            val r = TdSession.limpiarCache()
            aviso("Cache limpiada: $r -> ${TdSession.tamanoCache()}")
        }
        boton("btn_stats", "Estadisticas de streaming", "Tambien en http://127.0.0.1:8765/stats") {
            try {
                TdSession.servidor()
            } catch (t: Throwable) {
            }
            val id = TdSession.ultimoFileId
            val tdl = if (id >= 0) {
                try {
                    TdSession.infoArchivo(id)
                } catch (t: Throwable) {
                    "TDLib: error ${t.message}"
                }
            } else {
                "aun no hay archivo"
            }
            dialogo(
                "Estadisticas",
                "Puerto: ${TdSession.puerto()}\nServido a mpv: ${TgStats.bytesServidos / 1048576} MB\n$tdl\n" +
                    "Ultima limpieza: ${TdSession.ultimaLimpieza}\n\nMas detalle en http://127.0.0.1:${TdSession.puerto()}/stats",
            )
        }
        boton("btn_logout", "Cerrar sesion", "Cierra la sesion de Telegram de esta extension") {
            TdSession.cerrarSesion()
            aviso("Sesion cerrada")
        }
    }

    // ---------- catalogo ----------

    private fun estadoDe(texto: String): Int {
        val n = Catalogo.normalizar(texto)
        return when {
            n.startsWith("complet") || n.startsWith("finaliz") || n.startsWith("termin") -> SAnime.COMPLETED
            n.contains("emision") || n.contains("curso") || n.contains("ongoing") -> SAnime.ONGOING
            n.contains("cancel") -> SAnime.CANCELLED
            n.contains("pausa") || n.contains("hiato") -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    private fun llenar(anime: SAnime, p: Portada) {
        anime.title = p.titulo
        anime.url = p.clave
        anime.status = estadoDe(p.estado)
        anime.genre = p.tipo
        anime.author = p.fansub.ifBlank { null }
        anime.description = p.descripcion.ifBlank { null }
        val puerto = TdSession.puerto().takeIf { it > 0 } ?: 8765
        anime.thumbnail_url = "http://127.0.0.1:$puerto/cover/${p.chatId}/${p.mensajeId}"
    }

    private fun aSAnime(p: Portada): SAnime = SAnime.create().also { llenar(it, p) }

    /** Deja listas las carpetas de la copia del catalogo y de las portadas (es seguro repetirlo). */
    private fun preparar() {
        Catalogo.configurar(File(app.filesDir, "anigram_catalogo.json"))
        TdSession.prepararCarpetas(app)
    }

    private fun iniciarServidor() {
        try {
            TdSession.servidor() // las portadas se piden a este servidor
        } catch (t: Throwable) {
        }
    }

    /** Busca novedades sin hacer esperar a nadie. Solo actua si TDLib ya esta listo. */
    private fun refrescarEnSegundoPlano(enlace: String) {
        Thread {
            try {
                if (asegurarCliente() == "Ready") {
                    Catalogo.actualizar(enlace)
                    val nuevos = Catalogo.ultimosNuevos
                    if (nuevos > 0) {
                        aviso("AniGram: $nuevos anime(s) nuevo(s). Desliza hacia abajo para actualizar la lista")
                    }
                }
            } catch (t: Throwable) {
                TdSession.evento("la actualizacion en segundo plano fallo: ${t.message}")
            }
        }.start()
    }

    /**
     * Si hay copia guardada se muestra al instante (aunque TDLib aun no este listo) y, si ya tiene unos
     * minutos, se buscan novedades en segundo plano. Solo la primera vez se lee todo de Telegram.
     */
    private fun cargarCatalogo(): List<Portada> {
        preparar()
        val enlace = enlaceCatalogo()
        iniciarServidor()
        val copia = Catalogo.copia(enlace)
        if (copia != null) {
            if (Catalogo.estaViejo()) refrescarEnSegundoPlano(enlace)
            return copia
        }
        exigirListo()
        val lista = Catalogo.cargarTodo(enlace)
        if (lista.isEmpty()) throw Exception("El catalogo no devolvio animes.\n${Catalogo.ultimoResumen.take(400)}")
        return lista
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        return AnimesPage(cargarCatalogo().map { aSAnime(it) }, false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val todos = cargarCatalogo()
        val q = Catalogo.normalizar(query)
        val filtrados = if (q.isBlank()) todos else todos.filter { Catalogo.normalizar(it.titulo).contains(q) }
        return AnimesPage(filtrados.map { aSAnime(it) }, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        preparar()
        Catalogo.buscarPorClave(anime.url)?.let { llenar(anime, it) }
        return anime
    }

    // ---------- episodios ----------

    private fun formatearTamano(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1073741824.0)
        else -> "${bytes / 1048576} MB"
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        preparar()
        val partes = anime.url.split("|")
        if (partes.size < 2) throw Exception("Entrada invalida: ${anime.url}. Recarga el catalogo")
        val enlace = partes[0]
        val mensajeId = partes[1].toLongOrNull() ?: throw Exception("Entrada invalida: ${anime.url}")
        val esperados = Catalogo.buscarPorClave("$enlace|$mensajeId")?.episodios ?: partes.getOrNull(2)?.toIntOrNull() ?: 0

        exigirListo()
        val leidos = Catalogo.leerEpisodios(enlace, mensajeId, esperados)
        if (leidos.videos.isEmpty()) return emptyList()

        val ordenados = FilenameUtils.sortBySeasonAndEpisodeDescending(
            leidos.videos,
            { Catalogo.nombreParaLibreria(it.nombre) },
            showFilename,
        )
        return ordenados.map { ep ->
            SEpisode.create().apply {
                name = ep.display.name
                episode_number = ep.display.episodeNumber
                url = "${ep.item.chatId}|${ep.item.mensajeId}"
                scanlator = formatearTamano(ep.item.tamano).ifBlank { null }
                date_upload = ep.item.fecha * 1000L
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val partes = episode.url.split("|")
        val chatId = partes.getOrNull(0)?.toLongOrNull()
        val mensajeId = partes.getOrNull(1)?.toLongOrNull()
        if (chatId == null || mensajeId == null) throw Exception("Episodio invalido: ${episode.url}")
        exigirListo()
        val archivo = TdSession.resolverArchivo("$chatId:$mensajeId") {
            TdSession.archivoDeMensaje(chatId, mensajeId)
        }
        val url = TdSession.servidor().registrar(archivo)
        return listOf(Video(url, "Telegram - ${archivo.nombre}", url))
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("popularAnimeRequest")
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("popularAnimeParse")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("searchAnimeRequest")
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("searchAnimeParse")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("latestUpdatesRequest")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("latestUpdatesParse")
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("animeDetailsParse")
    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException("episodeListRequest")
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("episodeListParse")
    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException("videoListRequest")
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("videoListParse")
}
