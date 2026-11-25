package app.allbooks.opds

data class Feed(
    val title: String,
    val entries: List<Entry>,
    val nextUrl: String?,
    val searchUrl: String?
)

data class Author(
    val name: String,
    val uri: String
)

data class Entry(
    val id: String,
    val title: String,
    val authors: List<Author>,
    val summary: String,
    val links: List<Link>
) {
    val isBook: Boolean get() = links.any { it.isAcquisition }
    val thumbnail: Link? get() = links.firstOrNull { it.isThumbnail }
    val navigation: Link? get() = links.firstOrNull { it.isNavigation }
}

data class Link(
    val href: String,
    val rel: String,
    val type: String,
    val title: String
) {
    val isAcquisition: Boolean get() = rel.startsWith("http://opds-spec.org/acquisition")
    val isThumbnail: Boolean
        get() = rel == "http://opds-spec.org/image/thumbnail" || rel == "x-stanza-cover-image-thumbnail"
    val isNavigation: Boolean
        get() = !isAcquisition && !isThumbnail && type.contains("atom+xml")

    val format: String
        get() = when {
            type.contains("fb2") -> "fb2"
            type.contains("epub") -> "epub"
            type.contains("mobi") -> "mobi"
            type.contains("pdf") -> "pdf"
            type.contains("djvu") -> "djvu"
            else -> type.substringAfter('/', type)
        }
}