package com.kltef.voicekeyboard.ime

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kltef.voicekeyboard.R

/**
 * Builds a Gboard-style QWERTY keyboard (with a symbols page) into a container view and
 * reports key events to [listener]. Owns shift state and page switching; the hosting IME
 * stays focused on turning those events into InputConnection edits + dictation.
 */
class KeyboardLayout(
    private val context: Context,
    private val container: LinearLayout,
    private val listener: Listener,
) {
    interface Listener {
        fun onChar(text: String)
        fun onBackspace()
        fun onBackspaceRepeat()
        fun onEnter()
        fun haptic(v: View)
    }

    private enum class Page { LETTERS, SYMBOLS }

    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var page = Page.LETTERS
    private var shifted = false
    private var capsLock = false
    private var lastShiftTapMs = 0L

    // Letter keys whose caps must follow the shift state: (label view, base lowercase char).
    private val letterKeys = ArrayList<Pair<TextView, Char>>()
    private var shiftIcon: ImageView? = null

    init {
        build()
    }

    // ---- Layout construction --------------------------------------------------------

    private fun build() {
        container.removeAllViews()
        letterKeys.clear()
        shiftIcon = null
        when (page) {
            Page.LETTERS -> buildLetters()
            Page.SYMBOLS -> buildSymbols()
        }
        refreshCaps()
    }

    private fun buildLetters() {
        val r1 = "qwertyuiop"
        val hints = "1234567890"
        val row1 = newRow()
        r1.forEachIndexed { i, c -> row1.addView(charKey(c.toString(), 1f, hints[i].toString())) }
        container.addView(row1)

        val row2 = newRow()
        row2.addView(spacer(0.5f))
        "asdfghjkl".forEach { row2.addView(charKey(it.toString(), 1f)) }
        row2.addView(spacer(0.5f))
        container.addView(row2)

        val row3 = newRow()
        row3.addView(iconKey(R.drawable.ic_shift, 1.5f, special = true) { onShift() }
            .also { shiftIcon = (it as? FrameLayout)?.getChildAt(0) as? ImageView })
        "zxcvbnm".forEach { row3.addView(charKey(it.toString(), 1f)) }
        row3.addView(backspaceKey(1.5f))
        container.addView(row3)

        container.addView(bottomRow(symbolToggleLabel = "?123"))
    }

    private fun buildSymbols() {
        val rows = listOf(
            "1234567890",
            "@#\$_&-+()/",
        )
        rows.forEach { r ->
            val row = newRow()
            r.forEach { row.addView(charKey(it.toString(), 1f)) }
            container.addView(row)
        }
        val row3 = newRow()
        listOf("*", "\"", "'", ":", ";", "!", "?").forEach {
            row3.addView(charKey(it, 1f))
        }
        row3.addView(backspaceKey(1.5f))
        container.addView(row3)

        container.addView(bottomRow(symbolToggleLabel = "ABC"))
    }

    /** The shared bottom row: page toggle, comma, space, period, enter. */
    private fun bottomRow(symbolToggleLabel: String): LinearLayout {
        val row = newRow()
        row.addView(textKey(symbolToggleLabel, 1.6f, special = true) { togglePage() })
        row.addView(charKey(",", 1f, special = true))
        row.addView(spaceKey(4.4f))
        row.addView(charKey(".", 1f, special = true))
        row.addView(iconKey(R.drawable.ic_enter, 1.6f, special = true) { listener.onEnter() })
        return row
    }

    // ---- Key factories --------------------------------------------------------------

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        )
    }

    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, dp(52), weight).apply {
        setMargins(dp(2), dp(3), dp(2), dp(3))
    }

    private fun spacer(weight: Float) = View(context).apply { layoutParams = keyParams(weight) }

    /** A character key. For single a-z letters, label case follows shift; else literal. */
    private fun charKey(label: String, weight: Float, hint: String? = null, special: Boolean = false): View {
        val isLetter = label.length == 1 && label[0] in 'a'..'z'
        val frame = FrameLayout(context).apply {
            layoutParams = keyParams(weight)
            background = ContextCompat.getDrawable(
                context, if (special) R.drawable.key_bg_special else R.drawable.key_bg
            )
        }
        val tv = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.kb_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 16f else 19f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(tv)
        if (hint != null) {
            frame.addView(TextView(context).apply {
                text = hint
                setTextColor(ContextCompat.getColor(context, R.color.kb_hint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(3), dp(6), 0)
                }
            })
        }
        if (isLetter) letterKeys.add(tv to label[0])
        frame.setOnClickListener { v ->
            listener.haptic(v)
            if (isLetter) {
                val out = if (shifted || capsLock) label.uppercase() else label
                listener.onChar(out)
                if (shifted && !capsLock) { shifted = false; refreshCaps() }
            } else {
                listener.onChar(label)
            }
        }
        return frame
    }

    private fun textKey(label: String, weight: Float, special: Boolean, onClick: () -> Unit): View =
        charKeyShell(weight, special).also { frame ->
            (frame.getChildAt(0) as TextView).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            frame.setOnClickListener { v -> listener.haptic(v); onClick() }
        }

    private fun charKeyShell(weight: Float, special: Boolean): FrameLayout {
        val frame = FrameLayout(context).apply {
            layoutParams = keyParams(weight)
            background = ContextCompat.getDrawable(
                context, if (special) R.drawable.key_bg_special else R.drawable.key_bg
            )
        }
        frame.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.kb_text))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        return frame
    }

    private fun iconKey(iconRes: Int, weight: Float, special: Boolean, onClick: () -> Unit): View {
        val frame = FrameLayout(context).apply {
            layoutParams = keyParams(weight)
            background = ContextCompat.getDrawable(
                context, if (special) R.drawable.key_bg_special else R.drawable.key_bg
            )
        }
        frame.addView(ImageView(context).apply {
            setImageResource(iconRes)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                gravity = Gravity.CENTER
            }
        })
        frame.setOnClickListener { v -> listener.haptic(v); onClick() }
        return frame
    }

    private fun spaceKey(weight: Float): View =
        charKeyShell(weight, special = false).also { frame ->
            (frame.getChildAt(0) as TextView).apply {
                text = context.getString(R.string.app_name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.kb_hint))
            }
            frame.setOnClickListener { v -> listener.haptic(v); listener.onChar(" ") }
        }

    private fun backspaceKey(weight: Float): View {
        val frame = iconKey(R.drawable.ic_backspace, weight, special = true) { listener.onBackspace() }
        // Long-press → repeat delete until release.
        val repeat = object : Runnable {
            override fun run() {
                listener.onBackspaceRepeat()
                handler.postDelayed(this, 60)
            }
        }
        frame.setOnLongClickListener {
            handler.post(repeat)
            true
        }
        frame.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP ||
                e.action == android.view.MotionEvent.ACTION_CANCEL
            ) {
                handler.removeCallbacks(repeat)
            }
            false
        }
        return frame
    }

    // ---- State ---------------------------------------------------------------------

    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }                  // caps -> off
            shifted && now - lastShiftTapMs < DOUBLE_TAP_MS -> capsLock = true // quick double tap -> caps lock
            shifted -> shifted = false                                         // single shift -> off
            else -> shifted = true                                            // off -> one-shot shift
        }
        lastShiftTapMs = now
        refreshCaps()
    }

    /** Reset shift/caps state — called when a new text field opens so caps never gets "stuck". */
    fun resetShift() {
        shifted = false
        capsLock = false
        lastShiftTapMs = 0L
        refreshCaps()
    }

    private fun togglePage() {
        page = if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS
        build()
    }

    private fun refreshCaps() {
        val upper = shifted || capsLock
        letterKeys.forEach { (tv, base) ->
            tv.text = if (upper) base.uppercaseChar().toString() else base.toString()
        }
        shiftIcon?.apply {
            setImageResource(if (capsLock) R.drawable.ic_caps_lock else R.drawable.ic_shift)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (upper) R.color.mic_idle else R.color.kb_text)
            )
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        private const val DOUBLE_TAP_MS = 350L
    }
}
