package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.SharedPreferences

object MegaPreferences {

    data class Entry(val name: String, val url: String)

    const val PREF_KEY = "meganz_folder_list"
    const val REMOVE_ENTRY_KEY = "meganz_remove_entry"

    fun getEntries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, "") ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if ("::" !in line) return@mapNotNull null
                val idx = line.indexOf("::")
                val name = line.substring(0, idx).trim()
                val url = line.substring(idx + 2).trim()
                if (name.isBlank() || url.isBlank()) return@mapNotNull null
                Entry(name, url)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, url: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = "$name::$url"
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    /**
     * Busca si una URL ya está guardada, para evitar agregarla dos veces
     * (ver getSearchAnime en Mega.kt).
     */
    fun findByUrl(prefs: SharedPreferences, url: String): Entry? = getEntries(prefs).firstOrNull { it.url == url }

    /**
     * Elimina una entrada a partir de la URL o el nombre que el usuario
     * pegue en "Eliminar enlace", por match exacto. Solo borra si el valor
     * identifica una única entrada; si hay ambigüedad, no borra nada.
     * También acepta la línea completa "Nombre::URL" (compatibilidad con el
     * flujo de entradas con error404).
     */
    fun removeEntryByLine(prefs: SharedPreferences, pasted: String): String {
        val input = pasted.trim()
        val entries = getEntries(prefs)
        val inputUrl = input.substringAfter("::", input).trim()

        val urlMatches = entries.filter { it.url == inputUrl }
        val nameMatches = entries.filter { it.name == input }

        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = when {
            urlMatches.size == 1 -> current.lines().filter { it.substringAfter("::", "").trim() != inputUrl }.joinToString("\n")
            nameMatches.size == 1 -> current.lines().filter { it.substringBefore("::", it).trim() != input }.joinToString("\n")
            else -> current
        }
        prefs.edit().putString(PREF_KEY, updated).apply()
        return updated
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
