package com.kltef.voicekeyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.kltef.voicekeyboard.R
import com.kltef.voicekeyboard.databinding.KeyboardViewBinding
import com.kltef.voicekeyboard.engine.DictationController
import com.kltef.voicekeyboard.engine.ModelManager
import com.kltef.voicekeyboard.ui.SetupActivity
import com.kltef.voicekeyboard.util.Prefs

/**
 * The voice keyboard. A large mic button drives on-device dictation; the recognized text
 * is written into whatever app is focused via [getCurrentInputConnection].
 */
class VoiceKeyboardService : InputMethodService(), DictationController.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var binding: KeyboardViewBinding? = null
    private var controller: DictationController? = null
    private var keyboard: KeyboardLayout? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onCreateInputView(): View {
        val b = try {
            KeyboardViewBinding.inflate(layoutInflater)
        } catch (e: Throwable) {
            // Never let view inflation crash the keyboard process; show an empty view instead.
            Log.e(TAG, "Failed to inflate keyboard view", e)
            return View(this)
        }
        binding = b

        b.micButton.setOnClickListener { v ->
            haptic(v)
            toggleDictation()
        }
        b.switchIme.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        b.settingsKey.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // Build the QWERTY / symbols keyboard into the container. Guarded so a build error
        // can never crash the keyboard process.
        try {
            keyboard = KeyboardLayout(this, b.keysContainer, object : KeyboardLayout.Listener {
                override fun onChar(text: String) = safely { commit(text) }
                override fun onBackspace() = safely { sendBackspace() }
                override fun onBackspaceRepeat() = safely { sendBackspace() }
                override fun onEnter() = safely { sendEnter() }
                override fun haptic(v: View) = this@VoiceKeyboardService.haptic(v)
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to build keyboard", e)
        }
        return b.root
    }

    /** Run a key action without letting an exception crash the whole keyboard. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "key action failed", e)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showStatus(R.string.tap_to_dictate)
        setMicActive(false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        controller?.stop()
    }

    override fun onDestroy() {
        controller?.shutdown()
        controller = null
        keyboard = null
        binding = null
        super.onDestroy()
    }

    // ---- Dictation control ----------------------------------------------------------

    private fun toggleDictation() {
        val c = controller
        if (c != null && c.isActive) {
            c.stop()
            return
        }
        if (!hasMicPermission()) {
            showStatus(R.string.need_mic_permission)
            startActivity(Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Heads-up if the user enabled refine but hasn't downloaded the Whisper model yet.
        if (prefs.refineWithWhisper && !ModelManager.isWhisperReady(this)) {
            // Not fatal: the controller falls back to streaming text. Just inform once.
            showStatus(R.string.models_missing)
        }
        val ctrl = controller ?: DictationController(this, prefs, this).also { controller = it }
        ctrl.start()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---- DictationController.Listener (called on the ASR worker thread) --------------

    override fun onPartial(text: String) {
        main.post { currentInputConnection?.setComposingText(text, 1) }
    }

    override fun onFinalSegment(text: String) {
        // commitText replaces any active composing region (the last live partial) with the
        // finalized text, then we add a trailing space for the next phrase.
        main.post { currentInputConnection?.commitText("$text ", 1) }
    }

    override fun onStateChanged(state: DictationController.State) {
        main.post {
            when (state) {
                DictationController.State.IDLE -> {
                    setMicActive(false); showStatus(R.string.tap_to_dictate)
                }
                DictationController.State.LISTENING -> {
                    setMicActive(true); showStatus(R.string.listening)
                }
                DictationController.State.REFINING -> showStatus(R.string.refining)
            }
        }
    }

    override fun onError(message: String) {
        main.post {
            setMicActive(false)
            binding?.statusText?.text = message
        }
    }

    // ---- InputConnection helpers -----------------------------------------------------

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun sendBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE &&
            (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0)
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // ---- UI helpers ------------------------------------------------------------------

    private fun setMicActive(active: Boolean) {
        val mic = binding?.micButton ?: return
        val color = ContextCompat.getColor(
            this, if (active) R.color.mic_active else R.color.mic_idle
        )
        mic.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        if (active) {
            // Gentle pulse while listening so it's obvious the mic is live.
            mic.animate().scaleX(1.12f).scaleY(1.12f).setDuration(550)
                .withEndAction { pulseBack(mic) }.start()
        } else {
            mic.animate().cancel()
            mic.scaleX = 1f; mic.scaleY = 1f
        }
    }

    private fun pulseBack(mic: View) {
        if (controller?.isActive != true) return
        mic.animate().scaleX(1f).scaleY(1f).setDuration(550)
            .withEndAction { if (controller?.isActive == true) setMicActive(true) }.start()
    }

    private fun showStatus(resId: Int) {
        binding?.statusText?.setText(resId)
    }

    private fun haptic(v: View) {
        if (prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val TAG = "VoiceKbIme"
    }
}
