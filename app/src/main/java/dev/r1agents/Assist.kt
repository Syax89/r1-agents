package dev.r1agents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * R1 Agents come assistente digitale: "tieni premuto il pulsante di accensione" (gesto standard di Android) apre la
 * schermata vocale dell'app da qualunque app. Il pulsante resta quello di sistema (nessuna rimappatura): un tocco, due tocchi
 * e la barra a ruota della ROM funzionano come prima. Si invia tenendo premuto di nuovo o toccando il pannello.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    /**
     * Tasto laterale tenuto premuto: si passa sempre dall'app, con la stessa schermata vocale del push to talk (prima qui
     * c'era un pannello a parte). Se l'app sta già ascoltando, il secondo "tieni premuto" invia senza riaprire nulla:
     * riaprire l'attività la metterebbe in pausa e chiuderebbe il microfono.
     */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val live = MainActivity.current?.get()
        // Schermata vocale già aperta (in ascolto, in attesa della risposta o ferma): si continua lì, nella stessa conversazione.
        if (live != null && SideButton.action(ctx) == "voice" && (live.voiceRecording() || live.voiceOpen())) { live.runOnUiThread { live.runSideAction() }; hide(); return }
        runCatching { startAssistantActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        hide()
    }
}
