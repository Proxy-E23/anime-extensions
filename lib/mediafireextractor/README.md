# MediaFire Extractor Library

A thin client for MediaFire's public folder/file API, used to browse public
links and resolve their direct, playable video URL.

## Why this exists

MediaFire serves public files over plain HTTP with no encryption layer, so
this library is much simpler than `megaextractor`: it just wraps MediaFire's
public JSON API plus a couple of HTML scraping fallbacks for cases the API
doesn't cover well.

1. List a public folder's contents (subfolders and files, paginated).
2. Detect whether a folder link was deleted or is otherwise invalid.
3. Resolve a file's real name when the URL doesn't carry it in the path.
4. Resolve the direct download/streaming URL for a file, ready to hand to a
   player.

## How It Works

1. **Link parsing**: `MediaFireLinkParser` recognizes `/file/<quickkey>` and
   `/folder/<key>` URLs, extracting the identifying key and, for files, the
   filename when present in the path.
2. **API calls**: `MediaFireApi` talks to MediaFire's public endpoints
   (`get_content.php`, `get_info.php`, `get_links.php`) under
   `/api/1.5/folder/` and `/api/1.5/file/`.
3. **Direct URL resolution**: `MediaFireExtractor.resolveDirectVideoUrl`
   first tries `get_links.php` (`link_type=normal_download`) and follows its
   redirect looking for a `download` URL. If that doesn't produce a usable
   link, it falls back to scraping the file page's download button and
   following that redirect instead.

## Public API

```kotlin
val extractor = MediaFireExtractor(client, browserHeaders)

// Parse any mediafire.com link
val link = extractor.parseLink(url) // MediaFireLink.File | MediaFireLink.Folder | null

// Folder: check it still exists, then list its contents
val missing = extractor.isFolderMissing(key)
val subFolders = extractor.listSubFolders(key)
val files = extractor.listFiles(key)

// Resolve a file's real name (uses the URL hint if present, otherwise scrapes)
val name = extractor.resolveFileName(quickkey, hint = link.filename)

// Get a playable Video
val videos: List<Video> = extractor.videoFromFile(quickkey, filename)
```

## Known limitations

- Only public links are supported (no login-gated content).
- `isFolderMissing` relies on matching known error markers in the API
  response body (JSON or XML depending on MediaFire's current state), not on
  a strongly-typed error field -- MediaFire's public API doesn't consistently
  expose one.
- No retry/backoff logic; a transient network failure surfaces as a normal
  exception (or a safe fallback value, depending on the method -- see each
  function's doc comment).

## Architecture

```
Extension (src/all/mediafire)
    ↓
MediaFireExtractor (public facade)
    ↓
MediaFireApi (internal HTTP + JSON/HTML parsing)
    ↓
MediaFireLinkParser
```
