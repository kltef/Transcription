package com.kltef.voicekeyboard.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-disk location of the Whisper refine model(s) and downloads them on demand.
 *
 * Two choices are offered (selectable in settings): a fast "tiny.en" model and a more
 * accurate "base.en". Only the selected one needs to be present. The streaming (sherpa-onnx)
 * model is bundled in assets and always available; Whisper is fetched on demand.
 */
object ModelManager {
    private const val TAG = "VoiceKbModels"
    private const val MIN_VALID_BYTES = 5_000_000L // guard against truncated downloads

    /** A downloadable Whisper model. */
    enum class WhisperModel(val key: String, val fileName: String, val url: String) {
        TINY("tiny", "ggml-tiny.en-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"),
        BASE("base", "ggml-base.en-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin");

        companion object {
            fun from(key: String?): WhisperModel = entries.firstOrNull { it.key == key } ?: TINY
        }
    }

    fun modelFile(context: Context, model: WhisperModel): File =
        File(context.filesDir, "models/${model.fileName}")

    fun isReady(context: Context, model: WhisperModel): Boolean {
        val f = modelFile(context, model)
        return f.exists() && f.length() >= MIN_VALID_BYTES
    }

    /**
     * Downloads [model]. [onProgress] receives 0..100. Runs synchronously — call from a
     * background thread. Returns true on success.
     */
    fun download(context: Context, model: WhisperModel, onProgress: (Int) -> Unit): Boolean {
        val dest = modelFile(context, model)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${model.fileName}.part")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
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
