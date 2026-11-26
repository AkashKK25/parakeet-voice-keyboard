package com.parakeet.voicekeyboard.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsRoot = File(context.filesDir, "models").apply { mkdirs() }

    fun getSelectedModel(): ParakeetModelSpec {
        val savedId = prefs.getString(KEY_SELECTED_MODEL, ParakeetModelSpec.PARAKEET_CTC_110M.id)
        return ParakeetModelSpec.fromId(savedId)
    }

    fun setSelectedModel(spec: ParakeetModelSpec) {
        prefs.edit().putString(KEY_SELECTED_MODEL, spec.id).apply()
    }

    fun getModelDir(spec: ParakeetModelSpec): File = File(modelsRoot, spec.folderName)

    fun isModelInstalled(spec: ParakeetModelSpec = getSelectedModel()): Boolean {
        val dir = getModelDir(spec)
        if (!dir.exists() || !dir.isDirectory) return false
        return spec.requiredFiles().all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }
    }

    fun deleteModel(spec: ParakeetModelSpec = getSelectedModel()): Boolean {
        val dir = getModelDir(spec)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    suspend fun downloadAndInstall(
        spec: ParakeetModelSpec,
        onProgress: (percent: Int, status: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tmpArchive = File(context.cacheDir, "${spec.folderName}.tar.bz2")
        try {
            onProgress(0, "Connecting to GitHub Releases...")
            downloadFile(spec.archiveUrl, tmpArchive) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 70L) / total).toInt() else 0
                val mb = downloaded / (1024 * 1024)
                val totalMb = if (total > 0) "${total / (1024 * 1024)} MB" else "..."
                onProgress(pct, "Downloading ${spec.id}: $mb MB / $totalMb")
            }

            onProgress(70, "Extracting ONNX model files (this may take 1-2 min)...")
            tmpArchive.inputStream().use { input ->
                extractTarBz2(input) { extractedMb, currentFile ->
                    val pct = (70 + (extractedMb / 25).coerceAtMost(28)).toInt()
                    onProgress(pct, "Extracting $currentFile ($extractedMb MB)...")
                }
            }

            if (!isModelInstalled(spec)) {
                error("Extraction completed, but expected model files were not found in ${getModelDir(spec).absolutePath}")
            }
            onProgress(100, "Installed ${spec.displayName}")
        } finally {
            tmpArchive.delete()
        }
    }

    suspend fun importFromArchiveStream(
        inputStream: InputStream,
        onProgress: (percent: Int, status: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(10, "Extracting local .tar.bz2 archive...")
        extractTarBz2(inputStream) { extractedMb, currentFile ->
            val pct = (10 + (extractedMb / 10).coerceAtMost(88)).toInt()
            onProgress(pct, "Extracting $currentFile ($extractedMb MB)...")
        }
        onProgress(100, "Archive extracted")
    }

    private fun extractTarBz2(
        rawInput: InputStream,
        onExtractProgress: (extractedMb: Long, currentFile: String) -> Unit,
    ) {
        BufferedInputStream(rawInput, 64 * 1024).use { buffered ->
            BZip2CompressorInputStream(buffered, true).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    val buffer = ByteArray(64 * 1024)
                    var totalExtractedBytes = 0L
                    var lastReportedMb = -1L
                    while (entry != null) {
                        val outFile = File(modelsRoot, entry.name)
                        if (!outFile.canonicalPath.startsWith(modelsRoot.canonicalPath + File.separator)) {
                            throw SecurityException("Invalid tar entry path: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            val shortName = outFile.name
                            FileOutputStream(outFile).use { out ->
                                var read: Int
                                while (tar.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                    totalExtractedBytes += read
                                    val mb = totalExtractedBytes / (1024 * 1024)
                                    if (mb != lastReportedMb) {
                                        lastReportedMb = mb
                                        onExtractProgress(mb, shortName)
                                    }
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun downloadFile(
        urlString: String,
        destination: File,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        var currentUrl = urlString
        var redirects = 0
        var connection: HttpURLConnection
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 8) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect $code missing Location header")
                connection.disconnect()
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
            } else {
                break
            }
        }

        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("HTTP $code downloading $currentUrl")
        }

        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
    }

    companion object {
        private const val PREFS_NAME = "parakeet_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
    }
}
