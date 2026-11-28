# Changelog

## 0.2.0 — 2026-11-28

- HTTP cleartext allowed for all hosts (was: only flibusta.is)
- My Books: fixed metadata for titles/authors with «&»; FB2 shows the first author; EPUB chapters with percent-encoded hrefs are no longer skipped
- Reader: bounded archive reads (EPUB 16 MB per entry, FB2/TXT 48 MB); EPUB metadata reads bounded to 4 MB
- Reader: bookmarks and positions keyed by file name + size, with migration of old keys
- Backup: import no longer changes proxy settings; the download folder from a copy goes through the shared folder-name cleaning
- Metadata cache is pruned for deleted books, and only when the book list is non-empty
- CI: Gradle wrapper validation step
- Release build with R8 (minify + shrink resources), signed with the same key — the release APK is ~1.4 MB
- Note: the reader parser changed after 0.1.0, so saved positions and bookmarks from 0.1.0 may point at a different block — re-set them if a position looks wrong

## 0.1.0 — 2026-11-26

- OPDS catalogs: Flibusta, CoolLib, unglue.it, custom catalogs (optional Basic Auth)
- Downloads (DownloadManager / SAF / OkHttp with proxy and DoH), My Books, built-in FB2/EPUB/TXT reader
- Subscriptions with daily check and accumulating NEW badges, global search, cover grid
- Material You themes, Ukrainian / English / Russian UI, AppLog with Share logs
- HTTP cleartext allowed only for flibusta.is; catalog passwords encrypted (SecretBox)

Not yet: batch download of a series/folder, Room storage, EPUB pagination, sync.