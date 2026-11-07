package eu.kanade.tachiyomi.animeextension.all.meganz

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject

object MegaPreferences {

    /** [status] usa los valores de SAnime (UNKNOWN, ONGOING, COMPLETED). */
    data class Entry(val name: String, val url: String, val status: Int = SAnime.UNKNOWN)

    const val PREF_KEY = "meganz_folder_list"
    const val REMOVE_ENTRY_KEY = "meganz_remove_entry"

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
     * (ver getSearchAnime en Mega.kt).
     */
    fun findByUrl(prefs: SharedPreferences, url: String): Entry? = getEntries(prefs).firstOrNull { it.url == url }

    /**
     * Elimina una entrada a partir de la URL o el nombre que el usuario
     * pegue en "Eliminar enlace", por match exacto. Solo borra si el valor
     * identifica una única entrada; si hay ambigüedad, no borra nada.
     * También acepta la línea completa "Nombre::URL" o "Nombre::URL::c"
     * (compatibilidad con el flujo de entradas con error404).
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

    // ── Preferencia: email de MEGA recordado ──────────────────────────────────
    //
    // Solo el email (para no tener que retipearlo cada vez); la contraseña
    // JAMÁS se guarda acá ni en ningún otro lado -- se usa una sola vez, en
    // memoria, para derivar la sesión (ver MegaSessionStore en la librería).

    const val PREF_MEGA_EMAIL = "meganz_account_email"

    fun getSavedEmail(prefs: SharedPreferences): String = prefs.getString(PREF_MEGA_EMAIL, "") ?: ""

    // ── Preferencia: mostrar el Cloud Drive en el catálogo ───────────────────

    const val PREF_SHOW_CLOUD_DRIVE = "meganz_show_cloud_drive"

    fun showCloudDrive(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_CLOUD_DRIVE, true)

    // ── Caché en disco ───────────────────────────────────────────────────────
    //
    // Solo se guarda lo estructural. El estado de emisión NO se guarda: se lee
    // de las entradas al construir cada anime, para que los cambios en Ajustes
    // se apliquen sin recargar. Los episodios nunca se cachean: se leen en vivo.

    private const val CACHE_CLOUD_KEY = "meganz_cache_cloud_drive"
    private const val CACHE_VERIFIED_KEY = "meganz_cache_verified_folders"

    /** Una entrada del Cloud Drive; [url] es el MegaLinkData ya serializado y [isRoot] marca los videos sueltos de la raíz. */
    data class CloudEntry(val title: String, val url: String, val isRoot: Boolean)

    /**
     * Devuelve la caché del Cloud Drive, o null si no hay, está dañada o
     * pertenece a otra cuenta: el email va dentro para que un cambio de
     * sesión (también desde otra extensión que use la misma librería)
     * invalide la caché sola.
     */
    fun readCloudCache(prefs: SharedPreferences, email: String): List<CloudEntry>? {
        val raw = prefs.getString(CACHE_CLOUD_KEY, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (!obj.getString("e").equals(email, ignoreCase = true)) return null
            val items = obj.getJSONArray("items")
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                CloudEntry(o.getString("t"), o.getString("u"), o.getBoolean("r"))
            }
        }.getOrNull()
    }

    fun writeCloudCache(prefs: SharedPreferences, email: String, entries: List<CloudEntry>) {
        val items = JSONArray()
        entries.forEach { e ->
            items.put(JSONObject().put("t", e.title).put("u", e.url).put("r", e.isRoot))
        }
        val obj = JSONObject().put("e", email).put("items", items)
        prefs.edit().putString(CACHE_CLOUD_KEY, obj.toString()).apply()
    }

    fun clearCloudCache(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_CLOUD_KEY).apply()
    }

    /**
     * URLs de carpetas marcadas como completadas que ya se comprobaron vivas.
     * Mientras estén aquí no se vuelven a listar al abrir el catálogo.
     */
    fun readVerifiedFolders(prefs: SharedPreferences): Set<String> = prefs.getStringSet(CACHE_VERIFIED_KEY, null)?.toSet() ?: emptySet()

    fun writeVerifiedFolders(prefs: SharedPreferences, urls: Set<String>) {
        prefs.edit().putStringSet(CACHE_VERIFIED_KEY, urls.toSet()).apply()
    }

    fun clearVerifiedFolders(prefs: SharedPreferences) {
        prefs.edit().remove(CACHE_VERIFIED_KEY).apply()
    }
}
