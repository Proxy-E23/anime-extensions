package eu.kanade.tachiyomi.animeextension.es.elabismodedarkrai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class CategoryFilter(private val options: List<Pair<String, String>>) :
    AnimeFilter.Select<String>(
        "Categoría",
        options.map { it.first }.toTypedArray(),
        0,
    ) {
    fun getValue(): String = options[state].second
}
