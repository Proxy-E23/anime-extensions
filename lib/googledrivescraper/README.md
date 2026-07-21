# googledrivescraper

Scraping of Google Drive folders and files: content listing, recursive episode traversal, catalog (first-level subfolders), and details (cover + `details.json`).

## Relationship with other Drive libraries

This library is an evolution/merge of the logic that used to live duplicated inside the Google Drive extension, and in practice replaces `lib/googledriveepisodes` for any new usage: it covers everything that library did (recursively traversing a folder to build episodes) and additionally adds catalog, details, episode ranges, and support for loose files. `googledriveepisodes` is left untouched in the repository for compatibility, but is not recommended for new extensions.

`googledrivescraper` does **not** resolve the final video of an episode. `lib/googledriveextractor` (`GoogleDriveExtractor`) is still used for that, and is kept as a separate library because it's also used by extensions that don't scrape Drive folders at all (for example, a fansub site that just pastes a loose `fileId` found in its HTML). The typical setup is to use both libraries together: `googledrivescraper` to build the catalog/episodes, and `googledriveextractor` in `getVideoList` to resolve each episode's video.

`googledriveplayerextractor` is an experimental alternative (via WebView) to `googledriveextractor` for that same specific purpose of resolving the final video; it's unrelated to this library and is not currently used by any extension.

## Two levels of functions

### Low level

Standalone pieces for extensions with their own naming, episode, or catalog criteria (for example, an extension that only needs a folder's content without recursing into it, or that already has its own way of building each episode's name):

- `listFolderItems(folderId, pageToken)` / `listFolderItemsResult(folderId, pageToken)` — content of a single folder (one level, no recursion), with shortcuts already resolved. The `Result` variant distinguishes a folder that doesn't exist (real 404) from any other network error.
- `fetchFileName(fileId)` — real name of a loose file, read from the `<title>` of its public page. No authentication required, lighter than `fetchFileMetadata` when only the name is needed.
- `fetchFileMetadata(fileId)` — name, size, and modification date of a loose file, by its ID, without needing the folder that contains it. Requires authentication (SAPISIDHASH).

### High level

Full traversal already assembled, meant for extensions that want Google Drive's "as-is" behavior:

- `scrapeEpisodes(folderUrl, maxRecursionDepth, startPosition, stopPosition)` — recursively traverses a folder and builds the episode list. `ScrapedEpisode.name` is always the file's real name (this library doesn't decide whether to show "Episode N" or the real name: that's a presentation decision left to the consumer, typically resolved with `FilenameUtils.buildEpisodeDisplay`). Likewise, `ScrapedEpisode.episodeNumber` is the position within the folder, not the number detected in the file name -- this library intentionally doesn't depend on `filenameutils`, to keep them decoupled.
- `scrapeCatalogFolders(rootFolderUrl)` — first-level subfolders of a root folder, to use as a catalog.
- `scrapeFolderDetails(folderUrl)` — cover (an image named "cover") and `details.json`, if they exist. The anime's name is never included in the result: the name the user gave the saved entry should always take precedence over whatever `details.json` says.

## Typical usage

```kotlin
val scraper = GoogleDriveScraper(client, headers)

// Episodes of a folder, with an optional range
val scraped = scraper.scrapeEpisodes(folderUrl, maxRecursionDepth = 2, startPosition = null, stopPosition = null)

// Name presentation delegated to filenameutils
val episodes = scraped.map { ep ->
    val display = FilenameUtils.buildEpisodeDisplay(ep.name, showFilename = false)
    SEpisode.create().apply {
        name = display.name
        url = ep.url
        episode_number = display.episodeNumber
        date_upload = ep.dateUploadMillis
        scanlator = ep.sizeLabel
    }
}

// Final episode video: resolved with the other library
val videos = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))
```
