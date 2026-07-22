package eu.kanade.tachiyomi.animeextension.es.wingzeroplus

import android.content.SharedPreferences

object WingZeroPlusPreferences {

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
