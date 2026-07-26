# FilenameUtils

Utilidades para analizar nombres de archivo de episodios (video) y resolver, a partir de ellos, el nombre a mostrar, el `episode_number` y el orden correcto en la lista de episodios.

Pensada para reutilizarse desde cualquier extensión que liste archivos por nombre (MEGA, Google Drive, servidores HTTP simples, etc.), donde el nombre del archivo es la única fuente de información sobre qué episodio es.

## Uso básico

Para el caso normal (una sola temporada, sin mezclar varias en el mismo post):

```kotlin
val episodes = files.map { file ->
    val display = FilenameUtils.buildEpisodeDisplay(file.name, showFilename)
    SEpisode.create().apply {
        name = display.name
        episode_number = display.episodeNumber
        url = file.url
    }
}

val sorted = FilenameUtils.sortByEpisodeNumberDescending(episodes) { it.name }
```

`buildEpisodeDisplay` resuelve, para un nombre de archivo, tanto el nombre a mostrar como el `episode_number` -- ambos dependen del mismo análisis (categoría + número detectado), por eso se calculan juntos.

`sortByEpisodeNumberDescending` ordena la lista completa: agrupa por categoría (Ending, Opening, Special/Extra, OVA, Movie, Episode, en ese orden) y dentro de cada categoría ordena descendente por número.

## Multi-temporada: `sortBySeasonAndEpisodeDescending`

### El problema que resuelve

Algunos fansubs suben varias temporadas de una serie en el mismo post (una carpeta o link por temporada), en vez de un post por temporada. Ejemplo real:

```
[Fansub] Series Name - 01 (1920x1080 x264 AAC)[HASH].mp4       <- Temporada 1, no lo dice
[Fansub] Series Name T2 - 01 (1920x1080 x264 AAC)[HASH2].mp4  <- Temporada 2, sí lo dice
```

Si se procesan ambas carpetas con el flujo normal (`buildEpisodeDisplay` + `sortByEpisodeNumberDescending`), el archivo "T2 - 01" recibe `episode_number = 1`, igual que el "01" de la Temporada 1 -- quedan duplicados y el orden sale mal (T2 se intercala con T1 en vez de quedar como un bloque aparte y más reciente).

### Cómo lo resuelve

`sortBySeasonAndEpisodeDescending` recibe la lista completa de nombres (no uno a la vez, como `buildEpisodeDisplay`), porque para calcular el offset de temporada hace falta ver primero cuántos episodios reales tiene cada temporada anterior.

Reglas:

- Se detecta la temporada con un regex propio (`Temporada 2`, `Temp 2`, `T2`, `Season 2`, `S2`, `Cour 2`, `Cour2`). Un archivo sin ninguna mención de temporada se asume Temporada 1.
- **Solo se activa si se detecta más de una temporada distinta** en toda la lista. Si todos los archivos son de la misma temporada (mencionada o no), el resultado es idéntico a usar `buildEpisodeDisplay` + `sortByEpisodeNumberDescending` por separado -- es segura de usar por defecto, incluso si luego resulta que ese post no era multi-temporada.
- Cuando sí hay multi-temporada: el nombre pasa a ser `"Temporada N Episodio M"`, y el `episode_number` interno se corre con un **offset acumulado**: la Temporada 2 continúa desde el número más alto real detectado en la Temporada 1 (no desde un tamaño de temporada asumido de antemano).
- El offset usa el **número más alto detectado**, no la cantidad de archivos -- así, si a la Temporada 1 le faltan episodios intermedios (ej. tiene 1, 2, 3, 10 pero no del 4 al 9), esos huecos se siguen viendo como episodios faltantes en Aniyomi/Anikku, en vez de "compactarse".
- OP/ED/Special/Extra/Movie quedan completamente al margen de esta lógica: no se les calcula offset ni se les antepone "Temporada N", exactamente como si no existiera el concepto de temporada para ellos.

**Ejemplo** -- Temporada 1 con 12 episodios, Temporada 2 con 2 episodios subidos:

| Archivo | Nombre mostrado | `episode_number` |
|---|---|---|
| Series Name T2 - 02 | Temporada 2 Episodio 2 | 14 |
| Series Name T2 - 01 | Temporada 2 Episodio 1 | 13 |
| Series Name - 12 | Episodio 12 | 12 |
| ... | ... | ... |
| Series Name - 01 | Episodio 1 | 1 |

### Uso

```kotlin
val fileNames = files.map { it.name }
val result = FilenameUtils.sortBySeasonAndEpisodeDescending(fileNames, { it }, showFilename)

val episodes = result.map { seasoned ->
    val file = files.first { it.name == seasoned.item }
    SEpisode.create().apply {
        name = seasoned.display.name
        episode_number = seasoned.display.episodeNumber
        url = file.url
    }
}
```

`item` en cada `SeasonedEpisode` es el mismo valor que se pasó en `items` (en este ejemplo, el nombre crudo) -- sirve para volver a asociar el resultado con su archivo/link original, ya que la lista devuelta viene reordenada.

### Cuándo usarla en vez del flujo normal

Como es segura por defecto (no cambia nada si no detecta multi-temporada), se puede usar siempre que una extensión construya su lista de episodios a partir de nombres crudos disponibles de antemano. El único requisito real es tener todos los nombres juntos antes de armar los `SEpisode` -- si una extensión arma cada `SEpisode` a medida que descubre archivos (por ejemplo, iterando resultados paginados de una API), hace falta juntar los nombres primero, y solo entonces llamar a esta función.

## `UNKNOWN`: archivos sin ningún tag ni número

### El problema

Un archivo que no coincide con ningún patrón de categoría (OP/ED/Special/etc.) y tampoco tiene ningún número identificable -- por ejemplo, una película suelta con nombre propio como `Movie Title.mkv`, sin "movie" ni "película" en el nombre -- no tiene ninguna evidencia textual de qué es. No hay heurística segura para adivinar la categoría (asumir "es una película" por descarte generaría falsos positivos con otros casos, como un episodio suelto sin numerar).

Por eso existe `EpisodeCategory.UNKNOWN`: no afirma saber qué es el archivo, solo reconoce que no hay evidencia. En vez de una etiqueta genérica ("Episodio ?"), se muestra el nombre real del archivo, limpio de tags entre `[]`/`{}`/`()` y sin extensión -- ya que el nombre real (`Movie Title`) es más útil que cualquier etiqueta inventada.

### El `episode_number` de `UNKNOWN`

`buildEpisodeDisplay` (que ve un nombre a la vez) le asigna `-9999F` como marcador interno, sin intención de que sea el valor final -- necesita verse en el contexto de toda la lista para saber qué número le corresponde de verdad.

`sortBySeasonAndEpisodeDescending` (que sí ve la lista completa) corrige ese valor: en vez de `-9999F`, cada `UNKNOWN` continúa la numeración real -- toma el episodio más alto detectado + 1; si hay varios `UNKNOWN` en la misma lista, cada uno suma uno más que el anterior (+1, +2, +3...); si no hay ningún episodio real en toda la lista, el primero arranca en 1.

Esto importa porque, sin corregir, `-9999F` generaba miles de "episodios faltantes" falsos en Aniyomi/Anikku (el rango entre -9999 y el episodio real más alto), aunque nunca hubiera existido tal cantidad de episodios.

**Nota:** si una extensión usa el flujo normal (`buildEpisodeDisplay` + `sortByEpisodeNumberDescending`) en vez de `sortBySeasonAndEpisodeDescending`, sus archivos `UNKNOWN` seguirán mostrando `-9999F` -- esa corrección solo vive en la función nueva por ahora.

## Por qué el orden difiere entre `EPISODE` y el resto de categorías

Dentro de `sortByEpisodeNumberDescending`, un item sin número identificable se ordena de forma distinta según su categoría:

- **En `EPISODE`**: va al *principio* del bloque (antes que los que sí tienen número). Esto es así para no desplazar el puesto de un episodio real ante trackers como AniList/MAL, que cuentan por posición/número -- es preferible que un caso ambiguo quede visualmente primero a que se intercale entre episodios reales.
- **En el resto de categorías (OP/ED/Special/etc.)**: va al *final* del bloque, tratándose como el valor más bajo dentro de los que sí tienen número. Por ejemplo, entre `OP2` y `OP` (sin número), el orden correcto es `OP2, OP` -- como si `OP` fuera un "OP1" implícito. Si además existiera un `OP1` explícito en la misma lista, el orden sería `OP2, OP1, OP` (el implícito, al no tener evidencia de si es el mismo que el explícito o no, se coloca al final por seguridad).