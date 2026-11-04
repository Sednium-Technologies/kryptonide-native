package com.sednium.kryptonide.agent.pocket

import android.content.Context
import android.os.Environment
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DiscoveredModel(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    val detectedQuantization: String,
    val matchedCatalogSpec: LocalModelSpec?,
    val isSystemOneCandidate: Boolean
)

class StorageModelScanner(private val context: Context? = null) {

    private fun getScanDirectories(): List<File> {
        val list = mutableListOf<File>()

        // 1. Primary models dir: /storage/emulated/0/Kryptonide/models/
        val kryptonModels = StorageUtils.getModelsDir()
        if (kryptonModels.exists()) list.add(kryptonModels)

        // 2. Legacy /sdcard/KryptonIDE/models/ (only if distinct path)
        val legacyModels = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/models")
        if (legacyModels.exists() && legacyModels.canonicalPath != kryptonModels.canonicalPath) {
            list.add(legacyModels)
        }

        // 3. /storage/emulated/0/Download/
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads != null && downloads.exists()) list.add(downloads)

        // 4. App internal models dir
        context?.let { ctx ->
            val internalModels = File(ctx.filesDir, "models")
            if (internalModels.exists()) list.add(internalModels)
        }

        return list
    }

    suspend fun scanModels(): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredModel>()
        val scanDirs = getScanDirectories()

        for (dir in scanDirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) continue
                val nameLower = f.name.lowercase()
                val format = when {
                    nameLower.endsWith(".cact") -> ModelFormat.CACT
                    nameLower.endsWith(".gguf") -> ModelFormat.GGUF
                    nameLower.endsWith(".tflite") -> ModelFormat.LITERT
                    nameLower.endsWith(".onnx") -> ModelFormat.ONNX
                    nameLower.endsWith(".bin") -> ModelFormat.EMBEDDING
                    else -> null
                } ?: continue

                val matched = PocketModels.PREDEFINED_CATALOG.firstOrNull { spec ->
                    nameLower.contains(spec.id.lowercase()) ||
                            (nameLower.contains("needle") && spec.id.contains("needle")) ||
                            (nameLower.contains("minicpm") && spec.id.contains("minicpm")) ||
                            (nameLower.contains("qwen") && nameLower.contains("1.5b") && spec.id.contains("1.5b")) ||
                            (nameLower.contains("qwen") && nameLower.contains("7b") && spec.id.contains("7b")) ||
                            (nameLower.contains("qwen") && nameLower.contains("0.5b") && spec.id.contains("laya")) ||
                            (nameLower.contains("gemma") && spec.id.contains("gemma")) ||
                            (nameLower.contains("laya") && spec.id.contains("laya"))
                }

                val rawQuant = detectQuantization(f.name)
                val quant = if (rawQuant == "Unknown" && matched != null) matched.quantization else rawQuant

                val isS1 = nameLower.contains("laya") || nameLower.contains("needle") || (f.length() < 500L * 1024 * 1024 && nameLower.contains("0.5b"))

                discovered.add(
                    DiscoveredModel(
                        file = f,
                        name = f.name,
                        sizeBytes = f.length(),
                        format = matched?.format ?: format,
                        detectedQuantization = quant,
                        matchedCatalogSpec = matched,
                        isSystemOneCandidate = isS1
                    )
                )
            }
        }
        // Deduplicate strictly by file name, keeping the one from Kryptonide/models/ first
        discovered
            .sortedByDescending { it.file.parentFile?.canonicalPath == StorageUtils.getModelsDir().canonicalPath }
            .distinctBy { it.file.name.lowercase().trim() }
    }

    private fun detectQuantization(filename: String): String {
        val upper = filename.uppercase()
        if (upper.endsWith(".CACT") || upper.contains("NEEDLE")) return "Cactus Quants (~2-bit)"
        val quants = listOf(
            "Q4_K_M", "Q4_K_S", "Q4_0", "Q4_1", "Q4_K",
            "Q5_K_M", "Q5_K_S", "Q5_0", "Q5_1", "Q5_K",
            "Q8_0", "Q8_K", "FP16", "FP32", "BF16",
            "CQ4", "Q4", "Q5", "Q8"
        )
        for (q in quants) {
            if (upper.contains(q)) return q
        }
        return "Unknown"
    }
}
