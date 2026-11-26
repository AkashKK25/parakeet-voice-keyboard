package com.parakeet.voicekeyboard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.parakeet.voicekeyboard.R
import com.parakeet.voicekeyboard.model.ModelManager
import com.parakeet.voicekeyboard.model.ParakeetModelSpec
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var modelManager: ModelManager

    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var rgModels: RadioGroup
    private lateinit var tvModelStatus: TextView
    private lateinit var progressModelDownload: ProgressBar
    private lateinit var btnDownloadModel: MaterialButton
    private lateinit var btnImportModel: MaterialButton
    private lateinit var btnDeleteModel: MaterialButton

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionUi() }

    private val pickArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    modelManager.importFromArchiveStream(stream) { pct, msg ->
                        runOnUiThread {
                            progressModelDownload.progress = pct
                            tvModelStatus.text = msg
                        }
                    }
                } ?: error("Unable to open selected file")
            }.onFailure { err ->
                tvModelStatus.text = "Import failed: ${err.message}"
            }
            setBusy(false)
            refreshModelUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelManager = ModelManager(applicationContext)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        rgModels = findViewById(R.id.rgModels)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        progressModelDownload = findViewById(R.id.progressModelDownload)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnImportModel = findViewById(R.id.btnImportModel)
        btnDeleteModel = findViewById(R.id.btnDeleteModel)

        btnGrantPermission.setOnClickListener {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        when (modelManager.getSelectedModel()) {
            ParakeetModelSpec.PARAKEET_TDT_V3 -> findViewById<RadioButton>(R.id.rbParakeetV3).isChecked = true
            ParakeetModelSpec.PARAKEET_TDT_V2 -> findViewById<RadioButton>(R.id.rbParakeetV2).isChecked = true
            ParakeetModelSpec.PARAKEET_CTC_110M -> findViewById<RadioButton>(R.id.rbParakeet110m).isChecked = true
        }

        rgModels.setOnCheckedChangeListener { _, checkedId ->
            val spec = when (checkedId) {
                R.id.rbParakeetV2 -> ParakeetModelSpec.PARAKEET_TDT_V2
                R.id.rbParakeet110m -> ParakeetModelSpec.PARAKEET_CTC_110M
                else -> ParakeetModelSpec.PARAKEET_TDT_V3
            }
            modelManager.setSelectedModel(spec)
            refreshModelUi()
        }

        btnDownloadModel.setOnClickListener {
            val spec = modelManager.getSelectedModel()
            setBusy(true)
            lifecycleScope.launch {
                runCatching {
                    modelManager.downloadAndInstall(spec) { pct, msg ->
                        runOnUiThread {
                            progressModelDownload.progress = pct
                            tvModelStatus.text = msg
                        }
                    }
                }.onFailure { err ->
                    tvModelStatus.text = "Download failed: ${err.message}"
                }
                setBusy(false)
                refreshModelUi()
            }
        }

        btnImportModel.setOnClickListener {
            pickArchiveLauncher.launch(arrayOf("*/*"))
        }

        btnDeleteModel.setOnClickListener {
            modelManager.deleteModel(modelManager.getSelectedModel())
            refreshModelUi()
        }

        findViewById<MaterialButton>(R.id.btnEnableIme).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnSelectIme).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        refreshPermissionUi()
        refreshModelUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        refreshModelUi()
    }

    private fun refreshPermissionUi() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        tvPermissionStatus.text = if (granted) {
            getString(R.string.step_permission_granted)
        } else {
            getString(R.string.step_permission_missing)
        }
        btnGrantPermission.isEnabled = !granted
    }

    private fun refreshModelUi() {
        val spec = modelManager.getSelectedModel()
        val installed = modelManager.isModelInstalled(spec)
        tvModelStatus.text = if (installed) {
            "Installed locally: ${spec.folderName}"
        } else {
            "Not installed (${spec.id})"
        }
        btnDeleteModel.isEnabled = installed
    }

    private fun setBusy(busy: Boolean) {
        progressModelDownload.visibility = if (busy) View.VISIBLE else View.GONE
        btnDownloadModel.isEnabled = !busy
        btnImportModel.isEnabled = !busy
        btnDeleteModel.isEnabled = !busy
        rgModels.isEnabled = !busy
    }
}
