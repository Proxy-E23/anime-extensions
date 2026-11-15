package eu.kanade.tachiyomi.animeextension.all.anigram

import android.util.Log
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.UUID

/**
 * Servidor HTTP local (127.0.0.1) que traduce las peticiones Range del reproductor
 * en descargas por rangos de TDLib. Basado en MegaProxyServer, sin descifrado.
 * Ademas expone /stats con contadores de diagnostico.
 */
class TgProxyServer(puerto: Int = 0) : NanoHTTPD("127.0.0.1", puerto) {

    private val tag = "TgProxyServer"
    private val streams = Collections.synchronizedMap(LinkedHashMap<String, ArchivoTg>())

    val localPort: Int get() = super.getListeningPort()

    init {
        // Vigilante: si no hay conexiones durante un rato, cancela la descarga de TDLib
        Thread {
            while (true) {
                try {
                    Thread.sleep(2000)
                    val sinConexiones = synchronized(TgStats.activas) { TgStats.activas.isEmpty() }
                    if (sinConexiones && !TgStats.cancelada &&
                        System.currentTimeMillis() - TgStats.ultimaActividad > 4000
                    ) {
                        TgStats.cancelada = true
                        TdSession.cancelarDescarga()
                        Log.d(tag, "Sin conexiones: descarga de TDLib cancelada")
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    Log.w(tag, "Vigilante: ${t.message}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun registrar(archivo: ArchivoTg): String {
        val token = UUID.randomUUID().toString()
        streams[token] = archivo
        TgStats.reiniciar()
        TdSession.ultimoFileId = archivo.fileId
        return "http://127.0.0.1:$localPort/stream/$token"
    }

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/stats") return paginaStats()
        if (uri.startsWith("/cover/")) return portada(uri.removePrefix("/cover/"))
        if (!uri.startsWith("/stream/")) return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        val info = streams[uri.removePrefix("/stream/")]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Stream no registrado")

        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Usa GET o HEAD")
        }

        val total = info.size
        val header = session.headers["range"]?.takeIf { it.startsWith("bytes=") && ',' !in it }
        val range = if (header != null) {
            parseRange(header, total) ?: return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "Rango invalido")
                .also { it.addHeader("Content-Range", "bytes */$total") }
        } else {
            0L until total
        }

        val status = if (header != null) Status.PARTIAL_CONTENT else Status.OK
        val length = range.last - range.first + 1
        val mime = info.mime.ifBlank { "video/x-matroska" }

        val response = if (method == Method.HEAD) {
            newFixedLengthResponse(status, mime, ByteArrayInputStream(ByteArray(0)), length)
        } else {
            Log.d(tag, "Sirviendo bytes ${range.first}-${range.last} de ${info.nombre}")
            newFixedLengthResponse(status, mime, TgStream(info.fileId, range.first, range.last), length)
        }
        response.addHeader("Accept-Ranges", "bytes")
        if (header != null) response.addHeader("Content-Range", "bytes ${range.first}-${range.last}/$total")
        return response
    }

    /** Sirve la foto de una portada: /cover/<chatId>/<mensajeId>. */
    private fun portada(ruta: String): Response = try {
        val partes = ruta.split("/")
        val bytes = TdSession.bytesDePortada(partes[0].toLong(), partes[1].toLong())
        newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .also { it.addHeader("Cache-Control", "max-age=86400") }
    } catch (t: Throwable) {
        TdSession.evento("PORTADA error ($ruta): ${t.message}")
        newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "sin portada")
    }

    private fun paginaStats(): Response {
        val id = TdSession.ultimoFileId
        val tdl = if (id >= 0) {
            try {
                TdSession.infoArchivo(id)
            } catch (t: Throwable) {
                "TDLib: error ${t.message}"
            }
        } else {
            "TDLib: aun no hay archivo"
        }
        val ahora = System.currentTimeMillis()
        val activas = synchronized(TgStats.activas) { TgStats.activas.map { it.describir(ahora) } }
        val cerradas = synchronized(TgStats.peticiones) { TgStats.peticiones.toList() }
        val cuerpo = buildString {
            append("<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='3'>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<style>body{font-family:monospace;background:#111;color:#eee;padding:12px}</style></head><body>")
            append("<h3>AniGram stats (puerto $localPort)</h3>")
            append("<p>Servido a mpv: ${TgStats.bytesServidos / 1048576} MB<br>$tdl</p>")
            append("<p>Ultima limpieza: ${TdSession.ultimaLimpieza}</p>")
            append("<h4>Catalogo</h4><pre>${Catalogo.ultimoResumen}</pre>")
            append("<pre>${Catalogo.ultimoAviso}</pre>")
            append("<h4>Eventos (mas reciente arriba)</h4>")
            TdSession.eventosSnapshot().forEach { append("<div>$it</div>") }
            append("<h4>Archivos en disco</h4>")
            append("<pre>${TdSession.listarArchivos()}</pre>")
            append("<h4>Conexiones activas (${activas.size})</h4>")
            activas.forEach { append("<div>$it</div>") }
            append("<h4>Cerradas (${cerradas.size})</h4>")
            cerradas.reversed().forEach { append("<div>$it</div>") }
            append("</body></html>")
        }
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", cuerpo)
    }

    private fun parseRange(header: String, total: Long): LongRange? {
        val parts = header.removePrefix("bytes=").split("-")
        if (parts.size != 2) return null
        if (parts[0].isEmpty()) {
            val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (total - suffix).coerceAtLeast(0)..(total - 1)
        }
        val start = parts[0].toLongOrNull()?.takeIf { it in 0 until total } ?: return null
        val end = if (parts[1].isEmpty()) total - 1 else parts[1].toLongOrNull() ?: return null
        if (end < start) return null
        return start..end.coerceAtMost(total - 1)
    }
}

class ConexionActiva(val inicioMb: Long, val abierto: Long) {
    @Volatile var entregado = 0L

    @Volatile var abortar = false

    fun describir(ahora: Long) = "desde $inicioMb MB, entregado ${entregado / 1048576} MB, abierta hace ${(ahora - abierto) / 1000}s"
}

/** Contadores de diagnostico. */
object TgStats {
    val activas = mutableListOf<ConexionActiva>()
    val peticiones: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    @Volatile var bytesServidos = 0L

    @Volatile var t0 = System.currentTimeMillis()

    @Volatile var ultimaActividad = System.currentTimeMillis()

    @Volatile var cancelada = false

    fun reiniciar() {
        bytesServidos = 0L
        t0 = System.currentTimeMillis()
        peticiones.clear()
        cancelada = false
    }

    fun agregar(c: ConexionActiva) {
        synchronized(activas) { activas.add(c) }
        ultimaActividad = System.currentTimeMillis()
        cancelada = false
    }

    /** Marca todas las conexiones activas para que dejen de leer (cambio de episodio). */
    fun abortarTodas() {
        synchronized(activas) { activas.forEach { it.abortar = true } }
    }

    fun quitar(c: ConexionActiva) {
        synchronized(activas) { activas.remove(c) }
        ultimaActividad = System.currentTimeMillis()
    }

    fun registrar(texto: String) {
        peticiones.add(texto)
        while (peticiones.size > 30) peticiones.removeAt(0)
    }
}

/** Lee el archivo de Telegram por trozos de 1 MiB solo cuando el reproductor los consume. */
private class TgStream(private val fileId: Int, private var pos: Long, private val end: Long) : InputStream() {
    private var buf = ByteArray(0)
    private var idx = 0
    private val inicio = pos
    private var entregado = 0L
    private val abierto = System.currentTimeMillis()
    private var cerrado = false
    private val conexion = ConexionActiva(inicio / 1048576, abierto).also { TgStats.agregar(it) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (cerrado) throw IOException("Stream cerrado")
        if (conexion.abortar) throw IOException("Stream reemplazado por otro episodio")
        if (idx >= buf.size) {
            if (pos > end) return -1
            val pedir = minOf(TdSession.tamanoChunk(), end - pos + 1)
            buf = try {
                TdSession.leerRango(fileId, pos, pedir)
            } catch (e: Exception) {
                throw IOException("Fallo leyendo de Telegram: ${e.message}", e)
            }
            if (buf.isEmpty()) throw IOException("Telegram devolvio 0 bytes en offset $pos")
            idx = 0
            pos += buf.size
            TgStats.bytesServidos += buf.size
            TgStats.ultimaActividad = System.currentTimeMillis()
        }
        val n = minOf(len, buf.size - idx)
        System.arraycopy(buf, idx, b, off, n)
        idx += n
        entregado += n
        conexion.entregado = entregado
        return n
    }

    override fun close() {
        if (!cerrado) {
            cerrado = true
            TgStats.quitar(conexion)
            val ahora = System.currentTimeMillis()
            TgStats.registrar(
                "t=${(abierto - TgStats.t0) / 1000}s desde ${inicio / 1048576} MB, " +
                    "entrego ${entregado / 1048576} MB, duro ${(ahora - abierto) / 1000}s",
            )
        }
        super.close()
    }
}
