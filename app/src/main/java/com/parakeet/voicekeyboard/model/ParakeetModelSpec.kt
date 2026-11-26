package com.parakeet.voicekeyboard.model

enum class ParakeetModelSpec(
    val id: String,
    val displayName: String,
    val folderName: String,
    val archiveUrl: String,
    val isTransducer: Boolean,
) {
    PARAKEET_CTC_110M(
        id = "parakeet-tdt-ctc-110m-int8",
        displayName = "Parakeet TDT-CTC 110M (int8, English, ~115 MB, Fast)",
        folderName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
        isTransducer = false,
    ),
    PARAKEET_TDT_V2(
        id = "parakeet-tdt-0.6b-v2-int8",
        displayName = "Parakeet TDT 0.6B v2 (int8, English, ~640 MB)",
        folderName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        isTransducer = true,
    ),
    PARAKEET_TDT_V3(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 (int8, 25 Languages, ~640 MB)",
        folderName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        isTransducer = true,
    );

    fun requiredFiles(): List<String> = if (isTransducer) {
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    } else {
        listOf("model.int8.onnx", "tokens.txt")
    }

    companion object {
        fun fromId(id: String?): ParakeetModelSpec =
            entries.firstOrNull { it.id == id } ?: PARAKEET_CTC_110M
    }
}
