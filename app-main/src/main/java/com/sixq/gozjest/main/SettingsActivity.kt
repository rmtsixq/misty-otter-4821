package com.sixq.gozjest.main

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sixq.gozjest.main.databinding.ActivitySettingsBinding
import com.sixq.gozjest.main.vision.Tuning

/** Exposes the handful of thresholds worth tuning per device and per user. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        prefs = Prefs(this)

        binding.touchRadiusSlider.addOnChangeListener { _, value, _ ->
            binding.touchRadiusLabel.text = "Dokunma yarıçapı: %.2f".format(value)
        }
        binding.stabilitySlider.addOnChangeListener { _, value, _ ->
            binding.stabilityLabel.text = "Kararlılık: ${value.toInt()} kare"
        }
        binding.holdSlider.addOnChangeListener { _, value, _ ->
            binding.holdLabel.text = "Göz kapama süresi: %.1f saniye".format(value)
        }

        binding.saveButton.setOnClickListener { save() }
        binding.resetButton.setOnClickListener { load(Tuning.DEFAULT, defaults = true) }

        load(prefs.tuning(), defaults = false)
    }

    private fun load(tuning: Tuning, defaults: Boolean) {
        // "Reset" restores thresholds and the prompt, never the API key.
        binding.apiKeyInput.setText(prefs.apiKey)
        binding.modelInput.setText(if (defaults) Prefs.DEFAULT_MODEL else prefs.model)
        binding.promptInput.setText(if (defaults) Prefs.DEFAULT_PROMPT else prefs.prompt)

        binding.touchRadiusSlider.value = tuning.touchRadius.coerceIn(0.22f, 0.60f)
        binding.stabilitySlider.value = tuning.stabilityFrames.toFloat().coerceIn(3f, 14f)
        binding.holdSlider.value = (tuning.blackoutHoldMs / 1000f).coerceIn(2f, 10f)

        binding.strictSwitch.isChecked = tuning.strictPointing
        binding.hapticSwitch.isChecked = tuning.hapticFeedback
        binding.darkGestureSwitch.isChecked = tuning.gesturesWhileBlack
        binding.clearAfterSwitch.isChecked = if (defaults) true else prefs.clearAfterAnswer
        binding.debugSwitch.isChecked = if (defaults) false else prefs.showDebug

        // Sliders only fire their listener on change, so seed the labels.
        binding.touchRadiusLabel.text =
            "Dokunma yarıçapı: %.2f".format(binding.touchRadiusSlider.value)
        binding.stabilityLabel.text =
            "Kararlılık: ${binding.stabilitySlider.value.toInt()} kare"
        binding.holdLabel.text =
            "Göz kapama süresi: %.1f saniye".format(binding.holdSlider.value)
    }

    private fun save() {
        prefs.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
        prefs.model = binding.modelInput.text?.toString().orEmpty()
        prefs.prompt = binding.promptInput.text?.toString().orEmpty()

        prefs.touchRadius = binding.touchRadiusSlider.value
        prefs.stabilityFrames = binding.stabilitySlider.value.toInt()
        prefs.blackoutHoldMs = (binding.holdSlider.value * 1000f).toLong()

        prefs.strictPointing = binding.strictSwitch.isChecked
        prefs.hapticFeedback = binding.hapticSwitch.isChecked
        prefs.gesturesWhileBlack = binding.darkGestureSwitch.isChecked
        prefs.clearAfterAnswer = binding.clearAfterSwitch.isChecked
        prefs.showDebug = binding.debugSwitch.isChecked

        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        finish()
    }
}
