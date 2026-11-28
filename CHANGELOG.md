# Changelog

## Unreleased

- HTTP cleartext allowed for all hosts (was: only flibusta.is)
- My Books: fixed metadata for titles/authors with «&»; FB2 shows the first author; EPUB chapters with percent-encoded hrefs are no longer skipped
- Reader: bounded archive reads (EPUB 16 MB per entry, FB2/TXT 48 MB); EPUB metadata reads bounded to 4 MB
- Backup: import no longer changes proxy settings
- Bookmarks are now keyed by file name + size (positions already were); old bookmarks migrate on first read
- Metadata cache is pruned for deleted books
- Note: the reader parser changed after 0.1.0, so saved positions and bookmarks from 0.1.0 may point at a different block — re-set them if a position looks wrong

## 0.1.0 — 2026-11-26

- OPDS catalogs: Flibusta, CoolLib, unglue.it, custom catalogs (optional Basic Auth)
- Downloads (DownloadManager / SAF / OkHttp with proxy and DoH), My Books, built-in FB2/EPUB/TXT reader
- Subscriptions with daily check and accumulating NEW badges, global search, cover grid
- Material You themes, Ukrainian / English / Russian UI, AppLog with Share logs
- HTTP cleartext allowed only for flibusta.is; catalog passwords encrypted (SecretBox)

Not yet: batch download of a series/folder, Room storage, EPUB pagination, sync.