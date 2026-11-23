package aniyomi.lib.gofileextractor

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class GofileApi(
    client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
    private val apiUrl: String,
) {

    companion object {
        // Gofile exige un header X-Website-Token calculado con un valor fijo del
        // frontend web. Si Gofile lo cambia, la API responde con error y este es
        // el único valor que hay que actualizar (ver README de la lib).
        private const val WEBSITE_TOKEN_SALT = "12af056dacea0b"

        // La ventana del token cambia cada 4 horas.
        private const val WEBSITE_TOKEN_SLOT_SECONDS = 14_400L

        private const val LANGUAGE = "en-US"

        // Estados de error de la API que significan "no se puede leer sin sesión
        // o con esta cuenta": se tratan como enlace inaccesible, no como fallo.
        private val INACCESSIBLE_STATUSES = setOf(
            "error-notFound",
            "error-notPublic",
            "error-passwordRequired",
            "error-passwordWrong",
        )
    }

    // Gofile puede tardar varios segundos por petición: timeouts holgados.
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val userAgent: String get() = headers["User-Agent"] ?: "Mozilla/5.0"

    // Cuenta de invitado: se crea una vez y se reutiliza; los enlaces de descarga
    // quedan ligados a esta cuenta, por eso el mismo token se usa al reproducir.
    @Volatile
    private var guestToken: String? = null

    private fun websiteToken(accountToken: String): String {
        val slot = System.currentTimeMillis() / 1000L / WEBSITE_TOKEN_SLOT_SECONDS
        val raw = "$userAgent::$LANGUAGE::$accountToken::$slot::$WEBSITE_TOKEN_SALT"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun apiHeaders(accountToken: String): Headers = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .set("X-BL", LANGUAGE)
        .set("X-Website-Token", websiteToken(accountToken))
        .apply { if (accountToken.isNotEmpty()) set("Authorization", "Bearer $accountToken") }
        .build()

    @Synchronized
    fun token(forceNew: Boolean = false): String {
        guestToken?.takeIf { !forceNew }?.let { return it }

        val request = POST("$apiUrl/accounts", apiHeaders(""), FormBody.Builder().build())
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("No se pudo crear la sesión de Gofile (HTTP ${response.code})")
            response.body.string()
        }

        val parsed = body.parseAs<GofileAccountResponse>()
        val token = parsed.data?.token?.takeIf { parsed.status == "ok" && it.isNotBlank() }
            ?: throw IOException("Gofile rechazó la creación de sesión (${parsed.status.ifBlank { "sin estado" }})")

        guestToken = token
        return token
    }

    /**
     * Lee un contenido (carpeta o archivo) por su id. Devuelve null si no existe,
     * no es público o requiere contraseña; lanza [IOException] ante cualquier otro
     * fallo. Si la sesión de invitado dejó de ser válida, la renueva una vez.
     */
    fun fetchContent(contentId: String): GofileContent? {
        var lastStatus = ""

        for (attempt in 0..1) {
            val token = token(forceNew = attempt > 0)
            val url = "$apiUrl/contents/$contentId?cache=true&sortField=createTime&sortDirection=1"

            val (code, body) = http.newCall(GET(url, apiHeaders(token))).execute().use { response ->
                response.code to response.body.string()
            }

            val parsed = runCatching { body.parseAs<GofileResponse>() }.getOrNull()
            lastStatus = parsed?.status?.ifBlank { null } ?: "HTTP $code"

            when {
                parsed?.status == "ok" -> return parsed.data?.takeIf { it.isReadable() }?.toContent()
                parsed?.status in INACCESSIBLE_STATUSES -> return null
                code == 404 || code == 410 -> return null
                // Cualquier otro error en el primer intento: se renueva la sesión y se reintenta.
            }
        }

        throw IOException("Gofile respondió con error ($lastStatus)")
    }

    private fun GofileDataDto.isReadable(): Boolean = canAccess && (passwordStatus == null || passwordStatus == "passwordOk")

    private fun GofileDataDto.toNode() = GofileNode(
        id = id,
        name = name,
        type = type,
        mimetype = mimetype,
        size = size,
        created = createTime,
        link = link?.takeIf { it.isNotBlank() },
    )

    private fun GofileDataDto.toContent(): GofileContent {
        val root = toNode()
        val children = if (root.isFolder) {
            children.values.map { it.toNode() }
        } else {
            emptyList()
        }
        return GofileContent(root, children)
    }
}
