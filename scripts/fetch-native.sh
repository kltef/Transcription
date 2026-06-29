#!/usr/bin/env bash
#
# Downloads the large binary dependencies that are NOT committed to git:
#   1. sherpa-onnx Android AAR (JNI .so for all ABIs + compiled Kotlin API)  -> app/libs/
#   2. The English streaming Zipformer model (sherpa-onnx)                    -> app/src/main/assets/streaming-zipformer/
#   3. whisper.cpp source (compiled by CMake at build time)                   -> app/src/main/cpp/whisper.cpp/
#
# The Whisper refine MODEL is downloaded by the app itself on first run (see ModelManager).
#
# Run this once before building:  bash scripts/fetch-native.sh
set -euo pipefail

SHERPA_VER="1.13.3"
WHISPER_VER="1.7.4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
ASSETS="$ROOT/app/src/main/assets/streaming-zipformer"
PUNCT_ASSETS="$ROOT/app/src/main/assets/punctuation"
WHISPER_DIR="$ROOT/app/src/main/cpp/whisper.cpp"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$LIBS" "$ASSETS" "$PUNCT_ASSETS"

# --- 1. sherpa-onnx AAR ----------------------------------------------------------------
AAR="sherpa-onnx-${SHERPA_VER}.aar"
if [ ! -f "$LIBS/$AAR" ]; then
  echo "Downloading $AAR ..."
  curl -fL --retry 3 -o "$LIBS/$AAR" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VER}/${AAR}"
else
  echo "$AAR already present."
fi

# --- 2. streaming Zipformer English model ---------------------------------------------
MODEL="sherpa-onnx-streaming-zipformer-en-2023-06-26"
NEEDED=(
  "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
  "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
  "joiner-epoch-99-avg-1-chunk-16-left-128.onnx"
  "tokens.txt"
)
have_all=true
for f in "${NEEDED[@]}"; do [ -f "$ASSETS/$f" ] || have_all=false; done

if [ "$have_all" = false ]; then
  echo "Downloading streaming model ..."
  curl -fL --retry 3 -o "$TMP/model.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
  tar -xjf "$TMP/model.tar.bz2" -C "$TMP"
  for f in "${NEEDED[@]}"; do
    cp "$TMP/$MODEL/$f" "$ASSETS/$f"
  done
  echo "Streaming model files placed in app/src/main/assets/streaming-zipformer/"
else
  echo "Streaming model already present."
fi

# --- 2b. punctuation + capitalization model (sherpa-onnx OnlinePunctuation) -------------
PUNCT="sherpa-onnx-online-punct-en-2024-08-06"
PUNCT_NEEDED=( "model.int8.onnx" "bpe.vocab" )
have_punct=true
for f in "${PUNCT_NEEDED[@]}"; do [ -f "$PUNCT_ASSETS/$f" ] || have_punct=false; done
if [ "$have_punct" = false ]; then
  echo "Downloading punctuation model ..."
  curl -fL --retry 3 -o "$TMP/punct.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/${PUNCT}.tar.bz2"
  tar -xjf "$TMP/punct.tar.bz2" -C "$TMP"
  for f in "${PUNCT_NEEDED[@]}"; do
    cp "$TMP/$PUNCT/$f" "$PUNCT_ASSETS/$f"
  done
  echo "Punctuation model files placed in app/src/main/assets/punctuation/"
else
  echo "Punctuation model already present."
fi

# --- 3. whisper.cpp source -------------------------------------------------------------
if [ ! -f "$WHISPER_DIR/CMakeLists.txt" ]; then
  echo "Downloading whisper.cpp v${WHISPER_VER} source ..."
  curl -fL --retry 3 -o "$TMP/whisper.tar.gz" \
    "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${WHISPER_VER}.tar.gz"
  tar -xzf "$TMP/whisper.tar.gz" -C "$TMP"
  rm -rf "$WHISPER_DIR"
  mv "$TMP/whisper.cpp-${WHISPER_VER}" "$WHISPER_DIR"
  echo "whisper.cpp source placed in app/src/main/cpp/whisper.cpp/"
else
  echo "whisper.cpp source already present."
fi

echo "Done. You can now build:  ./gradlew assembleDebug"
