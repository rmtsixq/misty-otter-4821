package com.sixq.gozjest.camera

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sixq.gozjest.camera.databinding.ActivityCameraSettingsBinding
import com.sixq.gozjest.core.Protocol
import java.io.File

/** Host address and upload quality for the camera device. */
class CameraSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraSettingsBinding
    private lateinit var prefs: CameraPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        prefs = CameraPrefs(this)

        binding.hostInput.setText(prefs.manualHost)
        binding.portInput.setText(prefs.port.toString())
        binding.maxEdgeSlider.value = prefs.maxEdge.toFloat().coerceIn(640f, 2560f)
        binding.qualitySlider.value = prefs.quality.toFloat().coerceIn(50f, 95f)
        binding.localCopySwitch.isChecked = prefs.keepLocalCopy

        binding.maxEdgeSlider.addOnChangeListener { _, value, _ ->
            binding.maxEdgeLabel.text = "Uzun kenar: ${value.toInt()} px"
        }
        binding.qualitySlider.addOnChangeListener { _, value, _ ->
            binding.qualityLabel.text = "JPEG kalitesi: ${value.toInt()}"
        }
        binding.maxEdgeLabel.text = "Uzun kenar: ${binding.maxEdgeSlider.value.toInt()} px"
        binding.qualityLabel.text = "JPEG kalitesi: ${binding.qualitySlider.value.toInt()}"

        binding.clearLocalButton.setOnClickListener {
            val directory = File(cacheDir, "sent")
            val removed = directory.listFiles()?.count { it.delete() } ?: 0
            Toast.makeText(this, "$removed dosya silindi", Toast.LENGTH_SHORT).show()
        }

        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        prefs.manualHost = binding.hostInput.text?.toString().orEmpty()
        prefs.port = binding.portInput.text?.toString()?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: Protocol.HTTP_PORT
        prefs.maxEdge = binding.maxEdgeSlider.value.toInt()
        prefs.quality = binding.qualitySlider.value.toInt()
        prefs.keepLocalCopy = binding.localCopySwitch.isChecked

        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        finish()
    }
}
