package eu.kanade.tachiyomi.animeextension.all.gofile

import android.content.SharedPreferences
import aniyomi.lib.gofileextractor.GofileLinkParser
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject

object GofilePreferences {

    /** [status] usa los valores de SAnime (UNKNOWN, ONGOING, COMPLETED). */
    data class Entry(val name: String, val url: String, val status: Int)

    const val PREF_KEY = "gofile_link_list"
    const val REMOVE_ENTRY_KEY = "gofile_remove_entry"

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

    private fun idOf(entry: Entry): String? = GofileLinkParser.parse(entry.url)?.id

    /** Busca si un enlace ya está guardado (por id, sin importar el formato de la URL). */
    fun findById(prefs: SharedPreferences, id: String): Entry? = getEntries(prefs).firstOrNull { idOf(it) == id }

    /**
     * Elimina una entrada por nombre, URL o línea completa. Solo borra si el
     * valor identifica una única entrada; si hay ambigüedad, no borra nada.
     */
    fun removeEntryByLine(prefs: SharedPreferences, pasted: String): String {
        val input = pasted.trim()
        val inputId = GofileLinkParser.parse(input)?.id
        val entries = getEntries(prefs)

        val idMatches = if (inputId != null) entries.filter { idOf(it) == inputId } else emptyList()
        val nameMatches = entries.filter { it.name == input }

        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = when {
            idMatches.size == 1 -> current.lines()
                .filterNot { line -> parseLine(line)?.let { idOf(it) == inputId } == true }
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

    // ── Caché en disco ───────────────────────────────────────────────────────
    //
    // Solo se guarda lo estructural (título y URL de cada anime). El estado de
    // emisión NO se guarda: se calcula al leer, para que los cambios en Ajustes
    // se apliquen sin recargar. La lista de episodios nunca se cachea: se lee en vivo.

    data class CachedAnime(val title: String, val url: String)

    private const val CACHE_PUBLIC_KEY = "cache_public_completed"

    private fun encodeAnimes(list: List<CachedAnime>): JSONArray = JSONArray().also { arr ->
        list.forEach { a -> arr.put(JSONObject().put("t", a.title).put("u", a.url)) }
    }

    private fun decodeAnimes(arr: JSONArray): List<CachedAnime> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CachedAnime(title = o.getString("t"), url = o.getString("u"))
    }

    /** Caché de enlaces marcados como completados. Clave: "<id>::<nombre guardado>". */
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
