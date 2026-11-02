package io.github.sodafmr.cardo.brain.nlu

/**
 * The NLU artifact P3 produces and P2 loads.
 *
 * NLU artifact contract (also documented in `ml/README.md` later): directory `models/nlu/` containing
 * `model.onnx` (inputs `input_ids` int64 `[1,48]`, `attention_mask` int64 `[1,48]`; outputs
 * `intent_logits` `[1,50]`, `slot_logits` `[1,48,2*31+1]`, `lang_logits` `[1,2]`), `vocab.tsv`
 * (`piece<TAB>score` per line, line index = token id; ids 0-3 = `<pad>`, `<s>`, `</s>`, `<unk>`),
 * `labels.json` (`{"intents":[...IntentCatalog.ids], "slots":["O","B-contact","I-contact",...],
 * "langs":["EN","ES"]}`).
 */
const val NLU_MAX_TOKENS = 48

/** `slotLogits[token][tag]`. */
@Suppress("ArrayInDataClass")
data class ModelOutput(val intentLogits: FloatArray, val slotLogits: Array<FloatArray>, val langLogits: FloatArray)

interface IntentModel {
    fun run(inputIds: LongArray, attentionMask: LongArray): ModelOutput
}
