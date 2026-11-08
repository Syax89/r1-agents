package dev.r1agents

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** File lenti o interrotti, disponibili soltanto nell'APK QA. */
class FunctionalFiles : ContentProvider() {
    companion object {
        var opened = CountDownLatch(1)
        var release = CountDownLatch(1)
        fun reset() { opened = CountDownLatch(1); release = CountDownLatch(1) }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val gate = release
        Thread {
            opened.countDown()
            if (uri.lastPathSegment == "broken.txt") {
                java.io.FileOutputStream(pipe[1].fileDescriptor).write("inizio parziale".toByteArray())
                pipe[1].closeWithError("QA interrupted read")
            } else {
                try {
                    check(gate.await(8, TimeUnit.SECONDS))
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write("allegato QA ritardato".toByteArray()) }
                } catch (_: Exception) { runCatching { pipe[1].closeWithError("QA timeout") } }
            }
        }.apply { isDaemon = true; start() }
        return pipe[0]
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
