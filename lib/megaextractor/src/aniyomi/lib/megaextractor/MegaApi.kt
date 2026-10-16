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
 * Error de la API de MEGA con su código numérico original (p.ej. -9 email o
 * contraseña incorrectos, -26 falta el código de verificación en dos
 * pasos). A propósito NO es un nested class de [MegaApi] -- que es
 * internal al módulo de la librería -- para que quien consuma
 * [MegaExtractor] desde otra extensión pueda importarla y distinguir estos
 * códigos (ver [MegaExtractor.login]).
 */
class MegaApiException(val code: Int, message: String) : Exception(message)

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

    // Sesión de cuenta activa, si el usuario inició sesión (desde CUALQUIER
    // extensión que use esta librería: se auto-carga acá para que todas
    // vean la misma sin tener que volver a loguearse). Se adjunta sola en
    // [call] a toda petición autenticable -- incluidas las de un link
    // público ajeno, que es justamente lo que hace que la cuota de ancho
    // de banda de la cuenta aplique en vez de la anónima por IP.
    @Volatile
    var currentSession: MegaAccountSession? = MegaSessionStore.load()
        private set

    companion object {
        // Confirmado contra megaclient.cpp: el path real del endpoint es
        // "cs" (client-server command), no "/cgi/mega" como en versiones
        // antiguas/otros clientes de terceros.
        private const val API_BASE = "https://g.api.mega.co.nz/cs"
        private val MEDIA_JSON = "application/json".toMediaType()

        // SIDLEN del SDK oficial (megaclient.h): 2*KEYLENGTH(16) +
        // USERHANDLE(8)*4/3 (división entera) + 1 = 32 + 10 + 1 = 43 bytes.
        private const val SID_LEN = 43
    }

    /**
     * Ejecuta una petición a la API. El cuerpo es un array con un único
     * objeto de comando, tal como espera el protocolo MEGA.
     *
     * Si hay sesión activa y [attachSession] es true (default), se agrega
     * "&sid=" con el session id -- confirmado contra
     * `MegaClient::getAuthURI` (megaclient.cpp): así es como el SDK oficial
     * adjunta la sesión a cualquier llamada autenticada, incluidas las de
     * un link público ajeno. [attachSession] se pone en false únicamente
     * para prelogin/login: ahí todavía no hay (o se está por reemplazar)
     * la sesión, adjuntar una vieja no tendría sentido.
     */
    private fun call(
        bodyJson: String,
        extraParams: Map<String, String> = emptyMap(),
        attachSession: Boolean = true,
        hashcashHeader: String? = null,
        hashcashRetriesLeft: Int = 5,
    ): String {
        val session = currentSession
        val params = if (attachSession && session != null) {
            extraParams + ("sid" to MegaCrypto.megaBase64Encode(session.sid))
        } else {
            extraParams
        }

        val urlBuilder = StringBuilder(API_BASE)
            .append("?id=").append(seqno.getAndIncrement())
        params.forEach { (k, v) -> urlBuilder.append("&").append(k).append("=").append(v) }
        // "v=3" es la versión del protocolo que usa el SDK oficial
        // (megaclient.cpp: posturl.append("&v=3")). Sin este parámetro la
        // API puede responder distinto o rechazar la petición.
        urlBuilder.append("&v=3")

        val finalUrl = urlBuilder.toString()
        Log.d(tag, "POST $finalUrl body=$bodyJson")

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .post(bodyJson.toRequestBody(MEDIA_JSON))
        hashcashHeader?.let { requestBuilder.addHeader("X-Hashcash", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            // MEGA a veces exige resolver un desafío de seguridad (prueba de
            // trabajo) antes de dejar pasar la petición -- confirmado contra
            // megaclient.cpp/hashcash.cpp del SDK oficial. El cuerpo viene
            // vacío A PROPÓSITO en este caso (no es una respuesta corrupta).
            if (response.code == 402) {
                if (hashcashRetriesLeft <= 0) {
                    throw MegaApiException(
                        -1,
                        "MEGA sigue pidiendo un desafío de seguridad nuevo después de varios intentos ya resueltos -- " +
                            "puede ser un límite temporal por muchos intentos de login seguidos",
                    )
                }
                val challenge = response.header("X-Hashcash")
                    ?: throw MegaApiException(-1, "MEGA respondió 402 pero no mandó el header X-Hashcash")
                // Formato confirmado contra el SDK: "<versión>:<easiness>:<timestamp>:<tokenB64>"
                val parts = challenge.split(":")
                if (parts.size != 4 || parts[0] != "1") {
                    throw MegaApiException(-1, "Header X-Hashcash con formato inesperado: $challenge")
                }
                val easiness = parts[1].toIntOrNull()
                    ?: throw MegaApiException(-1, "Easiness de hashcash inválido: ${parts[1]}")
                val token = parts[3]

                Log.d(tag, "MEGA pidió resolver un desafío de seguridad (easiness=$easiness); calculando, puede tardar...")
                val t0 = System.currentTimeMillis()
                val solution = MegaCrypto.solveHashcash(token, easiness)
                Log.d(tag, "Desafío de seguridad resuelto en ${System.currentTimeMillis() - t0} ms")

                // Pequeño respiro antes de reintentar: por si el servidor es
                // sensible a la frecuencia de pedidos (no confirmado, pero es
                // una espera barata que no perjudica si la causa es otra).
                Thread.sleep(1000)

                return call(bodyJson, extraParams, attachSession, "1:$token:$solution", hashcashRetriesLeft - 1)
            }

            val body = response.body.string()
            Log.d(tag, "HTTP ${response.code} <- $body")
            // La API a veces responde solo un entero (código de error) en vez
            // de un array, p.ej. "-9" para "no existe".
            body.trim().toIntOrNull()?.let { errorCode ->
                if (errorCode == -15) {
                    // API_ESID ("Bad session ID"): el sid guardado ya no
                    // sirve. Se limpia acá mismo para que la app no lo siga
                    // reintentando en cada llamada y para que Ajustes deje
                    // de mostrar "sesión iniciada" con una sesión rota.
                    Log.w(tag, "Sesión inválida (-15); limpiando sesión guardada")
                    currentSession = null
                    MegaSessionStore.clear()
                }
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
        throwIfDownloadError(downloadResponse)
        return downloadResponse
    }

    /**
     * Traduce el código de error `e` de una respuesta de descarga ("a":"g")
     * a una excepción. -509 (cuota de transferencia agotada para la cuenta
     * o la IP) es, con diferencia, el más común en cuentas free descargando
     * seguido -- MEGA no siempre lo devuelve acá con un mensaje claro:
     * a veces la petición de la URL de descarga en sí sale bien (por eso el
     * reproductor sí arranca a reproducir), pero la conexión de descarga
     * real que abre [MegaProxyServer] se corta a mitad de la transferencia
     * cuando la cuota se agota EN ESE MOMENTO. Un mensaje específico acá
     * ayuda al menos en el caso en que MEGA sí lo reporta desde ya.
     */
    private fun throwIfDownloadError(response: MegaDownloadResponse) {
        response.e?.let {
            if (it == -509) {
                throw MegaApiException(
                    it,
                    "Se agotó la cuota de transferencia de MEGA para esta cuenta/IP por hoy. " +
                        "Espera unas horas (o cambia de red/reinicia el módem para obtener otra IP) e intenta de nuevo.",
                )
            }
            if (it < 0) throw MegaApiException(it, "Error al obtener link de descarga: $it")
        }
    }

    /**
     * Para un link de archivo único (no dentro de carpeta listada), la API
     * devuelve directamente los atributos cifrados "at" junto con "g"/"s".
     * Aquí se descifra ese nombre usando la key del propio link de archivo.
     */
    fun decryptSingleFileName(at: String, aesKey: ByteArray): String = decryptAttrName(at, aesKey)

    // ── Cuenta (a=us0 / a=us) ───────────────────────────────────────────────

    /** Averigua si una cuenta es v1 (legacy) o v2 (PBKDF2 con salt), y el salt si aplica. */
    fun prelogin(email: String): MegaPreloginResponse {
        val bodyJson = """[{"a":"us0","user":"${jsonEscape(email)}"}]"""
        val responseBody = call(bodyJson, attachSession = false)

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull() ?: throw MegaApiException(-1, "Respuesta vacía en prelogin")
        return json.decodeFromJsonElement(MegaPreloginResponse.serializer(), firstElement)
    }

    /**
     * Inicia sesión con email + contraseña. Solo cuentas v2 (modernas, con
     * salt) por ahora -- las v1 (legacy, sin salt) usan un algoritmo de
     * derivación bastante más viejo y particular (65.536 rondas de AES-ECB
     * en cascada sobre la propia contraseña) que todavía no está
     * implementado acá.
     *
     * Al resolver bien, deja la sesión guardada para TODAS las extensiones
     * que usen esta librería (ver [MegaSessionStore]), no solo la que
     * llamó a este método.
     *
     * @throws MegaApiException code=-9 si el email o la contraseña son
     * incorrectos, code=-26 si la cuenta tiene verificación en dos pasos y
     * no se mandó [pin].
     * @throws UnsupportedOperationException si la cuenta es v1.
     */
    fun login(email: String, password: String, pin: String? = null): MegaAccountSession {
        val pre = prelogin(email)
        val version = pre.v ?: throw MegaApiException(-1, "La API no devolvió versión de cuenta")
        if (version != 2) {
            throw UnsupportedOperationException(
                "Esta cuenta de MEGA es v$version (formato legacy, sin salt). Por ahora solo se soportan cuentas v2.",
            )
        }
        val saltBytes = pre.s?.let { MegaCrypto.megaBase64Decode(it) }
            ?: throw MegaApiException(-1, "La API no devolvió salt para una cuenta v2")

        val loginKey = MegaCrypto.deriveV2LoginKey(password, saltBytes)
        val uh = MegaCrypto.megaBase64Encode(loginKey.authKey)
        val pinField = pin?.let { ",\"mfa\":\"${jsonEscape(it)}\"" } ?: ""

        val bodyJson = """[{"a":"us","user":"${jsonEscape(email)}","uh":"$uh"$pinField}]"""
        val responseBody = call(bodyJson, attachSession = false)

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull() ?: throw MegaApiException(-1, "Respuesta vacía en login")
        val login = json.decodeFromJsonElement(MegaLoginResponse.serializer(), firstElement)

        val kBytes = login.k?.let { MegaCrypto.megaBase64Decode(it) }
            ?: throw MegaApiException(-1, "Login sin campo 'k'")
        val masterKey = MegaCrypto.aesEcbDecrypt(loginKey.key, kBytes)
        Log.d(tag, "login(): master key descifrada = ${masterKey.size} bytes (esperado 16)")

        val privkBytes = login.privk?.let { MegaCrypto.megaBase64Decode(it) }
            ?: throw MegaApiException(-1, "Login sin campo 'privk' (¿cuenta sin par RSA? no soportado)")
        val rsaKey = MegaCrypto.parseMegaRsaPrivateKey(MegaCrypto.aesEcbDecrypt(masterKey, privkBytes))
        Log.d(tag, "login(): clave RSA parseada -- p=${rsaKey.p.bitLength()} bits, q=${rsaKey.q.bitLength()} bits, d=${rsaKey.d.bitLength()} bits")

        val csidBytes = login.csid?.let { MegaCrypto.megaBase64Decode(it) }
            ?: throw MegaApiException(-1, "Login sin campo 'csid'")
        val sid = MegaCrypto.rsaRawDecrypt(csidBytes, rsaKey, SID_LEN)
        Log.d(tag, "login(): sid calculado = ${sid.size} bytes (esperado $SID_LEN)")

        // DIAGNÓSTICO (no bloquea el login): el SDK oficial verifica que los
        // bytes [16, 43) del sid, leídos como texto y decodificados en
        // base64, coincidan con el handle de usuario ("u") de la respuesta
        // -- es la única confirmación real de que el sid calculado es el
        // que el servidor realmente emitió. Un handle de 8 bytes necesita
        // 11 caracteres base64 (sin padding) para decodificar; se toman
        // esos 11 caracteres de texto y se decodifican con la MISMA
        // función ya verificada ([MegaCrypto.megaBase64Decode]), comparando
        // bytes contra bytes -- no un prefijo de texto crudo, que es lo que
        // hacía la versión anterior de este chequeo y podía dar falsos
        // "NO COINCIDE" sin que el sid en sí estuviera mal.
        login.u?.let { uField ->
            try {
                val handleBytes = MegaCrypto.megaBase64Decode(uField)
                val neededChars = (handleBytes.size * 8 + 5) / 6 // ceil(bits/6), 11 para 8 bytes
                val embeddedChars = sid.copyOfRange(16, 16 + neededChars).toString(Charsets.US_ASCII)
                val decodedHandle = MegaCrypto.megaBase64Decode(embeddedChars)
                val matches = decodedHandle.contentEquals(handleBytes)
                Log.d(tag, "login(): verificación de handle embebido en sid -- ${if (matches) "OK" else "NO COINCIDE"}")
            } catch (e: Exception) {
                Log.w(tag, "login(): no se pudo correr la verificación de handle embebido: ${e.message}")
            }
        }

        val ownHandle = login.u?.let { MegaCrypto.megaBase64Decode(it) }
            ?: throw MegaApiException(-1, "Login sin campo 'u' (handle de usuario)")

        val session = MegaAccountSession(email = email, sid = sid, masterKey = masterKey, ownHandle = ownHandle)
        currentSession = session
        MegaSessionStore.save(session)
        return session
    }

    /** Cierra la sesión para TODAS las extensiones que usan esta librería. */
    fun logout() {
        currentSession = null
        MegaSessionStore.clear()
    }

    /**
     * Lista el Cloud Drive (raíz) de la cuenta logueada. A diferencia de
     * [listFolder] -- que es para un link de carpeta PÚBLICO ajeno -- esto
     * requiere sesión activa y no manda ningún "&n=" de contexto en la
     * query string: el "sid" (adjuntado solo por [call]) ya identifica la
     * cuenta completa.
     */
    fun listCloudDrive(): List<MegaNode> {
        val session = currentSession
            ?: throw IllegalStateException("No hay sesión activa; inicia sesión primero con login()")

        val bodyJson = """[{"a":"f","c":1,"ca":1,"r":1}]"""
        val responseBody = call(bodyJson)
        lastRawResponse = responseBody

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull()
            ?: throw MegaApiException(-1, "Respuesta vacía al listar el Cloud Drive")

        val folderResponse = json.decodeFromJsonElement(MegaFolderResponse.serializer(), firstElement)
        val rawNodes = folderResponse.f ?: emptyList()
        Log.d(tag, "listCloudDrive(): ${rawNodes.size} nodos crudos recibidos")

        val nodes = mutableListOf<MegaNode>()
        for (raw in rawNodes) {
            // t=2/3/4 son nodos de sistema (raíz del Cloud Drive, Papelera,
            // Inbox de contactos): no tienen "k" ni "a" cifrado real (la API
            // los manda con a="" y sin k), así que nunca van a tener key
            // resoluble. No son ni carpeta ni archivo de contenido real, por
            // eso se saltan acá en vez de intentar descifrarlos y loguear un
            // warning que no indica ningún problema.
            if (raw.t >= 2) continue

            val nodeKeyRaw = raw.k?.let { decryptOwnNodeKey(it, session.masterKey, session.ownHandle) }

            val (fileKeyMaterial, folderMasterKey) = when {
                raw.t == 1 && nodeKeyRaw != null && nodeKeyRaw.size == 16 -> null to nodeKeyRaw
                raw.t == 0 && nodeKeyRaw != null && nodeKeyRaw.size == 32 -> MegaCrypto.fileKeyFromNodeKey(nodeKeyRaw) to null
                else -> {
                    Log.w(tag, "Nodo ${raw.h} (t=${raw.t}) del Cloud Drive sin key resoluble; k=${raw.k}")
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
        Log.d(tag, "listCloudDrive(): ${nodes.size} nodos procesados, ${nodes.count { !it.isFolder }} archivos")
        // Log adicional nodo por nodo (una línea por nodo, en vez de un solo
        // Log.d con el JSON crudo completo): logcat trunca líneas individuales
        // muy largas (~4KB), así que en cuentas con muchos archivos la
        // respuesta HTTP cruda de arriba queda cortada a mitad y no alcanza a
        // mostrar todos los nodos. Esto es solo para diagnóstico -- no cambia
        // ningún comportamiento, es puramente informativo.
        nodes.forEach { n ->
            Log.d(tag, "listCloudDrive(): nodo h=${n.handle} parent=${n.parentHandle} isFolder=${n.isFolder} name=\"${n.name}\" size=${n.size}")
        }
        return nodes
    }

    /**
     * Descifra la node key de un nodo del propio Cloud Drive.
     *
     * SUPOSICIÓN ANTERIOR INCORRECTA: "k" siempre trae un único par
     * "propioHandle:blobCifrado". Es falso -- cualquier nodo que tenga o
     * haya tenido alguna forma de compartir activa trae un COMPOUND KEY con
     * varios pares "handle:blob" separados por "/", igual que ya se sabía
     * que pasa con carpetas públicas. Confirmado con datos reales: la
     * carpeta raíz del Cloud Drive trae
     * "miHandle:blobA/otroHandle:blobB", y el blob correcto para acceso de
     * cuenta propia es el de "miHandle" (blobA), no el otro.
     *
     * Confirmado línea por línea contra `Node::applykey()` (node.cpp): para
     * acceso de cuenta propia (no vía folder link), hay que recorrer los
     * pares de izquierda a derecha y usar el PRIMERO cuyo handle decodifica
     * a exactamente 8 bytes (USERHANDLE) e igual al propio handle de
     * usuario -- ese par se descifra con la master key de la cuenta. Los
     * demás pares (handles de 6 bytes, de compartir CON otras cuentas) se
     * ignoran acá: no hace falta acceder a nodos compartidos por otros,
     * solo a los propios.
     */
    private fun decryptOwnNodeKey(kField: String, masterKey: ByteArray, ownHandle: ByteArray): ByteArray? {
        for (pair in kField.split("/")) {
            val colonIdx = pair.indexOf(":")
            if (colonIdx < 0) continue
            val handlePart = pair.substring(0, colonIdx)
            val blobPart = pair.substring(colonIdx + 1)
            val handleBytes = try {
                MegaCrypto.megaBase64Decode(handlePart)
            } catch (e: Exception) {
                continue
            }
            if (handleBytes.size == 8 && handleBytes.contentEquals(ownHandle)) {
                return try {
                    MegaCrypto.aesEcbDecrypt(masterKey, MegaCrypto.megaBase64Decode(blobPart))
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Obtiene la URL temporal de descarga de un archivo del propio Cloud
     * Drive (requiere sesión activa). A diferencia de [getDownloadUrl] con
     * link público, acá el handle va como "n" (node handle autenticado) y
     * NO se manda ningún "&n=" de contexto de carpeta: el "sid" ya
     * identifica la cuenta.
     */
    fun getOwnFileDownloadUrl(fileHandle: String): MegaDownloadResponse {
        check(currentSession != null) { "No hay sesión activa; inicia sesión primero con login()" }

        val bodyJson = """[{"a":"g","g":1,"n":"$fileHandle"}]"""
        val responseBody = call(bodyJson)

        val jsonArray = json.parseToJsonElement(responseBody).let { it as JsonArray }
        val firstElement = jsonArray.firstOrNull()
            ?: throw MegaApiException(-1, "Respuesta vacía al pedir descarga del Cloud Drive")

        val downloadResponse = json.decodeFromJsonElement(MegaDownloadResponse.serializer(), firstElement)
        throwIfDownloadError(downloadResponse)
        return downloadResponse
    }

    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

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
