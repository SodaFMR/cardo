package io.github.sodafmr.cardo.brain.dialog

import io.github.sodafmr.cardo.brain.Lang
import io.github.sodafmr.cardo.brain.schema.ActionResult
import io.github.sodafmr.cardo.brain.schema.Choice
import io.github.sodafmr.cardo.brain.schema.Command
import io.github.sodafmr.cardo.brain.schema.Response
import io.github.sodafmr.cardo.brain.schema.UiAction

data class SessionInfo(val wokenByVoice: Boolean, val locked: Boolean, val langHint: Lang)

sealed interface Turn {
    /** The caller executes [command], then calls [DialogManager.onResult]. */
    data class Run(val command: Command, val chosen: Choice? = null, val confirmed: Boolean = false) : Turn

    /** [listen] = open a follow-up window without wake word. */
    data class Reply(val response: Response, val listen: Boolean) : Turn

    data object Close : Turn
}

interface DialogManager {
    fun start(session: SessionInfo)

    fun onUtterance(text: String): Turn

    fun onUiAction(action: UiAction): Turn

    fun onResult(result: ActionResult): Turn
}
