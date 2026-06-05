package eu.kanade.tachiyomi.animeextension.all.mediafire

import android.content.SharedPreferences

object MediaFirePreferences {

    data class Entry(val name: String, val key: String)

    private const val PREF_KEY = "mediafire_folder_list"

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

                val resolvedKey = when {
                    value.startsWith("file::") -> value
                    "mediafire.com/folder" in value -> {
                        Regex("mediafire\\.com/folder/([A-Za-z0-9]+)").find(value)
                            ?.groupValues?.get(1) ?: return@mapNotNull null
                    }
                    value.all { it.isLetterOrDigit() } -> value
                    else -> return@mapNotNull null
                }

                MediaFirePreferences.Entry(name, resolvedKey)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, key: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = if (key.startsWith("file::")) {
            "$name::$key"
        } else {
            "$name::https://www.mediafire.com/folder/$key"
        }
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    fun removeEntry(prefs: SharedPreferences, key: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines()
            .filter { key !in it }
            .joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    fun updateEntryName(prefs: SharedPreferences, key: String, newName: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines().map { line ->
            if (key in line) "$newName::$key" else line
        }.joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = true

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
