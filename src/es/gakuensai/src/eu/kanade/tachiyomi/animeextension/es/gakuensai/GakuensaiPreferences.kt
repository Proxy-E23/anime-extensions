package eu.kanade.tachiyomi.animeextension.es.gakuensai

import android.content.SharedPreferences

object GakuensaiPreferences {

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)

    const val PREF_NSFW_MODE = "nsfw_mode"
    const val NSFW_MODE_HIDE = "hide"
    const val NSFW_MODE_SHOW_ALL = "show_all"
    const val NSFW_MODE_ONLY = "only"
    private const val PREF_NSFW_MODE_DEFAULT = NSFW_MODE_HIDE

    fun nsfwMode(prefs: SharedPreferences): String = prefs.getString(PREF_NSFW_MODE, PREF_NSFW_MODE_DEFAULT) ?: PREF_NSFW_MODE_DEFAULT
}
