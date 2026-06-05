package eu.kanade.tachiyomi.animeextension.all.mediafire

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class UrlFilter : AnimeFilter.Text("URL de carpeta o archivo MediaFire", "")

class NameFilter : AnimeFilter.Text("Nombre (editable)", "")

class SeparatorFilter : AnimeFilter.Separator()

class InfoFilter(info: String) : AnimeFilter.Header(info)
