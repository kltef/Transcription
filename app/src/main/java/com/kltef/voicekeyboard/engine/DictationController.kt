package com.kltef.voicekeyboard.engine

import android.content.Context
import android.util.Log
import com.kltef.voicekeyboard.asr.StreamingRecognizer
import com.kltef.voicekeyboard.asr.WhisperRefiner
import com.kltef.voicekeyboard.audio.AudioRecorder
import com.kltef.voicekeyboard.util.Prefs
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Orchestrates the full dictation pipeline:
 *
 *   mic → streaming recognizer (live partials) → on endpoint → Whisper refine → final text
 *
 * Everything recognition-related runs on a single worker thread, so partial and final
 * events are emitted in strict order (the audio callback only enqueues raw samples).
 * Callbacks fire on the worker thread; the listener must marshal UI work itself.
 */
class DictationController(
    private val context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    enum class State { IDLE, LISTENING, REFINING }

    interface Listener {
        /** Best-effort live transcript of the current (in-progress) utterance. */
        fun onPartial(text: String)
        /** A finished, finalized utterance ready to be committed. */
        fun onFinalSegment(text: String)
        fun onStateChanged(state: State)
        fun onError(message: String)
    }

    private val queue = LinkedBlockingQueue<FloatArray>()
    private val utterance = ArrayList<Float>(16000 * 30)

    @Volatile private var running = false
    private var worker: Thread? = null

    private val recorder = AudioRecorder { samples -> if (running) queue.offer(samples) }

    // Engines are loaded lazily on the worker thread (heavy native init).
    private var streaming: StreamingRecognizer? = null
    private var whisper: WhisperRefiner? = null
    private var whisperTriedToLoad = false

    val isActive: Boolean get() = running

    fun start() {
        if (running) return
        queue.clear()
        utterance.clear()
        running = true

        if (!recorder.start()) {
            running = false
            listener.onError("Could not access the microphone")
            return
        }
        worker = thread(name = "voicekb-asr") { runLoop() }
        listener.onStateChanged(State.LISTENING)
    }

    fun stop() {
        if (!running) return
        running = false
        recorder.stop()
        worker?.join(2000)
        worker = null
        queue.clear()
        listener.onStateChanged(State.IDLE)
    }

    fun shutdown() {
        stop()
        streaming?.release(); streaming = null
        whisper?.release(); whisper = null
    }

    private fun runLoop() {
        val asr = ensureStreaming() ?: run {
            running = false
            listener.onError("Speech model not ready — open the app to finish setup")
            return
        }
        asr.resetSession()

        while (running) {
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            for (s in chunk) utterance.add(s)

            val update = try {
                asr.accept(chunk)
            } catch (e: Throwable) {
                Log.e(TAG, "streaming decode failed", e); continue
            }

            if (prefs.streamingPreview && update.text.isNotBlank()) {
                listener.onPartial(update.text)
            }
            if (update.isEndpoint) {
                finalizeUtterance(update.text)
            }
        }

        // Drain whatever is left when the user stops.
        val tail = try { asr.finish() } catch (_: Throwable) { "" }
        finalizeUtterance(tail)
    }

    /** Produce the final text for the just-ended utterance (Whisper refine if enabled). */
    private fun finalizeUtterance(streamingText: String) {
        val samples = utterance.toFloatArray()
        utterance.clear()

        val refiner = if (prefs.refineWithWhisper) ensureWhisper() else null
        val finalText = if (refiner != null && samples.isNotEmpty()) {
            listener.onStateChanged(State.REFINING)
            val refined = refiner.transcribe(samples)
            if (running) listener.onStateChanged(State.LISTENING)
            refined.ifBlank { streamingText }
        } else {
            streamingText
        }

        if (finalText.isNotBlank()) listener.onFinalSegment(finalText.trim())
    }

    private fun ensureStreaming(): StreamingRecognizer? {
        streaming?.let { return it }
        return try {
            StreamingRecognizer(context.assets, prefs.threads).also { streaming = it }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load streaming model", e)
            null
        }
    }

    private fun ensureWhisper(): WhisperRefiner? {
        whisper?.let { return it }
        if (whisperTriedToLoad) return null
        whisperTriedToLoad = true
        if (!ModelManager.isWhisperReady(context)) return null
        whisper = WhisperRefiner.load(
            ModelManager.whisperModelFile(context).absolutePath,
            prefs.threads
        )
        return whisper
    }

    companion object {
        private const val TAG = "VoiceKbController"
    }
}
