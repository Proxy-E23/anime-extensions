package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject

object GoogleDrivePreferences {

    /** [status] usa los valores de SAnime (UNKNOWN, ONGOING, COMPLETED). */
    data class Entry(val name: String, val url: String, val status: Int = SAnime.UNKNOWN)

    const val PREF_KEY = "googledrive_folder_list"
    const val REMOVE_ENTRY_KEY = "googledrive_remove_entry"

    // Cada línea: "Nombre::URL" o "Nombre::URL::e|c|d" (e = en emisión,
    // c = completado, d = desconocido; también se aceptan o = ongoing, u = unknown).
    private val STATUS_SUFFIX_REGEX = Regex("""(?i)^(.*)::\s*([ecdou])\s*$""")

    private fun statusFromCode(code: String): Int = when (code.lowercase()) {
        "e", "o" -> SAnime.ONGOING
        "c" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun statusToCode(status: Int): String? = when (status) {
        SAnime.ONGOING -> "e"
        SAnime.COMPLETED -> "c"
        else -> null
    }

    fun parseLine(rawLine: String): Entry? {
        val line = rawLine.trim()
        val idx = line.indexOf("::")
        if (idx < 0) return null
        val name = line.substring(0, idx).trim()
        val rest = line.substring(idx + 2).trim()

        val match = STATUS_SUFFIX_REGEX.find(rest)
        val url = (match?.groupValues?.get(1) ?: rest).trim()
        val status = match?.let { statusFromCode(it.groupValues[2]) } ?: SAnime.UNKNOWN

        if (name.isBlank() || url.isBlank()) return null
        return Entry(name, url, status)
    }

    private fun formatLine(entry: Entry): String = buildString {
        append(entry.name).append("::").append(entry.url)
        statusToCode(entry.status)?.let { append("::").append(it) }
    }

    fun getEntries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, "") ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull(::parseLine)
    }

    fun addEntry(prefs: SharedPreferences, name: String, url: String, status: Int = SAnime.UNKNOWN) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = formatLine(Entry(name, url, status))
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    /**
     * Busca si una URL ya está guardada, para evitar agregarla dos veces
     * (ver getSearchAnime en GoogleDrive.kt).
     */
    fun findByUrl(prefs: SharedPreferences, url: String): Entry? = getEntries(prefs).firstOrNull { it.url == url }

    /**
     * Elimina una entrada a partir de la URL o el nombre que el usuario
     * pegue en "Eliminar enlace", por match exacto. Solo borra si el valor
     * identifica una única entrada; si hay ambigüedad, no borra nada.
     * También acepta la línea completa "Nombre::URL" o "Nombre::URL::c".
     */
    fun removeEntryByLine(prefs: SharedPreferences, pasted: String): String {
        val input = pasted.trim()
        val entries = getEntries(prefs)
        // Si pegó una línea completa se usa su URL; si pegó solo la URL (o un nombre), se usa tal cual.
        val inputUrl = parseLine(input)?.url ?: input

        val urlMatches = entries.filter { it.url == inputUrl }
        val nameMatches = entries.filter { it.name == input }

        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = when {
            urlMatches.size == 1 -> current.lines().filterNot { parseLine(it)?.url == inputUrl }.joinToString("\n")
            nameMatches.size == 1 -> current.lines().filterNot { parseLine(it)?.name == input }.joinToString("\n")
            else -> current
        }
        prefs.edit().putString(PREF_KEY, updated).apply()
        return updated
    }

    /**
     * No se usa actualmente en GoogleDrive.kt -- se deja como base para una
     * función futura de renombrado de entradas guardadas. No eliminar.
     */
    fun updateEntryName(prefs: SharedPreferences, url: String, newName: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines().joinToString("\n") { line ->
            val entry = parseLine(line)
            if (entry != null && entry.url == url) formatLine(entry.copy(name = newName)) else line
        }
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)

    // ── Mi Drive (cuenta con sesión iniciada) ────────────────────────────────

    const val PREF_SHOW_DRIVE = "show_drive"
    const val PREF_DRIVE_STATUS = "googledrive_drive_status"

    private const val ACCOUNT_EMAIL_KEY = "googledrive_account_email"

    /** Correo de la cuenta de Google, detectado la última vez que se cargó Mi Drive (null si no se conoce). */
    fun accountEmail(prefs: SharedPreferences): String? = prefs.getString(ACCOUNT_EMAIL_KEY, null)?.takeIf { it.isNotBlank() }

    fun setAccountEmail(prefs: SharedPreferences, email: String?) {
        prefs.edit().apply { if (email == null) remove(ACCOUNT_EMAIL_KEY) else putString(ACCOUNT_EMAIL_KEY, email) }.apply()
    }

    // Desactivado por defecto: depende de la sesión de Google y puede traer muchas carpetas.
    fun showDrive(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_DRIVE, false)

    // Cada línea: "Nombre de la carpeta::e|c|d" (mismos códigos que los enlaces guardados).
    private fun getDriveStatuses(prefs: SharedPreferences): Map<String, Int> {
        val raw = prefs.getString(PREF_DRIVE_STATUS, "") ?: return emptyMap()
        return raw.lines()
            .mapNotNull { line ->
                val match = STATUS_SUFFIX_REGEX.find(line.trim()) ?: return@mapNotNull null
                val name = match.groupValues[1].trim()
                if (name.isBlank()) null else name to statusFromCode(match.groupValues[2])
            }
            .toMap()
    }

    /** Estado de emisión de un elemento de Mi Drive según el nombre de su carpeta (o archivo, sin extensión). */
    fun driveStatusFor(prefs: SharedPreferences, name: String): Int = getDriveStatuses(prefs).entries
        .firstOrNull { (savedName, _) -> name.equals(savedName, ignoreCase = true) }
        ?.value
        ?: SAnime.UNKNOWN

    // ── Cachés en disco ──────────────────────────────────────────────────────
    //
    // Solo se guarda lo estructural (título y URL de cada anime ya armado). El estado
    // de emisión NO se guarda: se lee de las entradas al construir cada anime, para
    // que los cambios en Ajustes se apliquen sin recargar. La lista de episodios
    // nunca se cachea: se lee en vivo al abrir la entrada.

    data class CachedAnime(val title: String, val url: String)

    private const val CACHE_COMPLETED_KEY = "googledrive_cache_completed"
    private const val CACHE_CLOUD_KEY = "googledrive_cache_cloud"
    private const val OWNERS_KEY = "googledrive_item_owners"

    private fun encodeAnimes(list: List<CachedAnime>): JSONArray = JSONArray().also { arr ->
        list.forEach { a -> arr.put(JSONObject().put("t", a.title).put("u", a.url)) }
    }

    private fun decodeAnimes(arr: JSONArray): List<CachedAnime> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CachedAnime(o.getString("t"), o.getString("u"))
    }

    /** Caché de entradas marcadas como completadas. Clave: "<URL guardada>::<nombre guardado>". */
    fun readCompletedCache(prefs: SharedPreferences): Map<String, List<CachedAnime>> {
        val raw = prefs.getString(CACHE_COMPLETED_KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> decodeAnimes(obj.getJSONArray(key)) }
        }.getOrDefault(emptyMap())
    }

    fun writeCompletedCache(prefs: SharedPreferences, map: Map<String, List<CachedAnime>>) {
        val obj = JSONObject()
        map.forEach { (key, list) -> obj.put(key, encodeAnimes(list)) }
        prefs.edit().putString(CACHE_COMPLETED_KEY, obj.toString()).apply()
    }

    fun clearCompletedCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_COMPLETED_KEY).apply()
    }

    /** null si no hay caché (o está dañada), para que el llamador reconstruya. */
    fun readCloudCache(prefs: SharedPreferences): List<CachedAnime>? {
        val raw = prefs.getString(CACHE_CLOUD_KEY, null) ?: return null
        return runCatching { decodeAnimes(JSONArray(raw)) }.getOrNull()
    }

    fun writeCloudCache(prefs: SharedPreferences, list: List<CachedAnime>) {
        prefs.edit().putString(CACHE_CLOUD_KEY, encodeAnimes(list).toString()).apply()
    }

    fun clearCloudCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_CLOUD_KEY).apply()
    }

    // ── Dueño de cada elemento del catálogo ──────────────────────────────────
    //
    // Las URLs de los animes no llevan la entrada guardada de la que salieron (cambiarlas
    // rompería los animes ya agregados a la biblioteca). Para saber a qué entrada pertenece
    // una subcarpeta al abrirla (estado de emisión y aviso de "Eliminar enlace"), se
    // guarda aparte este mapa: id de carpeta/archivo -> URL de la entrada guardada.

    fun readOwners(prefs: SharedPreferences): Map<String, String> {
        val raw = prefs.getString(OWNERS_KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> obj.getString(key) }
        }.getOrDefault(emptyMap())
    }

    fun writeOwners(prefs: SharedPreferences, owners: Map<String, String>) {
        val obj = JSONObject()
        owners.forEach { (itemId, entryUrl) -> obj.put(itemId, entryUrl) }
        prefs.edit().putString(OWNERS_KEY, obj.toString()).apply()
    }
}
