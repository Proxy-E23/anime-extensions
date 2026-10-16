package aniyomi.lib.megaextractor

import android.util.Base64
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidades criptográficas del protocolo MEGA.
 *
 * MEGA cifra absolutamente todo del lado del cliente, incluso para links
 * públicos sin sesión: los nombres, las claves de nodo y el contenido de los
 * archivos. Este objeto centraliza esas operaciones para no repetir la
 * aritmética de bits en cada punto donde se necesita.
 */
internal object MegaCrypto {

    /**
     * MEGA usa una variante de base64 "url-safe" con "-" y "_" en vez de
     * "+"/"/", y sin padding. Hay que normalizarla antes de usar el decoder
     * estándar de Android.
     */
    fun megaBase64Decode(input: String): ByteArray {
        var s = input.replace('-', '+').replace('_', '/')
        val mod = s.length % 4
        if (mod != 0) {
            s += "=".repeat(4 - mod)
        }
        return Base64.decode(s, Base64.DEFAULT)
    }

    /**
     * Contraparte de [megaBase64Decode]: mismo alfabeto url-safe sin
     * padding. Se usa para mandar blobs binarios (el "sid" de una sesión)
     * en la query string de la API.
     */
    fun megaBase64Encode(input: ByteArray): String = Base64.encodeToString(input, Base64.NO_WRAP)
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')

    /**
     * Deriva la AES key real (16 bytes) y el nonce de CTR (8 bytes) a partir
     * de la key de 32 bytes que trae un link público de ARCHIVO STANDALONE
     * (mega.nz/file/handle#key). Solo aplica a este caso: MEGA empaqueta la
     * key de esa forma compacta específicamente para que quepa en la URL.
     *
     *   key[0..15] xor key[16..31] = AES key real
     *   key[16..23] = nonce (8 bytes) para CTR
     *   key[24..31] = meta-mac (verificación de integridad, no usado aquí)
     *
     * NO usar esta función para la key ya descifrada de un nodo dentro de
     * una carpeta listada (ver [fileKeyFromNodeKey] para ese caso) -- son
     * dos representaciones distintas de la key de archivo, y aplicar el XOR
     * a la que no corresponde produce una AES key corrupta sin dar ningún
     * error (silenciosamente descifra basura).
     */
    fun deriveFileKey(fullKey: ByteArray): MegaFileKeyMaterial {
        require(fullKey.size == 32) { "La key de archivo debe tener 32 bytes, tiene ${fullKey.size}" }

        val aesKey = ByteArray(16)
        for (i in 0 until 16) {
            aesKey[i] = (fullKey[i].toInt() xor fullKey[i + 16].toInt()).toByte()
        }
        val nonce = fullKey.copyOfRange(16, 24)
        val metaMac = fullKey.copyOfRange(24, 32)

        return MegaFileKeyMaterial(aesKey = aesKey, nonce = nonce, metaMac = metaMac)
    }

    /**
     * Deriva la AES key real y el nonce de CTR a partir de la key de 32
     * bytes ya descifrada (vía AES-ECB con la key del padre) de un nodo
     * dentro de un árbol de carpeta.
     *
     * CORRECCIÓN (antes decía lo contrario): el XOR de mitades SÍ aplica
     * aquí igual que en [deriveFileKey]. Verificado contra datos reales de
     * la API (carpeta "Ragna Crimson"): sin el XOR, el atributo cifrado
     * descifra a basura para los 12 archivos de la carpeta; con el XOR,
     * los 12 descifran correctamente a "MEGA{...}". La distinción real del
     * protocolo MEGA no es "standalone vs carpeta" sino el tamaño del
     * blob (32 bytes de archivo siempre llevan el XOR; 16 bytes de
     * carpeta nunca lo llevan), y ambas rutas de archivo son de 32 bytes.
     */
    fun fileKeyFromNodeKey(nodeKeyRaw: ByteArray): MegaFileKeyMaterial {
        require(nodeKeyRaw.size == 32) { "La node key de archivo debe tener 32 bytes, tiene ${nodeKeyRaw.size}" }

        val aesKey = ByteArray(16)
        for (i in 0 until 16) {
            aesKey[i] = (nodeKeyRaw[i].toInt() xor nodeKeyRaw[i + 16].toInt()).toByte()
        }
        val nonce = nodeKeyRaw.copyOfRange(16, 24)
        val metaMac = nodeKeyRaw.copyOfRange(24, 32)

        return MegaFileKeyMaterial(aesKey = aesKey, nonce = nonce, metaMac = metaMac)
    }

    /**
     * Descifra la node key cifrada (viene en la respuesta de la API tras los
     * ":" del campo "k") usando AES-ECB con la master key de la carpeta.
     * Sin padding: MEGA opera sobre bloques completos de 16 bytes.
     */
    fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * Descifra el atributo "a" (nombre/metadata) de un nodo: AES-CBC con IV
     * de ceros y sin padding automático (MEGA rellena con ceros al final).
     */
    fun aesCbcDecryptZeroIv(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val zeroIv = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), zeroIv)
        return cipher.doFinal(data)
    }

    /**
     * Descifra un rango de bytes cifrados con AES-CTR, donde [blockOffset]
     * es el índice del primer bloque de 16 bytes dentro del archivo completo
     * (es decir, byteOffset / 16, ya alineado por el llamador).
     *
     * El IV de 16 bytes para AES-CTR en MEGA es: nonce(8 bytes) + contador de
     * bloque (8 bytes, big-endian), donde el contador arranca en 0 al inicio
     * del archivo y se incrementa de a 1 por cada bloque de 16 bytes.
     */
    fun aesCtrDecrypt(aesKey: ByteArray, nonce: ByteArray, blockOffset: Long, data: ByteArray): ByteArray {
        require(nonce.size == 8) { "El nonce de CTR debe ser de 8 bytes" }

        val ivBytes = ByteArray(16)
        System.arraycopy(nonce, 0, ivBytes, 0, 8)
        writeLongBigEndian(blockOffset, ivBytes, 8)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(data)
    }

    private fun writeLongBigEndian(value: Long, target: ByteArray, offset: Int) {
        for (i in 0 until 8) {
            target[offset + 7 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
    }

    // ── Login de cuenta (a=us0 / a=us) ─────────────────────────────────────

    /**
     * PBKDF2-HMAC-SHA512 (RFC 8018) implementado a mano en vez de usar
     * `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`: ese nombre de
     * algoritmo no está garantizado en todas las versiones de Android,
     * mientras que "HmacSHA512" a secas (lo único que hace falta para
     * construir PBKDF2 uno mismo) sí lo está desde siempre.
     *
     * Verificado -- no solo leído -- contra el proveedor JCE estándar de un
     * JDK de escritorio antes de portarlo acá: mismo resultado byte a byte
     * para varias combinaciones de contraseña/salt/iteraciones/largo.
     */
    fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength
        val blocks = (dkLenBytes + hLen - 1) / hLen
        val output = ByteArray(blocks * hLen)

        for (i in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf((i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 1 until iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, output, (i - 1) * hLen, hLen)
        }
        return output.copyOf(dkLenBytes)
    }

    /**
     * Deriva la key de login v2 (cuentas modernas, con salt) a partir de la
     * contraseña en texto plano. Confirmado contra
     * `MegaClient::deriveKey`/`login2` (megaclient.cpp): PBKDF2-HMAC-SHA512
     * con 100.000 iteraciones y salida de 32 bytes. Los primeros 16 bytes
     * son la key que descifra la master key devuelta por el login ("k");
     * los últimos 16 son el "authKey" que se manda como "uh" en vez de
     * mandar la contraseña.
     */
    fun deriveV2LoginKey(password: String, saltBytes: ByteArray): MegaV2LoginKey {
        val derived = pbkdf2HmacSha512(password.toByteArray(Charsets.UTF_8), saltBytes, 100_000, 32)
        return MegaV2LoginKey(key = derived.copyOfRange(0, 16), authKey = derived.copyOfRange(16, 32))
    }

    /**
     * Parsea la clave privada RSA en el formato binario propio de MEGA: 3 o
     * 4 enteros consecutivos (p, q, d[, u]), cada uno prefijado por 2 bytes
     * big-endian con su longitud EN BITS, seguidos de ceil(bits/8) bytes de
     * magnitud big-endian. Si falta el 4to entero (u), se deriva como
     * inverso modular de p contra q -- igual que `decodeintarray()` en el
     * SDK oficial (crypto/cryptopp.cpp) cuando recibe `PRIVKEY_SHORT`.
     *
     * u = p⁻¹ mod q (no q⁻¹ mod p: el orden importa para la fórmula CRT de
     * [rsaRawDecrypt]).
     */
    fun parseMegaRsaPrivateKey(decrypted: ByteArray): MegaRsaPrivateKey {
        val ints = mutableListOf<BigInteger>()
        var pos = 0
        while (ints.size < 4 && pos + 2 <= decrypted.size) {
            val bitLen = ((decrypted[pos].toInt() and 0xFF) shl 8) or (decrypted[pos + 1].toInt() and 0xFF)
            val byteLen = (bitLen + 7) / 8
            pos += 2
            if (byteLen == 0 || pos + byteLen > decrypted.size) break
            ints += BigInteger(1, decrypted.copyOfRange(pos, pos + byteLen))
            pos += byteLen
        }
        require(ints.size >= 3) { "Clave privada RSA de MEGA incompleta: solo se pudieron leer ${ints.size} enteros" }

        val p = ints[0]
        val q = ints[1]
        val d = ints[2]
        val u = if (ints.size >= 4) ints[3] else p.modInverse(q)
        return MegaRsaPrivateKey(p = p, q = q, d = d, u = u)
    }

    /**
     * Descifra un bloque RSA "crudo" (sin padding PKCS#1 -- MEGA usa su
     * propio esquema minimalista donde el servidor rellena con bytes
     * arbitrarios y el cliente se queda solo con los primeros [outputLen]
     * bytes del resultado, sin validar el resto) usando CRT.
     *
     * [cipher] viene envuelto en el mismo formato MPI que los componentes
     * de la clave privada (2 bytes de longitud EN BITS + magnitud) -- hay
     * que sacarle ese prefijo antes de convertir a número. Confirmado
     * contra `AsymmCipher::decrypt()` en cryptopp.cpp, que llama a
     * `decodeintarray(&m, 1, cipher, cipherlen)` -- la MISMA función que
     * usa [parseMegaRsaPrivateKey] -- no `Integer m(cipher, cipherlen)`
     * directo (eso es `rawdecrypt()`, una función distinta).
     *
     * DIRECCIÓN DEL TRUNCAMIENTO (esto costó encontrar): el payload útil
     * queda en los primeros [outputLen] bytes del resultado completo -- el
     * extremo MÁS significativo, no el menos significativo. Retracé a mano
     * el bucle `while(numbytes--) out[numbytes]=m.GetByte(l++)` de
     * `AsymmCipher::decrypt()` con los tamaños reales de una cuenta real
     * (255 bytes de resultado, l=212) y armé una prueba sintética propia
     * que asumía la dirección contraria -- se auto-validaba sin comparar
     * contra el protocolo real. Recién se detectó al probar contra el
     * `csid` real de una cuenta de MEGA: con esta dirección, los bytes
     * [16,27) del sid resultante decodifican EXACTO al handle de usuario
     * esperado; con la dirección anterior, no.
     *
     * Fórmula CRT confirmada línea por línea contra `rsadecrypt()` en
     * crypto/cryptopp.cpp, y verificada EJECUTÁNDOLA -- primero contra un
     * par de claves RSA-2048 sintético, y después contra el login real de
     * una cuenta de MEGA real (ver IntegrationTest, que reconstruye la
     * dirección correcta del padding).
     */
    fun rsaRawDecrypt(cipher: ByteArray, privateKey: MegaRsaPrivateKey, outputLen: Int): ByteArray {
        require(cipher.size >= 2) { "Cipher RSA demasiado corto para traer el prefijo MPI: ${cipher.size} bytes" }
        val bitLen = ((cipher[0].toInt() and 0xFF) shl 8) or (cipher[1].toInt() and 0xFF)
        val byteLen = (bitLen + 7) / 8
        require(2 + byteLen <= cipher.size) {
            "Prefijo MPI del cipher RSA inconsistente: bitLen=$bitLen implica $byteLen bytes, pero solo hay ${cipher.size - 2} disponibles"
        }
        val c = BigInteger(1, cipher.copyOfRange(2, 2 + byteLen))
        val (p, q, d, u) = privateKey

        val dp = d.mod(p - BigInteger.ONE)
        val dq = d.mod(q - BigInteger.ONE)
        val xp = c.mod(p).modPow(dp, p)
        val xq = c.mod(q).modPow(dq, q)

        val m = if (xp > xq) {
            q - (((xp - xq) * u).mod(q))
        } else {
            ((xq - xp) * u).mod(q)
        }
        val full = m * p + xp

        return fixedWidthHighBytes(full, outputLen)
    }

    /**
     * Los [numBytes] bytes MÁS significativos de [value] en big-endian
     * (los primeros al escribirlo en notación estándar), rellenando con
     * ceros a la derecha si [value] ocupa menos que eso.
     */
    private fun fixedWidthHighBytes(value: BigInteger, numBytes: Int): ByteArray {
        val full = value.toByteArray()
        val start = if (full.size > 1 && full[0] == 0.toByte()) 1 else 0
        val usableLen = full.size - start
        val out = ByteArray(numBytes)
        val copyLen = minOf(usableLen, numBytes)
        System.arraycopy(full, start, out, 0, copyLen)
        return out
    }

    // ── Hashcash (desafío de prueba de trabajo, a veces exigido al loguearse) ──

    private const val HASHCASH_TOKEN_BYTES = 48
    private const val HASHCASH_PREFIX_BYTES = 4
    private const val HASHCASH_REPEAT = 262_144 // 12MB / 48B
    private const val HASHCASH_REPEAT_BYTES = HASHCASH_REPEAT * HASHCASH_TOKEN_BYTES
    private const val HASHCASH_BUF_SIZE = HASHCASH_PREFIX_BYTES + HASHCASH_REPEAT_BYTES

    // El propio SDK oficial limita a 2 hilos en Android/iOS (vs. 8 en
    // escritorio), probablemente por batería/calor -- se sigue el mismo
    // criterio acá.
    private const val HASHCASH_MAX_WORKERS = 2

    /** Fórmula exacta de thresholdFromEasiness() en hashcash.cpp del SDK oficial. Más alto = más fácil. */
    private fun hashcashThreshold(easiness: Int): Long {
        val e = easiness and 0xFF
        val a = ((e and 63) shl 1) + 1
        val shiftAmount = ((e shr 6) * 7) + 3
        return a.toLong() shl shiftAmount
    }

    private fun firstWordBigEndian(bytes: ByteArray): Long = ((bytes[0].toLong() and 0xFF) shl 24) or
        ((bytes[1].toLong() and 0xFF) shl 16) or
        ((bytes[2].toLong() and 0xFF) shl 8) or
        (bytes[3].toLong() and 0xFF)

    /**
     * Resuelve el desafío hashcash (prueba de trabajo anti-abuso que MEGA
     * exige a veces al loguearse: HTTP 402 con header X-Hashcash, cuerpo
     * vacío a propósito). Busca un nonce de 4 bytes tal que
     * SHA-256([nonce][60B del token][token repetido hasta completar 12MB])
     * dé, en sus primeros 4 bytes (big-endian), un valor <= threshold(easiness).
     *
     * Confirmado línea por línea contra hashcash.cpp del SDK oficial
     * (gencash/gencashWorker/thresholdFromEasiness/validateHashcash), y
     * verificado EJECUTÁNDOLO: para dificultades bajas la solución
     * encontrada se validó de forma independiente reconstruyendo el mensaje
     * completo (misma lógica que validateHashcash), y una solución alterada
     * a propósito se rechazó correctamente.
     *
     * ADVERTENCIA DE RENDIMIENTO: esto es una prueba de trabajo real, por
     * diseño puede tardar -- no es un bug si demora. Con la dificultad de
     * EJEMPLO documentada en el propio SDK (easiness=100) no se pudo
     * resolver en 20 segundos ni siquiera en una máquina de escritorio (con
     * un solo núcleo disponible en esa prueba puntual); en un teléfono el
     * tiempo real depende de qué dificultad mande el servidor en cada caso
     * concreto y de cuántos núcleos pueda aprovechar -- no hay forma de
     * saberlo de antemano sin probarlo en el equipo real. Por eso
     * [timeoutMillis] es generoso, y quien llame a esto debe correrlo en un
     * hilo de fondo con feedback claro de que puede demorar.
     *
     * @throws IllegalStateException si no encuentra una solución dentro de [timeoutMillis].
     */
    fun solveHashcash(tokenB64: String, easiness: Int, timeoutMillis: Long = 180_000L): String {
        val token = megaBase64Decode(tokenB64)
        require(token.size == HASHCASH_TOKEN_BYTES) {
            "Token de hashcash con tamaño inválido: ${token.size} bytes (se esperaban $HASHCASH_TOKEN_BYTES)"
        }

        // Buffer COMPARTIDO de solo lectura entre hilos: bytes [0,4) se
        // ignoran acá (cada hilo arma su propio bloque "caliente" de 64
        // bytes con su nonce), el resto es el token repetido hasta llenar
        // los ~12MB.
        val sharedArea = ByteArray(HASHCASH_BUF_SIZE)
        System.arraycopy(token, 0, sharedArea, HASHCASH_PREFIX_BYTES, token.size)
        var filled = token.size
        while (filled < HASHCASH_REPEAT_BYTES) {
            val copy = minOf(filled, HASHCASH_REPEAT_BYTES - filled)
            System.arraycopy(sharedArea, HASHCASH_PREFIX_BYTES, sharedArea, HASHCASH_PREFIX_BYTES + filled, copy)
            filled += copy
        }

        val threshold = hashcashThreshold(easiness)
        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(1, HASHCASH_MAX_WORKERS)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val found = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val deadline = System.currentTimeMillis() + timeoutMillis

        val threads = (0 until workerCount).map { start ->
            Thread {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                // Los primeros 64 bytes del mensaje son [4B nonce][60B del
                // área compartida desde el offset 4]; el resto (>12MB) se
                // lee directo del área compartida sin copiarlo por hilo.
                val hotBlock = ByteArray(64)
                System.arraycopy(sharedArea, HASHCASH_PREFIX_BYTES, hotBlock, 4, 60)

                var n = start
                while (!stop.get() && System.currentTimeMillis() <= deadline) {
                    hotBlock[0] = (n ushr 24).toByte()
                    hotBlock[1] = (n ushr 16).toByte()
                    hotBlock[2] = (n ushr 8).toByte()
                    hotBlock[3] = n.toByte()

                    digest.reset()
                    digest.update(hotBlock)
                    digest.update(sharedArea, HASHCASH_PREFIX_BYTES + 60, sharedArea.size - HASHCASH_PREFIX_BYTES - 60)
                    val hash = digest.digest()

                    if (firstWordBigEndian(hash) <= threshold) {
                        if (stop.compareAndSet(false, true)) {
                            found.set(byteArrayOf(hotBlock[0], hotBlock[1], hotBlock[2], hotBlock[3]))
                        }
                        return@Thread
                    }
                    n += workerCount
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val solutionBytes = found.get()
            ?: throw IllegalStateException("No se pudo resolver el desafío de seguridad de MEGA a tiempo (más de ${timeoutMillis / 1000}s)")
        return megaBase64Encode(solutionBytes)
    }
}
