package eu.kanade.tachiyomi.animeextension.all.meganz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class UrlFilter : AnimeFilter.Text("URL de archivo o carpeta de MEGA", "")

class NameFilter : AnimeFilter.Text("Nombre (opcional)", "")

class SeparatorFilter : AnimeFilter.Separator()

class InfoFilter(info: String) : AnimeFilter.Header(info)
