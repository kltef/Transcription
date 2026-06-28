# Voice Keyboard — on-device dictation for Android

A custom Android keyboard (IME) that lets you **dictate into any app, entirely on-device**.
Nothing is sent to the cloud — speech never leaves your phone.

It uses a two-stage **stream + refine** pipeline for the best of both speed and accuracy:

1. **sherpa-onnx** (streaming Zipformer transducer) shows words **live as you speak**.
2. **whisper.cpp** (`base.en`, quantized) re-transcribes each finished phrase for **accurate,
   punctuated** final text.

> Why a keyboard and not "inside Gboard"? Gboard is closed-source and does not let third-party
> engines power its voice typing. Being the keyboard is the only robust way to dictate into
> *every* app. This is the same approach FUTO Voice Input / Sayboard / HeliBoard take.

## How it works

```
mic ─► AudioRecorder (16 kHz mono)
        │
        ▼
   StreamingRecognizer (sherpa-onnx)  ──► live partials ──► InputConnection.setComposingText()
        │ endpoint (natural pause)
        ▼
   WhisperRefiner (whisper.cpp)        ──► final text   ──► InputConnection.commitText()
```

All recognition runs on one worker thread, so partial and final text are emitted in order.
See `engine/DictationController.kt` for the orchestration.

### Key source files

| Area | File |
|------|------|
| Keyboard (IME) | `app/src/main/java/.../ime/VoiceKeyboardService.kt` |
| Pipeline orchestration | `app/src/main/java/.../engine/DictationController.kt` |
| Mic capture | `app/src/main/java/.../audio/AudioRecorder.kt` |
| Streaming ASR (sherpa-onnx) | `app/src/main/java/.../asr/StreamingRecognizer.kt` |
| Whisper refine (JNI) | `app/src/main/java/.../asr/WhisperRefiner.kt` + `app/src/main/cpp/` |
| Model download | `app/src/main/java/.../engine/ModelManager.kt` |
| Onboarding / settings | `app/src/main/java/.../ui/` |

## Building

Requirements: Android SDK (platform 34, build-tools 34), **NDK 26**, **CMake 3.22.1**, JDK 17.

```bash
# 1. Fetch the large binaries that are NOT in git:
#    - sherpa-onnx AAR (JNI .so + Kotlin API) -> app/libs/
#    - English streaming Zipformer model      -> app/src/main/assets/streaming-zipformer/
bash scripts/fetch-native.sh

# 2. Build. CMake compiles whisper.cpp from source (cloned via FetchContent) for each ABI.
./gradlew assembleDebug      # or: gradle :app:assembleDebug
```

The Whisper refine model (`ggml-base.en-q5_1.bin`, ~57 MB) is **not** bundled — the app
downloads it on first run from the setup screen (see `ModelManager`).

## Installing & using

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. Open **Voice Keyboard** (the installed app) and run through setup:
   enable the keyboard, select it, grant the microphone, download the refine model.
2. In any text field, open the keyboard switcher and pick **Voice Keyboard**.
3. Tap the mic and speak. Words appear live; on each pause they snap to the refined version.

Verify it's truly on-device by enabling **Airplane Mode** — dictation keeps working
(after the one-time model download).

## Settings

- **Refine with Whisper** — accuracy vs. latency. Off = pure streaming, lowest latency.
- **Live streaming preview** — show words before refinement.
- **CPU threads** — speed vs. battery for both engines.
- **Haptics** — vibration on key/mic taps.

## Notes / roadmap

- First release targets **English**. Both engines support multilingual models (swap the
  streaming model + a multilingual Whisper build).
- A physical device is recommended for realistic latency/accuracy (emulators forward the host mic).
- The native dependencies (AAR, ONNX models, ggml model) are intentionally git-ignored; see
  `scripts/fetch-native.sh`.

## Credits / licenses

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) — streaming ASR.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) — refine pass.
- Whisper models © OpenAI (MIT).
