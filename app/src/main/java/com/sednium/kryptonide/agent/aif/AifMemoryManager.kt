package com.sednium.kryptonide.agent.aif

import android.content.Context
import android.util.Log
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.UUID

data class AifBlock(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val projectId: String,
    val importance: Float, // 0.0 to 1.0
    val isRedCore: Boolean = false, // High priority INT16 core block
    val tags: List<String> = emptyList(),
    val summary: String,
    val contextPointers: List<String> = emptyList()
)

class AifMemoryManager(private val context: Context) {
    private val TAG = "AifMemoryManager"

    private val aifDir: File by lazy {
        val ext = File(StorageUtils.getKryptonideRoot(), "aif")
        if (ext.exists() || ext.mkdirs() || ext.canWrite()) {
            ext
        } else {
            File(context.filesDir, "aif").apply { mkdirs() }
        }
    }

    private val bridgeNexusFile: File
        get() = File(aifDir, "bridge_nexus.ai")

    private val scratchFile: File
        get() = File(aifDir, ".scratch_state.tmp")

    fun getProjectAiFile(projectId: String): File {
        val safeName = projectId.trim().replace("/", "_").ifBlank { "default" }
        return File(aifDir, "$safeName.ai")
    }

    fun getProjectMarkdownFile(projectId: String): File {
        val safeName = projectId.trim().replace("/", "_").ifBlank { "default" }
        return File(aifDir, "$safeName.aif.md")
    }

    // ── Binary Serialization ───────────────────────────────────

    private val MAGIC_BYTES = byteArrayOf(0x41, 0x49, 0x46, 0x01) // AIF\x01
    private val FORMAT_VERSION = 1

    suspend fun loadBlocks(file: File): List<AifBlock> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() < 8) return@withContext emptyList()
        val blocks = mutableListOf<AifBlock>()
        try {
            FileInputStream(file).use { fis ->
                val dis = DataInputStream(fis)
                val magic = ByteArray(4)
                dis.readFully(magic)
                if (!magic.contentEquals(MAGIC_BYTES)) {
                    Log.w(TAG, "Invalid magic bytes in ${file.name}")
                    return@withContext emptyList()
                }
                val version = dis.readInt()
                val count = dis.readInt()
                for (i in 0 until count) {
                    val id = dis.readUTF()
                    val timestamp = dis.readLong()
                    val projectId = dis.readUTF()
                    val importance = dis.readFloat()
                    val isRedCore = dis.readBoolean()
                    val tagCount = dis.readInt()
                    val tags = mutableListOf<String>()
                    for (t in 0 until tagCount) {
                        tags.add(dis.readUTF())
                    }
                    val summary = dis.readUTF()
                    val ptrCount = dis.readInt()
                    val ptrs = mutableListOf<String>()
                    for (p in 0 until ptrCount) {
                        ptrs.add(dis.readUTF())
                    }
                    blocks.add(
                        AifBlock(
                            id = id,
                            timestamp = timestamp,
                            projectId = projectId,
                            importance = importance,
                            isRedCore = isRedCore,
                            tags = tags,
                            summary = summary,
                            contextPointers = ptrs
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading binary AIF file ${file.name}: ${e.message}")
        }
        blocks
    }

    suspend fun saveBlocks(file: File, blocks: List<AifBlock>) = withContext(Dispatchers.IO) {
        aifDir.mkdirs()
        try {
            val temp = File(file.parentFile, ".${file.name}.tmp")
            FileOutputStream(temp).use { fos ->
                val dos = DataOutputStream(fos)
                dos.write(MAGIC_BYTES)
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(blocks.size)
                for (b in blocks) {
                    dos.writeUTF(b.id)
                    dos.writeLong(b.timestamp)
                    dos.writeUTF(b.projectId)
                    dos.writeFloat(b.importance)
                    dos.writeBoolean(b.isRedCore)
                    dos.writeInt(b.tags.size)
                    for (t in b.tags) dos.writeUTF(t)
                    dos.writeUTF(b.summary)
                    dos.writeInt(b.contextPointers.size)
                    for (p in b.contextPointers) dos.writeUTF(p)
                }
                dos.flush()
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving binary AIF file ${file.name}: ${e.message}")
        }
    }

    // ── High-Level Protocols ───────────────────────────────────

    suspend fun bootRecall(projectId: String): String = withContext(Dispatchers.IO) {
        val projBlocks = loadBlocks(getProjectAiFile(projectId))
        val bridgeBlocks = loadBlocks(bridgeNexusFile)

        val redCores = projBlocks.filter { it.isRedCore || it.importance >= 0.85f }
            .sortedByDescending { it.importance }
            .take(6)

        val crossProjectInsights = bridgeBlocks.filter { it.importance >= 0.8f }
            .sortedByDescending { it.importance }
            .take(3)

        if (redCores.isEmpty() && crossProjectInsights.isEmpty()) {
            return@withContext ""
        }

        buildString {
            appendLine("\n\n<aif_recalled_memory>")
            if (redCores.isNotEmpty()) {
                appendLine("### Active Project Core Memory (AIF)")
                redCores.forEach { b ->
                    val tagStr = if (b.tags.isNotEmpty()) " [${b.tags.joinToString(", ")}]" else ""
                    appendLine("- (Priority ${String.format("%.2f", b.importance)})$tagStr: ${b.summary}")
                }
            }
            if (crossProjectInsights.isNotEmpty()) {
                appendLine("### Cross-Project Nexus Pointers")
                crossProjectInsights.forEach { b ->
                    appendLine("- [${b.projectId}] ${b.summary}")
                }
            }
            appendLine("</aif_recalled_memory>\n")
        }
    }

    suspend fun recordVolatileScratch(intent: String) = withContext(Dispatchers.IO) {
        try {
            aifDir.mkdirs()
            scratchFile.writeText("TIMESTAMP: ${System.currentTimeMillis()}\nINTENT: $intent\n")
        } catch (_: Throwable) {}
    }

    suspend fun clearVolatileScratch() = withContext(Dispatchers.IO) {
        try {
            if (scratchFile.exists()) scratchFile.delete()
        } catch (_: Throwable) {}
    }

    suspend fun commitTurn(
        projectId: String,
        importance: Float,
        summary: String,
        tags: List<String> = emptyList(),
        contextPointers: List<String> = emptyList(),
        isRedCore: Boolean = false
    ): AifBlock = withContext(Dispatchers.IO) {
        val block = AifBlock(
            projectId = projectId,
            importance = importance.coerceIn(0.0f, 1.0f),
            isRedCore = isRedCore || importance >= 0.9f,
            tags = tags,
            summary = summary.trim(),
            contextPointers = contextPointers
        )

        // Append to project file
        val projFile = getProjectAiFile(projectId)
        val current = loadBlocks(projFile).toMutableList()
        current.add(block)
        saveBlocks(projFile, current)
        syncMarkdownMirror(projectId, current)

        // If highly important or cross-project relevant, bridge to Nexus
        if (block.importance >= 0.85f || tags.contains("architecture") || tags.contains("nexus")) {
            val nexusBlocks = loadBlocks(bridgeNexusFile).toMutableList()
            nexusBlocks.add(block)
            // Cap nexus to 50 items
            if (nexusBlocks.size > 50) {
                nexusBlocks.sortByDescending { it.importance }
                val trimmed = nexusBlocks.take(50)
                saveBlocks(bridgeNexusFile, trimmed)
            } else {
                saveBlocks(bridgeNexusFile, nexusBlocks)
            }
        }

        clearVolatileScratch()
        block
    }

    private suspend fun syncMarkdownMirror(projectId: String, blocks: List<AifBlock>) = withContext(Dispatchers.IO) {
        try {
            val mdFile = getProjectMarkdownFile(projectId)
            val text = buildString {
                appendLine("# Cognitive Memory Log: $projectId")
                appendLine("Generated by Oorty Lite AIF Memory Manager\n")
                if (blocks.isEmpty()) {
                    appendLine("*(No memory blocks recorded yet)*")
                } else {
                    blocks.reversed().forEach { b ->
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(b.timestamp))
                        val coreBadge = if (b.isRedCore) " [RED_CORE]" else ""
                        appendLine("### Memory Block: ${b.id.take(8)}$coreBadge")
                        appendLine("- **Timestamp:** $date")
                        appendLine("- **Importance:** ${String.format("%.2f", b.importance)}")
                        if (b.tags.isNotEmpty()) appendLine("- **Tags:** ${b.tags.joinToString(", ")}")
                        appendLine("- **Summary:** ${b.summary}")
                        if (b.contextPointers.isNotEmpty()) {
                            appendLine("- **Pointers:** ${b.contextPointers.joinToString(", ")}")
                        }
                        appendLine()
                    }
                }
            }
            mdFile.writeText(text)
        } catch (_: Throwable) {}
    }

    suspend fun queryMemory(projectId: String, keyword: String): List<AifBlock> = withContext(Dispatchers.IO) {
        val projBlocks = loadBlocks(getProjectAiFile(projectId))
        val nexusBlocks = loadBlocks(bridgeNexusFile)
        val combined = (projBlocks + nexusBlocks).distinctBy { it.id }

        if (keyword.isBlank()) return@withContext combined.sortedByDescending { it.importance }

        combined.filter {
            it.summary.contains(keyword, ignoreCase = true) ||
                    it.tags.any { t -> t.contains(keyword, ignoreCase = true) }
        }.sortedByDescending { it.importance }
    }
}
