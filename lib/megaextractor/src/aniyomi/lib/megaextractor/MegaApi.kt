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

        // handle -> master key (16 bytes) de cada carpeta ya resuelta, para
        // poder descifrar hijos anidados. Se pre-puebla con el propio handle
        // de la carpeta raíz: la mayoría de los nodos de primer nivel tienen
        // "p" (parent) apuntando exactamente a ese handle, así que sin esta
        // entrada nunca se encontraba su key y fallaban en cascada (bug real
        // detectado: solo el nodo cuyo padre coincidía por otra vía resolvía
        // bien, el resto caía silenciosamente a "sin key resoluble").
        val folderKeys = mutableMapOf(folderHandle to folderKeyRaw)

        val nodes = mutableListOf<MegaNode>()

        // El primer nodo que devuelve la API para un link de carpeta pública
        // es siempre el nodo raíz de esa carpeta (confirmado contra datos
        // reales: p.ej. para el link .../folder/WkdXGIyb#..., el primer nodo
        // trae h="Px0wVRIB" — un handle distinto de "WkdXGIyb", el del link).
        // Se necesita esta bandera explícita para el bug de abajo.
        var rootNodeHandle: String? = null

        // Se resuelve la key de cada nodo en el orden en que llega. Esto
        // asume que la API devuelve los nodos en orden topológico (carpetas
        // padre antes que sus hijos), que es el comportamiento observado del
        // protocolo. Si algún nodo llegara antes que su padre, simplemente
        // no se podrá descifrar su nombre (queda como "(sin nombre)") en vez
        // de romper el resto del listado.
        for (raw in rawNodes) {
            val parentFolderKey = raw.p?.let { folderKeys[it] } ?: folderKeyRaw
            val nodeKeyRaw = raw.k?.let { decryptNodeKey(it, parentFolderKey) }
            if (rootNodeHandle == null) rootNodeHandle = raw.h

            val (fileKeyMaterial, folderMasterKey) = when {
                raw.t == 1 && nodeKeyRaw != null && nodeKeyRaw.size == 16 -> {
                    // BUG REAL (confirmado con datos reales de la API, carpeta
                    // "Ragna Crimson"): para descifrar a los HIJOS del nodo
                    // raíz de la carpeta compartida, la master key correcta
                    // es folderKeyRaw (la key del propio link, tal cual), NO
                    // el nodekey que resulta de descifrar el campo "k" del
                    // nodo raíz — ese nodekey solo sirve para el atributo "a"
                    // del root mismo, y si se propaga a los hijos, todos sus
                    // atributos descifran a basura silenciosamente.
                    //
                    // Por eso el nodo raíz (raw.h == rootNodeHandle) NO debe
                    // agregar una entrada a folderKeys: sus hijos (cuyo "p"
                    // es raw.h) no la encontrarán ahí y caerán al fallback
                    // `?: folderKeyRaw` de la línea de arriba, que es la key
                    // correcta. Solo las sub-carpetas reales más profundas
                    // (t=1 que no son el root) sí deben registrar su propio
                    // nodekey para que sus hijos lo usen.
                    if (raw.h != rootNodeHandle) {
                        folderKeys[raw.h] = nodeKeyRaw
                    }
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
     * Descifra la node key de un nodo. El campo "k" viene como
     * "handleDelPropioNodo:blobCifrado", donde el blob está cifrado con la
     * key de la carpeta *padre* (la del propio nodo aún no existe hasta
     * descifrar esto). A veces trae varios pares separados por "/" cuando el
     * nodo fue compartido con múltiples usuarios; en ese caso alcanza con
     * encontrar el primer par que se logre descifrar con la key del padre.
     */
    private fun decryptNodeKey(kField: String, parentFolderKey: ByteArray): ByteArray? {
        val pairs = kField.split("/")
        for (pair in pairs) {
            val idx = pair.indexOf(":")
            if (idx == -1) continue
            val blob = pair.substring(idx + 1)

            val decrypted = try {
                val cipherBytes = MegaCrypto.megaBase64Decode(blob)
                MegaCrypto.aesEcbDecrypt(parentFolderKey, cipherBytes)
            } catch (e: Exception) {
                null
            }
            if (decrypted != null) return decrypted
        }
        return null
    }
}
