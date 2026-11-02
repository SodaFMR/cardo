package io.github.sodafmr.cardo.brain.nlu

import io.github.sodafmr.cardo.brain.Lang
import io.github.sodafmr.cardo.brain.schema.Command

/** [start] and [end] are offsets into the normalized text. */
data class SlotSpan(val type: String, val text: String, val start: Int, val end: Int)

data class Interpretation(
    val intent: String,
    val confidence: Float,
    val alternatives: List<Pair<String, Float>>,
    val slots: List<SlotSpan>,
    val command: Command?,
    val lang: Lang,
)

/**
 * Stateless; follow-up answers to a pending question ("at 7", "la segunda") are parsed by the dialog manager
 * with slot parsers, not by Understanding.
 */
interface Understanding {
    fun interpret(text: String, langHint: Lang): Interpretation
}
