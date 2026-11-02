# Cardo P3 — Understanding Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `models/nlu/` — a ≤ 40 MB int8 ONNX joint intent + slot + language model for English and Spanish that hits ≥ 95% intent accuracy per language and ≥ 90% slot exact-match on an independent held-out set — plus the golden files that prove the Kotlin runtime decodes it identically.

**Architecture:** A Python project in `ml/nlu/` (uv-managed). Template banks generate labelled training data with ASR-style noise. A separately authored held-out set measures generalization. A small multilingual encoder is fine-tuned with three heads, its vocabulary trimmed to English+Spanish, then exported to ONNX and dynamically quantized. Nothing in `ml/` ships in the app; only `models/nlu/*` does.

**Tech Stack:** Python 3.12 via `uv` (`uv python install 3.12`), PyTorch CPU wheels, `transformers`, `sentencepiece`, `onnx`, `onnxruntime` 1.31.1 (same version the app uses), `pytest`. The GPU is busy with other work — train on CPU only.

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md` (section 5)

## Global Constraints

- Intent ids, slot types and their order come from `brain/src/main/kotlin/io/github/sodafmr/cardo/brain/schema/IntentCatalog.kt` (50 intents, 31 slot types). Read them from that file at build time (parse the Kotlin list literals). Never duplicate them by hand.
- Artifact contract (verbatim from `brain/.../nlu/ModelContract.kt`):
  - `model.onnx` — inputs `input_ids` int64 `[1,48]`, `attention_mask` int64 `[1,48]`; outputs `intent_logits` `[1,50]`, `slot_logits` `[1,48,63]`, `lang_logits` `[1,2]`.
  - `vocab.tsv` — `piece<TAB>score`; line index = id; ids 0–3 = `<pad>`, `<s>`, `</s>`, `<unk>`.
  - `labels.json` — `{"intents":[…], "slots":["O","B-<type>","I-<type>",… in slot-type order], "langs":["EN","ES"]}`.
- Text normalization must equal Kotlin `TextNormalizer.normalize`:
  1. NFKC; lowercase.
  2. `’ ‘ ´` → `'`.
  3. `: . ,` kept only between two digits.
  4. Keep letters (accents kept), digits, spaces, and `% + * / ' -`; every other character → space.
  5. Collapse whitespace; trim.
- Tokenization: SentencePiece unigram over the **trimmed** vocabulary. Spaces become `▁` with a dummy prefix; output is `<s>` + ≤ 46 pieces + `</s>`, padded to 48; an unknown char → `<unk>`. Training data must be tokenized with exactly this procedure, so Kotlin and Python agree.
- Slot labels: word-level BIO; the first piece of a word carries the word's label, continuation pieces carry `I-<type>` (or `O`).
- Targets on held-out: intent accuracy ≥ 95% for EN and for ES separately; slot exact-match (all spans equal after `TextNormalizer`) ≥ 90%; language accuracy ≥ 99%.
- Size: `model.onnx` ≤ 40 MB. Latency: ≤ 8 ms p95 per utterance on the host with 1 intra-op thread.
- Base model licence must be MIT or Apache-2.0. Record the licence in `models/LICENSES.md`.
- The held-out set is never used for training, vocabulary trimming, template writing or hyper-parameter selection beyond the final gate. The dev split comes from generated data.
- Commits: plain messages, no `Co-Authored-By` / "Generated with" trailers. Large intermediate files (datasets, checkpoints) go under `ml/nlu/out/` (git-ignored). Commit only code, templates, values, held-out JSONL, the final `models/nlu/*` files and reports.

## Review Focus

1. ASR-style input: no punctuation, numbers as digits *or* words, filler words ("um", "eh", "pues", "porfa"), missing articles — the model must not depend on clean text.
2. Code-switching common in bilingual users ("pon una alarma for 7", "call a mi madre") — the model must still pick the right intent; language goes to the majority language.
3. Very short inputs ("lights", "más alto", "next", "sí") and very long dictated message bodies (30+ words) — must not crash and must not truncate the intent decision.
4. Slot boundary errors on message bodies and reminder tasks ("text Ana that I'll be late to the meeting" → body is "i'll be late to the meeting", not including "that").
5. Out-of-scope requests that share keywords with real intents ("what's the history of alarm clocks", "who invented the telephone") → `web.search` or `out_of_scope`, not `alarm.set`/`call.*`.

---

### Task 1: Project, normalization and catalog loading

**Files:**
- Create: `ml/nlu/pyproject.toml`, `ml/nlu/cardo_nlu/__init__.py`, `ml/nlu/cardo_nlu/normalize.py`, `ml/nlu/cardo_nlu/catalog.py`, `ml/nlu/tests/test_normalize.py`, `ml/nlu/tests/test_catalog.py`, `ml/nlu/README.md`, `ml/nlu/.gitignore` (`out/`, `.venv/`)

**Interfaces:**
- Produces: `normalize(text: str) -> str`; `load_catalog(repo_root: Path) -> Catalog` with `Catalog.intents: list[str]` (50), `Catalog.slot_types: list[str]` (31), `Catalog.slot_tags: list[str]` (`["O"] + [f"{p}-{t}" for t in slot_types for p in ("B","I")]`, 63 entries).

- [ ] **Step 1:** Write `test_normalize.py`:
  - `normalize("¿Qué hora es?") == "qué hora es"`
  - `normalize("Set an alarm for 7:30.") == "set an alarm for 7:30"`
  - `normalize("1,5 kilos") == "1,5 kilos"`
  - `normalize("Hello, world") == "hello world"`
  - `normalize("What's 15% of 80?") == "what's 15% of 80"`
  - `normalize("“Hey Cardo”") == "hey cardo"`

  Write `test_catalog.py`: 50 intents, starting with `call.contact` and ending with `out_of_scope`; 63 tags.
- [ ] **Step 2:** `uv run pytest` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `uv run pytest` → PASS.
- [ ] **Step 5:** Commit `feat(ml): nlu project with normalization and catalog loader`.

### Task 2: Template banks and value lists

**Files:**
- Create: `ml/nlu/data/templates/{en,es}/<intent>.txt` (one file per intent per language), `ml/nlu/data/values/{en,es}/<slot_type>.txt`, `ml/nlu/cardo_nlu/templates.py`, `ml/nlu/tests/test_templates.py`

**Interfaces:**
- Template line format: plain text with `{slot_type}` placeholders, e.g. `can you remind me {datetime} to {task}`. Lines starting with `#` are comments.
- Value files: one value per line. Values for `datetime`, `duration`, `phone_number` and `quantity` are spoken forms in both digits and words. `contact` mixes Spanish, Latin American, English and international names plus relationship words.
- Produces: `load_templates(lang) -> dict[intent, list[str]]`, `load_values(lang) -> dict[slot_type, list[str]]`.

- [ ] **Step 1:** Write `test_templates.py`:
  - every intent has ≥ 40 templates in each language
  - `out_of_scope` has ≥ 300 per language, including keyword-sharing traps (Review Focus 5)
  - every placeholder names a slot type in the catalog
  - every slot type used anywhere has a value file with ≥ 30 entries; `contact` ≥ 400, `message_body`/`task`/`note_text`/`search_query` ≥ 300 each
  - no template line is duplicated across intents
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Author the banks. Variety is the point:
  - Registers: imperative, polite ("could you…", "¿me puedes…?"), indirect ("I need to wake up at…", "tengo que…"), terse ("timer 5 min").
  - Regional variants: Spain and Latin America (*móvil/celular*, *coger/tomar*, *ordenador/computadora*), UK and US.
  - Word order: slot first or slot last.
  - Fillers.
  - Code-switched lines (≈ 5%).
  - Very short forms for device/media/yes/no/cancel.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(ml): bilingual template banks and slot values`.

### Task 3: Independent held-out set

This task's implementer must **not** read `ml/nlu/data/templates/` or `ml/nlu/data/values/`. Dispatch it with that instruction.

**Files:**
- Create: `ml/nlu/data/heldout/{en,es}.jsonl`, `ml/nlu/cardo_nlu/heldout.py`, `ml/nlu/tests/test_heldout.py`

**Interfaces:**
- Line format: `{"text": "...", "intent": "<id>", "slots": [{"type": "<slot_type>", "text": "<exact substring of text>"}]}`. Slot text is matched on the normalized text; the first occurrence wins.
- Produces: `load_heldout(lang) -> list[Example]` with word-level BIO tags computed from the slot substrings.

- [ ] **Step 1:** Write `test_heldout.py`:
  - ≥ 60 lines per intent per language
  - every slot `text` is a substring of `normalize(text)` after normalizing both
  - intents and slot types are valid
  - ≥ 15% of lines have no punctuation and lowercase (ASR style)
  - ≥ 5% contain fillers
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Write the sentences the way real people talk to a phone assistant: varied names, places and times, including the Review Focus cases. Split the work by domain if needed (calls+messages, clock+reminders+notes+calendar, apps+device+media, info+handoffs+meta).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `test(ml): independent held-out evaluation set`.

### Task 4: Dataset generation with ASR-style noise

**Files:**
- Create: `ml/nlu/cardo_nlu/generate.py`, `ml/nlu/cardo_nlu/noise.py`, `ml/nlu/tests/test_generate.py`

**Interfaces:**
- Produces: `generate(lang, n_per_intent, seed) -> list[Example]`, with `Example(text: str, words: list[str], tags: list[str], intent: str, lang: str)`. Also the CLI `uv run python -m cardo_nlu.generate --out out/data --per-intent 1500 --seed 7`, which writes `train.jsonl` and `dev.jsonl` (95/5 split, stratified).
- Noise (each applied with its own probability, recorded in `noise.py` constants):
  - Lowercase / strip punctuation: 0.5.
  - Digits ↔ words for numbers and times: 0.5.
  - Fillers inserted: 0.1.
  - Drop an article or preposition: 0.1.
  - Duplicate a word (stutter): 0.03.
  - Prepend "please" / "por favor" or append "thanks" / "gracias": 0.1.

- [ ] **Step 1:** Write `test_generate.py`:
  - the same seed gives identical output
  - every example's tags align 1:1 with `normalize(text).split()`
  - every slot value appears with a `B-` tag followed by `I-` tags
  - intent counts are balanced within ±5%
  - no generated text equals any held-out text after normalization
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Generate the data.
- [ ] **Step 6:** Commit `feat(ml): labelled dataset generator with ASR-style noise`.

### Task 5: Trimmed tokenizer and golden files

**Files:**
- Create: `ml/nlu/cardo_nlu/tokenizer.py`, `ml/nlu/tests/test_tokenizer.py`

**Interfaces:**
- Produces: `build_trimmed_tokenizer(base_model_id, corpus_texts, out_dir) -> TrimmedTokenizer`.
  - It keeps every piece used when the base SentencePiece model encodes the corpus (generated train+dev texts plus `ml/nlu/data/wordlists/{en,es}.txt`, ≈ 50k common words per language, generated from an openly licensed frequency list noted in the README). Also kept: the 4 specials, every single character seen, and `▁`.
  - It writes `vocab.tsv` and a trimmed `.model` protobuf. It returns `old_to_new` and `new_to_old` id maps for slicing embeddings.
- Produces: `TrimmedTokenizer.encode(normalized) -> (ids[48], mask[48], offsets)`, which must use the trimmed model.
- Produces: `write_golden(tok, texts, path)` → `models/nlu/tokenizer-golden.tsv` (`normalized_text<TAB>space-separated ids`, 2,000 lines sampled from dev + held-out-style variety, **excluding** held-out texts).

- [ ] **Step 1:** Write tests:
  - specials are ids 0–3
  - `encode` pads to 48 and truncates to `<s>` + 46 + `</s>`
  - offsets slice back to the piece text (minus `▁`)
  - re-encoding through the trimmed model equals encoding with an independent pure-Python Viterbi over `vocab.tsv` — implement that reference in the test; it is the same algorithm Kotlin uses
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(ml): trimmed bilingual tokenizer with golden parity file`.

### Task 6: Train, select, export, quantize

**Files:**
- Create: `ml/nlu/cardo_nlu/model.py`, `ml/nlu/cardo_nlu/train.py`, `ml/nlu/cardo_nlu/export.py`, `ml/nlu/cardo_nlu/evaluate.py`, `ml/nlu/tests/test_model.py`, `ml/nlu/tests/test_export.py`

**Interfaces:**
- Model: base encoder (embeddings sliced with `new_to_old`) + intent head (mean-pooled masked hidden → dropout 0.1 → linear 50) + slot head (per token → linear 63) + lang head (pooled → linear 2). Loss = CE(intent) + CE(slot, ignore pad) + 0.2·CE(lang).
- Training: AdamW lr 5e-5 (heads 1e-3), batch 64, ≤ 6 epochs, early stop on dev intent accuracy; `torch.set_num_threads(12)`.
- Candidates: `intfloat/multilingual-e5-small` and `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`. Keep whichever is better on dev; break ties on size. Record both in the report.
- Export: `torch.onnx.export` (opset 17, static shapes `[1,48]`, names per the contract) → `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8)` → `models/nlu/model.onnx`. Also write `models/nlu/vocab.tsv`, `models/nlu/labels.json`, and `models/nlu/model-golden.jsonl` (100 non-held-out sentences: `{"normalized":…, "intent":…, "confidence":…}` from the quantized model).
- CLI: `uv run python -m cardo_nlu.train --base <id> --data out/data --out out/run-<name>`; `uv run python -m cardo_nlu.export --run out/run-<name>`; `uv run python -m cardo_nlu.evaluate --model ../../models/nlu` (prints and writes the report).

- [ ] **Step 1:** Write `test_model.py` (a forward pass on a 2-example batch gives the three logits with the contract shapes) and `test_export.py` (the exported quantized model loads in onnxruntime 1.31.1 with exactly the contract input/output names and shapes; quantized vs float intent argmax agree on ≥ 99% of the dev set; size ≤ 40 MB).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Train both candidates, export the winner.
- [ ] **Step 6:** Commit `feat(ml): joint intent/slot/lang model training and int8 ONNX export`.

### Task 7: Held-out gate and error-analysis loop

**Files:**
- Create: `docs/benchmarks/nlu.md`, `models/LICENSES.md` (add the NLU entry; keep the file append-friendly — P4 adds voice models)
- Modify (only if the gate fails): `ml/nlu/data/templates/**`, `ml/nlu/data/values/**`

**Interfaces:**
- `evaluate.py` produces the report:
  - per-language intent accuracy, slot exact-match and language accuracy on held-out
  - the ten most-confused intent pairs
  - the 30 worst examples
  - host latency (p50/p95, 1 thread, 1,000 runs)
  - model size
  - base model, its licence and training data size

- [ ] **Step 1:** Run `evaluate`. If every target in Global Constraints is met, write `docs/benchmarks/nlu.md` and go to Step 3.
- [ ] **Step 2:** If not, run the error-analysis loop, at most 4 iterations:
  1. Read the report's confusion pairs and worst examples.
  2. Fix the **templates/values/noise** (never copy held-out sentences; describe the *pattern* you're adding in the commit message).
  3. Regenerate, retrain, re-export, re-evaluate.

  If the targets are still missed after 4 iterations, stop and report DONE_WITH_CONCERNS with the numbers.
- [ ] **Step 3:** Commit `feat(ml): NLU model v1 — <EN acc>/<ES acc> intent, <slot EM> slot EM` including `models/nlu/{model.onnx,vocab.tsv,labels.json,tokenizer-golden.tsv,model-golden.jsonl}`, the report and `models/LICENSES.md`. If `model.onnx` > 50 MB, stop and report instead — the spec says it must then go to a release asset.
