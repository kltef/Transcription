package com.kltef.voicekeyboard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.kltef.voicekeyboard.util.CrashLog
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kltef.voicekeyboard.R
import com.kltef.voicekeyboard.databinding.ActivitySetupBinding
import com.kltef.voicekeyboard.engine.ModelManager
import com.kltef.voicekeyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-screen onboarding: enable the keyboard, select it, grant the mic, and download the
 * Whisper refine model. Also reachable later as the app's main screen.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatuses() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.btnSelect.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        binding.btnMic.setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.btnModel.setOnClickListener { downloadModel() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        maybeShowCrash()
    }

    /** If the app/keyboard crashed last time, offer to share the stack trace for diagnosis. */
    private fun maybeShowCrash() {
        val trace = CrashLog.read(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("A crash was recorded")
            .setMessage("Tap Share to send the error so it can be fixed.\n\n" + trace.take(3000))
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Voice Keyboard crash")
                        putExtra(Intent.EXTRA_TEXT, trace)
                    }, "Share crash report"))
            }
            .setNegativeButton("Clear") { _, _ -> CrashLog.clear(this) }
            .show()
    }

    private fun refreshStatuses() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        binding.tvMicStatus.text = if (micGranted) getString(R.string.mic_granted) else ""
        binding.btnMic.isEnabled = !micGranted

        val model = ModelManager.WhisperModel.from(Prefs(this).whisperModelKey)
        val modelReady = ModelManager.isReady(this, model)
        binding.tvModelStatus.text = if (modelReady) getString(R.string.model_ready) else ""
        binding.btnModel.isEnabled = !modelReady
    }

    private fun downloadModel() {
        binding.btnModel.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        binding.progress.progress = 0
        binding.tvModelStatus.text = getString(R.string.downloading, 0)

        val model = ModelManager.WhisperModel.from(Prefs(this).whisperModelKey)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ModelManager.download(this@SetupActivity, model) { pct ->
                    runOnUiThread {
                        binding.progress.progress = pct
                        binding.tvModelStatus.text = getString(R.string.downloading, pct)
                    }
                }
            }
            binding.progress.visibility = android.view.View.GONE
            if (ok) {
                refreshStatuses()
            } else {
                binding.tvModelStatus.text = getString(R.string.download_failed)
                binding.btnModel.isEnabled = true
            }
        }
    }
}
