package com.kltef.voicekeyboard.util

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Thin typed wrapper over the default SharedPreferences so the IME service and the
 * settings UI agree on keys and defaults.
 */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Refine each finished phrase with Whisper for best accuracy. Off by default: it adds
     * noticeable latency, and streaming + punctuation already gives fast, cased, punctuated text.
     */
    val refineWithWhisper: Boolean get() = sp.getBoolean(KEY_REFINE, false)

    /** Show streaming words as you speak (before the Whisper refine pass). */
    val streamingPreview: Boolean get() = sp.getBoolean(KEY_STREAMING, true)

    /** Add capitalization + punctuation to finalized text (independent of Whisper). */
    val autoPunctuation: Boolean get() = sp.getBoolean(KEY_PUNCTUATION, true)

    /** CPU threads used by both engines. Clamped to a sane range. */
    val threads: Int get() = sp.getString(KEY_THREADS, "4")?.toIntOrNull()?.coerceIn(1, 8) ?: 4

    /** Vibrate on key/mic interaction. */
    val haptics: Boolean get() = sp.getBoolean(KEY_HAPTICS, true)

    /** Which Whisper refine model to use: "tiny" (fast) or "base" (accurate). */
    val whisperModelKey: String get() = sp.getString(KEY_WHISPER_MODEL, "tiny") ?: "tiny"

    companion object {
        const val KEY_REFINE = "refine_with_whisper"
        const val KEY_STREAMING = "streaming_preview"
        const val KEY_PUNCTUATION = "auto_punctuation"
        const val KEY_THREADS = "cpu_threads"
        const val KEY_HAPTICS = "haptics"
        const val KEY_WHISPER_MODEL = "whisper_model"
    }
}
