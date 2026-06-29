package com.kltef.voicekeyboard.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists the most recent uncaught exception so the user can surface it from the app
 * (Setup screen offers to share it). Helps diagnose crashes on devices we can't attach to.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun write(context: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("Voice Keyboard crash\n")
            append("thread: ${thread.name}\n")
            append("message: ${e.message}\n\n")
            append(sw.toString())
        }
        runCatching { file(context).writeText(text) }
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
