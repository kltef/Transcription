package com.kltef.voicekeyboard.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-disk location of the Whisper refine model and downloads it on first run.
 *
 * The streaming (sherpa-onnx) model is bundled in assets and always present; only the
 * larger Whisper model is fetched on demand to keep the APK small.
 */
object ModelManager {
    private const val TAG = "VoiceKbModels"

    // ggml base.en, q5_1 quantized (~57 MB): a good accuracy/speed tradeoff for phones.
    private const val WHISPER_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
    private const val WHISPER_FILENAME = "ggml-base.en-q5_1.bin"
    private const val MIN_VALID_BYTES = 10_000_000L // guard against truncated downloads

    fun whisperModelFile(context: Context): File =
        File(context.filesDir, "models/$WHISPER_FILENAME")

    fun isWhisperReady(context: Context): Boolean {
        val f = whisperModelFile(context)
        return f.exists() && f.length() >= MIN_VALID_BYTES
    }

    /**
     * Downloads the Whisper model. [onProgress] receives 0..100. Runs synchronously, so call
     * from a background thread. Returns true on success.
     */
    fun downloadWhisper(context: Context, onProgress: (Int) -> Unit): Boolean {
        val dest = whisperModelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "$WHISPER_FILENAME.part")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(WHISPER_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (tmp.length() < MIN_VALID_BYTES) {
                Log.e(TAG, "Downloaded file too small: ${tmp.length()}")
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            onProgress(100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Whisper download failed", e)
            tmp.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
