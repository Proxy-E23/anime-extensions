package aniyomi.lib.megaextractor

import android.util.Base64
import javax.crypto.Cipher
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
}
