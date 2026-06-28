package com.kltef.voicekeyboard.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures 16 kHz mono audio from the microphone and delivers it as normalized
 * float samples (range roughly [-1, 1]) — the format both sherpa-onnx and
 * whisper.cpp expect.
 *
 * Capture runs on its own thread; [onSamples] is invoked from that thread, so the
 * consumer is responsible for marshalling any UI work back to the main thread.
 */
class AudioRecorder(
    private val onSamples: (FloatArray) -> Unit,
) {
    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var record: AudioRecord? = null

    val isRecording: Boolean get() = running

    /** @return true if capture started; false if the recorder could not be initialized (e.g. no permission). */
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        // Use a generous buffer to avoid overruns; read in smaller chunks.
        val bufferSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)

        val rec = try {
            @Suppress("MissingPermission") // permission is verified by the caller before start()
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return false
        }

        record = rec
        running = true
        rec.startRecording()

        recordThread = thread(name = "voicekb-audio") {
            val pcm = ShortArray(CHUNK_SAMPLES)
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val out = FloatArray(n)
                for (i in 0 until n) {
                    out[i] = pcm[i] / 32768.0f
                }
                onSamples(out)
            }
        }
        return true
    }

    fun stop() {
        running = false
        recordThread?.join(500)
        recordThread = null
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }

    companion object {
        private const val TAG = "VoiceKbAudio"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // ~100 ms chunks — small enough for low-latency streaming, large enough to be efficient.
        private const val CHUNK_SAMPLES = 1600
    }
}
