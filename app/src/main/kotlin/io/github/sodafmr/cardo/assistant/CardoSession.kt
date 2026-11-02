package io.github.sodafmr.cardo.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/** One assistant invocation. P1 only proves it can be shown; the UI arrives in a later phase. */
class CardoSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "session shown")
    }

    private companion object {
        const val TAG = "Cardo"
    }
}
