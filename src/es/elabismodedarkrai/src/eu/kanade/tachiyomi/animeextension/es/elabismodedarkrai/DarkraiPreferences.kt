package eu.kanade.tachiyomi.animeextension.es.elabismodedarkrai

import android.content.SharedPreferences

object DarkraiPreferences {

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
