package com.parakeet.voicekeyboard.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.parakeet.voicekeyboard.model.ParakeetModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ParakeetAsrEngine {

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedModelId: String? = null

    val isReady: Boolean
        get() = recognizer != null

    suspend fun ensureLoaded(
        spec: ParakeetModelSpec,
        modelDir: File,
        numThreads: Int = 2,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (recognizer != null && loadedModelId == spec.id) {
                return@withLock
            }
            recognizer?.release()
            recognizer = null
            loadedModelId = null

            val modelConfig = if (spec.isTransducer) {
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                        decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                        joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = numThreads,
                    provider = "cpu",
                )
            } else {
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = File(modelDir, "model.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = numThreads,
                    provider = "cpu",
                )
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = 16000,
                    featureDim = 80,
                ),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )

            recognizer = OfflineRecognizer(
                assetManager = null,
                config = config,
            )
            loadedModelId = spec.id
        }
    }

    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16000,
    ): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val rec = recognizer ?: return@withLock ""
            if (samples.isEmpty()) return@withLock ""

            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                return@withLock rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            recognizer?.release()
            recognizer = null
            loadedModelId = null
        }
    }
}
