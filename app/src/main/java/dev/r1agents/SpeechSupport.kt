package dev.r1agents

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.tts.TextToSpeech

internal object SpeechSupport {
    fun enginePackage(ctx: Context): String? {
        val installed = ctx.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).map { it.serviceInfo.packageName }.distinct()
        val preferred = android.provider.Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
        return preferred?.takeIf { it in installed } ?: installed.firstOrNull()
    }
    fun hasEngine(ctx: Context): Boolean = enginePackage(ctx) != null
    fun isMuted(ctx: Context): Boolean = ctx.getSystemService(AudioManager::class.java).let { it.isStreamMute(AudioManager.STREAM_MUSIC) || it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }
}
