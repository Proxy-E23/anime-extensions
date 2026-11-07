package eu.kanade.tachiyomi.animeextension.all.mediafire

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject

object MediaFirePreferences {

    /** [status] usa los valores de SAnime (UNKNOWN, ONGOING, COMPLETED). */
    data class Entry(val name: String, val url: String, val status: Int = SAnime.UNKNOWN)

    const val PREF_KEY = "mediafire_folder_list"
    const val REMOVE_ENTRY_KEY = "mediafire_remove_entry"

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

    /** Busca si una URL ya está guardada, para evitar duplicados al agregar. */
    fun findByUrl(prefs: SharedPreferences, url: String): Entry? = getEntries(prefs).firstOrNull { it.url == url }

    /**
     * Elimina una entrada por nombre o URL exactos. Solo borra si el valor
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

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)

    // ── Caché en disco de carpetas completadas ───────────────────────────────
    //
    // Solo se guarda lo estructural (título y URL de cada anime ya expandido).
    // El estado de emisión NO se guarda: se lee de las entradas al construir
    // cada anime, para que los cambios en Ajustes se apliquen sin recargar.
    // La lista de episodios nunca se cachea: se lee en vivo.

    data class CachedAnime(val title: String, val url: String)

    private const val CACHE_COMPLETED_KEY = "mediafire_cache_completed"

    /** Caché de carpetas marcadas como completadas. Clave: "<folderKey>::<nombre guardado>". */
    fun readCompletedCache(prefs: SharedPreferences): Map<String, List<CachedAnime>> {
        val raw = prefs.getString(CACHE_COMPLETED_KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.getJSONArray(key)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CachedAnime(o.getString("t"), o.getString("u"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun writeCompletedCache(prefs: SharedPreferences, map: Map<String, List<CachedAnime>>) {
        val obj = JSONObject()
        map.forEach { (key, list) ->
            val arr = JSONArray()
            list.forEach { a -> arr.put(JSONObject().put("t", a.title).put("u", a.url)) }
            obj.put(key, arr)
        }
        prefs.edit().putString(CACHE_COMPLETED_KEY, obj.toString()).apply()
    }

    fun clearCompletedCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_COMPLETED_KEY).apply()
    }
}
