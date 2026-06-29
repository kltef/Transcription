package com.kltef.voicekeyboard.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig

/**
 * Adds punctuation and capitalization to raw streaming text using sherpa-onnx's
 * OnlinePunctuation model. The streaming Zipformer emits lowercase, unpunctuated text
 * (e.g. "one two three four"); this turns it into "One, two, three, four."
 *
 * Model files are read from the APK assets. Not thread-safe; call from the ASR worker.
 */
class PunctuationProcessor private constructor(private val impl: OnlinePunctuation) {

    fun format(text: String): String {
        if (text.isBlank()) return text
        return try {
            impl.addPunctuation(text)
        } catch (e: Throwable) {
            Log.e(TAG, "addPunctuation failed", e)
            text
        }
    }

    fun release() = impl.release()

    companion object {
        private const val TAG = "VoiceKbPunct"
        private const val MODEL_DIR = "punctuation"

        /** Returns null if the punctuation model is missing/unloadable. */
        fun load(assetManager: AssetManager, numThreads: Int): PunctuationProcessor? {
            return try {
                val config = OnlinePunctuationConfig(
                    model = OnlinePunctuationModelConfig(
                        cnnBilstm = "$MODEL_DIR/model.int8.onnx",
                        bpeVocab = "$MODEL_DIR/bpe.vocab",
                        numThreads = numThreads,
                    )
                )
                PunctuationProcessor(OnlinePunctuation(assetManager, config))
            } catch (e: Throwable) {
                Log.e(TAG, "failed to load punctuation model", e)
                null
            }
        }
    }
}
