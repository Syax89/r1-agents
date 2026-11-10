package dev.r1agents

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

enum class Kind { IMAGE, PDF, TEXT, OTHER }

/** Un allegato già copiato nella cartella privata dell'app (così resta leggibile anche quando il permesso del selettore è scaduto). */
class Attachment(val name: String, val mime: String, val file: File) {
    val kind: Kind get() = Attach.kindOf(name, mime)
}

object Attach {
    const val MAX_BYTES = 20L * 1024 * 1024   // sotto il limite di 25 MB di Hermes
    const val MAX_TEXT = 60_000                // caratteri di un file di testo inseriti nel messaggio
    private val textExt = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log", "html", "htm", "css", "js", "ts", "kt", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "sh", "sql", "tex", "rtf")

    fun kindOf(name: String, mime: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> Kind.IMAGE
            mime == "application/pdf" || ext == "pdf" -> Kind.PDF
            mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/x-yaml") || ext in textExt -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "attach").also { it.mkdirs() }

    /** Vero solo per file dentro `files/attach` (percorso canonico: niente "../" per uscire dalla cartella). */
    fun inAttachDir(f: File): Boolean = runCatching { f.canonicalFile.parentFile?.let { it.name == "attach" && it.parentFile?.name == "files" } ?: false }.getOrDefault(false)

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifEmpty { "file" }

    /**
     * Solo contenuti di altre app (`content://` con un'authority che non e' nostra): un `file://` o un nostro provider
     * permetterebbero a un'app esterna, con una "condivisione", di farci allegare e inviare i nostri file privati.
     */
    fun allowedUri(scheme: String?, authority: String?, ownPackage: String): Boolean =
        scheme.equals("content", ignoreCase = true) && !authority.isNullOrBlank() &&
            authority != ownPackage && !authority.startsWith("$ownPackage.") && !authority.startsWith("dev.r1agents")

    /** Copia il contenuto di `uri` nella cartella degli allegati. Lancia con un messaggio chiaro se è troppo grande o illeggibile. */
    fun fromUri(ctx: Context, uri: Uri, fallbackName: String = "allegato"): Attachment {
        if (!allowedUri(uri.scheme, uri.authority, ctx.packageName)) throw IllegalStateException("File non accettato.")
        val cr = ctx.contentResolver
        var name = fallbackName
        cr.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name } }
        if (name == fallbackName) uri.lastPathSegment?.let { name = it.substringAfterLast('/') }
        val mime = cr.getType(uri) ?: ""
        val out = File.createTempFile("att-", "-" + safe(name), dir(ctx))
        var total = 0L
        try {
            (cr.openInputStream(uri) ?: throw IllegalStateException("Non riesco a leggere il file.")).use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("File troppo grande (massimo 20 MB).")
                        o.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) { out.delete(); throw e }
        return Attachment(name, mime, out)
    }

    fun fromFile(file: File, name: String, mime: String) = Attachment(name, mime, file)

    /** JPEG ridotto (lato lungo ≤ 1600 px, qualità 85): le foto da 12 megapixel non devono viaggiare intere. */
    fun imageBytes(a: Attachment): ByteArray {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(a.file.path, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeFile(a.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Non riesco a leggere l'immagine ${a.name}.")
        val k = 1600f / maxOf(bmp.width, bmp.height)
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    fun text(a: Attachment): String = a.file.reader().use { reader ->
        val buffer = CharArray(MAX_TEXT + 1); var total = 0
        while (total < buffer.size) { val n = reader.read(buffer, total, buffer.size - total); if (n < 0) break; total += n }
        String(buffer, 0, minOf(total, MAX_TEXT)) + if (total > MAX_TEXT) "\n… (troncato)" else ""
    }
}

/** Piccolo ContentProvider che dà alla fotocamera di sistema un posto dove scrivere la foto (`EXTRA_OUTPUT`), senza permessi di archiviazione. */
class PhotoProvider : ContentProvider() {
    override fun onCreate() = true
    private fun fileOf(uri: Uri): File {
        val f = File(File(context!!.cacheDir, "photos"), uri.lastPathSegment ?: "x.jpg")
        require(f.canonicalPath.startsWith(File(context!!.cacheDir, "photos").canonicalPath)) { "percorso non valido" }
        return f
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(uri); f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode) or if (mode.contains('w')) ParcelFileDescriptor.MODE_CREATE else 0)
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    companion object { const val AUTH = "dev.r1agents.photos" }
}
