package eu.kanade.tachiyomi.animeextension.es.vampirnofansub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class TypeFilter :
    AnimeFilter.Select<String>(
        "Tipo",
        arrayOf("Series (BD)", "Películas"),
    ) {
    fun toUriPart(): String = if (state == 1) "movies" else "series"
}

class InfoFilter(info: String) : AnimeFilter.Header(info)
