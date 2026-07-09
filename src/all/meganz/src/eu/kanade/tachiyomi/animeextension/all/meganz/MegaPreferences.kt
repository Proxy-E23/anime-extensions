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
     * Elimina una entrada por coincidencia exacta de la línea completa
     * "Nombre::URL", tal como se le pide al usuario copiar desde la
     * descripción de una entrada con error. Devuelve el texto actualizado
     * para que la UI (EditTextPreference) pueda refrescarse sin releer prefs.
     */
    fun removeEntryByLine(prefs: SharedPreferences, exactLine: String): String {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val updated = current.lines()
            .filter { it.trim() != exactLine.trim() }
            .joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
        return updated
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
