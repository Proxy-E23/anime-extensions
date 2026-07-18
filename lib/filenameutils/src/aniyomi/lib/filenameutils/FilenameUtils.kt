package aniyomi.lib.filenameutils

/**
 * Utilidades para analizar nombres de archivo de episodios (video) y
 * extraer/ordenar por su número de episodio.
 *
 * Pensada para reusarse desde cualquier extensión que liste archivos por
 * nombre (MEGA, Google Drive, servidores HTTP simples, etc.) donde el
 * nombre del archivo es la única fuente de verdad sobre qué episodio es.
 *
 * Ejemplos que debe resolver correctamente:
 *   - "[Fansub] Serie 05 (BD 1920x1080 x264 AAC) [35E5A43B].mp4" -> 5
 *   - "[Fansub]Serie_1-2_(2024)-_EP12_-_[1080p].mkv" -> 12
 *   - "[Fansub] Serie - 13.mkv" -> 13
 *   - "[Fansub] OP NCOP [1080p].mkv" -> null (sin número real de episodio)
 */
object FilenameUtils {

    // Contenido entre corchetes/paréntesis/llaves: tags de fansub,
    // resolución, codec, hash, etc. -- se limpia antes de buscar el número.
    // Apertura y cierre se aceptan cruzados ([...} , (...] , etc.) porque
    // en releases reales es común un tag mal cerrado (p.ej. "[1080p x264
    // AAC}" en vez de "]"), que si no se tolera deja ese tag entero sin
    // limpiar y cuela texto como "x264" como candidato a episodio.
    private val BRACKETED_CONTENT_REGEX = Regex("""[\[{(][^\[\]{}()]*[]})]""")

    // Patrón explícito: EP12, Episode 12, Capitulo 12, etc. Se prueba
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
            ?: return null

        ATTACHED_VERSION_SUFFIX_REGEX.find(realEpisodeWord)?.let {
            return it.groupValues[1].toFloatOrNull()
        }

        return TRAILING_NUMBER_REGEX.find(realEpisodeWord)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Extrae el último número suelto del nombre, SIN el filtro de tags
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
     * Categoría de un item, usada solo para decidir el orden en
     * [sortByEpisodeNumberDescending] -- no afecta [extractEpisodeNumber].
     *
     * [sortPriority] define el orden de los bloques (0 = primero):
     * Insert Song -> Ending -> Opening -> Special/Extra -> OVA -> Movie ->
     * Episode.
     *
     * SPECIAL y EXTRA comparten el mismo bloque (mismo [sortPriority]),
     * pero SPECIAL siempre se ordena antes que EXTRA dentro de ese bloque
     * -- ver [sortByEpisodeNumberDescending].
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
    }

    // Cada categoría se reconoce por palabra completa (\b) para no
    // disparar dentro de otra palabra (ej. "OVA" en "Nova"). Se evalúa en
    // orden y gana la primera que matchee. OP/ED/IS admiten número pegado
    // (OP2, IS02) y NC = "sin créditos" (NCOP/NCED) cae en la misma
    // categoría que su contraparte con créditos.
    private val CATEGORY_PATTERNS: List<Pair<EpisodeCategory, Regex>> = listOf(
        EpisodeCategory.INSERT_SONG to Regex("""\b(?:is\d*|isong\d*|insert\s*song|image\s*song)\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.ENDING to Regex("""\b(?:nc)?ed\d*\b""", RegexOption.IGNORE_CASE),
        EpisodeCategory.OPENING to Regex("""\b(?:nc)?op\d*\b""", RegexOption.IGNORE_CASE),
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
     *   matchea ningún patrón especial (el caso normal).
     */
    fun detectCategory(rawName: String): EpisodeCategory {
        val normalized = rawName.replace(Regex("""\s+"""), " ").trim()
        val withoutBrackets = BRACKETED_CONTENT_REGEX.replace(normalized, " ")
        // "_" cuenta como carácter de palabra para \b, así que "_OP" no
        // tiene boundary ahí y no dispararía ningún patrón de CATEGORY_PATTERNS
        // sin este reemplazo (ej. "Serie_-_OP.mkv").
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
     * Los items sin número identificable van al FINAL de su categoría --
     * excepto en EPISODE, donde van al PRINCIPIO del bloque, para no
     * desplazar el puesto de un episodio real (ej. "Episodio 1") ante
     * trackers como AniList/MAL, que cuentan por posición/número.
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
                val hasNumber = extractEpisodeNumber(nameSelector(item)) != null
                if (category == EpisodeCategory.EPISODE) !hasNumber else hasNumber
            }
            .thenByDescending { extractEpisodeNumber(nameSelector(it)) ?: Float.NEGATIVE_INFINITY },
    )

    // Etiqueta corta para mostrar en el nombre del episodio cuando no es
    // un episodio normal (ej. "OP 2", "Special", "Extra 1"). EPISODE no
    // tiene label propio porque su nombre se arma distinto (ver
    // [buildEpisodeDisplay]: "Episodio {n}").
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
     * a mostrar (nombre real del archivo, o una etiqueta genérica como
     * "Episodio 4"/"OP 2" si el usuario prefiere ocultar el nombre real) y
     * el `episode_number` correcto para ese archivo.
     *
     * Los especiales (cualquier categoría que no sea EPISODE) usan `0F`
     * como episode_number -- el valor estándar en Aniyomi/Tachiyomi para
     * "no participa en el conteo secuencial de episodios". Usar un offset
     * propio por tipo (como -1000, -2000...) rompe el cálculo de
     * "episodios faltantes" de Aniyomi/Anikku, que mira el rango completo
     * entre el episode_number más chico y el más grande de toda la lista:
     * con un especial en -2000 y un episodio real en 20, ese rango pasa a
     * ser de -2000 a 20, la mayoría de esos números inexistentes por
     * diseño -- eso se ve en la app como miles de "episodios faltantes".
     *
     * @param rawName nombre real del archivo
     * @param showFilename si `true`, [EpisodeDisplay.name] es `rawName` tal
     *   cual; si `false`, se arma una etiqueta genérica ("Episodio 4",
     *   "OP 2", "Special") según la categoría y número detectados
     */
    fun buildEpisodeDisplay(rawName: String, showFilename: Boolean): EpisodeDisplay {
        val category = detectCategory(rawName)
        val number = if (category == EpisodeCategory.EPISODE) {
            extractEpisodeNumber(rawName)
        } else {
            extractTrailingNumber(rawName)
        }

        val name = when {
            showFilename -> rawName
            category == EpisodeCategory.EPISODE -> "Episodio ${number?.let { formatEpisodeNumber(it) } ?: "?"}"
            number != null -> "${category.label} ${formatEpisodeNumber(number)}"
            else -> category.label
        }

        val episodeNumber = when {
            category != EpisodeCategory.EPISODE -> 0F
            number != null -> number
            else -> -9999F
        }

        return EpisodeDisplay(name, episodeNumber)
    }

    // "12.0" se muestra como "12" (sin el ".0" redundante); "12.5" se
    // muestra tal cual, para no perder el episodio parcial.
    private fun formatEpisodeNumber(number: Float): String = if (number == number.toInt().toFloat()) {
        number.toInt().toString()
    } else {
        number.toString()
    }
}
