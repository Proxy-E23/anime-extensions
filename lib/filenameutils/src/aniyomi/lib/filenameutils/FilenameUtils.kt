package aniyomi.lib.filenameutils

/**
 * Utilidades para analizar nombres de archivo de episodios (video) y
 * extraer/ordenar por su número de episodio.
 *
 * Pensada para reutilizarse desde cualquier extensión que liste archivos
 * por nombre (MEGA, Google Drive, servidores HTTP simples, etc.) donde el
 * nombre del archivo es la única fuente de información sobre qué episodio
 * es.
 *
 * Ver README.md (en esta misma carpeta) para casos de uso, ejemplos y guía
 * de cuándo usar cada función.
 */
object FilenameUtils {

    // Contenido entre corchetes/paréntesis/llaves: tags de fansub,
    // resolución, códec, hash, etc. -- se limpia antes de buscar el número.
    // Apertura y cierre se aceptan cruzados ([...}, (...], etc.) porque en
    // releases reales es común un tag mal cerrado (ej. "[1080p x264 AAC}"
    // en vez de "]"), que si no se tolera deja ese tag entero sin limpiar y
    // permite que texto como "x264" se cuele como candidato a episodio.
    private val BRACKETED_CONTENT_REGEX = Regex("""[\[{(][^\[\]{}()]*[]})]""")

    // Patrón explícito: EP12, Episode 12, Capítulo 12, etc. Se prueba
    // primero por ser más confiable que "el último número del nombre".
    private val EXPLICIT_EPISODE_REGEX = Regex(
        """(?:episode|ep|cap[ií]tulo|cap)\.?\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    // Palabras que NO son el número de episodio aunque tengan un dígito
    // pegado (OP2, NCED, IS02, v2...) -- openings/endings/insert songs y
    // números de versión de un release, no del episodio.
    private val NON_EPISODE_TAG_REGEX = Regex(
        """^(?:(?:nc)?(?:op|ed)\d*|isong\d*|is\d*|v\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    // Sufijo de versión pegado sin separador al número real (ej. "16v2" =
    // episodio 16, versión 2). Sin esto, "2" ganaría por ser el último
    // dígito de la palabra.
    private val ATTACHED_VERSION_SUFFIX_REGEX = Regex(
        """^(\d+)v\d+$""",
        RegexOption.IGNORE_CASE,
    )

    // Conector "parte/part" (ej. "12 Fin parte 1" = episodio 12, parte 1,
    // no episodio 1): el número que lo sigue se descarta como episodio.
    private val PART_CONNECTOR_REGEX = Regex(
        """^(?:parte|part|pt)\.?$""",
        RegexOption.IGNORE_CASE,
    )

    // Último número suelto del nombre, como respaldo si no hay patrón
    // explícito. Admite decimal pegado (ej. "12.5") para episodios parciales.
    private val TRAILING_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)(?!.*\d)""")

    // Fallback de último recurso para nombres que ponen el episodio DENTRO
    // de un paréntesis (ej. "(Cap.24)"), que normalmente se descarta entero
    // por BRACKETED_CONTENT_REGEX antes de llegar a EXPLICIT_EPISODE_REGEX.
    // Deliberadamente estricto (solo variantes de "cap"/"capítulo", nunca
    // "episode"/"ep") para no capturar texto ambiguo de otros tags que
    // también viven entre paréntesis (resolución, códec, hash, etc.).
    private val PARENTHESIZED_CAP_REGEX = Regex(
        """\(\s*cap[ií]tulo\.?\s*(\d+)\s*\)|\(\s*cap\.?\s*(\d+)\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    // Número de temporada: Temporada 2, Temp 2, T2, Season 2, S2, Cour 2,
    // Cour2. "T"/"S" van SIN espacio antes del número (T2, S2) porque una
    // letra suelta seguida de espacio y número es demasiado ambigua (ej.
    // "T 2" podría ser parte de cualquier otra cosa); las formas largas
    // (temporada/temp/season/cour) sí admiten espacio o punto.
    private val SEASON_REGEX = Regex(
        """\b(?:temporada|temp\.?|season|cour)\.?\s*(\d+)\b|\b[ts](\d+)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extrae el número de temporada mencionado en el nombre, si lo hay.
     *
     * @return el número de temporada, o `null` si no se menciona ninguna
     *   (lo que [sortBySeasonAndEpisodeDescending] interpreta como
     *   Temporada 1 implícita).
     */
    fun extractSeasonNumber(rawName: String): Int? {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")
        val match = SEASON_REGEX.find(withoutBrackets) ?: return null
        return (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
    }

    /**
     * Extrae el número de episodio de un nombre de archivo.
     *
     * @return el número como [Float] (soporta episodios parciales tipo
     *   "12.5"), o `null` si no hay ningún número identificable.
     */
    fun extractEpisodeNumber(rawName: String): Float? {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")

        EXPLICIT_EPISODE_REGEX.find(withoutBrackets)?.let {
            return it.groupValues[1].toFloatOrNull()
        }

        val withoutExtension = withoutBrackets.substringBeforeLast('.')
        val words = withoutExtension.split(Regex("""[\s_-]+""")).filter { it.isNotBlank() }
        val wordsWithDigits = words.filter { it.any(Char::isDigit) }

        // Si el conector "parte/part" precede a la última palabra con
        // dígitos, esa palabra es un sub-número: se retoma la búsqueda
        // antes del conector.
        val partConnectorIndex = words.indexOfLast { PART_CONNECTOR_REGEX.matches(it) }
        val candidateWords = if (partConnectorIndex > 0 && wordsWithDigits.lastOrNull() == words.getOrNull(partConnectorIndex + 1)) {
            words.subList(0, partConnectorIndex).filter { it.any(Char::isDigit) }
        } else {
            wordsWithDigits
        }

        val realEpisodeWord = candidateWords.lastOrNull { !NON_EPISODE_TAG_REGEX.matches(it) }
            ?: return PARENTHESIZED_CAP_REGEX.find(normalized)?.let {
                (it.groupValues[1].ifBlank { it.groupValues[2] }).toFloatOrNull()
            }

        ATTACHED_VERSION_SUFFIX_REGEX.find(realEpisodeWord)?.let {
            return it.groupValues[1].toFloatOrNull()
        }

        return TRAILING_NUMBER_REGEX.find(realEpisodeWord)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Extrae el último número suelto del nombre, sin el filtro de tags
     * no-episodio (OP/ED/v2) que sí aplica [extractEpisodeNumber].
     *
     * Para cuando el llamador ya sabe que el archivo es un especial y solo
     * necesita el número pegado a la etiqueta (ej. "OP2.mkv" -> 2).
     */
    fun extractTrailingNumber(rawName: String): Float? {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")
        val withoutExtension = withoutBrackets.substringBeforeLast('.')
        return TRAILING_NUMBER_REGEX.find(withoutExtension)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Categoría de un item, usada para decidir el orden en
     * [sortByEpisodeNumberDescending] -- no afecta [extractEpisodeNumber].
     *
     * [sortPriority] define el orden de los bloques (0 = primero):
     * Insert Song -> Ending -> Opening -> Special/Extra -> OVA -> Movie ->
     * Episode. SPECIAL y EXTRA comparten bloque, pero SPECIAL siempre se
     * ordena antes que EXTRA dentro de ese bloque (ver
     * [sortByEpisodeNumberDescending]).
     *
     * UNKNOWN no se detecta en [detectCategory] -- se resuelve dentro de
     * [buildEpisodeDisplay] como el caso EPISODE que además no tiene
     * ningún número identificable. Comparte sortPriority con EPISODE.
     */
    enum class EpisodeCategory(val sortPriority: Int) {
        INSERT_SONG(0),
        ENDING(1),
        OPENING(2),
        SPECIAL(3),
        EXTRA(3),
        OVA(4),
        MOVIE(5),
        EPISODE(6),
        UNKNOWN(6),
    }

    // Cada categoría se reconoce por palabra completa (\b) para no
    // disparar dentro de otra palabra (ej. "OVA" en "Nova"). Se evalúa en
    // orden y gana la primera que matchee. OP/ED/IS admiten número pegado
    // (OP2, IS02); NC = "sin créditos" (NCOP/NCED) cae en la misma
    // categoría que su contraparte con créditos. OP/ED también admiten su
    // forma larga (Opening/Ending, con o sin número pegado y con o sin
    // "NC" delante).
    private val CATEGORY_PATTERNS: List<Pair<EpisodeCategory, Regex>> = listOf(
        EpisodeCategory.INSERT_SONG to Regex("""\b(?:is\d*|isong\d*|insert\s*song|image\s*song)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.ENDING to Regex("""\b(?:(?:nc)?ed\d*|ending\d*|ncending\d*)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.OPENING to Regex("""\b(?:(?:nc)?op\d*|opening\d*|ncopening\d*)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.SPECIAL to Regex("""\b(?:special|especial|sp)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.EXTRA to Regex("""\bextra(?:s)?\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.OVA to Regex("""\b(?:ova|oav)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.MOVIE to Regex("""\b(?:movie|film|pel[ií]cula)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Detecta la categoría de un nombre de archivo, usada solo para
     * ordenar -- ver [sortByEpisodeNumberDescending].
     *
     * @return la categoría detectada, o [EpisodeCategory.EPISODE] si no
     *   coincide con ningún patrón especial (el caso normal).
     */
    fun detectCategory(rawName: String): EpisodeCategory {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")
        // "_" cuenta como carácter de palabra para \b, así que "_OP" no
        // tiene boundary ahí y no dispararía ningún patrón de
        // CATEGORY_PATTERNS sin este reemplazo (ej. "Serie_-_OP.mkv").
        val forMatching = withoutBrackets.replace('_', ' ')
        return CATEGORY_PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(forMatching) }
            ?.first
            ?: EpisodeCategory.EPISODE
    }

    /**
     * Ordena una lista de items agrupando primero por categoría (ver
     * [EpisodeCategory]) y, dentro de cada categoría, de forma descendente
     * por número de episodio (el más alto primero).
     *
     * Los items sin número identificable van al final de su categoría --
     * excepto en EPISODE, donde van al principio del bloque, para no
     * desplazar el puesto de un episodio real ante trackers como
     * AniList/MAL, que cuentan por posición/número. Ver README.md para el
     * detalle de por qué difiere entre EPISODE y el resto de categorías.
     *
     * @param items la lista a ordenar
     * @param nameSelector función para obtener el nombre de archivo de cada item
     */
    fun <T> sortByEpisodeNumberDescending(items: List<T>, nameSelector: (T) -> String): List<T> = items.sortedWith(
        compareBy<T> { detectCategory(nameSelector(it)).sortPriority }
            // SPECIAL y EXTRA comparten sortPriority; este paso desempata
            // para que SPECIAL siempre quede antes que EXTRA dentro de ese bloque.
            .thenBy { detectCategory(nameSelector(it)) == EpisodeCategory.EXTRA }
            .thenByDescending { item ->
                val category = detectCategory(nameSelector(item))
                // El número se extrae con la misma función que usa
                // buildEpisodeDisplay para cada categoría: EPISODE busca el
                // número en cualquier parte del nombre (extractEpisodeNumber);
                // el resto (OP/ED/etc.) busca el número pegado al tag
                // (extractTrailingNumber, ej. el "2" de "OP2").
                val number = if (category == EpisodeCategory.EPISODE) {
                    extractEpisodeNumber(nameSelector(item))
                } else {
                    extractTrailingNumber(nameSelector(item))
                }
                category == EpisodeCategory.EPISODE && number == null
            }
            .thenByDescending { item ->
                val category = detectCategory(nameSelector(item))
                val number = if (category == EpisodeCategory.EPISODE) {
                    extractEpisodeNumber(nameSelector(item))
                } else {
                    extractTrailingNumber(nameSelector(item))
                }
                // Sin número, se trata como el valor más bajo DENTRO de los
                // que sí tienen número (ej. "OP2" antes que "OP"). -1 en vez
                // de NEGATIVE_INFINITY para no quedar detrás de categorías
                // (como EXTRA) que puedan tener números negativos válidos.
                number ?: -1f
            }
    )

    // Etiqueta corta para mostrar en el nombre del episodio cuando no es
    // un episodio normal (ej. "OP 2", "Special", "Extra 1"). EPISODE no
    // tiene label propio porque su nombre se arma distinto (ver
    // [buildEpisodeDisplay]).
    private val EpisodeCategory.label: String
        get() = when (this) {
            EpisodeCategory.INSERT_SONG -> "IS"
            EpisodeCategory.OPENING -> "OP"
            EpisodeCategory.ENDING -> "ED"
            EpisodeCategory.SPECIAL -> "Special"
            EpisodeCategory.EXTRA -> "Extra"
            EpisodeCategory.OVA -> "OVA"
            EpisodeCategory.MOVIE -> "Movie"
            EpisodeCategory.EPISODE -> "Episodio"
            // Sin uso real: buildEpisodeDisplay resuelve el nombre de
            // UNKNOWN directo, nunca pasa por este label.
            EpisodeCategory.UNKNOWN -> "Desconocido"
        }

    /**
     * Nombre a mostrar y episode_number a asignar para un archivo,
     * resueltos juntos porque ambos dependen del mismo análisis del
     * nombre (categoría + número extraído).
     *
     * @param name nombre mostrado (según [showFilename])
     * @param episodeNumber valor listo para asignar a `SEpisode.episode_number`
     */
    data class EpisodeDisplay(val name: String, val episodeNumber: Float)

    /**
     * Resuelve en una sola llamada lo que casi todas las extensiones que
     * listan episodios por nombre de archivo necesitan repetir: el nombre
     * a mostrar y el `episode_number` correcto para ese archivo.
     *
     * Ver README.md para el razonamiento detrás de los valores de
     * `episodeNumber` por categoría (por qué 0F para especiales, por qué
     * UNKNOWN es un caso aparte).
     *
     * @param rawName nombre real del archivo
     * @param showFilename si `true`, [EpisodeDisplay.name] es `rawName` tal
     *   cual; si `false`, se arma una etiqueta genérica ("Episodio 4",
     *   "OP 2", "Special") según la categoría y número detectados --
     *   excepto en UNKNOWN, que siempre muestra el nombre real limpio de
     *   tags entre corchetes/paréntesis/llaves y sin extensión
     */
    fun buildEpisodeDisplay(rawName: String, showFilename: Boolean): EpisodeDisplay {
        val detectedCategory = detectCategory(rawName)
        val number = if (detectedCategory == EpisodeCategory.EPISODE) {
            extractEpisodeNumber(rawName)
        } else {
            extractTrailingNumber(rawName)
        }
        // EPISODE por descarte (ningún tag reconocido) sin número detectado
        // -- no hay evidencia de qué es este archivo.
        val category = if (detectedCategory == EpisodeCategory.EPISODE && number == null) {
            EpisodeCategory.UNKNOWN
        } else {
            detectedCategory
        }

        val name = when {
            category == EpisodeCategory.UNKNOWN -> {
                val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
                val withoutExtension = normalized.substringBeforeLast('.')
                BRACKETED_CONTENT_REGEX.replace(withoutExtension, " ").replace(Regex("""\s+"""), " ").trim()
            }
            showFilename -> rawName
            category == EpisodeCategory.EPISODE -> "Episodio ${number?.let { formatEpisodeNumber(it) } ?: "?"}"
            number != null -> "${category.label} ${formatEpisodeNumber(number)}"
            else -> category.label
        }

        val episodeNumber = when {
            category == EpisodeCategory.UNKNOWN -> -9999F
            category != EpisodeCategory.EPISODE -> 0F
            number != null -> number
            else -> -9999F
        }

        return EpisodeDisplay(name, episodeNumber)
    }

    /**
     * Resultado de [sortBySeasonAndEpisodeDescending]: el item original tal
     * como llegó (para que el llamador pueda re-vincularlo con su URL,
     * calidad, etc.) junto con el [EpisodeDisplay] ya resuelto.
     */
    data class SeasonedEpisode<T>(val item: T, val display: EpisodeDisplay)

    /**
     * Como [sortByEpisodeNumberDescending] + [buildEpisodeDisplay] juntos,
     * con soporte adicional para listas que mezclan varias temporadas de
     * una serie, y con un cálculo distinto para UNKNOWN. Ver README.md
     * para el detalle completo de ambos comportamientos y ejemplos.
     *
     * Resumen:
     * - Si hay más de una temporada distinta entre los EPISODE con número
     *   identificable, aplica un offset acumulado por temporada y muestra
     *   "Temporada N Episodio M". Si no, el resultado es idéntico a usar
     *   [buildEpisodeDisplay] + [sortByEpisodeNumberDescending] por
     *   separado -- segura de usar por defecto en cualquier lista.
     * - UNKNOWN nunca usa -9999F aquí: continúa la numeración real
     *   (máximo detectado + 1, +2...), o arranca en 1 si no hay ningún
     *   episodio real.
     *
     * @param items la lista a ordenar
     * @param nameSelector función para obtener el nombre de archivo de cada item
     * @param showFilename mismo significado que en [buildEpisodeDisplay];
     *   no afecta a items con temporada asignada (siempre muestran
     *   "Temporada N Episodio M") ni a UNKNOWN (siempre muestra el nombre
     *   real limpio)
     */
    fun <T> sortBySeasonAndEpisodeDescending(
        items: List<T>,
        nameSelector: (T) -> String,
        showFilename: Boolean,
    ): List<SeasonedEpisode<T>> {
        // Temporada detectada por item, solo para los que son EPISODE con
        // número real -- un OP/ED o un UNKNOWN no participan del conteo de
        // "cuántas temporadas distintas hay", aunque su nombre contenga
        // algo que coincida con SEASON_REGEX por casualidad.
        data class Detected(val item: T, val category: EpisodeCategory, val episodeNumber: Float?, val season: Int?)

        val detected = items.map { item ->
            val name = nameSelector(item)
            val category = detectCategory(name)
            val episodeNumber = if (category == EpisodeCategory.EPISODE) extractEpisodeNumber(name) else null
            val season = if (category == EpisodeCategory.EPISODE && episodeNumber != null) {
                extractSeasonNumber(name) ?: 1
            } else {
                null
            }
            Detected(item, category, episodeNumber, season)
        }

        val distinctSeasons = detected.mapNotNull { it.season }.distinct()

        // Sin multi-temporada real: se delega en la lógica ya existente,
        // sin ningún offset ni cambio de nombre.
        val seasoned = if (distinctSeasons.size <= 1) {
            detected.map { d -> SeasonedEpisode(d.item, buildEpisodeDisplay(nameSelector(d.item), showFilename)) }
        } else {
            // Offset acumulado: Temporada N empieza donde termina el número
            // más alto real de la Temporada N-1 (que a su vez ya incluye el
            // offset de las anteriores).
            val offsetBySeason = mutableMapOf<Int, Float>()
            distinctSeasons.sorted().fold(0F) { offsetSoFar, season ->
                offsetBySeason[season] = offsetSoFar
                val maxInSeason = detected
                    .filter { it.season == season }
                    .mapNotNull { it.episodeNumber }
                    .maxOrNull() ?: 0F
                offsetSoFar + maxInSeason
            }

            detected.map { d ->
                if (d.season != null && d.episodeNumber != null) {
                    val offsetNumber = offsetBySeason.getValue(d.season) + d.episodeNumber
                    SeasonedEpisode(
                        d.item,
                        EpisodeDisplay("Temporada ${d.season} Episodio ${formatEpisodeNumber(d.episodeNumber)}", offsetNumber),
                    )
                } else {
                    // No-EPISODE (OP/ED/Special/etc.) o UNKNOWN: sin offset
                    // de temporada (el de UNKNOWN se resuelve aparte, abajo).
                    SeasonedEpisode(d.item, buildEpisodeDisplay(nameSelector(d.item), showFilename))
                }
            }
        }

        // UNKNOWN se identifica por episodeNumber == -9999F: es el único
        // caso en que buildEpisodeDisplay asigna ese valor. No se puede
        // usar detectCategory aquí porque esa función nunca devuelve
        // UNKNOWN -- esa categoría solo existe dentro de buildEpisodeDisplay,
        // que ya la resolvió al construir `seasoned` más arriba.
        val maxRealEpisodeNumber = seasoned
            .filter { it.display.episodeNumber != -9999F && detectCategory(nameSelector(it.item)) == EpisodeCategory.EPISODE }
            .maxOfOrNull { it.display.episodeNumber } ?: 0F

        var nextUnknownNumber = maxRealEpisodeNumber + 1
        val withUnknownFixed = seasoned.map { s ->
            if (s.display.episodeNumber == -9999F) {
                val fixed = SeasonedEpisode(s.item, EpisodeDisplay(s.display.name, nextUnknownNumber))
                nextUnknownNumber += 1F
                fixed
            } else {
                s
            }
        }

        return withUnknownFixed.sortedWith(
            compareBy<SeasonedEpisode<T>> { detectCategory(nameSelector(it.item)).sortPriority }
                .thenBy { detectCategory(nameSelector(it.item)) == EpisodeCategory.EXTRA }
                .thenByDescending { it.display.episodeNumber }
        )
    }

    // "12.0" se muestra como "12" (sin el ".0" redundante); "12.5" se
    // muestra tal cual, para no perder el episodio parcial.
    private fun formatEpisodeNumber(number: Float): String = if (number == number.toInt().toFloat()) {
        number.toInt().toString()
    } else {
        number.toString()
    }
}
