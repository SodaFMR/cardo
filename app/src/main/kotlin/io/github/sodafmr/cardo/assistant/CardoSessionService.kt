package io.github.sodafmr.cardo.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates a [CardoSession] each time the system shows the assistant. */
class CardoSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CardoSession(this)
}
