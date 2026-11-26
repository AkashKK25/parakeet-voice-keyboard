package com.parakeet.voicekeyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val sampleChunks = ArrayList<FloatArray>()

    @Volatile
    var isRecording: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        onAmplitude: (Float) -> Unit,
        onMaxDurationReached: () -> Unit,
    ): Boolean {
        if (isRecording) return false

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val bufferSize = maxOf(minBufBytes * 2, SAMPLE_RATE / 5 * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        synchronized(sampleChunks) {
            sampleChunks.clear()
        }

        audioRecord = record
        isRecording = true
        record.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            var totalSamples = 0
            while (isActive && isRecording) {
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    val floats = FloatArray(read)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val v = shortBuf[i] / 32768.0f
                        floats[i] = v
                        sumSq += v * v
                    }
                    synchronized(sampleChunks) {
                        sampleChunks.add(floats)
                    }
                    totalSamples += read
                    val rms = sqrt(sumSq / read).toFloat().coerceIn(0f, 1f)
                    onAmplitude(rms)

                    if (totalSamples >= MAX_SAMPLES) {
                        isRecording = false
                        onMaxDurationReached()
                        break
                    }
                }
            }
        }
        return true
    }

    fun stopAndCollect(): FloatArray {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: IllegalStateException) {
            } finally {
                record.release()
            }
        }
        audioRecord = null

        synchronized(sampleChunks) {
            val totalSize = sampleChunks.sumOf { it.size }
            val result = FloatArray(totalSize)
            var offset = 0
            for (chunk in sampleChunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            sampleChunks.clear()
            return result
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1600 // 100 ms at 16 kHz
        private const val MAX_SECONDS = 60
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS
    }
}
