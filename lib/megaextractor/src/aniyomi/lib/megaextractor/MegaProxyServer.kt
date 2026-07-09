package aniyomi.lib.megaextractor

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servidor HTTP local (127.0.0.1) que actúa de puente entre ExoPlayer y los
 * servidores de descarga de MEGA. MEGA nunca entrega contenido en claro: el
 * proxy pide los bytes cifrados, los descifra en AES-CTR respetando el
 * offset exacto pedido, y responde como un servidor HTTP normal con soporte
 * de `Range`, que es indispensable para que el reproductor pueda hacer seek.
 *
 * Una instancia de este servidor puede servir múltiples streams a la vez;
 * cada uno se registra con [registerStream] y obtiene una URL local propia.
 */
internal class MegaProxyServer(
    private val client: OkHttpClient,
    port: Int = 0,
) : NanoHTTPD(port) {

    private val tag = "MegaProxyServer"

    data class StreamInfo(
        val downloadUrl: String,
        val aesKey: ByteArray,
        val nonce: ByteArray,
        val totalSize: Long,
        val mimeType: String = "video/mp4",
    )

    private val streams = ConcurrentHashMap<String, StreamInfo>()
    private val ioExecutor = Executors.newCachedThreadPool()

    val localPort: Int
        get() = super.getListeningPort()

    fun registerStream(streamId: String, info: StreamInfo) {
        streams[streamId] = info
    }

    fun unregisterStream(streamId: String) {
        streams.remove(streamId)
    }

    fun urlFor(streamId: String): String = "http://127.0.0.1:$localPort/stream/$streamId"

    override fun start() {
        try {
            super.start()
            Log.d(tag, "MegaProxyServer escuchando en puerto $localPort")
        } catch (e: Exception) {
            Log.e(tag, "No se pudo iniciar MegaProxyServer: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        ioExecutor.shutdownNow()
    }

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/stream/")) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        }

        val streamId = uri.removePrefix("/stream/")
        val info = streams[streamId]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Stream no registrado: $streamId")

        val rangeHeader = session.headers["range"]
        val (start, end) = parseRange(rangeHeader, info.totalSize)

        return try {
            serveRange(info, start, end)
        } catch (e: Exception) {
            Log.e(tag, "Error sirviendo stream $streamId: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    /**
     * Calcula el rango real solicitado. Si no hay header Range, se sirve
     * desde 0 hasta el final (comportamiento de descarga completa, pero
     * ExoPlayer casi siempre manda Range).
     */
    private fun parseRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long> {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return 0L to (totalSize - 1)
        }
        val spec = rangeHeader.removePrefix("bytes=")
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: (totalSize - 1)
        return start to end.coerceAtMost(totalSize - 1)
    }

    /**
     * Sirve el rango [start, end] (inclusive) descifrado.
     *
     * AES-CTR permite descifrar cualquier bloque de 16 bytes de forma
     * independiente conociendo su índice de bloque, así que no hace falta
     * descifrar el archivo desde el byte 0 para servir un rango arbitrario:
     * basta pedir a MEGA los bytes cifrados alineados al bloque de 16 más
     * cercano por debajo de `start`, descifrar, y recortar el sobrante.
     */
    private fun serveRange(info: StreamInfo, start: Long, end: Long): Response {
        val alignedStart = start - (start % 16)
        val blockOffset = alignedStart / 16
        val trimFront = (start - alignedStart).toInt()
        val requestedLength = end - start + 1

        val upstreamRequest = Request.Builder()
            .url(info.downloadUrl)
            .header("Range", "bytes=$alignedStart-$end")
            .build()

        val upstreamResponse = client.newCall(upstreamRequest).execute()
        if (!upstreamResponse.isSuccessful) {
            val upstreamCode = upstreamResponse.code
            upstreamResponse.close()
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "MEGA respondió $upstreamCode al pedir el rango",
            )
        }

        val cipherStream = upstreamResponse.body.byteStream()

        // Pipe: descifrado en un hilo productor, NanoHTTPD consume del lado lector.
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)

        ioExecutor.execute {
            try {
                decryptStreamInto(
                    cipherStream = cipherStream,
                    output = pipedOut,
                    aesKey = info.aesKey,
                    nonce = info.nonce,
                    startBlockOffset = blockOffset,
                    trimFront = trimFront,
                    totalPlainBytesToEmit = requestedLength,
                )
            } catch (e: Exception) {
                Log.e(tag, "Error descifrando stream: ${e.message}", e)
            } finally {
                runCatching { pipedOut.close() }
                runCatching { upstreamResponse.close() }
            }
        }

        // Usamos newFixedLengthResponse con un InputStream (no un byte[]):
        // como conocemos de antemano cuántos bytes en claro vamos a emitir
        // (requestedLength), NanoHTTPD escribe un Content-Length real en vez
        // de Transfer-Encoding: chunked. Mezclar ambos headers violaría el
        // spec HTTP/1.1 y podía confundir el parseo de ExoPlayer/OkHttp.
        val response = newFixedLengthResponse(Status.PARTIAL_CONTENT, info.mimeType, pipedIn, requestedLength)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$end/${info.totalSize}")
        return response
    }

    /**
     * Lee el stream cifrado en bloques de 16 bytes (tamaño de bloque AES),
     * descifra cada bloque con el contador correcto, descarta el recorte
     * inicial ([trimFront]) y escribe exactamente [totalPlainBytesToEmit]
     * bytes en claro al output.
     */
    private fun decryptStreamInto(
        cipherStream: java.io.InputStream,
        output: java.io.OutputStream,
        aesKey: ByteArray,
        nonce: ByteArray,
        startBlockOffset: Long,
        trimFront: Int,
        totalPlainBytesToEmit: Long,
    ) {
        val chunkBlocks = 4096 // 4096 * 16 bytes = 64 KiB por lote, buen balance CPU/IO
        val chunkBytes = chunkBlocks * 16
        val buffer = ByteArray(chunkBytes)

        var blockOffset = startBlockOffset
        var pendingTrim = trimFront
        var emitted = 0L

        while (emitted < totalPlainBytesToEmit) {
            val read = readFully(cipherStream, buffer)
            if (read <= 0) break

            // Los datos cifrados deben venir en múltiplos de 16 salvo el
            // último bloque del archivo, que MEGA no rellena: si el tramo
            // leído no es múltiplo de 16, se procesa igual (el cipher AES-CTR
            // no requiere padding, opera XOR byte a byte con el keystream).
            val plain = MegaCrypto.aesCtrDecrypt(aesKey, nonce, blockOffset, buffer.copyOf(read))

            var from = 0
            if (pendingTrim > 0) {
                val skip = pendingTrim.coerceAtMost(plain.size)
                from = skip
                pendingTrim -= skip
            }

            if (from < plain.size) {
                val remainingToEmit = (totalPlainBytesToEmit - emitted).coerceAtMost((plain.size - from).toLong()).toInt()
                if (remainingToEmit > 0) {
                    output.write(plain, from, remainingToEmit)
                    emitted += remainingToEmit
                }
            }

            blockOffset += (read / 16)
            if (read % 16 != 0) blockOffset += 1
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
