package dev.r1agents

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Descrizione di una versione, come la scrive tools/publish-update.py. */
data class UpdateManifest(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val sizeBytes: Long, val notes: String)

/** Un errore da mostrare all'utente: `resource` e' un testo del catalogo (it/en), `arg` il suo eventuale segnaposto. */
class UpdateError(val resource: Int, val arg: String? = null) : Exception(arg)

/**
 * Controllo aggiornamenti senza servizi in secondo piano: solo su richiesta dell'utente. Tutto cio' che arriva dalla rete si valida prima
 * di toccarlo: HTTPS senza downgrade, manifest piccolo e con campi precisi, pacchetto uguale al nostro, hash e dimensione, stessa firma
 * dell'app installata. L'installazione passa dal sistema (PackageInstaller) e chiede sempre conferma all'utente.
 */
object Updater {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    /** True se l'indirizzo e' un URL https senza credenziali ne' frammento. */
    fun httpsUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.host.isNotEmpty()
    }

    /** Valida il manifest. `ownPackage` e' il pacchetto dell'app in esecuzione: un aggiornamento per un'altra app si rifiuta. */
    fun parse(json: String, ownPackage: String, allowLoopbackHttp: Boolean = false): UpdateManifest {
        val o = try { JSONObject(json) } catch (e: Exception) { throw UpdateError(R.string.ui_f0e07e26a5) }
        if (o.optInt("schema") != 1) throw UpdateError(R.string.ui_f0e07e26a5)
        if (o.optString("packageName") != ownPackage) throw UpdateError(R.string.ui_84e62dec0f)
        val code = o.optLong("versionCode", 0); val size = o.optLong("sizeBytes", 0)
        val sha = o.optString("sha256"); val url = o.optString("apkUrl"); val notes = o.optString("notes")
        if (code <= 0 || size <= 0 || size > MAX_APK_BYTES || !hex64.matches(sha) || !(httpsUrl(url) || (allowLoopbackHttp && url.trim().startsWith("http://127.0.0.1"))) || notes.length > 12000) throw UpdateError(R.string.ui_f0e07e26a5)
        return UpdateManifest(code, o.optString("versionName").take(40), url.trim(), sha.lowercase(), size, notes)
    }

    fun isNewer(manifest: UpdateManifest, installedCode: Long) = manifest.versionCode > installedCode

    private fun client() = OkHttpClient.Builder().followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Scarica e valida il manifest (solo https, massimo 64 KB). */
    fun fetchManifest(ctx: Context, url: String, allowLoopbackHttp: Boolean = false): UpdateManifest {
        // `allowLoopbackHttp` serve solo alle prove strumentali (pacchetto QA): http verso 127.0.0.1, mai verso altri indirizzi.
        val loopback = allowLoopbackHttp && url.trim().toHttpUrlOrNull()?.let { !it.isHttps && it.host == "127.0.0.1" } == true
        if (!loopback && !httpsUrl(url)) throw UpdateError(R.string.ui_1320f7c837)
        client().newCall(Request.Builder().url(url.trim()).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdateError(R.string.ui_483f5c9b20, "HTTP ${r.code}")
            val stream = r.body?.byteStream() ?: throw UpdateError(R.string.ui_f0e07e26a5)
            val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk); if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > MAX_MANIFEST_BYTES) throw UpdateError(R.string.ui_f0e07e26a5)
            }
            return parse(buffer.toString(Charsets.UTF_8.name()), ctx.packageName, loopback)
        }
    }

    /** Scarica l'APK in `dest` calcolando l'hash mentre scorre; si ferma se supera la dimensione dichiarata. Ritorna il file solo se hash e dimensione corrispondono. */
    fun download(manifest: UpdateManifest, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val digest = MessageDigest.getInstance("SHA-256")
        dest.parentFile?.mkdirs()
        try {
            client().newCall(Request.Builder().url(manifest.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw UpdateError(R.string.ui_483f5c9b20, "HTTP ${r.code}")
                val body = r.body ?: throw UpdateError(R.string.ui_1a5c3e2bf7)
                var total = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val chunk = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(chunk); if (n < 0) break
                            total += n
                            if (total > manifest.sizeBytes) throw UpdateError(R.string.ui_1a5c3e2bf7)
                            digest.update(chunk, 0, n); out.write(chunk, 0, n); progress(total, manifest.sizeBytes)
                        }
                    }
                }
                if (total != manifest.sizeBytes) throw UpdateError(R.string.ui_1a5c3e2bf7)
            }
            if (!toHex(digest.digest()).equals(manifest.sha256, ignoreCase = true)) throw UpdateError(R.string.ui_1a5c3e2bf7)
            return dest
        } catch (e: Exception) { dest.delete(); throw e }
    }

    fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Controlla pacchetto, versione e firma del file scaricato rispetto all'app installata. */
    @Suppress("DEPRECATION")
    fun verifyApk(ctx: Context, apk: File, manifest: UpdateManifest) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateError(R.string.ui_1a5c3e2bf7)
        if (archive.packageName != ctx.packageName || archive.longVersionCode != manifest.versionCode) throw UpdateError(R.string.ui_84e62dec0f)
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        if (signers(archive) != signers(installed) || signers(archive).isEmpty()) throw UpdateError(R.string.ui_4b07cc5415)
    }

    private fun signers(info: android.content.pm.PackageInfo): Set<String> =
        (info.signingInfo?.apkContentsSigners ?: emptyArray()).map { toHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Consegna l'APK verificato al sistema, che mostra la conferma. L'esito arriva a [UpdateResultReceiver]. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("r1agents.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(ctx, UpdateResultReceiver::class.java).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** Esito dell'installazione: se serve la conferma dell'utente la fa mostrare al sistema; il resto lo segnala con un messaggio. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.widget.Toast.makeText(ctx, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: UiText.text(ctx, R.string.ui_483f5c9b20, ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
