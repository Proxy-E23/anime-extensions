package eu.kanade.tachiyomi.animeextension.all.drime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class UrlFilter : AnimeFilter.Text("URL de carpeta o archivo compartido de Drime", "")

class NameFilter : AnimeFilter.Text("Nombre (editable)", "")

// El orden coincide con SAnime.UNKNOWN (0), ONGOING (1) y COMPLETED (2): el índice es el estado.
class StatusFilter :
    AnimeFilter.Select<String>(
        "Estado de emisión",
        arrayOf("Desconocido", "En emisión", "Completado"),
    )

class SeparatorFilter : AnimeFilter.Separator()

class InfoFilter(info: String) : AnimeFilter.Header(info)
