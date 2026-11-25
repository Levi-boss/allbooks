package app.allbooks

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import app.allbooks.opds.Entry
import app.allbooks.opds.Link

object Downloads {

    fun extension(link: Link): String {
        val t = link.type.lowercase()
        return if (t.contains("fb2") && t.contains("zip")) "fb2.zip" else link.format
    }

    fun start(context: Context, entry: Entry, link: Link) {
        val safe = entry.title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .trim()
            .take(80)
            .ifEmpty { "book" }
        val name = "$safe.${extension(link)}"
        try {
            val req = DownloadManager.Request(Uri.parse(link.href))
                .setTitle(entry.title)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "AllBooks/$name"
                )
                .addRequestHeader("User-Agent", "AllBooks/2.0 (Android)")
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(context, "Завантаження: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
