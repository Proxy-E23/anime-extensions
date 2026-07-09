# MEGA Extractor Library

A local HTTP proxy library for streaming and decrypting MEGA (mega.nz) public
file/folder links without requiring login or an account.

## Why this exists

MEGA encrypts everything client-side, even for public links shared without an
account: file names, node keys, and the file content itself. The decryption
key travels in the URL fragment (`#key`), and the server never serves
plaintext bytes. This library implements just enough of MEGA's public JSON-RPC
API and its AES scheme to:

1. List a public folder's contents (already decrypted: names, sizes, handles).
2. Resolve a public single-file link's metadata.
3. Serve the actual video content to a player (e.g. ExoPlayer) by decrypting
   it on the fly through a local HTTP proxy — never touching disk.

This is **not** a port of the official `meganz/sdk` (a large native C++
library covering the full MEGA client feature set — accounts, sync, chat,
etc). It only implements the small subset of the public API needed to read a
public link and stream its content, similar in spirit to what `megatools` or
`mega.py` do at the protocol level.

## How It Works

1. **Link parsing**: `MegaLinkParser` recognizes legacy (`#!` / `#F!`) and
   current (`/file/`, `/folder/`) link formats, extracting the node handle
   and the base64 key from the URL.
2. **API calls**: `MegaApi` talks to `https://g.api.mega.co.nz/cgi/mega`, MEGA's
   JSON-RPC endpoint, to list a folder tree (`a:f`) or request a temporary
   download URL for a file (`a:g`).
3. **Decryption**: `MegaCrypto` implements the three AES operations MEGA's
   protocol relies on:
   - AES-ECB to decrypt each node's key using the folder's master key.
   - AES-CBC (zero IV) to decrypt file/folder names from the `a` attribute.
   - AES-CTR to decrypt the actual file content, block by block.
4. **Streaming proxy**: `MegaProxyServer` (NanoHTTPD) exposes each resolved
   video at `http://127.0.0.1:<port>/stream/<handle>`. When the player
   requests a `Range`, the proxy:
   - Aligns the requested byte offset down to the nearest 16-byte AES block.
   - Requests that aligned range from MEGA's temporary download URL.
   - Decrypts it with AES-CTR using the correct block counter for that offset.
   - Trims the leading bytes that were only needed for block alignment.
   - Responds with a proper `206 Partial Content` and `Content-Range`.

This is what makes seeking possible: AES-CTR lets you decrypt any block
independently as long as you know its position, so the proxy never needs to
decrypt a file from byte zero to serve an arbitrary range.

## Public API

```kotlin
val extractor = MegaExtractor(client)

// Parse any mega.nz link
val link = extractor.parseLink(url) // MegaLink.File | MegaLink.Folder | null

// Folder: list contents (already decrypted)
val nodes: List<MegaNode> = extractor.listFolder(link as MegaLink.Folder)

// Single file: resolve name/size lazily
val meta = extractor.resolveSingleFile(link as MegaLink.File)

// Get a playable Video pointing at the local proxy
val videos: List<Video> = extractor.videoFromNode(node, folderHandle = link.handle)
// or, for a single-file link:
val videos: List<Video> = extractor.videoFromSingleFile(meta)
```

## Known limitations

- Only public links are supported (no login, no shared-with-me content that
  requires an account session).
- No integrity check against MEGA's MAC verification (the `metaMac` bytes are
  derived but not currently validated against the downloaded content).
- The proxy server is a per-process singleton created lazily by
  `MegaExtractor`; call `shutdown()` when it's no longer needed.
- Relies on a third-party NanoHTTPD fork already used elsewhere in this repo
  (`lib/m3u8server`) — see that module if the dependency coordinates ever need
  to change.

## Architecture

```
Extension (src/all/meganz)
    ↓
MegaExtractor (public facade)
    ↓                    ↓
MegaApi              MegaProxyServer (NanoHTTPD)
    ↓                    ↓
MegaCrypto  ←───── AES-CTR decrypt per Range request
    ↓
MegaLinkParser
```
