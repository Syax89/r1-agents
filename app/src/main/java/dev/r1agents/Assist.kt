package dev.r1agents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dpi
import dev.r1os.host.ui.keepCase
import dev.r1os.host.ui.text

/**
 * R1 Agents come assistente digitale: "tieni premuto il pulsante di accensione" (gesto standard di Android) apre il
 * pannello vocale sopra qualunque app. Il pulsante resta quello di sistema (nessuna rimappatura): un tocco, due tocchi
 * e la barra a ruota della ROM funzionano come prima. La registrazione si ferma da sola quando smetti di parlare.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    private var mic: Mic? = null
    private var ch: CharacterView? = null
    private var words: TextView? = null
    private var hint: TextView? = null
    private val box by lazy { LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER } }

    override fun onCreateContentView(): View {
        val frame = FrameLayout(ctx)
        frame.addView(box, FrameLayout.LayoutParams(-1, -1))
        frame.setOnClickListener { hide() }
        return frame
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val db = Db.get(ctx)
        val dashboard = prefs.getString("provider", "anthropic") == "dashboard"
        val a = db.agents().filter { if (dashboard) it.profile.isNotEmpty() else it.profile.isEmpty() }.getOrNull(prefs.getInt("home_index", 0).coerceAtLeast(0))
        if (a == null) { hide(); return }
        Theme.init(ctx)
        Theme.setLook(prefs.getString("look_mode", "dark") == "light", prefs.getString("look_accent", "orange") ?: "orange", prefs.getString("look_font", "rubik") ?: "rubik")
        box.removeAllViews(); box.setBackgroundColor(Theme.withAlpha(Theme.BG, 0.94f))
        val c = CharacterView(ctx, ctx.dpi(92f)).also { it.show(a, "listening") }
        val w = ctx.text(22f, 650).keepCase().apply { text = UiText.text(ctx, R.string.ui_77cfbcff1e); gravity = Gravity.CENTER; setPadding(ctx.dpi(24f), ctx.dpi(14f), ctx.dpi(24f), 0) }
        val h = ctx.text(14f, 600, Theme.MUTED).apply { text = UiText.text(ctx, R.string.ui_45127113bc, a.name); setPadding(0, ctx.dpi(12f), 0, 0) }
        box.addView(c); box.addView(w); box.addView(h)
        ch = c; words = w; hint = h
        mic?.cancel()
        val m = Mics.create(ctx, prefs, { w.text = it }, { t ->
            ch?.show(a, "thinking"); hide()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("say", t).putExtra("say_agent", a.id))
        }, { err -> hide(); if (err != null) Toast.makeText(ctx, UiText.message(ctx, err), Toast.LENGTH_LONG).show() }, autoStop = true)
        mic = m
        if (!m.start()) hide()
    }

    override fun onHide() { mic?.cancel(); mic = null; super.onHide() }
    override fun onDestroy() { mic?.cancel(); super.onDestroy() }
}


