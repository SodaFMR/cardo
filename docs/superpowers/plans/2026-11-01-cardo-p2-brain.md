# Cardo P2 — Brain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything Cardo needs to turn a transcript into the right command and the right words, in English and Spanish, as pure Kotlin: normalization, exact rules (numbers, durations, dates/times, phone numbers, maths, units, cities), entity matching, the wake-word check, a keyword fallback understanding, the ONNX model runtime, bilingual responses, and the dialog manager.

**Architecture:** All code lives in `:brain` (pure JVM, no Android). It codes against the schema created in P1 (`schema/*`, `dialog/DialogContracts.kt`, `nlu/*Contract*.kt`) and never changes those signatures. Rules are table-tested from TSV fixtures. The dialog manager is a state machine tested with scripted conversations against fakes.

**Tech Stack:** Kotlin 2.4 JVM, JUnit 5 (parameterized tests from TSV), kotlinx-serialization-json, `ai.onnxruntime` (compileOnly; desktop jar in tests), `java.time` with an injected `Clock`.

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md` (sections 3, 5, 10)

## Global Constraints

- Package root `io.github.sodafmr.cardo.brain`; no `android.*` imports anywhere in `:brain`.
- Do not change any type or signature defined in P1's `schema/`, `dialog/DialogContracts.kt`, `nlu/UnderstandingContracts.kt`, `nlu/ModelContract.kt`. Add new types in new files only.
- "Now" always comes from an injected `java.time.Clock`; tests use fixed clocks (default fixture: `2026-11-04T10:15` local, a Wednesday, zone `Europe/Madrid`).
- Confidence policy: ≥ 0.85 act; 0.50–0.85 ask between the top two; < 0.50 out of scope with a web-search offer.
- Confirm before: calling a raw number, sending any message (SMS, reply, WhatsApp/Telegram), creating an event whose time was not stated.
- Bare hour with no am/pm or day-part (alarms, reminders, events): choose the **next future occurrence** among `h:00` and `(h+12):00`.
- Day-part defaults: morning/mañana 09:00, noon/mediodía 12:00, afternoon/tarde 16:00, evening 19:00, tonight/esta noche 21:00, midnight/medianoche 00:00 next day.
- Copy tone: warm, brief, confident. One sentence where possible. Never say "As an AI".
- Commits: plain messages, no `Co-Authored-By` / "Generated with" trailers.
- `org.gradle.jvmargs=-Xmx3g` stays as is; run `./gradlew :brain:test` (not the whole build) while iterating.

## Review Focus

1. Spanish number words with "y" and accents ("veintitrés", "treinta y cinco", "mil novecientos") and ES decimal comma ("1,5") vs EN thousands comma ("1,500").
2. Times that cross midnight or the current moment ("in 30 minutes" at 23:50; "at 10" when it's 10:15 → 22:00 today).
3. Transcripts that contain only the wake word, the wake word twice, or "Ricardo" — must not be treated as a wake + command.
4. A dialog that is waiting for a slot answer and receives a brand-new command instead ("call Ana" → "which number?" → "set a timer for 5 minutes") must switch to the new command, not stuff it into the slot.
5. Contact queries with accents or nicknames ("Mamá", "mama", "mum", "José" vs "Jose") must match the same contact.

---

### Task 1: Text normalization and wake-word handling

**Files:**
- Create: `brain/src/main/kotlin/io/github/sodafmr/cardo/brain/text/TextNormalizer.kt`, `text/Fold.kt`, `text/WakeWord.kt`
- Test: `brain/src/test/kotlin/.../text/TextNormalizerTest.kt`, `text/WakeWordTest.kt`, resource `brain/src/test/resources/text/normalize.tsv` (≥ 150 rows: `input<TAB>expected`)

**Interfaces:**
- Produces: `object TextNormalizer { fun normalize(text: String): String }`. The rules, in order (P3 mirrors these exactly in Python):
  1. NFKC; lowercase with `Locale.ROOT`.
  2. `’ ‘ ´` → `'`.
  3. `: . ,` survive only when a digit is on both sides.
  4. Keep letters (any script, accents kept), digits, spaces, and `% + * / ' -`.
  5. Every other character → space.
  6. Collapse runs of whitespace; trim.
- Produces: `object Fold { fun key(s: String): String }` — normalize, then strip diacritics (NFD + remove `\p{Mn}`), keep `ñ` as `n`, remove `'` and `-`.
- Produces: `object WakeWord { data class Match(val variant: String, val endIndex: Int); fun leading(normalized: String): Match?; fun strip(normalized: String): String }`. Variants: `cardo`, `hey cardo`, `hei cardo`, `oye cardo`, `oie cardo`, `ok cardo`, plus ASR spellings `kardo`, `cardó`, `car do`. A match only counts at the start, as whole words. `ricardo`, `cardio`, `cardos` never match. `strip` removes the leading match plus one following comma or space; it returns the input unchanged when there's no match.

- [ ] **Step 1:** Write `normalize.tsv` with ≥ 150 cases, including:
  - `¿Qué hora es?` → `qué hora es`
  - `Set an alarm for 7:30.` → `set an alarm for 7:30`
  - `1,5 kilos` → `1,5 kilos`
  - `Hello, world` → `hello world`
  - `What's 15% of 80?` → `what's 15% of 80`
  - `“Hey Cardo”` → `hey cardo`
  - and `Llama a Mamá!!` → `llama a mamá`.
- [ ] **Step 2:** Write `WakeWordTest`:
  - `leading("hey cardo set a timer")!!.endIndex == 9`
  - `strip("oye cardo pon una alarma") == "pon una alarma"`
  - `leading("ricardo dice hola") == null`
  - `leading("cardo") != null` with `strip("cardo") == ""`
  - `leading("ok cardo ok cardo llama a ana")` matches only the first occurrence
  - `strip("llama a cardo")` is unchanged.
- [ ] **Step 3:** Run `./gradlew :brain:test --tests '*text*'` → FAIL.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** Run → PASS.
- [ ] **Step 6:** Commit `feat(brain): text normalization, folding and wake-word check`.

### Task 2: Numbers, durations and phone numbers

**Files:**
- Create: `brain/src/main/kotlin/.../rules/NumberParser.kt`, `rules/DurationParser.kt`, `rules/PhoneNumberParser.kt`
- Test: `rules/NumberParserTest.kt`, `rules/DurationParserTest.kt`, `rules/PhoneNumberParserTest.kt`; resources `rules/numbers.tsv` (≥ 200 rows `lang<TAB>input<TAB>expected`), `rules/durations.tsv` (≥ 120 rows, expected in ISO-8601 like `PT1H30M`), `rules/phones.tsv` (≥ 60 rows)

**Interfaces:**
- Produces:
  - `object NumberParser { fun parse(text: String, lang: Lang): Double?; fun parseInt(text: String, lang: Lang): Int? }`. It accepts digits, words, and mixes ("2 mil", "twenty 5"), negatives ("minus/menos"), decimals ("point/coma", ES "1,5", EN "1.5"), fractions "half/medio/media", "quarter/cuarto", ordinals "first…tenth / primero…décimo" (in `parseInt`), and "a hundred / cien / ciento".
  - `object DurationParser { fun parse(text: String, lang: Lang): java.time.Duration? }`. It covers "5 minutes", "an hour and a half", "hora y media", "1h30", "90 seconds", "dos horas y cuarto", "half an hour / media hora" and "cinco minutos y medio".
  - `object PhoneNumberParser { fun parse(text: String, lang: Lang): String? }`. It returns digits with an optional leading `+`, accepting grouped digits, digit words and pair/triple words ("seis uno dos, treinta y cuatro, cincuenta y seis…", "plus/más 34"). It returns null if fewer than 3 digits.

- [ ] **Step 1:** Write the TSVs and the parameterized tests. They must include:
  - `es 1,5 → 1.5`
  - `en 1,500 → 1500`
  - `es veintitrés → 23`
  - `es mil novecientos noventa y nueve → 1999`
  - `en a hundred and five → 105`
  - `es menos tres → -3`
  - `en PT1H30M ← an hour and a half`
  - `es PT2H15M ← dos horas y cuarto`
  - `es "+34612345678" ← más treinta y cuatro seis uno dos tres cuatro cinco seis siete ocho`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Use one word table per language with a tokenized left-to-right accumulator (units/tens/hundreds/thousands/millions); ES handles "y" joiners and the "veinti-" compounds.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): bilingual number, duration and phone-number parsing`.

### Task 3: Dates, times and recurrence

**Files:**
- Create: `brain/src/main/kotlin/.../rules/DateTimeParser.kt`, `rules/DateTimeValue.kt`
- Test: `rules/DateTimeParserTest.kt`; resource `rules/datetimes.tsv` (≥ 300 rows: `lang<TAB>context<TAB>input<TAB>expected`, where `context` ∈ `alarm|reminder|event|query` and `expected` is `date=…;time=…;days=…;relative=…` with `-` for null)

**Interfaces:**
- Produces: `data class DateTimeValue(val date: java.time.LocalDate?, val time: java.time.LocalTime?, val repeat: Recurrence, val relative: Boolean)` and `class DateTimeParser(private val clock: java.time.Clock) { fun parse(text: String, lang: Lang, context: TimeContext): DateTimeValue?; fun parseRange(text: String, lang: Lang): DateRange? }` with `enum class TimeContext { ALARM, REMINDER, EVENT, QUERY }`.
- Behaviour:
  - Absolute times: "7", "7 pm", "19:30", "seven thirty", "half past seven", "quarter to eight", "a las siete y media", "a las ocho menos cuarto", "a las 7 de la tarde", "noon / mediodía", "midnight".
  - Relative times: "in 20 minutes / dentro de 20 minutos / en 20 minutos" set `relative=true` and fill date+time from the clock.
  - Dates: "today / hoy", "tomorrow / mañana", "day after tomorrow / pasado mañana", "on friday / el viernes", "next monday / el lunes que viene / el próximo lunes", "november 12 / el 12 de noviembre", "the 12th / el día 12".
  - Recurrence: "every day / todos los días / cada día", "weekdays / entre semana / de lunes a viernes", "weekends / los fines de semana", "every monday / todos los lunes".
  - Ranges for `parseRange`: "today", "tomorrow", "this week / esta semana", "this weekend", "next week".
  - "mañana" means tomorrow unless followed by a time-of-day phrase ("por la mañana", "de la mañana").
  - Global Constraints' bare-hour and day-part rules apply. A date alone with context `EVENT` leaves `time` null.

- [ ] **Step 1:** Write `datetimes.tsv` + parameterized test with the fixture clock. It must include:
  - `es alarm "a las 10"` at 10:15 → `date=2026-11-04;time=22:00`
  - `en reminder "in 30 minutes"` with a clock at `23:50` → `date=2026-11-05;time=00:20;relative=true`
  - `es reminder "mañana por la mañana"` → `date=2026-11-05;time=09:00`
  - `en alarm "every weekday at 6:45"` → `days=MON,TUE,WED,THU,FRI;time=06:45`
  - `es event "el viernes"` → `date=2026-11-06;time=-`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement on top of `NumberParser`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): bilingual date, time and recurrence parsing`.

### Task 4: Maths, units and cities

**Files:**
- Create: `brain/src/main/kotlin/.../rules/MathParser.kt`, `rules/Units.kt`, `rules/CityTimeZones.kt`; resource `brain/src/main/resources/cities.tsv` (≥ 600 rows `name_en<TAB>name_es<TAB>country_code<TAB>iana_zone`, covering every capital, the 100 largest metros, every Spanish province capital and every Latin American capital)
- Test: `rules/MathParserTest.kt` (resource `rules/math.tsv` ≥ 120 rows), `rules/UnitsTest.kt` (resource `rules/units.tsv` ≥ 150 rows), `rules/CityTimeZonesTest.kt`

**Interfaces:**
- Produces: `object MathParser { fun evaluate(text: String, lang: Lang): Double? }`. Supported: + − × ÷ in words and symbols ("plus/más", "minus/menos", "times/por/multiplicado por", "divided by/entre/dividido entre"), percent ("15% of 80", "el 15 por ciento de 80"), square root ("raíz cuadrada de"), powers ("to the power of / elevado a", "squared/al cuadrado", "cubed/al cubo"). Standard precedence. Division by zero → null.
- Produces: `object Units { data class UnitDef(val id: String, val dimension: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double); fun resolve(text: String, lang: Lang): UnitDef?; fun convert(value: Double, from: UnitDef, to: UnitDef): Double? }`. Dimensions: length, mass, volume (incl. cups, tablespoons, fl oz, gallons), temperature (C/F/K), speed, area, time, digital storage. EN/ES names, plurals and abbreviations. Converting across dimensions → null.
- Produces: `object CityTimeZones { fun resolve(place: String): java.time.ZoneId? }` matching by `Fold.key` on either language name; the country name alone resolves to its capital's zone.

- [ ] **Step 1:** Write the TSVs and tests. They must include:
  - `es "el 15 por ciento de 80" → 12.0`
  - `en "2 to the power of 10" → 1024.0`
  - `es "raíz cuadrada de 144" → 12.0`
  - `en "5 divided by 0" → null`
  - `en 100 fahrenheit → celsius ≈ 37.78`
  - `es 3 tazas → mililitros ≈ 709.76`
  - `resolve("Tokio") == ZoneId.of("Asia/Tokyo")`
  - `resolve("nueva york") == ZoneId.of("America/New_York")`
  - every zone in `cities.tsv` loads with `ZoneId.of`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. `MathParser` is a recursive-descent parser over a token list produced from words/symbols.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): spoken maths, unit conversion and city time zones`.

### Task 5: Entity matching

**Files:**
- Create: `brain/src/main/kotlin/.../match/EntityMatcher.kt`, `match/Phonetic.kt`, `match/Relations.kt`
- Test: `match/EntityMatcherTest.kt`, `match/PhoneticTest.kt`

**Interfaces:**
- Produces:
  - `enum class Relation { MOTHER, FATHER, PARTNER, SISTER, BROTHER, SON, DAUGHTER, GRANDMOTHER, GRANDFATHER, BOSS, FRIEND }`
  - `object Relations { fun fromWords(text: String): Relation? }`. It covers EN/ES forms: mom/mum/mother/mommy/mamá/mama/mami/madre/mi madre…
  - `data class Candidate<T>(val item: T, val names: List<String>, val relations: Set<Relation> = emptySet())`
  - `data class Scored<T>(val item: T, val score: Float)`
  - `class EntityMatcher<T>(candidates: List<Candidate<T>>) { fun match(query: String, lang: Lang): List<Scored<T>> }`. It returns matches sorted by score, keeping only those ≥ 0.6, at most 5.
- Scoring:
  - Relation match → 1.0.
  - `Fold.key` equality on any name → 1.0.
  - Query equals a name's first token or full token subset → 0.92.
  - Phonetic key equality → 0.85. `Phonetic.key(s, lang)`: Double Metaphone primary code for EN; for ES, a Spanish phonetic key (b/v→b, c(e,i)/z/s→s, c(a,o,u)/k/qu→k, g(e,i)/j→j, ll/y→y, silent h dropped, rr→r, ñ→n).
  - Otherwise normalized Levenshtein similarity × 0.9.

- [ ] **Step 1:** Write tests:
  - a contact named "Mamá" plus another "Mama Africa Bar" with relation MOTHER on the first → `match("mi madre", ES).first().item` is the first contact
  - `match("jose")` matches "José Pérez"
  - `match("Ana")` returns both "Ana López" and "Ana María" ≥ 0.9
  - `match("spotifai")` ranks "Spotify" first
  - `match("xyz")` is empty
  - `Phonetic.key("Valencia", ES) == Phonetic.key("Balensia", ES)`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): fuzzy, phonetic and relationship-aware entity matching`.

### Task 6: Command building and keyword understanding

**Files:**
- Create: `brain/src/main/kotlin/.../nlu/CommandBuilder.kt`, `nlu/KeywordUnderstanding.kt`, resource `brain/src/main/resources/keywords/{en,es}.tsv`
- Test: `nlu/CommandBuilderTest.kt`, `nlu/KeywordUnderstandingTest.kt` (resource `nlu/keyword-cases.tsv`, ≥ 6 cases per intent per language)

**Interfaces:**
- Consumes: P1 `Command` classes, `SlotSpan`, `Interpretation`, `Understanding`; the Task 2–4 parsers.
- Produces: `class CommandBuilder(clock: java.time.Clock) { fun build(intent: String, slots: List<SlotSpan>, lang: Lang, normalizedText: String): Command? }`.
  - It maps slot text to typed fields with the rule parsers.
  - Unknown or unparseable slot values become `null` fields; it never throws.
  - `out_of_scope` → `OutOfScope(normalizedText)`.
  - Defaults: `MessagingApp.SMS` unless the `messaging_app` slot names WhatsApp/Telegram/Signal; `speaker=true` if the text contains speaker/altavoz; `VolumeStream.MEDIA` unless ring/ringtone/timbre/alarm is named; `TravelMode.DRIVING` unless walking/andando/a pie/transit/transporte público/bike/bici.
- Produces: `class KeywordUnderstanding(builder: CommandBuilder) : Understanding`. It is the deterministic fallback used when the model file is missing and as a test double.
  - Ordered regex rules per language from `keywords/{en,es}.tsv` (`intent<TAB>regex with named groups = slot types`).
  - Confidence: 0.9 for a full match, 0.6 for a keyword-only match, else `out_of_scope` at 0.3.
  - `lang`: the language whose rules matched; ties go to `langHint`.

- [ ] **Step 1:** Write `CommandBuilderTest` cases for every intent (one each; for example `build("timer.set", [duration:"diez minutos"], ES, …) == SetTimer(Duration.ofMinutes(10), null)`), and `keyword-cases.tsv` covering all 50 intents in both languages.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): command builder and keyword fallback understanding`.

### Task 7: Model runtime

**Files:**
- Create: `brain/src/main/kotlin/.../nlu/UnigramTokenizer.kt`, `nlu/NluLabels.kt`, `nlu/OrtIntentModel.kt`, `nlu/ModelUnderstanding.kt`
- Test: `nlu/UnigramTokenizerTest.kt` (fixture vocab `brain/src/test/resources/nlu/tiny-vocab.tsv`), `nlu/ModelUnderstandingTest.kt` (fake `IntentModel`), `nlu/GoldenParityTest.kt`

**Interfaces:**
- Consumes: P1 `IntentModel`, `ModelOutput`, `NLU_MAX_TOKENS`, and the artifact contract in `ModelContract.kt` KDoc.
- Produces: `class UnigramTokenizer(pieces: List<Pair<String, Float>>) { data class Encoding(val ids: LongArray, val mask: LongArray, val offsets: List<IntRange?>); fun encode(normalized: String): Encoding; companion object { fun fromTsv(text: String): UnigramTokenizer } }`.
  - Algorithm: SentencePiece unigram Viterbi, equivalent to `sentencepiece` encoding with a trimmed vocabulary.
  - Spaces become `▁`, with a dummy `▁` prefix.
  - Best path maximizes the sum of piece scores. A character with no covering piece becomes `<unk>` (id 3) with score `minScore - 10`.
  - Output is `<s>` + pieces (truncated to 46) + `</s>`, padded with `<pad>` to 48.
  - `offsets` maps each piece to its char range in `normalized`; specials and pads are `null`.
- Produces: `class NluLabels(val intents: List<String>, val slots: List<String>, val langs: List<Lang>) { companion object { fun fromJson(text: String): NluLabels } }`.
- Produces: `class OrtIntentModel(modelBytes: ByteArray) : IntentModel, AutoCloseable`. One `OrtSession` with intra-op threads = 2, created once.
- Produces: `class ModelUnderstanding(model: IntentModel, tokenizer: UnigramTokenizer, labels: NluLabels, builder: CommandBuilder) : Understanding`. Pipeline:
  1. Normalize.
  2. Encode.
  3. Run the model.
  4. Softmax the intent; take the top 3 as `alternatives`.
  5. Slots: per-token argmax; consecutive pieces of one type merge into a span; `B-` starts a new span; span text is the `normalized` substring covering the pieces' offsets, trimmed.
  6. Lang: argmax of `lang_logits`.
  7. `builder.build`.
- `GoldenParityTest` runs only when `models/nlu/tokenizer-golden.tsv` and `models/nlu/model-golden.jsonl` exist (`Assumptions.assumeTrue`). It asserts exact token ids for every golden line, and the same top-1 intent with confidence within 1e-3. P7 makes the files present, so it becomes mandatory there.

- [ ] **Step 1:** Write `UnigramTokenizerTest` with a 30-piece vocab where Viterbi must prefer `▁hola` (one piece) over `▁ho`+`la`, handle an unknown character, truncate a 60-word input to 48 ids, and produce correct offsets. Write `ModelUnderstandingTest` with a fake model emitting crafted logits for "llama a mamá" (intent `call.contact`, `B-contact` on the `▁ma` piece) → `CallContact(contact="mamá", …)` and confidence = softmax value.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS (golden test skipped).
- [ ] **Step 5:** Commit `feat(brain): unigram tokenizer and ONNX model understanding`.

### Task 8: Responses in English and Spanish

**Files:**
- Create: `brain/src/main/kotlin/.../respond/Phrasebook.kt`, `respond/ResponseWriter.kt`, `respond/Formats.kt`
- Test: `respond/ResponseWriterTest.kt`

**Interfaces:**
- Consumes: P1 `Outcome`, `Response`, `Card`, `UiAction`, `Choice`, `FailReason`, `CardoPermission`, `Command`.
- Produces: `class ResponseWriter(clock: java.time.Clock, random: kotlin.random.Random)` with:
  - `fun done(outcome: Outcome, lang: Lang): Response` — a card where the spec lists one.
  - `fun ask(slot: String, command: Command, lang: Lang): Response` — e.g. "Who should I call?" / "¿A quién llamo?", "What time?" / "¿A qué hora?"
  - `fun askChoice(slot: String, options: List<Choice>, lang: Lang): Response` — `Card.Choices` + `Pick` buttons.
  - `fun confirm(command: Command, preview: Outcome?, lang: Lang): Response` — readback + `Confirm`/`CancelAction` buttons. For example "Send "running late" to Ana?" / "¿Envío «llego tarde» a Ana?"
  - `fun askIntent(a: String, b: String, lang: Lang): Response`
  - `fun permission(p: CardoPermission, lang: Lang): Response` — one-line why + `Grant(p)`.
  - `fun unlock(lang: Lang): Response`
  - `fun failed(reason: FailReason, detail: String?, lang: Lang): Response`
  - `fun outOfScope(text: String, lang: Lang): Response` — offers a web search via a `Confirm` button.
  - `fun notHeard(attempt: Int, lang: Lang): Response` — the second attempt adds the `Type` button.
  - `fun help(lang: Lang): Response`, `fun canned(command: Command, lang: Lang): Response` (greeting/thanks/identity, ≥ 4 variants each per language), `fun cancelled(lang: Lang): Response`.
- `Formats`:
  - Times: EN `7:30 AM`, ES `7:30` (24 h) — speech for ES says "a las 7:30".
  - Durations: "5 minutes" / "5 minutos", "1 hour 30 minutes" / "1 hora y 30 minutos".
  - Numbers: up to 4 decimals, trailing zeros trimmed, ES decimal comma.
  - Lists: "Ana, Luis and Marta" / "Ana, Luis y Marta".
  - Speech text never contains emoji, symbols or markdown.

- [ ] **Step 1:** Write tests. Every `Outcome` subtype gives non-empty EN and ES text. Specific checks:
  - `done(TimerSet(10 min), ES).speech == "Temporizador de 10 minutos en marcha."`
  - `done(AlarmSet(07:00, tomorrow…), EN).display` contains `7:00 AM`
  - `Computed("15% of 80", 12.0)` in ES displays `12`
  - `Converted` in ES uses a decimal comma
  - `confirm(SendMessage(…))` has exactly the `Confirm` and `CancelAction` buttons
  - `notHeard(2, EN).buttons` contains `UiAction.Type`
  - with a seeded `Random`, canned output is deterministic.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. All strings live in `Phrasebook` keyed by (key, lang), so a reviewer sees the whole copy in one file.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(brain): bilingual response writer`.

### Task 9: Dialog manager

**Files:**
- Create: `brain/src/main/kotlin/.../dialog/CardoDialog.kt`, `dialog/DialogState.kt`
- Test: `dialog/CardoDialogTest.kt` (scripted conversations with `KeywordUnderstanding` and a fixed clock)

**Interfaces:**
- Consumes: `DialogManager`, `Turn`, `SessionInfo`, `Understanding`, `ResponseWriter`, `WakeWord`, the parsers, `IntentCatalog.requiredSlots`.
- Produces: `class CardoDialog(understanding: Understanding, writer: ResponseWriter, clock: java.time.Clock) : DialogManager`.
- Behaviour:
  - `onUtterance` normalizes and strips a leading wake word. An empty remainder after a wake word → `Reply("Yes?"/"¿Sí?", listen=true)`. An empty transcript without one → `notHeard`.
  - While waiting for a slot, first try parsing the answer with that slot's parser. Contact/app/name slots take the raw remainder. If the answer instead interprets as a different intent at ≥ 0.85 (other than yes/no/cancel), start that command (Review Focus 4).
  - While waiting for a choice, match ordinals ("the first one", "el segundo"), number labels ("mobile/móvil/casa/trabajo") or option labels via `EntityMatcher`.
  - While waiting for confirmation: Yes → `Run(cmd, confirmed=true)`; No/Cancel → `cancelled`, then `Close` on the next turn.
  - Apply the confidence policy and confirmation rules from Global Constraints.
  - `Repeat` replays the last `Reply`. `Cancel` replies `cancelled` with `listen=false`.
  - `onResult` maps each `ActionResult` per the spec §5/§10 table: `NeedsChoice`/`NeedsConfirmation` keep the turn open (`listen=true`); `Done`/`Failed`/`NeedsPermission`/`NeedsUnlock` end it (`listen=false`).
  - The language for all replies is the latest `Interpretation.lang`; before any interpretation it's `SessionInfo.langHint`.
  - An out-of-scope offer accepted via `onUiAction(Confirm)` or "yes" → `Run(WebSearch(original text))`.

- [ ] **Step 1:** Write scripted tests:
  - (a) "pon un temporizador de diez minutos" → `Run(SetTimer(10m))`; `onResult(Done(TimerSet))` → Reply ES, `listen=false`.
  - (b) "call Ana" → `Run(CallContact("ana"))` → `NeedsChoice(contact, 2 options)` → "the second one" → `Run(…, chosen=option2)`.
  - (c) "text mum I'm late" → `Run` → `NeedsConfirmation` → "yes" → `Run(confirmed=true)`.
  - (d) "remind me to buy milk" (no time) → ask time → "at 5" → `Run(CreateReminder(task="buy milk", at=17:00 today))`.
  - (e) Review Focus 4: an ask is pending, then "set a timer for 5 minutes" → `Run(SetTimer)`.
  - (f) "hey cardo" alone → "Yes?" with `listen=true`.
  - (g) "ricardo dice hola" → not treated as wake; out of scope.
  - (h) Two empty transcripts → second reply has the `Type` button.
  - (i) An intent confidence of 0.7 → `askIntent` with the top two.
  - (j) "repeat" → identical last reply.
  - (k) "never mind" during a confirmation → cancelled, then `Close`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS. Run the full `./gradlew :brain:test` → all green.
- [ ] **Step 5:** Commit `feat(brain): dialog manager with clarifications, confirmations and follow-ups`.
