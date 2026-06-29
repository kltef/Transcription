package com.kltef.voicekeyboard.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Wraps sherpa-onnx's streaming Zipformer transducer for low-latency, word-as-you-speak
 * recognition. Model files are read straight from the APK's assets (no copy needed).
 *
 * Not thread-safe: feed it from a single ASR thread.
 */
class StreamingRecognizer(
    assetManager: AssetManager,
    numThreads: Int,
) {
    /** Result of feeding a chunk of audio. */
    data class Update(val text: String, val isEndpoint: Boolean)

    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream

    init {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                decoder = "$MODEL_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                joiner = "$MODEL_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
            ),
            tokens = "$MODEL_DIR/tokens.txt",
            modelType = "zipformer2",
            numThreads = numThreads,
            debug = false,
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            // Endpoint detection segments speech into utterances (used to trigger refine/punctuation).
            // Tighter trailing-silence thresholds than the defaults so a phrase finalizes ~1 s after
            // you stop talking, which makes dictation feel snappier.
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 1.8f, 0.0f), // silence after no decoded text
                rule2 = EndpointRule(true, 1.0f, 0.0f),  // silence after some decoded text
                rule3 = EndpointRule(false, 0.0f, 20.0f) // hard cap on utterance length (s)
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(assetManager, config)
        stream = recognizer.createStream()
        Log.i(TAG, "Streaming recognizer ready (threads=$numThreads)")
    }

    /**
     * Feed 16 kHz mono samples. Returns the current best partial transcript and whether
     * sherpa-onnx detected an endpoint (a natural pause ending the utterance).
     */
    fun accept(samples: FloatArray): Update {
        stream.acceptWaveform(samples, AUDIO_SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val endpoint = recognizer.isEndpoint(stream)
        val text = recognizer.getResult(stream).text
        if (endpoint) {
            recognizer.reset(stream)
        }
        return Update(text, endpoint)
    }

    /** Flush remaining audio and return the final partial. Call when the user stops the mic. */
    fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        return recognizer.getResult(stream).text
    }

    /** Reset state for a new dictation session. */
    fun resetSession() {
        stream.release()
        stream = recognizer.createStream()
    }

    fun release() {
        stream.release()
        recognizer.release()
    }

    companion object {
        private const val TAG = "VoiceKbStreaming"
        private const val AUDIO_SAMPLE_RATE = 16000
        /** Folder inside app/src/main/assets that holds the streaming model files. */
        const val MODEL_DIR = "streaming-zipformer"
    }
}
