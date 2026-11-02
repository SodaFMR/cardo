package io.github.sodafmr.cardo.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required companion of a voice interaction service. Stub until P7 wires in on-device ASR:
 * every request fails with [SpeechRecognizer.ERROR_CLIENT].
 */
class CardoRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
