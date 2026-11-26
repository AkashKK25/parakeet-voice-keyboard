# Parakeet Voice Keyboard (Android)

A completely local, open-source Android Input Method Editor (IME) voice keyboard powered by **NVIDIA Parakeet** ONNX models and **[Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)**. All audio capture and speech-to-text inference run 100% on-device with zero cloud APIs.

## Features
- **On-device Parakeet STT** via `OfflineRecognizer` (`com.k2fsa.sherpa.onnx`):
  - [Parakeet TDT 0.6B v3 (int8)](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) — 25 European languages, punctuation & casing (`modelType = "nemo_transducer"`)
  - [Parakeet TDT 0.6B v2 (int8)](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8) — English, punctuation & casing (`modelType = "nemo_transducer"`)
  - [Parakeet TDT-CTC 110M (int8)](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/english.html) — Lightweight English model (`OfflineNemoEncDecCtcModelConfig`)
- **Tap-to-Toggle or Hold-to-Talk** microphone dictation with live RMS audio level bar.
- **Built-in Editing Keys**: Smart auto-spacing, Undo Last Dictation, hold-to-repeat Backspace, Space, Enter, `,`, `.`, `?`, and quick keyboard switcher (`showInputMethodPicker`).
- **Two Ways to Install Models**:
  1. **One-tap in-app download** from [Sherpa-ONNX ASR Models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html) (downloads & extracts `.tar.bz2` into `filesDir/models/`).
  2. **100% offline import**: Transfer the `.tar.bz2` archive to your phone and select **Import Local .tar.bz2** in the setup screen.

## Setup & Build Instructions

1. **Add the Sherpa-ONNX Android AAR**
   - Download `sherpa-onnx-2.0.2.aar` (or latest) from [Sherpa-ONNX Releases](https://github.com/k2-fsa/sherpa-onnx/releases).
   - Place it in `app/libs/sherpa-onnx-2.0.2.aar`.
2. **Open in Android Studio**
   - Open the `ParakeetVoiceKeyboard/` folder in Android Studio (AGP `8.7.3`, Kotlin `2.0.21`, `compileSdk 35`, `minSdk 26`).
   - Sync Gradle and run on an `arm64-v8a` Android device (or `x86_64` emulator).
3. **Activate the Keyboard**
   - Open **Parakeet Voice Keyboard** on your phone.
   - Grant **Microphone** permission.
   - Download or import your preferred Parakeet model.
   - Tap **Open Keyboard Settings** -> enable **Parakeet Local Voice**, then tap **Switch to Parakeet**.
