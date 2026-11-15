package eu.kanade.tachiyomi.animeextension.all.anigram

import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Una portada del catalogo: foto con la ficha del anime en el pie de foto. */
class Portada(
    val titulo: String,
    val tipo: String,
    val estado: String,
    val episodios: Int,
    val fansub: String,
    val descripcion: String,
    val enlaceLetra: String,
    val mensajeId: Long,
    val chatId: Long,
) {
    /** Identificador estable que se guarda como url del anime. */
    val clave: String get() = "$enlaceLetra|$mensajeId"
}

/** Estado guardado de un tema de letra: sus portadas y el ultimo mensaje visto. */
class EstadoLetra(val enlace: String, val ultimoId: Long, val portadas: List<Portada>)

class VideoMensaje(
    val chatId: Long,
    val mensajeId: Long,
    val nombre: String,
    val tamano: Long,
    val fecha: Long,
)

class EpisodiosLeidos(val videos: List<VideoMensaje>, val aviso: String)

/**
 * Lectura del catalogo de Telegram:
 * - un tema "Catalogo" con enlaces a los temas de cada letra,
 * - en cada tema de letra, portadas (fotos con ficha) seguidas de los videos del anime.
 */
object Catalogo {
    @Volatile var ultimoResumen: String = "(el catalogo aun no se ha cargado)"

    @Volatile var ultimoAviso: String = ""

    private val bloqueo = Any()
    private var estado: List<EstadoLetra>? = null
    private var enlaceDelEstado = ""
    private var archivoCopia: File? = null
    private val refrescando = AtomicBoolean(false)

    @Volatile var actualizadoMs = 0L

    /** Animes nuevos que encontro la ultima actualizacion. */
    @Volatile var ultimosNuevos = 0

    /** Pasado este tiempo, al abrir la extension se buscan novedades en segundo plano. */
    private const val REFRESCO_MS = 10 * 60 * 1000L

    @Volatile private var filtroFotoFunciona = true

    private val regexUrl = Regex("https?://t\\.me/\\S+")
    private val extensionesVideo = listOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "flv", "wmv")

    /** Indica donde se guarda la copia del catalogo (un archivo JSON en el telefono). */
    fun configurar(archivo: File) {
        archivoCopia = archivo
    }

    fun estaViejo(): Boolean = System.currentTimeMillis() - actualizadoMs > REFRESCO_MS

    private fun aplanar(letras: List<EstadoLetra>): List<Portada> = letras.flatMap { it.portadas }.distinctBy { it.clave }.sortedBy { it.titulo.lowercase() }

    fun buscarPorClave(clave: String): Portada? = synchronized(bloqueo) { estado?.flatMap { it.portadas }?.firstOrNull { it.clave == clave } }

    /** Olvida la copia (memoria y archivo): la siguiente carga lee todo de nuevo. */
    fun invalidar() {
        synchronized(bloqueo) {
            estado = null
            actualizadoMs = 0L
        }
        try {
            archivoCopia?.delete()
        } catch (t: Throwable) {
        }
    }

    // ---------- copia en disco ----------

    private fun aJson(p: Portada): JSONObject = JSONObject()
        .put("t", p.titulo).put("ti", p.tipo).put("es", p.estado).put("ep", p.episodios)
        .put("fa", p.fansub).put("de", p.descripcion).put("en", p.enlaceLetra)
        .put("id", p.mensajeId).put("ch", p.chatId)

    private fun deJson(o: JSONObject): Portada = Portada(
        o.getString("t"), o.getString("ti"), o.getString("es"), o.getInt("ep"),
        o.getString("fa"), o.getString("de"), o.getString("en"), o.getLong("id"), o.getLong("ch"),
    )

    private fun guardar() {
        val f = archivoCopia ?: return
        val letras = synchronized(bloqueo) { estado } ?: return
        try {
            val arr = JSONArray()
            for (l in letras) {
                val ps = JSONArray()
                l.portadas.forEach { ps.put(aJson(it)) }
                arr.put(JSONObject().put("e", l.enlace).put("u", l.ultimoId).put("p", ps))
            }
            val raiz = JSONObject().put("enlace", enlaceDelEstado).put("ms", actualizadoMs).put("letras", arr)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(raiz.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(raiz.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            TdSession.evento("no se pudo guardar la copia del catalogo: ${t.message}")
        }
    }

    /** Llamar con el bloqueo tomado. */
    private fun cargarDeDisco(enlaceCatalogo: String) {
        val f = archivoCopia ?: return
        if (!f.exists()) return
        try {
            val raiz = JSONObject(f.readText())
            if (raiz.optString("enlace") != enlaceCatalogo.trim()) return
            val arr = raiz.getJSONArray("letras")
            estado = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("p")
                EstadoLetra(o.getString("e"), o.getLong("u"), (0 until ps.length()).map { j -> deJson(ps.getJSONObject(j)) })
            }
            enlaceDelEstado = enlaceCatalogo.trim()
            actualizadoMs = raiz.optLong("ms", 0L)
        } catch (t: Throwable) {
            TdSession.evento("la copia del catalogo no se pudo leer: ${t.message}")
        }
    }

    fun normalizar(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    private fun temaDe(info: TdApi.MessageLinkInfo): TdApi.MessageTopic? = try {
        info.javaClass.getField("topicId").get(info) as? TdApi.MessageTopic
    } catch (t: Throwable) {
        null
    }

    private fun textoDe(m: TdApi.Message): TdApi.FormattedText? = when (val c = m.content) {
        is TdApi.MessageText -> c.text
        is TdApi.MessagePhoto -> c.caption
        else -> null
    }

    /** Lee todos los mensajes de un tema (los mas recientes primero), con un tope de paginas. */
    private fun leerTodos(
        chatId: Long,
        tema: TdApi.MessageTopic?,
        filtro: TdApi.SearchMessagesFilter?,
        maxPaginas: Int,
    ): List<TdApi.Message> {
        val res = mutableListOf<TdApi.Message>()
        var desde = 0L
        for (pagina in 0 until maxPaginas) {
            val r = TdSession.call(TdApi.SearchChatMessages(chatId, tema, "", null, desde, 0, 100, filtro))
            if (r.messages.isEmpty()) break
            res.addAll(r.messages)
            desde = r.nextFromMessageId
            if (desde == 0L) break
        }
        return res
    }

    // ---------- ficha ----------

    /** Primera linea = titulo; despues "Clave: valor". Sin "Episodios:" no se considera portada. */
    fun parseFicha(texto: String, enlaceLetra: String, mensajeId: Long, chatId: Long = 0L): Portada? {
        val lineas = texto.lines()
        val primera = lineas.indexOfFirst { it.isNotBlank() }
        if (primera < 0) return null
        val titulo = lineas[primera].trim()
        var tipo = "Anime"
        var estado = ""
        var episodios = 0
        var fansub = ""
        var descripcion = ""
        var tieneEpisodios = false
        var i = primera + 1
        while (i < lineas.size) {
            val linea = lineas[i]
            val idx = linea.indexOf(':')
            if (idx > 0) {
                val clave = normalizar(linea.substring(0, idx))
                val valor = linea.substring(idx + 1).trim()
                when (clave) {
                    "tipo" -> tipo = valor.ifBlank { "Anime" }
                    "estado" -> estado = valor
                    "episodios" -> {
                        episodios = valor.filter { it.isDigit() }.toIntOrNull() ?: 0
                        tieneEpisodios = true
                    }
                    "fansub" -> fansub = valor
                    "descripcion", "sinopsis" -> {
                        descripcion = (listOf(valor) + lineas.drop(i + 1)).joinToString("\n").trim()
                        break
                    }
                }
            }
            i++
        }
        if (!tieneEpisodios) return null
        return Portada(titulo, tipo, estado, episodios, fansub, descripcion, enlaceLetra, mensajeId, chatId)
    }

    // ---------- catalogo ----------

    /** Enlaces (a los temas de cada letra) que aparecen en los mensajes del tema "Catalogo". */
    fun enlacesDeLetras(enlaceCatalogo: String): List<String> {
        val info = TdSession.call(TdApi.GetMessageLinkInfo(enlaceCatalogo.trim()))
        val msg = info.message ?: error("El enlace del catalogo no apunta a un mensaje accesible")
        val tema = temaDe(info)
        val mensajes = leerTodos(msg.chatId, tema, null, 10)
        val enlaces = LinkedHashSet<String>()
        for (m in mensajes.sortedBy { it.id }) {
            val ft = textoDe(m) ?: continue
            for (e in ft.entities) {
                val tipo = e.type
                if (tipo is TdApi.TextEntityTypeTextUrl && regexUrl.containsMatchIn(tipo.url)) enlaces.add(tipo.url)
            }
            regexUrl.findAll(ft.text).forEach { enlaces.add(it.value.trimEnd('.', ',', ')')) }
        }
        return enlaces.toList()
    }

    @Volatile private var filtroVerificado = false

    private fun buscarFotos(chatId: Long, tema: TdApi.MessageTopic): List<TdApi.Message> {
        if (filtroFotoFunciona) {
            try {
                val fotos = leerTodos(chatId, tema, TdApi.SearchMessagesFilterPhoto(), 20)
                if (fotos.isNotEmpty()) {
                    filtroVerificado = true
                    return fotos
                }
                // Un tema sin fotos es normal: solo se sospecha del filtro mientras no se haya visto funcionar
                if (filtroVerificado) return emptyList()
            } catch (t: Throwable) {
                filtroFotoFunciona = false
                TdSession.evento("el filtro de fotos fallo: ${t.message}")
            }
        }
        return leerTodos(chatId, tema, null, 60).filter { it.content is TdApi.MessagePhoto }
    }

    private class LetraLeida(val estado: EstadoLetra?, val linea: String)

    private fun <T> enParalelo(tareas: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(8)
        return try {
            pool.invokeAll(tareas.map { t -> Callable { t() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Chat y tema a los que apunta el enlace de una letra. */
    private fun ubicar(enlace: String): Pair<Long, TdApi.MessageTopic> {
        val info = TdSession.call(TdApi.GetMessageLinkInfo(enlace.trim()))
        val msg = info.message ?: error("el enlace no apunta a un mensaje accesible")
        val tema = temaDe(info) ?: error("el enlace no pertenece a un tema")
        return Pair(msg.chatId, tema)
    }

    private fun portadasDe(fotos: List<TdApi.Message>, enlace: String, chatId: Long): List<Portada> = fotos.mapNotNull { m ->
        val c = m.content as? TdApi.MessagePhoto ?: return@mapNotNull null
        parseFicha(c.caption.text, enlace.trim(), m.id, chatId)
    }

    /** Lee un tema de letra completo. */
    private fun leerLetraCompleta(enlace: String): LetraLeida {
        val t1 = System.currentTimeMillis()
        return try {
            val (chatId, tema) = ubicar(enlace)
            val fotos = buscarFotos(chatId, tema)
            val portadas = portadasDe(fotos, enlace, chatId)
            val ultimo = fotos.maxOfOrNull { it.id } ?: 0L
            LetraLeida(
                EstadoLetra(enlace.trim(), ultimo, portadas),
                "- $enlace -> ${portadas.size} portadas (${System.currentTimeMillis() - t1} ms)",
            )
        } catch (t: Throwable) {
            LetraLeida(null, "- $enlace -> ERROR: ${t.message}")
        }
    }

    /** Lee solo las fotos mas nuevas que lo ultimo visto. Si falla, conserva lo que ya habia. */
    private fun leerNuevas(enlace: String, previo: EstadoLetra): LetraLeida {
        val t1 = System.currentTimeMillis()
        return try {
            val (chatId, tema) = ubicar(enlace)
            val nuevas = mutableListOf<TdApi.Message>()
            var mayor = previo.ultimoId
            var desde = 0L
            for (pagina in 0 until 10) {
                val filtro = if (filtroFotoFunciona) TdApi.SearchMessagesFilterPhoto() else null
                val r = TdSession.call(TdApi.SearchChatMessages(chatId, tema, "", null, desde, 0, 100, filtro))
                if (r.messages.isEmpty()) break
                var terminado = false
                for (m in r.messages) {
                    if (m.id <= previo.ultimoId) {
                        terminado = true
                        break
                    }
                    if (m.id > mayor) mayor = m.id
                    if (m.content is TdApi.MessagePhoto) nuevas.add(m)
                }
                desde = r.nextFromMessageId
                if (terminado || desde == 0L) break
            }
            val conocidas = previo.portadas.map { it.mensajeId }.toSet()
            val agregadas = portadasDe(nuevas.filter { it.id !in conocidas }, enlace, chatId)
            LetraLeida(
                EstadoLetra(previo.enlace, mayor, previo.portadas + agregadas),
                "- $enlace -> ${agregadas.size} nuevas (${System.currentTimeMillis() - t1} ms)",
            )
        } catch (t: Throwable) {
            LetraLeida(previo, "- $enlace -> ERROR (se conserva lo anterior): ${t.message}")
        }
    }

    /** Copia guardada del catalogo (memoria o archivo), sin pedir nada a Telegram. */
    fun copia(enlaceCatalogo: String): List<Portada>? = synchronized(bloqueo) {
        if (estado == null) cargarDeDisco(enlaceCatalogo)
        val e = estado
        if (e == null || enlaceDelEstado != enlaceCatalogo.trim()) null else aplanar(e).takeIf { it.isNotEmpty() }
    }

    /** Lee todo el catalogo desde cero (temas de letra en paralelo) y lo guarda. */
    fun cargarTodo(enlaceCatalogo: String): List<Portada> {
        val t0 = System.currentTimeMillis()
        val letras = enlacesDeLetras(enlaceCatalogo)
        val resultados = enParalelo(letras.map { enlace -> { leerLetraCompleta(enlace) } })
        val nuevo = resultados.mapNotNull { it.estado }
        synchronized(bloqueo) {
            estado = nuevo
            enlaceDelEstado = enlaceCatalogo.trim()
            actualizadoMs = System.currentTimeMillis()
        }
        guardar()
        val lista = aplanar(nuevo)
        val detalle = StringBuilder("Enlaces de letras encontrados en el catalogo: ${letras.size}\n")
        resultados.forEach { detalle.append(it.linea).append("\n") }
        ultimoResumen = "Catalogo completo: ${lista.size} animes en ${System.currentTimeMillis() - t0} ms\n$detalle"
        return lista
    }

    /** Busca solo lo nuevo en cada tema. Un tema que no estaba guardado se lee completo. */
    fun actualizar(enlaceCatalogo: String): String {
        if (!refrescando.compareAndSet(false, true)) return "Ya hay una actualizacion en curso"
        ultimosNuevos = 0
        try {
            val t0 = System.currentTimeMillis()
            val letras = enlacesDeLetras(enlaceCatalogo)
            val previos = synchronized(bloqueo) { estado?.associateBy { it.enlace } ?: emptyMap() }
            val resultados = enParalelo(
                letras.map { enlace ->
                    {
                        val previo = previos[enlace.trim()]
                        if (previo == null) leerLetraCompleta(enlace) else leerNuevas(enlace, previo)
                    }
                },
            )
            val nuevo = resultados.mapNotNull { it.estado }
            val antes = previos.values.sumOf { it.portadas.size }
            val despues = nuevo.sumOf { it.portadas.size }
            ultimosNuevos = (despues - antes).coerceAtLeast(0)
            synchronized(bloqueo) {
                estado = nuevo
                enlaceDelEstado = enlaceCatalogo.trim()
                actualizadoMs = System.currentTimeMillis()
            }
            guardar()
            val detalle = StringBuilder()
            resultados.forEach { detalle.append(it.linea).append("\n") }
            val resumen = "Actualizacion: ${despues - antes} animes nuevos, ${aplanar(nuevo).size} en total " +
                "(${System.currentTimeMillis() - t0} ms)"
            ultimoResumen = "$resumen\n$detalle"
            return resumen
        } finally {
            refrescando.set(false)
        }
    }

    // ---------- episodios ----------

    private fun comoVideo(m: TdApi.Message): VideoMensaje? = when (val c = m.content) {
        is TdApi.MessageVideo -> VideoMensaje(
            m.chatId,
            m.id,
            c.video.fileName.orEmpty(),
            c.video.video.size.toLong(),
            m.date.toLong(),
        )
        is TdApi.MessageDocument -> {
            val nombre = c.document.fileName.orEmpty()
            val esVideo = c.document.mimeType.orEmpty().startsWith("video/") ||
                extensionesVideo.any { nombre.lowercase().endsWith(".$it") }
            if (esVideo) VideoMensaje(m.chatId, m.id, nombre, c.document.document.size.toLong(), m.date.toLong()) else null
        }
        else -> null
    }

    /** Videos que siguen a la portada hasta la siguiente portada (o hasta completar los esperados). */
    private fun recorrer(
        mensajes: Collection<TdApi.Message>,
        mensajeId: Long,
        esperados: Int,
    ): Pair<List<VideoMensaje>, Boolean> {
        val videos = mutableListOf<VideoMensaje>()
        for (m in mensajes) {
            if (m.id <= mensajeId) continue
            val c = m.content
            if (c is TdApi.MessagePhoto && parseFicha(c.caption.text, "", 0) != null) return Pair(videos, true)
            val v = comoVideo(m) ?: continue
            videos.add(v)
            if (esperados > 0 && videos.size >= esperados) return Pair(videos, true)
        }
        return Pair(videos, false)
    }

    fun leerEpisodios(enlaceLetra: String, mensajeId: Long, esperados: Int): EpisodiosLeidos {
        val info = TdSession.call(TdApi.GetMessageLinkInfo(enlaceLetra.trim()))
        val msg = info.message ?: error("El enlace del tema no apunta a un mensaje accesible")
        val tema = temaDe(info) ?: error("El enlace no pertenece a un tema")
        val encontrados = java.util.TreeMap<Long, TdApi.Message>()
        var desde = mensajeId
        var videos: List<VideoMensaje> = emptyList()
        for (pagina in 0 until 12) {
            val r = TdSession.call(TdApi.SearchChatMessages(msg.chatId, tema, "", null, desde, -49, 50, null))
            val nuevos = r.messages.filter { it.id >= mensajeId && !encontrados.containsKey(it.id) }
            if (nuevos.isEmpty()) break
            nuevos.forEach { encontrados[it.id] = it }
            desde = encontrados.lastKey()
            val (leidos, fin) = recorrer(encontrados.values, mensajeId, esperados)
            videos = leidos
            if (fin) break
        }
        val aviso = if (esperados > 0 && videos.size < esperados) {
            "Faltan ${esperados - videos.size} episodios (hay ${videos.size} de $esperados)"
        } else {
            ""
        }
        ultimoAviso = if (aviso.isEmpty()) "Ultimo anime leido: ${videos.size} episodios" else "Ultimo anime leido: $aviso"
        return EpisodiosLeidos(videos, aviso)
    }
}
