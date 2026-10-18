package com.nova.assistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/** Big files (AI model, wake-word model) go through Android's DownloadManager so they survive closing the app. */
object Downloads {
    data class Progress(val running: Boolean, val done: Boolean, val failed: Boolean, val bytes: Long, val total: Long)

    fun start(ctx: Context, url: String, fileName: String, title: String): Long {
        val dest = File(ctx.getExternalFilesDir(null), fileName)
        dest.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, fileName)
            .setAllowedOverMetered(true)
        return ctx.getSystemService(DownloadManager::class.java).enqueue(req)
    }

    fun progress(ctx: Context, id: Long): Progress? {
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Progress(
                running = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED,
                done = status == DownloadManager.STATUS_SUCCESSFUL,
                failed = status == DownloadManager.STATUS_FAILED,
                bytes = got, total = total,
            )
        }
        return null
    }

    /** Unzip the wake-word model into app storage (strips the zip's top folder). */
    fun unzipVosk(ctx: Context, zip: File, into: File): Boolean = try {
        into.deleteRecursively()
        into.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(into, rel)
                if (!out.canonicalPath.startsWith(into.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        true
    } catch (e: Exception) {
        false
    }
}
