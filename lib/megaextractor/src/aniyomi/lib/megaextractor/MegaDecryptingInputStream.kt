package aniyomi.lib.megaextractor

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * [InputStream] que descifra AES-CTR de MEGA a demanda: cada `read` consume
 * lo que haga falta del stream cifrado, lo descifra y entrega los bytes en
 * claro. No usa hilos ni pipes: el descifrado ocurre en el mismo hilo que
 * lee (el de NanoHTTPD), y cerrar este stream cierra la conexión con MEGA,
 * así que un corte del reproductor libera todo de inmediato.
 *
 * @param source bytes cifrados, empezando en un límite de bloque de 16.
 * @param owner recurso que se cierra junto con este stream (la respuesta de OkHttp).
 * @param startBlockOffset índice de bloque AES del primer byte de [source].
 * @param trimFront bytes en claro a descartar al inicio (0..15), por la alineación.
 * @param totalPlainBytes bytes en claro a entregar en total; después devuelve EOF.
 */
internal class MegaDecryptingInputStream(
    private val source: InputStream,
    private val owner: Closeable,
    private val aesKey: ByteArray,
    private val nonce: ByteArray,
    startBlockOffset: Long,
    trimFront: Int,
    private val totalPlainBytes: Long,
) : InputStream() {

    // Lotes múltiplos de 16 (4096 bloques = 64 KiB): el contador de cada lote
    // se calcula con el índice de bloque, igual que antes del cambio a pull.
    private val cipherBuffer = ByteArray(CHUNK_BYTES)
    private val singleByte = ByteArray(1)

    private var plain = ByteArray(0)
    private var plainPos = 0
    private var blockOffset = startBlockOffset
    private var pendingTrim = trimFront
    private var emitted = 0L
    private var closed = false

    override fun read(): Int = if (read(singleByte, 0, 1) == -1) -1 else singleByte[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream cerrado")
        if (len == 0) return 0
        if (emitted >= totalPlainBytes) return -1

        // El ciclo cubre el caso de un lote entero descartado por el recorte inicial.
        while (plainPos >= plain.size) refill()

        val n = minOf(len.toLong(), (plain.size - plainPos).toLong(), totalPlainBytes - emitted).toInt()
        System.arraycopy(plain, plainPos, b, off, n)
        plainPos += n
        emitted += n
        return n
    }

    override fun available(): Int = if (closed) 0 else minOf((plain.size - plainPos).toLong(), totalPlainBytes - emitted).toInt()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
        runCatching { owner.close() }
    }

    private fun refill() {
        val read = readFully(source, cipherBuffer)
        if (read <= 0) {
            // MEGA cerró la conexión antes de entregar los bytes prometidos en
            // el Content-Length. Se lanza para que NanoHTTPD aborte la
            // respuesta con un error visible, en vez de cerrarla "bien" y
            // dejar al reproductor con un archivo truncado sin explicación.
            throw IOException(
                "MEGA cortó el stream a mitad de la transferencia: se llevaban " +
                    "$emitted de $totalPlainBytes bytes en claro esperados. " +
                    "Causa más probable: cuota de transferencia agotada o corte de red.",
            )
        }

        // El último tramo del archivo puede no ser múltiplo de 16 (MEGA no
        // rellena); AES-CTR no necesita padding, así que se procesa igual.
        val decrypted = MegaCrypto.aesCtrDecrypt(aesKey, nonce, blockOffset, cipherBuffer.copyOf(read))
        blockOffset += (read + 15) / 16

        var from = 0
        if (pendingTrim > 0) {
            from = pendingTrim.coerceAtMost(decrypted.size)
            pendingTrim -= from
        }
        plain = decrypted
        plainPos = from
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private companion object {
        const val CHUNK_BYTES = 4096 * 16
    }
}
