package fr.artsetmetiers.evtestcopilot.voice

import android.content.Context
import android.content.Intent as AndroidIntent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reconnaissance vocale et synthèse vocale d'Android, en français.
 * Une pression sur le bouton « Parler » lance l'écoute ; la réponse est lue à voix haute, sans rien afficher d'autre.
 */
class VoiceController(
    context: Context,
    private val onHeard: (String) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) ttsLanguage()
    }

    private fun ttsLanguage() {
        tts.language = Locale.FRANCE
        tts.setSpeechRate(1.1f)
    }

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState("J'écoute…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onState("Analyse…")
            override fun onError(error: Int) = onState("Je n'ai pas compris, réessayez.")
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onState("Je n'ai pas compris, réessayez.") else onHeard(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    val available: Boolean get() = recognizer != null

    fun listen() {
        val intent = AndroidIntent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent) ?: onState("Reconnaissance vocale indisponible sur ce téléphone.")
    }

    fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "evtc-reply")
    }

    fun release() {
        recognizer?.destroy()
        tts.shutdown()
    }
}
