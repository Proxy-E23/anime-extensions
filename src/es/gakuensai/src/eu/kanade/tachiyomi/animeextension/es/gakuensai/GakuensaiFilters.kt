package eu.kanade.tachiyomi.animeextension.es.gakuensai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

// El sitio no tiene filtros reales de género, año, etc. -- solo divide sus series en
// Activos, Finalizados y Otros dentro de /proyectos/. Se usa esa división como filtro de
// categoría; hoy con pocas series no aporta mucho, pero a futuro debería ser útil.
class CategoryFilter :
    AnimeFilter.Select<String>(
        "Categoría",
        OPTIONS,
    ) {
    // state=0 es "Todas" (sin filtrar); el resto corresponde 1:1 con OPTIONS.
    val selectedSection: String? get() = if (state == 0) null else OPTIONS[state]

    companion object {
        val OPTIONS = arrayOf("Todas", GakuensaiFansub.SECTION_ONGOING, GakuensaiFansub.SECTION_COMPLETED, GakuensaiFansub.SECTION_OTHER)
    }
}

class InfoFilter(info: String) : AnimeFilter.Header(info)
