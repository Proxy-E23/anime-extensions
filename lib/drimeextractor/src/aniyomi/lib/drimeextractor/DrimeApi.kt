package aniyomi.lib.drimeextractor

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class DrimeApi(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {

    companion object {
        private const val MAX_PAGES = 50
    }

    // Drime puede tardar varios segundos por petición: timeouts holgados.
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val apiUrl get() = "$baseUrl/api/v1"

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .build()
    }

    /**
     * Lee un enlace compartido. Con [folderHash] lee esa subcarpeta del
     * enlace (la API la direcciona como `<hash del enlace>:<hash de la carpeta>`).
     * En ese caso [DrimeShare.root] es la subcarpeta y [DrimeShare.children] su contenido.
     *
     * Devuelve null si el enlace o la carpeta no existen, fueron revocados o
     * no son accesibles sin sesión (403/404/410); lanza [IOException] ante
     * cualquier otro fallo de red o de servidor.
     */
    fun fetchShare(hash: String, folderHash: String? = null): DrimeShare? {
        val target = if (folderHash.isNullOrBlank()) hash else "$hash:$folderHash"
        val baseRequest = "$apiUrl/shareable-links/$target?withEntries=true"
        val firstBody = getJson(baseRequest) ?: return null

        val first = json.decodeFromString<DrimeShareRoot>(firstBody)
        val link = first.link ?: return null
        val entry = link.entry ?: return null
        if (link.revoked_at != null) return null

        val root = entry.toNode()
        val children = mutableListOf<DrimeNode>()

        if (root.isFolder) {
            val seen = HashSet<Long>()
            var page = 1
            var parsed = first
            while (true) {
                val pageData = parsed.folderChildren.toChildrenPage()
                var added = 0
                pageData?.data?.forEach { child ->
                    if (seen.add(child.id)) {
                        children += child.toNode()
                        added++
                    }
                }
                val next = pageData?.next_page
                if (next == null || added == 0 || page >= MAX_PAGES) break
                page = next
                val body = getJson("$baseRequest&page=$page") ?: break
                parsed = json.decodeFromString<DrimeShareRoot>(body)
            }
        }

        return DrimeShare(
            linkId = link.id,
            hash = link.hash.ifBlank { hash },
            owner = link.user?.display_name?.takeIf { it.isNotBlank() },
            root = root,
            children = children,
        )
    }

    /**
     * Resuelve la URL directa de un archivo de un enlace compartido.
     * La ruta de descarga responde con un 302 a una URL firmada del
     * almacenamiento (válida 24 h, acepta Range), así que no se siguen
     * las redirecciones: se lee el header Location.
     */
    fun resolveDirectUrl(shareHash: String, linkId: Long, entryHash: String): String {
        val url = "$apiUrl/file-entries/download/$entryHash?shareable_link=$linkId"
        val requestHeaders = apiHeaders.newBuilder()
            .set("Referer", "$baseUrl/drive/s/$shareHash")
            .set("Origin", baseUrl)
            .build()

        val noRedirectClient = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        noRedirectClient.newCall(GET(url, requestHeaders)).execute().use { response ->
            if (response.isRedirect) {
                response.header("Location")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            throw IOException("No se pudo obtener el enlace de reproducción (HTTP ${response.code})")
        }
    }

    private fun authHeaders(token: String): Headers = apiHeaders.newBuilder()
        .set("Authorization", "Bearer $token")
        .build()

    /**
     * Lista una carpeta de la cuenta del usuario con su token de API
     * ([folderHash] null = raíz del Drive). Devuelve null si la carpeta ya no existe;
     * lanza [IOException] con un mensaje claro si el token es inválido o sin permiso.
     */
    fun listDrive(token: String, folderHash: String?): DrimeFolderListing? {
        val requestHeaders = authHeaders(token)
        val folderParam = if (folderHash.isNullOrBlank()) "" else "&folderId=$folderHash"
        val children = mutableListOf<DrimeNode>()
        val seen = HashSet<Long>()
        var root: DrimeNode? = null
        var page = 1

        while (true) {
            val url = "$apiUrl/drive/file-entries?workspaceId=0&perPage=50&orderBy=name&orderDir=asc$folderParam&page=$page"
            val body = getAuthedJson(url, requestHeaders) ?: return null
            val parsed = json.decodeFromString<DrimeEntriesPage>(body)
            if (root == null) root = parsed.folder?.toNode()

            var added = 0
            parsed.data.forEach { child ->
                if (seen.add(child.id)) {
                    children += child.toNode()
                    added++
                }
            }
            if (parsed.current_page >= parsed.last_page || added == 0 || page >= MAX_PAGES) break
            page++
        }

        return DrimeFolderListing(root, children)
    }

    /** Igual que [resolveDirectUrl], pero para un archivo de la cuenta del usuario (con token). */
    fun resolveDriveUrl(token: String, entryHash: String): String {
        val url = "$apiUrl/file-entries/download/$entryHash"

        val noRedirectClient = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        noRedirectClient.newCall(GET(url, authHeaders(token))).execute().use { response ->
            if (response.isRedirect) {
                response.header("Location")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            if (response.code == 401) throw IOException("Token de Drime inválido o eliminado (HTTP 401)")
            throw IOException("No se pudo obtener el enlace de reproducción (HTTP ${response.code})")
        }
    }

    private fun getAuthedJson(url: String, requestHeaders: Headers): String? = http.newCall(GET(url, requestHeaders)).execute().use { response ->
        when {
            response.code == 401 -> throw IOException("Token de Drime inválido o eliminado (HTTP 401)")
            response.code == 403 -> throw IOException("El token no tiene permiso para leer archivos (HTTP 403)")
            response.code in listOf(404, 410) -> null
            !response.isSuccessful -> throw IOException("Drime respondió HTTP ${response.code}")
            else -> response.body.string()
        }
    }

    private fun getJson(url: String): String? = http.newCall(GET(url, apiHeaders)).execute().use { response ->
        when {
            response.code in listOf(403, 404, 410) -> null
            !response.isSuccessful -> throw IOException("Drime respondió HTTP ${response.code}")
            else -> response.body.string()
        }
    }

    private fun JsonElement?.toChildrenPage(): DrimeChildrenPage? = (this as? JsonObject)
        ?.let { json.decodeFromJsonElement<DrimeChildrenPage>(it) }

    private fun DrimeEntryDto.toNode() = DrimeNode(
        id = id,
        hash = hash,
        name = name,
        type = type,
        extension = (extension ?: name.substringAfterLast('.', "")).lowercase(),
        size = file_size ?: 0L,
        created = created_at.orEmpty(),
    )
}
