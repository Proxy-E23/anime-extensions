package aniyomi.lib.megaextractor

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente mínimo de la API pública de MEGA (JSON-RPC sobre HTTP), suficiente
 * para leer links públicos de archivo/carpeta sin necesidad de sesión.
 *
 * No implementa el SDK completo (cuentas, sync, chat, etc.) -- solo lo
 * necesario para listar contenido y obtener URLs de descarga de un link
 * público, que es un subconjunto muy pequeño del protocolo real.
 */
internal class MegaApi(private val client: OkHttpClient) {

    private val tag = "MegaApi"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val seqno = AtomicInteger((0..Int.MAX_VALUE / 2).random())

    // DIAGNÓSTICO TEMPORAL: guarda la última respuesta cruda de "f" para
    // poder mostrarla en el mensaje de error de la extensión sin necesitar
    // logcat/adb. Se debe quitar una vez identificado el bug real.
    var lastRawResponse: String? = null
        private set

    companion object {
        // Confirmado contra megaclient.cpp: el path real del endpoint es
        // "cs" (client-server command), no "/cgi/mega" como en versiones
        // antiguas/otros clientes de terceros.
        private const val API_BASE = "https://g.api.mega.co.nz/cs"
        private val MEDIA_JSON = "application/json".toMediaType()
    }

    class MegaApiException(val code: Int, message: String) : Exception(message)

    /**
     * Ejecuta una petición a la API. El cuerpo es un array con un único
     * objeto de comando, tal como espera el protocolo MEGA.
     */
    private fun call(bodyJson: String, extraParams: Map<String, String> = emptyMap()): String {
        val urlBuilder = StringBuilder(API_BASE)
            .append("?id=").append(seqno.getAndIncrement())
        extraParams.forEach { (k, v) -> urlBuilder.append("&").append(k).append("=").append(v) }
        // "v=3" es la versión del protocolo que usa el SDK oficial
        // (megaclient.cpp: posturl.append("&v=3")). Sin este parámetro la
        // API puede responder distinto o rechazar la petición.
        urlBuilder.append("&v=3")

        val finalUrl = urlBuilder.toString()
        Log.d(tag, "POST $finalUrl body=$bodyJson")

        val request = Request.Builder()
            .url(finalUrl)
            .post(bodyJson.toRequestBody(MEDIA_JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            Log.d(tag, "HTTP ${response.code} <- $body")
            // La API a veces responde solo un entero (código de error) en vez
            // de un array, p.ej. "-9" para "no existe".
            body.trim().toIntOrNull()?.let { errorCode ->
                if (errorCode < 0) throw MegaApiException(errorCode, "Error de la API de MEGA: $errorCode")
            }
            return body
        }
    }

    /**
     * Lista el árbol completo de una carpeta pública ya descifrado.
     * [folderHandle] es el handle de la carpeta raíz, [folderKeyRaw] son los
     * 16 bytes de master key decodificados desde el link.
     */
    fun listFolder(folderHandle: String, folderKeyRaw: ByteArray): List<MegaNode> {
        val bodyJson = """[{"a":"f","c":1,"ca":1,"r":1}]"""
        val responseBody = call(bodyJson, extraParams = mapOf("n" to folderHandle))
        lastRawResponse = responseBody

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull()
            ?: throw MegaApiException(-1, "Respuesta vacía al listar la carpeta")

        val folderResponse = json.decodeFromJsonElement(MegaFolderResponse.serializer(), firstElement)
        val rawNodes = folderResponse.f ?: emptyList()
        Log.d(tag, "listFolder($folderHandle): ${rawNodes.size} nodos crudos recibidos")

        val nodes = mutableListOf<MegaNode>()

        // El primer nodo que devuelve la API para un link de carpeta pública
        // es siempre el nodo raíz de esa carpeta (confirmado contra datos
        // reales: p.ej. para el link .../folder/WkdXGIyb#..., el primer nodo
        // trae h="Px0wVRIB" — un handle distinto de "WkdXGIyb", el del link).
        var rootNodeHandle: String? = null

        // FIX REAL (reemplaza el intento anterior de "cascada de keys por
        // padre"), confirmado línea por línea contra Node::applykey() del
        // SDK oficial de MEGA (node.cpp) y verificado con datos reales de la
        // API para la carpeta "Dr. Stone" (8 nodos, carpetas y archivos, 3
        // niveles de profundidad, todos con compound key de 3-5 pares):
        //
        // El protocolo NO descifra la nodekey de un hijo con la nodekey ya
        // descifrada de su padre inmediato (eso es lo que hacía la versión
        // anterior, y por eso fallaba: no existe tal cascada). En vez de
        // eso, cada nodo trae en su campo "k" un compound key con un par
        // "handle:blob" por cada nivel de ancestro hasta la raíz del árbol
        // devuelto, y el par que hay que usar es el que tiene como handle el
        // handle del NODO RAÍZ DEL ÁRBOL (rootNodeHandle, el primer nodo que
        // devuelve listFolder) — no el handle del link de la URL, y no el
        // del padre directo ("p") salvo que este coincida con la raíz. Ese
        // blob se descifra SIEMPRE con folderKeyRaw (la master key del
        // link), sin importar cuán profundo esté el nodo en el árbol.
        //
        // Esto coincide con MegaClient::folderaccess() (megaclient.cpp):
        // `key.setkey(folderkey)` deja la master key del cliente fija e
        // igual a la del link para toda la sesión de acceso al folder link,
        // y Node::applykey() (node.cpp) siempre usa esa misma key salvo que
        // el handle matcheado corresponda a un share ajeno con su propio
        // sharekey (mNewKeyRepository/n->sharekey) — caso que no aplica a
        // los nodos internos de un mismo folder link público.
        for (raw in rawNodes) {
            if (rootNodeHandle == null) rootNodeHandle = raw.h
            val nodeKeyRaw = raw.k?.let { decryptNodeKey(it, folderKeyRaw, rootNodeHandle) }

            val (fileKeyMaterial, folderMasterKey) = when {
                raw.t == 1 && nodeKeyRaw != null && nodeKeyRaw.size == 16 -> {
                    null to nodeKeyRaw
                }
                raw.t == 0 && nodeKeyRaw != null && nodeKeyRaw.size == 32 -> {
                    MegaCrypto.fileKeyFromNodeKey(nodeKeyRaw) to null
                }
                else -> {
                    Log.w(tag, "Nodo ${raw.h} (t=${raw.t}) sin key resoluble; k=${raw.k}, nodeKeyRaw.size=${nodeKeyRaw?.size}")
                    null to null
                }
            }

            val attrKey = folderMasterKey ?: fileKeyMaterial?.aesKey
            val name = attrKey?.let { decryptAttrName(raw.a, it) } ?: "(sin nombre)"

            nodes += MegaNode(
                handle = raw.h,
                parentHandle = raw.p,
                isFolder = raw.t == 1,
                name = name,
                size = raw.s ?: 0L,
                timestamp = (raw.ts ?: 0L) * 1000L,
                fileKey = fileKeyMaterial,
                folderKey = folderMasterKey,
            )
        }

        Log.d(tag, "listFolder($folderHandle): ${nodes.size} nodos procesados, ${nodes.count { !it.isFolder }} archivos")
        return nodes
    }

    /**
     * Obtiene la URL temporal de descarga (cifrada) para un archivo.
     *
     * Confirmado contra el SDK oficial (CommandGetPH / CommandGetFile en
     * commands.cpp): el handle del archivo va siempre dentro del body del
     * comando "g", nunca en la query string. La clave del argumento cambia
     * según el contexto:
     *  - Archivo standalone (link público de archivo, sin carpeta): `"p"`
     *    (public handle).
     *  - Archivo dentro de una carpeta pública: `"n"` (node handle), y el
     *    contexto de la carpeta se identifica en la query string con
     *    `&n=<folderHandle>` (ver MegaClient::getAuthURI en megaclient.cpp).
     */
    fun getDownloadUrl(fileHandle: String, folderHandle: String? = null): MegaDownloadResponse {
        val bodyJson: String
        val extraParams: Map<String, String>

        if (folderHandle == null) {
            bodyJson = """[{"a":"g","g":1,"p":"$fileHandle"}]"""
            extraParams = emptyMap()
        } else {
            bodyJson = """[{"a":"g","g":1,"n":"$fileHandle"}]"""
            extraParams = mapOf("n" to folderHandle)
        }

        val responseBody = call(bodyJson, extraParams)

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull()
            ?: throw MegaApiException(-1, "Respuesta vacía al pedir descarga")

        val downloadResponse = json.decodeFromJsonElement(MegaDownloadResponse.serializer(), firstElement)
        downloadResponse.e?.let { if (it < 0) throw MegaApiException(it, "Error al obtener link de descarga: $it") }
        return downloadResponse
    }

    /**
     * Para un link de archivo único (no dentro de carpeta listada), la API
     * devuelve directamente los atributos cifrados "at" junto con "g"/"s".
     * Aquí se descifra ese nombre usando la key del propio link de archivo.
     */
    fun decryptSingleFileName(at: String, aesKey: ByteArray): String = decryptAttrName(at, aesKey)

    private fun decryptAttrName(attrBase64: String, aesKey: ByteArray): String {
        return try {
            val cipherBytes = MegaCrypto.megaBase64Decode(attrBase64)
            val plain = MegaCrypto.aesCbcDecryptZeroIv(aesKey, cipherBytes)
            val text = plain.toString(Charsets.UTF_8)
            if (!text.startsWith("MEGA")) {
                Log.w(tag, "Atributo descifrado no empieza con 'MEGA': ${text.take(20)}")
                return "(nombre no disponible)"
            }
            val jsonPart = text.substring(4).trimEnd('\u0000')
            val element = json.parseToJsonElement(jsonPart)
            jsonStringField(element, "n") ?: "(nombre no disponible)"
        } catch (e: Exception) {
            Log.e(tag, "Fallo al descifrar atributo (attrBase64.len=${attrBase64.length}, key.size=${aesKey.size}): ${e.javaClass.simpleName} ${e.message}")
            "(nombre no disponible)"
        }
    }

    private fun jsonStringField(element: JsonElement, key: String): String? = try {
        (element as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.content }
    } catch (e: Exception) {
        null
    }

    /**
     * Descifra la node key de un nodo dentro de un folder link público.
     *
     * El campo "k" trae uno o más pares "handle:blobCifrado" separados por
     * "/" — uno por cada nivel de ancestro compartido entre el nodo y la
     * raíz del árbol devuelto. NO es una cascada donde cada nivel se
     * descifra con la key del nivel anterior: confirmado línea por línea
     * contra Node::applykey() del SDK oficial (node.cpp) y verificado con
     * datos reales de la API (carpeta "Dr. Stone": 8 nodos, carpetas y
     * archivos, hasta 3 niveles de profundidad, compound keys de 1 a 5
     * pares) que el par correcto es siempre el que tiene como handle el
     * handle del NODO RAÍZ del árbol (rootNodeHandle, el primer nodo que
     * devuelve listFolder), y ese blob se descifra SIEMPRE con la master
     * key del propio link (folderKeyRaw) — nunca con la key de un padre
     * intermedio, sin importar cuán profundo esté el nodo.
     *
     * Esto coincide con MegaClient::folderaccess() (megaclient.cpp), que
     * fija `client->key` a la key del link para toda la sesión, y con
     * Node::applykey(), que siempre descifra contra esa misma key salvo
     * que el handle matcheado sea el de un share ajeno con su propio
     * sharekey — caso que no aplica entre nodos de un mismo folder link.
     *
     * Si el compound key no trae ningún par con el handle raíz (no
     * debería pasar en la práctica), se cae a probar todos los pares en
     * orden como último recurso.
     */
    private fun decryptNodeKey(kField: String, folderKeyRaw: ByteArray, rootNodeHandle: String? = null): ByteArray? {
        val pairs = kField.split("/").mapNotNull { pair ->
            val idx = pair.indexOf(":")
            if (idx == -1) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }

        val matchingPair = rootNodeHandle?.let { h -> pairs.firstOrNull { it.first == h } }
        val orderedBlobs = if (matchingPair != null) {
            listOf(matchingPair.second) + pairs.filter { it != matchingPair }.map { it.second }
        } else {
            pairs.map { it.second }
        }

        for (blob in orderedBlobs) {
            val decrypted = try {
                val cipherBytes = MegaCrypto.megaBase64Decode(blob)
                MegaCrypto.aesEcbDecrypt(folderKeyRaw, cipherBytes)
            } catch (e: Exception) {
                null
            }
            if (decrypted != null) return decrypted
        }
        return null
    }
}
