package dev.r1agents

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Protegge solo turni effettivamente in corso; nessun polling permanente o avvio al boot. */
class HermesTurnService : Service() {
    private lateinit var runtime: HermesRuntime
    private val handler = Handler(Looper.getMainLooper())
    private val update = Runnable { refresh() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); runtime = HermesRuntime.get(this)
        runtime.jobsChanged = { handler.removeCallbacks(update); handler.postDelayed(update, 150) }
    }
    private fun notification(): Notification {
        val tasks = runtime.activeTasks()
        val stop = PendingIntent.getService(this, 0, Intent(this, HermesTurnService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, HermesRuntime.CHANNEL_WORK).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(UiText.text(this@HermesTurnService, R.string.ui_419e3b6963)).setContentText(if (tasks.size == 1) UiText.text(this@HermesTurnService, R.string.ui_2b8633a8ce) else UiText.text(this@HermesTurnService, R.string.ui_c4f9dbb908, tasks.size))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(runtime.pendingIntent(tasks.firstOrNull()))
            .addAction(Notification.Action.Builder(null, UiText.text(this@HermesTurnService, R.string.ui_1bf3b5b1e6), stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(3, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == "stop") { runtime.cancelAll(); stopSelf(); return START_NOT_STICKY }
        runtime.restore()
        handler.removeCallbacks(update); handler.postDelayed(update, 1500)
        return START_STICKY
    }
    private fun refresh() {
        if (runtime.restoring) { handler.postDelayed(update, 1000); return }
        if (runtime.activeTasks().isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else getSystemService(android.app.NotificationManager::class.java).notify(3, notification())
    }
    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Nessun riavvio automatico oltre il limite di Android. Il lavoro remoto resta in Hermes.
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); runtime.jobsChanged = null; super.onDestroy() }
}
