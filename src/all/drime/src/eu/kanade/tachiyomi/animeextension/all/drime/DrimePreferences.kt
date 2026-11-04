package eu.kanade.tachiyomi.animeextension.all.drime

import android.content.SharedPreferences
import aniyomi.lib.drimeextractor.DrimeLinkParser
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject

object DrimePreferences {

    /** [status] usa los valores de SAnime (UNKNOWN, ONGOING, COMPLETED). */
    data class Entry(val name: String, val url: String, val status: Int)

    const val PREF_KEY = "drime_link_list"
    const val REMOVE_ENTRY_KEY = "drime_remove_entry"

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

    fun addEntry(prefs: SharedPreferences, name: String, url: String, status: Int) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = formatLine(Entry(name, url, status))
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    private fun hashOf(entry: Entry): String? = DrimeLinkParser.parse(entry.url)?.hash

    /** Busca si un enlace ya está guardado (por hash, sin importar el formato de la URL). */
    fun findByHash(prefs: SharedPreferences, hash: String): Entry? = getEntries(prefs).firstOrNull { hashOf(it) == hash }

    /**
     * Elimina una entrada por nombre, URL o línea completa. Solo borra si el
     * valor identifica una única entrada; si hay ambigüedad, no borra nada.
     */
    fun removeEntryByLine(prefs: SharedPreferences, pasted: String): String {
        val input = pasted.trim()
        val inputHash = DrimeLinkParser.parse(input)?.hash
        val entries = getEntries(prefs)

        val hashMatches = if (inputHash != null) entries.filter { hashOf(it) == inputHash } else emptyList()
        val nameMatches = entries.filter { it.name == input }

        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = when {
            hashMatches.size == 1 -> current.lines()
                .filterNot { line -> parseLine(line)?.let { hashOf(it) == inputHash } == true }
                .joinToString("\n")
            nameMatches.size == 1 -> current.lines()
                .filterNot { line -> parseLine(line)?.name == input }
                .joinToString("\n")
            else -> current
        }
        prefs.edit().putString(PREF_KEY, updated).apply()
        return updated
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)

    // ── Mi Drive (token de API) ──────────────────────────────────────────────

    const val PREF_TOKEN = "drime_api_token"
    const val PREF_SHOW_DRIVE = "show_drive"
    const val PREF_DRIVE_STATUS = "drime_drive_status"

    fun getToken(prefs: SharedPreferences): String = prefs.getString(PREF_TOKEN, "")?.trim().orEmpty()

    fun showDrive(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_DRIVE, true)

    // Cada línea: "Nombre de la carpeta raíz::e|c|d" (mismos códigos que los enlaces guardados).
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

    /**
     * Estado de emisión de un anime de Mi Drive según el nombre de su carpeta raíz.
     * Coincide si el título es igual al nombre o empieza con "Nombre - " (la forma
     * "Carpeta - Subcarpeta"); si varias coinciden, gana el nombre más largo.
     */
    fun driveStatusFor(prefs: SharedPreferences, title: String): Int = getDriveStatuses(prefs).entries
        .filter { (name, _) -> title.equals(name, ignoreCase = true) || title.startsWith("$name - ", ignoreCase = true) }
        .maxByOrNull { it.key.length }
        ?.value
        ?: SAnime.UNKNOWN

    // ── Caché en disco ───────────────────────────────────────────────────────
    //
    // Solo se guarda lo estructural (título, URL y autor de cada anime). El estado
    // de emisión NO se guarda: se calcula al leer, para que los cambios en Ajustes
    // se apliquen sin recargar. La lista de episodios nunca se cachea: se lee en vivo.

    data class CachedAnime(val title: String, val url: String, val author: String?)

    private const val CACHE_DRIVE_KEY = "cache_drive"
    private const val CACHE_PUBLIC_KEY = "cache_public_completed"

    private fun encodeAnimes(list: List<CachedAnime>): JSONArray = JSONArray().also { arr ->
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("t", a.title)
                    .put("u", a.url)
                    .put("a", a.author ?: JSONObject.NULL),
            )
        }
    }

    private fun decodeAnimes(arr: JSONArray): List<CachedAnime> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CachedAnime(
            title = o.getString("t"),
            url = o.getString("u"),
            author = if (o.isNull("a")) null else o.getString("a"),
        )
    }

    /** null si no hay caché (o está dañada), para que el llamador reconstruya. */
    fun readDriveCache(prefs: SharedPreferences): List<CachedAnime>? {
        val raw = prefs.getString(CACHE_DRIVE_KEY, null) ?: return null
        return runCatching { decodeAnimes(JSONArray(raw)) }.getOrNull()
    }

    fun writeDriveCache(prefs: SharedPreferences, list: List<CachedAnime>) {
        prefs.edit().putString(CACHE_DRIVE_KEY, encodeAnimes(list).toString()).apply()
    }

    fun clearDriveCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_DRIVE_KEY).apply()
    }

    /** Caché de enlaces públicos marcados como completados. Clave: "<hash>::<nombre guardado>". */
    fun readPublicCache(prefs: SharedPreferences): Map<String, List<CachedAnime>> {
        val raw = prefs.getString(CACHE_PUBLIC_KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> decodeAnimes(obj.getJSONArray(key)) }
        }.getOrDefault(emptyMap())
    }

    fun writePublicCache(prefs: SharedPreferences, map: Map<String, List<CachedAnime>>) {
        val obj = JSONObject()
        map.forEach { (key, list) -> obj.put(key, encodeAnimes(list)) }
        prefs.edit().putString(CACHE_PUBLIC_KEY, obj.toString()).apply()
    }

    fun clearPublicCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_PUBLIC_KEY).apply()
    }
}
