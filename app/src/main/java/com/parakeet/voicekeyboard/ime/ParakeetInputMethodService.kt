package com.parakeet.voicekeyboard.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.parakeet.voicekeyboard.R
import com.parakeet.voicekeyboard.asr.ParakeetAsrEngine
import com.parakeet.voicekeyboard.audio.AudioRecorder
import com.parakeet.voicekeyboard.model.ModelManager
import com.parakeet.voicekeyboard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ParakeetInputMethodService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var modelManager: ModelManager
    private val asrEngine = ParakeetAsrEngine()
    private val audioRecorder = AudioRecorder()

    private var tvStatus: TextView? = null
    private var tvMicHint: TextView? = null
    private var statusDot: View? = null
    private var progressAudioLevel: ProgressBar? = null
    private var btnMic: ImageButton? = null

    private var lastCommittedLength = 0
    private var micDownTimeMs = 0L

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatBackspaceRunnable = object : Runnable {
        override fun run() {
            performBackspace()
            repeatHandler.postDelayed(this, 65L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(applicationContext)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ParakeetVoiceKeyboard)
        val root = LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)

        tvStatus = root.findViewById(R.id.tvStatus)
        tvMicHint = root.findViewById(R.id.tvMicHint)
        statusDot = root.findViewById(R.id.statusDot)
        progressAudioLevel = root.findViewById(R.id.progressAudioLevel)
        btnMic = root.findViewById(R.id.btnMic)

        root.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        root.findViewById<ImageButton>(R.id.btnSwitchIme).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        root.findViewById<Button>(R.id.btnComma).setOnClickListener { commitSimpleText(", ") }
        root.findViewById<Button>(R.id.btnPeriod).setOnClickListener { commitSimpleText(". ") }
        root.findViewById<Button>(R.id.btnQuestion).setOnClickListener { commitSimpleText("? ") }
        root.findViewById<Button>(R.id.btnSpace).setOnClickListener { commitSimpleText(" ") }

        root.findViewById<View>(R.id.btnUndo).setOnClickListener { undoLastDictation() }

        root.findViewById<ImageButton>(R.id.btnEnter).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            lastCommittedLength = 0
        }

        root.findViewById<ImageButton>(R.id.btnBackspace).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performBackspace()
                    repeatHandler.postDelayed(repeatBackspaceRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatHandler.removeCallbacks(repeatBackspaceRunnable)
                    true
                }
                else -> false
            }
        }

        btnMic?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    micDownTimeMs = System.currentTimeMillis()
                    if (!audioRecorder.isRecording) {
                        startDictation()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val heldMs = System.currentTimeMillis() - micDownTimeMs
                    if (audioRecorder.isRecording) {
                        if (heldMs > 350L) {
                            stopAndTranscribe()
                        } else if (tvMicHint?.text == "Tap to Stop") {
                            stopAndTranscribe()
                        } else {
                            tvMicHint?.text = "Tap to Stop"
                        }
                    }
                    true
                }
                else -> false
            }
        }

        loadSelectedModelAsync()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSelectedModelAsync()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        repeatHandler.removeCallbacks(repeatBackspaceRunnable)
        if (audioRecorder.isRecording) {
            audioRecorder.stopAndCollect()
            setIdleUi()
        }
    }

    override fun onDestroy() {
        repeatHandler.removeCallbacks(repeatBackspaceRunnable)
        if (audioRecorder.isRecording) {
            audioRecorder.stopAndCollect()
        }
        serviceScope.launch {
            try {
                asrEngine.release()
            } catch (_: Throwable) {
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun loadSelectedModelAsync() {
        val spec = modelManager.getSelectedModel()
        if (!modelManager.isModelInstalled(spec)) {
            tvStatus?.text = getString(R.string.status_no_model)
            return
        }
        if (!hasMicPermission()) {
            tvStatus?.text = getString(R.string.status_no_permission)
            return
        }
        if (asrEngine.isReadyFor(spec.id)) {
            if (!audioRecorder.isRecording) {
                tvStatus?.text = getString(R.string.status_ready)
            }
            return
        }

        tvStatus?.text = getString(R.string.status_loading)
        serviceScope.launch {
            try {
                asrEngine.ensureLoaded(spec, modelManager.getModelDir(spec))
                if (!audioRecorder.isRecording) {
                    tvStatus?.text = getString(R.string.status_ready)
                }
            } catch (t: Throwable) {
                tvStatus?.text = "Load error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun startDictation() {
        if (!hasMicPermission()) {
            tvStatus?.text = getString(R.string.status_no_permission)
            return
        }
        val spec = modelManager.getSelectedModel()
        if (!modelManager.isModelInstalled(spec)) {
            tvStatus?.text = getString(R.string.status_no_model)
            return
        }
        if (!asrEngine.isReady) {
            loadSelectedModelAsync()
            return
        }

        val started = audioRecorder.start(
            scope = serviceScope,
            onAmplitude = { rms ->
                val scaled = (rms * 400f).toInt().coerceIn(4, 100)
                repeatHandler.post {
                    progressAudioLevel?.progress = scaled
                }
            },
            onMaxDurationReached = {
                repeatHandler.post {
                    stopAndTranscribe()
                }
            },
        )

        if (started) {
            tvStatus?.text = getString(R.string.status_listening)
            tvMicHint?.text = "Release or Tap"
            btnMic?.setBackgroundResource(R.drawable.bg_mic_recording)
            statusDot?.setBackgroundResource(R.drawable.bg_mic_recording)
            progressAudioLevel?.visibility = View.VISIBLE
            progressAudioLevel?.progress = 10
        }
    }

    private fun stopAndTranscribe() {
        val samples = audioRecorder.stopAndCollect()
        setIdleUi()
        if (samples.size < AudioRecorder.SAMPLE_RATE / 4) {
            tvStatus?.text = getString(R.string.status_ready)
            return
        }

        tvStatus?.text = getString(R.string.status_transcribing)
        serviceScope.launch {
            try {
                val text = asrEngine.transcribe(samples, AudioRecorder.SAMPLE_RATE)
                if (text.isNotBlank()) {
                    commitDictatedText(text)
                }
                tvStatus?.text = getString(R.string.status_ready)
            } catch (t: Throwable) {
                tvStatus?.text = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun setIdleUi() {
        tvMicHint?.text = "Tap or Hold"
        btnMic?.setBackgroundResource(R.drawable.bg_mic_idle)
        statusDot?.setBackgroundResource(R.drawable.bg_mic_idle)
        progressAudioLevel?.visibility = View.INVISIBLE
        progressAudioLevel?.progress = 0
    }

    private fun commitDictatedText(rawText: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val needsLeadingSpace = before.isNotEmpty() && !before.last().isWhitespace()
        val finalText = if (needsLeadingSpace) " $rawText" else rawText
        ic.commitText(finalText, 1)
        lastCommittedLength = finalText.length
    }

    private fun commitSimpleText(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastCommittedLength = 0
    }

    private fun undoLastDictation() {
        val ic = currentInputConnection ?: return
        if (lastCommittedLength > 0) {
            ic.deleteSurroundingText(lastCommittedLength, 0)
            lastCommittedLength = 0
        }
    }

    private fun performBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        lastCommittedLength = 0
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}
