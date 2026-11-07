package aniyomi.lib.megaextractor

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Servidor HTTP local (127.0.0.1) que actúa de puente entre el reproductor y
 * los servidores de descarga de MEGA. MEGA nunca entrega contenido en claro:
 * el proxy pide los bytes cifrados, los descifra en AES-CTR respetando el
 * offset exacto pedido, y responde como un servidor HTTP normal con soporte
 * de `Range`, que es indispensable para que el reproductor pueda hacer seek.
 *
 * Solo escucha en loopback (127.0.0.1): nadie más en la red local puede
 * conectarse. Cada stream se registra con [registerStream], que devuelve un
 * token aleatorio (UUID) imposible de adivinar; la URL local se arma con
 * [urlFor]. Puede servir múltiples streams a la vez.
 *
 * No se auto-apaga por inactividad: el costo de dejarlo corriendo es
 * mínimo, y cualquier heurística basada en tráfico podría cortar una
 * reproducción real en curso. Se detiene solo con [MegaExtractor.shutdown].
 */
internal class MegaProxyServer(
    private val client: OkHttpClient,
    port: Int = 0,
) : NanoHTTPD("127.0.0.1", port) {

    private val tag = "MegaProxyServer"

    private companion object {
        // Streams recientes que se conservan (LRU por acceso).
        const val MAX_STREAMS = 64
    }

    data class StreamInfo(
        val downloadUrl: String,
        val aesKey: ByteArray,
        val nonce: ByteArray,
        val totalSize: Long,
        val mimeType: String = "video/mp4",
    )

    // Streams registrados en orden de ACCESO (LRU), no de registro: cada Range
    // que pide el reproductor deja ese stream como el más reciente, así el que
    // se está reproduciendo nunca es el que se descarta al registrar otros.
    private val streams = Collections.synchronizedMap(
        object : LinkedHashMap<String, StreamInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamInfo>): Boolean = size > MAX_STREAMS
        },
    )

    val localPort: Int
        get() = super.getListeningPort()

    /** Registra el stream y devuelve su token aleatorio (úsalo con [urlFor]). */
    fun registerStream(info: StreamInfo): String {
        val token = UUID.randomUUID().toString()
        streams[token] = info
        return token
    }

    fun unregisterStream(token: String) {
        streams.remove(token)
    }

    fun urlFor(token: String): String = "http://127.0.0.1:$localPort/stream/$token"

    override fun start() {
        try {
            super.start()
            Log.d(tag, "MegaProxyServer escuchando en puerto $localPort")
        } catch (e: Exception) {
            Log.e(tag, "No se pudo iniciar MegaProxyServer: ${e.message}")
            throw e
        }
    }

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/stream/")) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        }

        val streamId = uri.removePrefix("/stream/")
        val info = streams[streamId]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Stream no registrado: $streamId")

        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Usa GET o HEAD")
        }

        // Solo se respeta un único rango "bytes=". Cualquier otra cosa (otra
        // unidad, o varios rangos separados por coma) se ignora y se sirve el
        // archivo completo con 200, que es lo que permite el RFC 9110.
        val rangeHeader = session.headers["range"]?.takeIf { it.startsWith("bytes=") && ',' !in it }
        val isRangeRequest = rangeHeader != null
        val range = if (rangeHeader != null) {
            parseRange(rangeHeader, info.totalSize) ?: run {
                Log.w(tag, "Rango inválido pedido para stream $streamId: Range=$rangeHeader (totalSize=${info.totalSize})")
                val invalidRangeResponse = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "Rango inválido")
                invalidRangeResponse.addHeader("Content-Range", "bytes */${info.totalSize}")
                return invalidRangeResponse
            }
        } else {
            0L until info.totalSize
        }

        val status = if (isRangeRequest) Status.PARTIAL_CONTENT else Status.OK
        val length = range.last - range.first + 1

        val response = if (method == Method.HEAD) {
            // HEAD: solo cabeceras, sin pedir nada a MEGA.
            newFixedLengthResponse(status, info.mimeType, ByteArrayInputStream(ByteArray(0)), length)
        } else {
            try {
                serveRange(info, range.first, range.last, status)
            } catch (e: Exception) {
                Log.e(tag, "Error sirviendo stream $streamId: ${e.message}", e)
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        // Si serveRange devolvió un error (503, etc.), va tal cual, sin cabeceras de rango.
        if (response.status != status) return response
        response.addHeader("Accept-Ranges", "bytes")
        if (isRangeRequest) {
            response.addHeader("Content-Range", "bytes ${range.first}-${range.last}/${info.totalSize}")
        }
        return response
    }

    /**
     * Interpreta un único `Range: bytes=...` ya validado como tal.
     *
     * Soporta `bytes=a-b`, `bytes=a-` y el rango de sufijo `bytes=-N` (los
     * últimos N bytes). Este último lo piden algunos reproductores para leer
     * el `moov` de MP4 que está al final del archivo; antes se interpretaba
     * como "desde 0 hasta N", devolviendo bytes equivocados.
     *
     * @return el rango normalizado (inclusive), o `null` si es inválido o
     *   está fuera del archivo (se responde 416).
     */
    private fun parseRange(header: String, totalSize: Long): LongRange? {
        val parts = header.removePrefix("bytes=").split("-")
        if (parts.size != 2) return null

        if (parts[0].isEmpty()) {
            val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (totalSize - suffix).coerceAtLeast(0)..(totalSize - 1)
        }

        val start = parts[0].toLongOrNull()?.takeIf { it in 0 until totalSize } ?: return null
        val end = if (parts[1].isEmpty()) totalSize - 1 else parts[1].toLongOrNull() ?: return null
        if (end < start) return null
        return start..end.coerceAtMost(totalSize - 1)
    }

    // Cliente con timeouts propios para pedir el rango a MEGA.
    //
    // connectTimeout: falla rápido si ni siquiera se puede conectar.
    // readTimeout: tiempo máximo SIN recibir ningún byte nuevo -- 30s da
    // margen para pausas normales de una transferencia larga (más que los
    // 15s originales, que cortaban conexiones sanas simplemente porque el
    // ancho de banda de MEGA es variable, sobre todo en cuentas free).
    // callTimeout: DELIBERADAMENTE SIN LÍMITE. Un callTimeout fijo limita
    // la duración de la petición COMPLETA sin importar cuánto pese el
    // rango pedido -- con un límite de 20s, cualquier rango grande que
    // tarde más en bajar (algo esperable al pedir el buffer inicial de un
    // video, o con una cuenta con bajo ancho de banda) se cortaba sí o sí,
    // sin que hubiera nada roto en la conexión. Eso era la causa real de
    // "reproduce 1 segundo, se corta, reintenta" en links públicos y de
    // que el contenido propio nunca llegara a arrancar.
    private val rangeClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * Sirve el rango [start, end] (inclusive) descifrado.
     *
     * AES-CTR permite descifrar cualquier bloque de 16 bytes de forma
     * independiente conociendo su índice de bloque, así que no hace falta
     * descifrar el archivo desde el byte 0 para servir un rango arbitrario:
     * basta pedir a MEGA los bytes cifrados alineados al bloque de 16 más
     * cercano por debajo de `start`, descifrar, y recortar el sobrante.
     */
    private fun serveRange(info: StreamInfo, start: Long, end: Long, status: Status): Response {
        val alignedStart = start - (start % 16)
        val blockOffset = alignedStart / 16
        val trimFront = (start - alignedStart).toInt()
        val requestedLength = end - start + 1

        val upstreamRequest = Request.Builder()
            .url(info.downloadUrl)
            .header("Range", "bytes=$alignedStart-$end")
            .build()

        val upstreamResponse = try {
            rangeClient.newCall(upstreamRequest).execute()
        } catch (e: java.io.IOException) {
            // Timeout o fallo de red hablando con el servidor de MEGA (no
            // con nuestro propio proxy).
            Log.e(tag, "Timeout/fallo de red pidiendo bytes=$alignedStart-$end a MEGA: ${e.message}", e)
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No se pudo contactar al servidor de MEGA: ${e.message}",
            )
        }
        if (!upstreamResponse.isSuccessful) {
            val upstreamCode = upstreamResponse.code
            val upstreamBody = runCatching { upstreamResponse.body.string() }.getOrNull()
            upstreamResponse.close()
            if (upstreamCode == 509) {
                Log.e(
                    tag,
                    "MEGA cortó la descarga con 509 (cuota de transferencia agotada) a mitad de " +
                        "bytes=$alignedStart-$end de ${info.downloadUrl.substringBefore('?')}. " +
                        "Esto deja el archivo incompleto para el reproductor -- si el video se queda " +
                        "cargando sin avanzar o reporta 'end of file'/'seconds missing from the " +
                        "timeline', esta es la causa más probable.",
                )
                return newFixedLengthResponse(
                    Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Se agotó la cuota de transferencia de MEGA para esta cuenta/IP por hoy.",
                )
            }
            Log.e(
                tag,
                "MEGA respondió $upstreamCode al pedir bytes=$alignedStart-$end de " +
                    "${info.downloadUrl.substringBefore('?')}. Body: $upstreamBody",
            )
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "MEGA respondió $upstreamCode al pedir el rango",
            )
        }

        // Validación temprana: MEGA debe devolver exactamente los bytes alineados
        // que pedimos. Si el largo no coincide (p. ej. ignoró el Range y mandó el
        // archivo entero), descifrar daría basura o dejaría el Content-Length que
        // prometemos al reproductor a medias; mejor fallar acá con un error claro.
        val expectedUpstreamLength = end - alignedStart + 1
        val actualUpstreamLength = upstreamResponse.body.contentLength()
        if (actualUpstreamLength >= 0 && actualUpstreamLength != expectedUpstreamLength) {
            Log.e(
                tag,
                "MEGA devolvió $actualUpstreamLength bytes para bytes=$alignedStart-$end " +
                    "(se esperaban $expectedUpstreamLength). Code=${upstreamResponse.code}",
            )
            upstreamResponse.close()
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "MEGA devolvió un tamaño de rango inesperado",
            )
        }

        val cipherStream = upstreamResponse.body.byteStream()

        // Descifrado a demanda: NanoHTTPD lee de este stream y cada lectura
        // consume y descifra lo necesario del stream de MEGA, sin hilos ni pipes.
        // Como conocemos de antemano cuántos bytes en claro vamos a emitir
        // (requestedLength), NanoHTTPD escribe un Content-Length real en vez
        // de Transfer-Encoding: chunked. Mezclar ambos headers violaría el
        // spec HTTP/1.1 y podía confundir el parseo de ExoPlayer/OkHttp.
        // NanoHTTPD cierra el stream al terminar o si el cliente se desconecta,
        // y eso cierra también la respuesta de MEGA.
        val body = MegaDecryptingInputStream(
            source = cipherStream,
            owner = upstreamResponse,
            aesKey = info.aesKey,
            nonce = info.nonce,
            startBlockOffset = blockOffset,
            trimFront = trimFront,
            totalPlainBytes = requestedLength,
        )
        return newFixedLengthResponse(status, info.mimeType, body, requestedLength)
    }
}
