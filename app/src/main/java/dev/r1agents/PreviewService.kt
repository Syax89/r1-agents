package dev.r1agents

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * Anteprime del multitasking (facoltative, le attiva l'utente nelle impostazioni di accessibilità).
 * Quando un'altra app è in primo piano, ogni tanto salva una sua schermata piccola in `files/previews/<pacchetto>.jpg`:
 * il selettore delle app la mostra nella scheda. Non legge il contenuto delle finestre e non manda nulla da nessuna parte.
 * Le app con FLAG_SECURE (banche, password) Android le cattura nere: si scartano.
 */
class PreviewService : AccessibilityService() {
    private var lastShot = 0L
    private var current: String? = null

    override fun onServiceConnected() { running = true }
    override fun onDestroy() { running = false; super.onDestroy() }
    /** Spento dall'utente nelle impostazioni di accessibilità: le immagini salvate non restano. */
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = false; clear(this); return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != current) {
            current = pkg
            // Anche le app aperte da fuori (notifiche, altre app) entrano nei recenti del multitasking.
            if (!skip(pkg) && packageManager.getLaunchIntentForPackage(pkg) != null) noteRecent(this, pkg)
        }
        if (pkg != current || skip(pkg)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShot < MIN_GAP_MS || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        lastShot = now
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    s.hardwareBuffer.close()
                    if (hw == null || current != pkg) return
                    Io.run { save(this@PreviewService, pkg, hw) }
                }
                override fun onFailure(code: Int) {}
            })
        }
    }

    private fun skip(pkg: String) = pkg == packageName || pkg.startsWith("dev.r1agents") || pkg == "com.android.systemui" || pkg == "android" ||
        pkg.contains("inputmethod") || pkg.contains("launcher")

    companion object {
        private const val MIN_GAP_MS = 2500L
        private const val WIDTH = 240
        @Volatile var running = false
            private set

        fun dir(ctx: Context) = File(ctx.filesDir, "previews")
        fun file(ctx: Context, pkg: String) = File(dir(ctx), pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")

        /** L'anteprima di un'app, se c'è (piccola: decodifica diretta). */
        fun load(ctx: Context, pkg: String): Bitmap? = file(ctx, pkg).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

        fun forget(ctx: Context, pkg: String) { file(ctx, pkg).delete() }

        /** Vero se l'utente ha attivato il servizio nelle impostazioni di accessibilità. */
        fun enabled(ctx: Context): Boolean = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals("${ctx.packageName}/${PreviewService::class.java.name}", true) || it.equals("${ctx.packageName}/.PreviewService", true) }

        private fun noteRecent(ctx: Context, pkg: String) {
            val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val list = p.getString("recent_apps", "").orEmpty().split(",").filter { it.isNotEmpty() && it != pkg }
            p.edit().putString("recent_apps", (listOf(pkg) + list).take(8).joinToString(",")).apply()
        }
        fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

        /** Vero se l'immagine è quasi tutta nera (finestra protetta o schermo spento): non serve come anteprima. */
        internal fun mostlyBlack(px: IntArray): Boolean {
            if (px.isEmpty()) return true
            val dark = px.count { (it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff) < 30 }
            return dark > px.size * 0.97
        }

        private fun save(ctx: Context, pkg: String, hw: Bitmap) {
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return
            hw.recycle()
            val h = (soft.height * WIDTH / soft.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(soft, WIDTH, h, true)
            if (small !== soft) soft.recycle()
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            if (mostlyBlack(px)) { small.recycle(); return }
            dir(ctx).mkdirs()
            val tmp = File(dir(ctx), "$pkg.tmp")
            runCatching { tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 72, it) }; tmp.renameTo(file(ctx, pkg)) }
            small.recycle()
        }
    }
}
