package io.github.sodafmr.cardo.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/** Entry point Android binds when Cardo is the default digital assistant. */
class CardoVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "assistant ready")
    }

    private companion object {
        const val TAG = "Cardo"
    }
}
