package com.kltef.voicekeyboard.asr

import android.util.Log

/**
 * Wraps whisper.cpp (via our small JNI shim, libwhisper-jni.so) to re-transcribe a
 * finished utterance for higher accuracy and punctuation than the streaming engine.
 *
 * The model file (ggml-base.en, quantized) lives in the app's files dir and is loaded
 * once; [transcribe] is then called per utterance. Run it off the UI thread.
 */
class WhisperRefiner private constructor(private val ctx: Long, private val threads: Int) {

    /** @return polished text for the given 16 kHz mono samples, or "" on failure. */
    fun transcribe(samples: FloatArray): String {
        if (ctx == 0L || samples.isEmpty()) return ""
        return try {
            nativeTranscribe(ctx, samples, threads).trim()
        } catch (e: Throwable) {
            Log.e(TAG, "whisper transcribe failed", e)
            ""
        }
    }

    fun release() {
        if (ctx != 0L) nativeFree(ctx)
    }

    private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int): String
    private external fun nativeFree(ctx: Long)

    companion object {
        private const val TAG = "VoiceKbWhisper"

        init {
            System.loadLibrary("whisper-jni")
        }

        @JvmStatic private external fun nativeInit(modelPath: String, threads: Int): Long

        /** Loads the model at [modelPath]. Returns null if the model is missing/unreadable. */
        fun load(modelPath: String, threads: Int): WhisperRefiner? {
            val ctx = try {
                nativeInit(modelPath, threads)
            } catch (e: Throwable) {
                Log.e(TAG, "whisper init failed", e)
                0L
            }
            return if (ctx != 0L) WhisperRefiner(ctx, threads) else null
        }
    }
}
