package eu.kanade.tachiyomi.animeextension.all.collectednotes

import android.content.SharedPreferences

object CollectedNotesPreferences {

    data class Entry(val name: String, val sitePath: String)

    const val PREF_KEY = "collectednotes_site_list"
    const val REMOVE_ENTRY_KEY = "collectednotes_remove_entry"

    fun getEntries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, "") ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if ("::" !in line) return@mapNotNull null
                val idx = line.indexOf("::")
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 2).trim()
                if (name.isBlank() || value.isBlank()) return@mapNotNull null
                // Soporta tanto URL completa como solo sitePath
                val sitePath = value
                    .removePrefix("https://collectednotes.com/")
                    .removePrefix("http://collectednotes.com/")
                    .trim()
                Entry(name, sitePath)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, url: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = "$name::$url"
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    /**
     * Busca si un sitio ya está guardado, para evitar agregarlo dos veces
     * (ver getSearchAnime en CollectedNotesSrc.kt).
     */
    fun findBySitePath(prefs: SharedPreferences, sitePath: String): Entry? = getEntries(prefs).firstOrNull { it.sitePath == sitePath }

    /**
     * Elimina una entrada a partir de la URL o el nombre que el usuario
     * pegue en "Eliminar fansub", por match exacto. Solo borra si el valor
     * identifica una única entrada; si hay ambigüedad, no borra nada.
     */
    fun removeEntryByLine(prefs: SharedPreferences, pasted: String): String {
        val input = pasted.trim()
        val entries = getEntries(prefs)
        val inputSitePath = input.substringAfter("::", input).trim()
            .removePrefix("https://collectednotes.com/")
            .removePrefix("http://collectednotes.com/")
            .trim()

        val siteMatches = entries.filter { it.sitePath == inputSitePath }
        val nameMatches = entries.filter { it.name == input }

        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = when {
            siteMatches.size == 1 -> current.lines().filter { line ->
                val value = line.substringAfter("::", "").trim()
                    .removePrefix("https://collectednotes.com/")
                    .removePrefix("http://collectednotes.com/")
                    .trim()
                value != inputSitePath
            }.joinToString("\n")
            nameMatches.size == 1 -> current.lines().filter { it.substringBefore("::", it).trim() != input }.joinToString("\n")
            else -> current
        }
        prefs.edit().putString(PREF_KEY, updated).apply()
        return updated
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = true

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
