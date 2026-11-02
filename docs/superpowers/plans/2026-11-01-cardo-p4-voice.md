# Cardo P4 — Voice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pick the wake-word, speech-recognition and speech-synthesis models by measurement, pin them, and build the `:voice` module. It hears "Cardo", captures the command that follows (including the second before the wake word), transcribes it on-device, and speaks replies — from the microphone or from WAV files.

**Architecture:** First a host-side bake-off in `ml/voice/` (Python, sherpa-onnx 2.0.1) on synthesized and openly licensed audio, producing `docs/benchmarks/voice-bakeoff.md` and pinned entries in `artifacts.lock.json`. Then `:voice` wraps sherpa-onnx's Kotlin API behind small interfaces, with a coroutine state machine (`CardoVoicePipeline`) that is unit-tested with fakes and instrumented-tested on the emulator with real models and WAV fixtures.

**Tech Stack:** Python 3.12 via `uv`, `sherpa-onnx==2.0.1`, `numpy`, `soundfile`, `jiwer`, a MOS predictor (UTMOS via its published checkpoint), `vosk` (baseline only); Kotlin, coroutines/Flow, sherpa-onnx static AAR (from P1), Android `AudioRecord`/`AudioTrack`, JUnit4/Robolectric, AndroidX test on the `cardo-api37` emulator.

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md` (sections 4 "Runtime flow" and "Listening policy", 6, 9)

## Global Constraints

- Audio: 16 kHz mono, float samples in [-1, 1] inside the pipeline (PCM16 from `AudioRecord`, converted).
- Ring buffer: 2 s. Pre-roll for ASR: the window starts 1.0 s before the wake hit's end sample.
- Endpointing: end of speech after 600 ms of silence; hard cap 8 s of capture; no speech within 4 s of capture start → `NoSpeech`.
- Wake targets: recall ≥ 95% at 10 dB SNR, and ≤ 1 false accept per 10 hours of negative speech after the second-stage transcript check (the transcript starts with `cardo` / `hey cardo` / `oye cardo`, never `ricardo`).
- ASR selection: WER on command-style utterances per language, latency for a 4 s utterance on 2 host threads ≤ 250 ms, and size. Pick the smallest model within 2 WER points of the best that meets latency. (End-to-end intent accuracy with the final NLU model is re-checked in P7; the lock file makes swapping cheap.)
- TTS: one voice per language at launch. Licence CC0, CC-BY, MIT or Apache-2.0 only. Host time-to-first-audio ≤ 150 ms for a 10-word sentence. Rank by predicted MOS and ASR round-trip WER.
- Nothing is downloaded at runtime; the app has no `INTERNET` permission. Models arrive via `artifacts.lock.json` at build time.
- Audio is never written to disk by `:voice` in release builds.
- Module namespace `io.github.sodafmr.cardo.voice`; `:voice` must not depend on `:brain` (pass language as a BCP-47 tag string, `"en"`/`"es"`).
- Commits: plain messages, no `Co-Authored-By` / "Generated with" trailers. Bake-off audio and downloaded corpora live under `ml/voice/out/` (git-ignored).
- The GPU is busy with other work; everything runs on CPU. Machine RAM is limited: run one heavy job at a time.

## Review Focus

1. The user starts the command *before* the wake word fully ends, or with no pause ("Cardoponuntemporizador") — the 1 s pre-roll plus second-stage check must still yield a usable transcript.
2. Silence or background TV right after a false wake must end in `NoSpeech` within 4 s, never a stuck `CAPTURING` state.
3. Another app takes the microphone (or `AudioRecord.read` returns an error) mid-listening — the pipeline must emit `Error`, move to `PAUSED`, and recover on `resume()` without leaking the recorder.
4. Cardo's own voice saying "Cardo" (e.g. "I'm Cardo") must not wake it — wake detection is paused while speaking.
5. A transcript in the other language than the last one (user switches EN↔ES between turns) must be transcribed in that language, not forced to the previous one.

---

### Task 1: Bench project, candidate models and command scripts

**Files:**
- Create: `ml/voice/pyproject.toml`, `ml/voice/README.md`, `ml/voice/.gitignore` (`out/`, `.venv/`), `ml/voice/cardo_voice/models.py`, `ml/voice/cardo_voice/candidates.json`, `ml/voice/data/commands/{en,es}.txt`, `ml/voice/data/hard_negatives.txt`, `ml/voice/tests/test_models.py`

**Interfaces:**
- `candidates.json` lists every candidate:
  - `{name, kind: kws|vad|asr|tts, url, sha256, license, langs}`
  - KWS: the sherpa-onnx open-vocabulary zipformer KWS models (gigaspeech EN 3.3M; zh-en 3M 2025-12-20; any newer EN or multilingual KWS release).
  - VAD: silero VAD as packaged by sherpa-onnx.
  - ASR: Whisper tiny/base/small int8 multilingual; Parakeet-TDT multilingual int8 if published for sherpa-onnx; any other sherpa-onnx model covering both EN and ES; Vosk small EN + small ES as baseline.
  - TTS: Piper EN and ES voices with eligible licences; Kokoro multi-lang.
  - Check the sherpa-onnx release pages for current names; record what you found.
- `models.fetch(name) -> Path` downloads into `out/models/<name>` with sha256 verification.
- `commands/{en,es}.txt`: ≥ 300 natural assistant commands per language covering every domain in spec §3.
- `hard_negatives.txt`: the spec's list plus ≥ 40 more near-misses in both languages ("Ricardo", "cardio", "car door", "cardado", "guardo", "Leonardo", "card", "cuadro", "cardinal", "Eduardo", "lo guardo", "acuerdo", "cartón", "Bernardo"…).

- [ ] **Step 1:** Write `test_models.py` (every candidate has a licence, a sha256 and a supported kind; command files have ≥ 300 unique lines).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement, fill in the lists, fetch every candidate once.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(voice-bench): candidate models and command scripts`.

### Task 2: Test audio

**Files:**
- Create: `ml/voice/cardo_voice/synth.py`, `ml/voice/cardo_voice/corpora.py`, `ml/voice/cardo_voice/mix.py`, `ml/voice/tests/test_mix.py`

**Interfaces:**
- `synth.py`: renders text with **many** speakers. Use multi-speaker TTS models (e.g. Piper `libritts_r` for EN, every eligible ES voice and speaker, Kokoro EN/ES voices) with speed 0.85–1.2 and pitch ±2 semitones. Speakers used for fixtures must not include the voices that will ship (decided in Task 5); keep a held-back list.
- Positives: for ≥ 60 distinct speakers per language, "Cardo, …" / "Hey Cardo, …" / "Oye Cardo, …" + a command from `commands/*.txt`, with 0–400 ms pause variants (including 0 ms — Review Focus 1). ≥ 1,500 clips per language.
- Negatives:
  - ≥ 10 h total of openly licensed read speech: LibriSpeech dev-clean + test-clean (CC BY 4.0) for EN, and Multilingual LibriSpeech Spanish test/dev (CC BY 4.0) for ES, ≥ 3 h of it ES.
  - Synthesized hard negatives (every line × ≥ 20 speakers).
  - Synthesized commands without the wake word.
- Noise: babble (overlapped synthetic speech), pink and brown noise, plus an openly licensed environmental noise set if available (record its licence). Mix at 20/10/5 dB SNR.
- `mix(clean, noise, snr_db) -> np.ndarray` and manifests `out/fixtures/{positives,negatives}.jsonl` (`path, text, lang, speaker, snr, wake_end_s`).

- [ ] **Step 1:** Write `test_mix.py`: mixing at 10 dB gives a measured SNR within ±0.5 dB; output stays in [-1, 1]; 16 kHz mono.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement, generate the fixtures, download the corpora.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(voice-bench): synthesized and corpus test audio with noise mixing`.

### Task 3: Wake-word bake-off

**Files:**
- Create: `ml/voice/cardo_voice/bench_wake.py`, `docs/benchmarks/voice-bakeoff.md` (Wake section)

**Interfaces:**
- For each KWS model and keyword token set:
  - Keyword sets: `CARDO`; `CARDO` + `HEY CARDO` + `OYE CARDO`; plus phonetic spellings that model's tokens allow.
  - Thresholds swept from 0.05 to 0.6, with the boosting score swept.
  - Measure recall per SNR and language on positives, and false accepts per hour on negatives **before and after** the second-stage check.
  - The second stage is emulated by decoding the pre-roll window with the ASR candidate chosen in Task 4 (a provisional mid-size Whisper until then) and testing that the normalized text starts with a wake variant (not `ricardo`).
- Measure CPU: real-time factor of KWS+VAD on 1 thread.
- Output: chosen model, keywords file content and three thresholds (LOW/NORMAL/HIGH sensitivity). NORMAL must meet the Global Constraints targets.

- [ ] **Step 1:** Implement and run the sweep.
- [ ] **Step 2:** Write the Wake section: DET-style table per model, chosen operating points, CPU cost, and the honest caveat that synthetic audio flatters recall.
- [ ] **Step 3:** **If no candidate meets NORMAL targets:** add Task 3b to this plan before continuing. Train a small custom "cardo" keyword model (openWakeWord-style or sherpa-onnx KWS fine-tune) on synthetic positives with held-back speakers for validation, then re-run this task's measurement. Record the decision in the report.
- [ ] **Step 4:** Commit `bench(voice): wake-word bake-off and operating points`.

### Task 4: Speech-recognition bake-off

**Files:**
- Create: `ml/voice/cardo_voice/bench_asr.py`; extend `docs/benchmarks/voice-bakeoff.md` (ASR section)

**Interfaces:**
- For each ASR candidate, on clean and 10 dB command clips per language, measure:
  - WER (after `TextNormalizer` rules — reuse `ml/nlu/cardo_nlu/normalize.py` if present, else copy the five rules from P2 Task 1)
  - language-ID accuracy if the model reports it
  - latency for 4 s audio (2 threads, median of 50)
  - model size
- Selection per Global Constraints. Record the runner-up as the swap candidate.

- [ ] **Step 1:** Implement and run.
- [ ] **Step 2:** Write the ASR section with the table and decision.
- [ ] **Step 3:** Re-run Task 3's second-stage numbers with the chosen ASR model and update the Wake section.
- [ ] **Step 4:** Commit `bench(voice): speech-recognition bake-off`.

### Task 5: Speech-synthesis bake-off

**Files:**
- Create: `ml/voice/cardo_voice/bench_tts.py`; extend `docs/benchmarks/voice-bakeoff.md` (TTS section)

**Interfaces:**
- For each eligible voice, synthesize 40 typical Cardo replies in its language (take them from `brain/.../respond/Phrasebook.kt` if it exists, else write 40 per language). Measure:
  - predicted MOS
  - ASR round-trip WER with the chosen ASR model
  - time to first audio
  - real-time factor
  - size and licence
- Choose one EN and one ES voice: highest MOS among those meeting latency and licence. Add them to `synth.py`'s held-back list so fixtures never use them.

- [ ] **Step 1:** Implement and run.
- [ ] **Step 2:** Write the TTS section.
- [ ] **Step 3:** Commit `bench(voice): speech-synthesis bake-off`.

### Task 6: Pin the chosen models

**Files:**
- Modify: `artifacts.lock.json`, `gradle/fetch-artifacts.settings.gradle.kts` (archive support), `voice/build.gradle.kts`, `models/LICENSES.md` (create if absent; append-friendly)
- Create: `voice/src/main/assets/models/.gitignore` (ignore everything but itself), `voice/src/main/kotlin/io/github/sodafmr/cardo/voice/ModelCatalog.kt`

**Interfaces:**
- Lock entries gain optional `"extract": "tar.bz2"|"zip"` and `"keep": ["<glob>", …]`. When `extract` is set, `dest` is a directory, the archive is unpacked there keeping only `keep` matches, and a `.sha256` stamp file makes the step idempotent.
- Chosen models land in `voice/src/main/assets/models/{kws,vad,asr,tts-en,tts-es}/`.
- Produces: `object ModelCatalog` with constant asset paths and configs:
  - `kwsModelDir`, `kwsKeywords` (contents), `kwsThresholds: Map<Sensitivity, Float>`
  - `vadModel`
  - `asrConfig` (model type + files)
  - `ttsVoices: Map<String /*"en"|"es"*/, TtsVoice>`
- `androidResources.noCompress` includes `onnx`, `ort`, `bin`, `txt`.

- [ ] **Step 1:** Implement archive support; verify the clean-checkout and second-build behaviour from P1's Review Focus 1–2 still hold with archives.
- [ ] **Step 2:** `./gradlew :app:assembleDebug`. Report the APK size per ABI in the voice report. Expected: the arm64 release estimate is ≤ 200 MB including the NLU model at 40 MB.
- [ ] **Step 3:** Commit `build(voice): pin chosen wake, VAD, ASR and TTS models`.

### Task 7: Audio sources, ring buffer and level meter

**Files:**
- Create under `voice/src/main/kotlin/io/github/sodafmr/cardo/voice/audio/`: `AudioSource.kt`, `MicAudioSource.kt`, `WavAudioSource.kt`, `RingBuffer.kt`, `LevelMeter.kt`
- Test: `voice/src/test/kotlin/.../audio/RingBufferTest.kt`, `WavAudioSourceTest.kt`, `LevelMeterTest.kt` (Robolectric where Android types are touched)

**Interfaces:**
- Produces:
  - `interface AudioSource : AutoCloseable { val sampleRate: Int; fun start(); fun read(buffer: FloatArray): Int; fun stop() }`. `read` blocks, returns samples read, `-1` at end of stream, and throws `AudioUnavailableException` when the device fails.
  - `class MicAudioSource(context: Context) : AudioSource`: `AudioRecord` with `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz mono PCM16, a buffer of 4× the minimum size.
  - `class WavAudioSource(input: InputStream, realtime: Boolean) : AudioSource`: PCM16 mono 16 kHz WAV only; `realtime=true` sleeps to match wall-clock.
  - `class RingBuffer(capacitySamples: Int) { fun write(src: FloatArray, n: Int); fun last(nSamples: Int): FloatArray; val totalWritten: Long }`
  - `class LevelMeter(attackMs: Int = 60, releaseMs: Int = 150, sampleRate: Int = 16000) { fun update(chunk: FloatArray, n: Int): Float }`. Output 0..1, mapped from RMS dBFS −60…−10.

- [ ] **Step 1:** Write tests:
  - RingBuffer wraparound: after writing 3 s into a 2 s buffer, `last(16000)` equals the final second
  - WAV reader rejects stereo/44.1 kHz with `IllegalArgumentException` and reads a generated 1 kHz tone exactly
  - LevelMeter rises within 60 ms on a loud chunk and decays more slowly
- [ ] **Step 2:** Run `./gradlew :voice:testDebugUnitTest` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(voice): audio sources, ring buffer and level meter`.

### Task 8: Engine wrappers

**Files:**
- Create under `voice/src/main/kotlin/io/github/sodafmr/cardo/voice/engine/`: `WakeWordDetector.kt`, `EndpointDetector.kt`, `SpeechRecognizer.kt`, `SpeechSynthesizer.kt`, `Engines.kt`
- Test (instrumented): `voice/src/androidTest/kotlin/.../engine/EnginesTest.kt` with fixture WAVs in `voice/src/androidTest/assets/fixtures/` (≤ 20 short clips copied from the bake-off: positives EN/ES, a "Ricardo" negative, silence, a command without wake)

**Interfaces:**
- Produces:
  - `enum class Sensitivity { LOW, NORMAL, HIGH }`
  - `class WakeWordDetector(assets: AssetManager, sensitivity: Sensitivity) { fun accept(samples: FloatArray, n: Int): WakeHit?; fun reset(); fun setSensitivity(s: Sensitivity) }`, with `data class WakeHit(val keyword: String, val endSample: Long)`
  - `class EndpointDetector(assets: AssetManager) { fun accept(samples: FloatArray, n: Int): EndpointEvent?; fun reset() }`, with `sealed interface EndpointEvent { data object SpeechStart; data object SpeechEnd }` (600 ms silence)
  - `class SpeechRecognizer(assets: AssetManager) { suspend fun transcribe(samples: FloatArray): Transcript }`, with `data class Transcript(val text: String, val languageTag: String?, val decodeMs: Long)` (runs on `Dispatchers.Default`, 2 threads)
  - `class SpeechSynthesizer(assets: AssetManager) { val level: StateFlow<Float>; val speaking: StateFlow<Boolean>; suspend fun speak(text: String, languageTag: String); fun stop() }`. It streams to `AudioTrack` (USAGE_ASSISTANT); `speak` returns when playback ends or `stop()` is called.
  - `class Engines(context: Context)` lazily constructs and holds one of each; `fun release()`.

- [ ] **Step 1:** Write instrumented tests:
  - each EN/ES positive fixture yields a `WakeHit`; "Ricardo" and silence yield none
  - `EndpointDetector` reports `SpeechStart` then `SpeechEnd` on a command clip
  - `transcribe` of an EN fixture contains its key command words; the ES fixture likewise (WER-tolerant: assert ≥ 2 key words)
  - `speak("Hola, soy Cardo", "es")` completes, `speaking` toggles true→false, and `level` rises above 0.05 during playback
- [ ] **Step 2:** `scripts/emulator.sh start`; `./gradlew :voice:connectedDebugAndroidTest` → FAIL.
- [ ] **Step 3:** Implement with sherpa-onnx's Kotlin API (`KeywordSpotter`, `Vad`, `OfflineRecognizer` or `OnlineRecognizer` per the chosen model, `OfflineTts`), loading from `AssetManager` via `ModelCatalog` paths.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(voice): sherpa-onnx wake, endpoint, recognizer and synthesizer wrappers`.

### Task 9: Voice pipeline state machine

**Files:**
- Create: `voice/src/main/kotlin/io/github/sodafmr/cardo/voice/VoicePipeline.kt`, `CardoVoicePipeline.kt`
- Test: `voice/src/test/kotlin/.../CardoVoicePipelineTest.kt` (fakes for source/detectors/recognizer, `runTest` virtual time); instrumented `voice/src/androidTest/kotlin/.../PipelineEndToEndTest.kt` (real engines + `WavAudioSource`)

**Interfaces:**
- Produces:
  ```kotlin
  enum class VoiceState { IDLE, WAKE_LISTENING, CAPTURING, TRANSCRIBING, PAUSED }
  sealed interface VoiceEvent {
      data class WakeDetected(val keyword: String) : VoiceEvent
      data object SpeechStarted : VoiceEvent
      data class Transcribed(val text: String, val languageTag: String?, val includesWake: Boolean, val audioMs: Long, val decodeMs: Long) : VoiceEvent
      data object NoSpeech : VoiceEvent
      data class Failed(val cause: Throwable) : VoiceEvent
  }
  interface VoicePipeline {
      val state: StateFlow<VoiceState>
      val level: StateFlow<Float>
      val events: SharedFlow<VoiceEvent>
      fun start(source: AudioSource)          // begin WAKE_LISTENING
      fun captureNow()                        // skip wake: CAPTURING immediately (assistant gesture, follow-up)
      fun pause()                             // stop reading the mic (PAUSED); used while speaking and when policy pauses
      fun resume()                            // back to WAKE_LISTENING
      fun stop()                              // release source, IDLE
      fun setSensitivity(s: Sensitivity)
  }
  class CardoVoicePipeline(engines: EnginesApi, scope: CoroutineScope) : VoicePipeline
  ```
  `EnginesApi` is an interface over the four Task 8 classes so tests can fake them.
- Behaviour:
  - In `WAKE_LISTENING`, every chunk goes to the ring buffer and wake detector.
  - On a hit: emit `WakeDetected` → `CAPTURING` with the ASR buffer seeded from `ring.last(1.0 s + samples since hit)`.
  - In `CAPTURING`, the endpoint detector runs: `SpeechEnd` or the 8 s cap → `TRANSCRIBING` → `Transcribed(includesWake = captured via wake)`. Then go to `PAUSED` — the controller decides when to `resume()` or `captureNow()`.
  - No `SpeechStart` within 4 s → `NoSpeech` → `PAUSED`.
  - `AudioUnavailableException` → `Failed` → `PAUSED` with the source stopped; `resume()` restarts the source.
  - `level` is updated in every state that reads audio.

- [ ] **Step 1:** Write unit tests with fakes:
  - wake → speech → end → `Transcribed(includesWake=true)` with audio starting 1 s before the hit
  - wake → silence → `NoSpeech` at 4 s (virtual time)
  - `captureNow()` → `Transcribed(includesWake=false)`
  - a source throwing mid-capture → `Failed` then `PAUSED`, and `resume()` reopens it
  - `pause()` stops wake detection (a hit fed while paused is ignored — Review Focus 4)
  - 8 s cap

  Write an instrumented test: a WAV of "Hey Cardo, set a timer for ten minutes" through `WavAudioSource(realtime=false)` → one `Transcribed` whose text contains "timer" and "ten"/"10".
- [ ] **Step 2:** Run unit tests → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run unit + instrumented tests → PASS.
- [ ] **Step 5:** Commit `feat(voice): wake-to-transcript pipeline state machine`.
