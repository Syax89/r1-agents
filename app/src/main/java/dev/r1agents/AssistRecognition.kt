package dev.r1agents

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Richiesto da Android per un assistente: R1 Agents non offre il riconoscimento vocale ad altre app (la sua voce usa
 * Groq Whisper dentro l'app), quindi qui risponde soltanto "non disponibile".
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, cb: Callback?) { runCatching { cb?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(cb: Callback?) {}
    override fun onStopListening(cb: Callback?) {}
}
