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

interface Understanding {
    fun interpret(text: String, langHint: Lang): Interpretation
}
