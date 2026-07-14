package aniyomi.lib.filenameutils

/**
 * Utilidades para analizar nombres de archivo de episodios (video) y
 * extraer/ordenar por su número de episodio.
 *
 * Pensada para reusarse desde cualquier extensión que liste archivos por
 * nombre (MEGA, Google Drive, servidores HTTP simples, etc.) donde el
 * nombre del archivo es la única fuente de verdad sobre qué episodio es.
 *
 * Ejemplos reales que esta librería debe resolver correctamente
 * (verificados contra nombres reales de usuario):
 *   - "[FS] Rakudai Kishi no Cavalry 05 (BD 1920x1080 x264 AAC) [35E5A43B].mp4" -> 5
 *   - "[HaibaneSubs]Ranma_1-2_(2024)-_EP12_-_[1080p].mkv" -> 12
 *   - "[HaibaneSubs]Ao_no_Hako_-_EP20_-_[1080p].mkv" -> 20
 *   - "[HaibaneSubs] Ragna Crimson - 13.mkv" -> 13
 *   - "[HaibaneSubs] OP NCOP [1080p].mkv" -> null (sin número real de episodio)
 */
object FilenameUtils {

    // Contenido entre corchetes o paréntesis: casi siempre son tags de
    // fansub, resolución, codec, hash o año -- nunca el número de episodio
    // en sí. Se quita ANTES de buscar el número para no confundir, por
    // ejemplo, la resolución "1920x1080" o un hash "35E5A43B" con el
    // episodio real.
    private val BRACKETED_CONTENT_REGEX = Regex("""\[[^\[\]]*]|\([^()]*\)""")

    // Patrón explícito de episodio: EP12, E12, Episode 12, Ep. 12,
    // Capitulo 12, Cap 12, Cap. 12 (con o sin espacio entre la etiqueta y
    // el número, y sin distinguir mayúsculas/minúsculas). Se prueba primero
    // porque es más confiable que "el último número del nombre": evita que
    // un número que forma parte del título de la serie (p.ej. el "1-2" de
    // "Ranma 1/2") se confunda con el número de episodio.
    private val EXPLICIT_EPISODE_REGEX = Regex(
        """(?:episode|ep|cap[ií]tulo|cap)\.?\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    // Etiquetas que NO son episodios numerados aunque tengan un dígito
    // pegado, cuando aparecen como la ÚLTIMA palabra con dígitos del
    // nombre (tras limpiar corchetes/paréntesis):
    //   - "OP2", "ED2", "NCOP", "NCED": openings/endings y sus variantes
    //     "sin créditos" (NC = "non-credit").
    //   - "v2", "v3": número de versión de un release (re-encode/fix),
    //     NO el número de episodio. Ej: "Serie - 2 v2.mkv" es el episodio
    //     2, versión 2 -- no el episodio "2v2" ni la versión 2 a secas.
    private val NON_EPISODE_TAG_REGEX = Regex(
        """^(?:(?:nc)?(?:op|ed)\d*|v\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    // Último número suelto del nombre, usado solo como respaldo cuando no
    // hay ningún patrón explícito de episodio (p.ej. "Ragna Crimson - 13").
    private val TRAILING_NUMBER_REGEX = Regex("""(\d+)(?!.*\d)""")

    /**
     * Extrae el número de episodio de un nombre de archivo.
     *
     * @return el número de episodio como [Float] (para soportar episodios
     *   parciales tipo "12.5"), o `null` si el nombre no contiene ningún
     *   número identificable como episodio.
     */
    fun extractEpisodeNumber(rawName: String): Float? {
        // Colapsa cualquier whitespace (incluyendo saltos de línea sueltos
        // que a veces se cuelan al copiar/pegar o desde un JSON mal
        // formado) a un solo espacio, para que los regex de arriba no
        // dependan de que el nombre venga en una sola línea limpia.
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()

        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")

        EXPLICIT_EPISODE_REGEX.find(withoutBrackets)?.let {
            return it.groupValues[1].toFloatOrNull()
        }

        // Si no hubo patrón explícito, se cae al último número suelto que
        // quede tras limpiar corchetes/paréntesis (cubre nombres simples
        // como "Serie - 13.mkv" sin literal "EP"). Antes de eso, se separa
        // el nombre en "palabras" (separadas por espacio, guion o guion
        // bajo) para poder ignorar, de atrás hacia adelante, cualquier
        // palabra que sea en realidad un tag de OP/ED o de versión de
        // release con número pegado (p.ej. "OP2", "NCED2", "v2") -- ninguna
        // de esas cuenta como número de episodio, aunque sea la última
        // palabra con dígitos del nombre.
        val withoutExtension = withoutBrackets.substringBeforeLast('.')
        val words = withoutExtension.split(Regex("""[\s_-]+""")).filter { it.isNotBlank() }
        val wordsWithDigits = words.filter { it.any(Char::isDigit) }
        val realEpisodeWord = wordsWithDigits.lastOrNull { !NON_EPISODE_TAG_REGEX.matches(it) }
            ?: return null

        return TRAILING_NUMBER_REGEX.find(realEpisodeWord)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Extrae el último número suelto del nombre (tras limpiar corchetes y
     * paréntesis), SIN aplicar el filtro de tags no-episodio (OP/ED/v2) que
     * sí aplica [extractEpisodeNumber].
     *
     * Pensada para cuando el llamador ya identificó por su cuenta que el
     * archivo es un especial (OP, ED, OVA...) y solo necesita el número de
     * versión pegado a esa etiqueta -- p.ej. para "OP2.mkv" devolver 2 (y
     * poder mostrar "OP 2"), en vez de `null` como haría
     * [extractEpisodeNumber] (que trata "OP2" como "sin número de episodio
     * real" a propósito, para no chocar con el conteo de trackers).
     */
    fun extractTrailingNumber(rawName: String): Float? {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")
        val withoutExtension = withoutBrackets.substringBeforeLast('.')
        return TRAILING_NUMBER_REGEX.find(withoutExtension)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Ordena una lista de nombres/items de episodio de forma descendente
     * por número de episodio (el más alto primero).
     *
     * Los items SIN número identificable (openings, endings, specials
     * sueltos, karaokes) se colocan al PRINCIPIO de la lista resultante,
     * antes incluso del episodio con número más alto -- decisión explícita
     * para que nunca ocupen ni desplacen el puesto de un episodio real
     * (p.ej. "Episodio 1") ante trackers como AniList/MAL, que cuentan por
     * posición/número, no por nombre.
     *
     * @param items la lista a ordenar
     * @param nameSelector función para obtener el nombre de archivo de cada item
     */
    fun <T> sortByEpisodeNumberDescending(items: List<T>, nameSelector: (T) -> String): List<T> = items.sortedWith(
        compareByDescending<T> { extractEpisodeNumber(nameSelector(it)) == null }
            .thenByDescending { extractEpisodeNumber(nameSelector(it)) ?: Float.NEGATIVE_INFINITY },
    )
}
