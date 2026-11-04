package com.sednium.kryptonide.data.repository

import android.content.Context
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class Skill(val name: String, val content: String, val sizeBytes: Long)

@Serializable
data class SkillMetadata(
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * Skills are just markdown files — instructions/context you want Oorty Lite to
 * always have available, the same idea as Claude's own skills: a folder of
 * guidance read before certain tasks, except here (for now) they're simply
 * concatenated into the system prompt every request rather than being
 * discovered/triggered selectively. That's a real, honest scope for a first
 * pass — selective triggering by task relevance would need its own matching
 * logic and is a reasonable follow-up, not something to fake here.
 */
class SkillsRepository(private val context: Context) {

    private val skillsDir: File by lazy {
        val externalDir = File(StorageUtils.getKryptonideRoot(), "skills")
        val legacyDir = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/skills")
        if (legacyDir.exists() && legacyDir.isDirectory && (!externalDir.exists() || (externalDir.listFiles()?.isEmpty() == true))) {
            runCatching { legacyDir.copyRecursively(externalDir, overwrite = false) }
        }
        if (externalDir.exists() || externalDir.mkdirs() || externalDir.canWrite()) {
            externalDir
        } else {
            File(context.filesDir, "skills").apply { mkdirs() }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun loadMetadata(): List<SkillMetadata> {
        val jsonFile = File(skillsDir, "skills.json")
        if (!jsonFile.exists()) return emptyList()
        val text = try {
            jsonFile.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<SkillMetadata>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun syncSkillsJson(specificName: String? = null, specificFileName: String? = null): List<SkillMetadata> {
        return try {
            skillsDir.mkdirs()
            val jsonFile = File(skillsDir, "skills.json")
            val existingMap = mutableMapOf<String, SkillMetadata>()

            if (jsonFile.exists()) {
                try {
                    val text = jsonFile.readText()
                    if (text.isNotBlank()) {
                        val parsed = json.decodeFromString<List<SkillMetadata>>(text)
                        parsed.forEach { existingMap[it.fileName] = it }
                    }
                } catch (_: Exception) {
                }
            }

            if (specificName != null && specificFileName != null) {
                val f = File(skillsDir, specificFileName)
                existingMap[specificFileName] = SkillMetadata(
                    name = specificName,
                    fileName = specificFileName,
                    sizeBytes = if (f.exists()) f.length() else 0L,
                    lastModified = if (f.exists()) f.lastModified() else System.currentTimeMillis()
                )
            }

            val mdFiles = skillsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray()
            val existingFileNames = mdFiles.map { it.name }.toSet()

            // Remove metadata for files that no longer exist
            existingMap.keys.retainAll(existingFileNames)

            // Add or update metadata for existing files
            for (file in mdFiles) {
                val prev = existingMap[file.name]
                val extractedName = if (prev == null && file.length() < 100_000 && file.exists()) {
                    try {
                        file.bufferedReader().use { r ->
                            val firstLine = r.readLine() ?: ""
                            if (firstLine.trim() == "---") {
                                var foundName: String? = null
                                var line = r.readLine()
                                while (line != null && line.trim() != "---") {
                                    if (line.trim().startsWith("name:")) {
                                        foundName = line.substringAfter("name:").trim().trim('"', '\'')
                                        break
                                    }
                                    line = r.readLine()
                                }
                                foundName
                            } else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                } else null

                existingMap[file.name] = SkillMetadata(
                    name = prev?.name ?: extractedName ?: file.nameWithoutExtension,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            }

            val syncedList = existingMap.values.sortedBy { it.name }
            jsonFile.writeText(json.encodeToString(syncedList))
            syncedList
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun migrateOldInternalSkillsIfNeeded() {
        try {
            val oldDir = File(context.filesDir, "skills")
            if (oldDir.exists() && oldDir.canonicalPath != skillsDir.canonicalPath) {
                val oldFiles = oldDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return
                for (file in oldFiles) {
                    skillsDir.mkdirs()
                    val dest = File(skillsDir, file.name)
                    if (!dest.exists()) {
                        file.copyTo(dest, overwrite = false)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun listSkills(): List<Skill> = withContext(Dispatchers.IO) {
        migrateOldInternalSkillsIfNeeded()
        val syncedMeta = syncSkillsJson().associateBy { it.fileName }
        val mdFiles = skillsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: emptyList()
        mdFiles.map { file ->
            val skillName = syncedMeta[file.name]?.name ?: file.nameWithoutExtension
            Skill(
                name = skillName,
                content = file.readText(),
                sizeBytes = file.length()
            )
        }
    }

    suspend fun addSkill(name: String, content: String) = withContext(Dispatchers.IO) {
        skillsDir.mkdirs()
        val safeName = name.trim().ifBlank { "untitled" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(skillsDir, "$safeName.md")
        file.writeText(content)
        syncSkillsJson(specificName = name.trim().ifBlank { safeName }, specificFileName = file.name)
    }

    suspend fun importSkill(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val extractedName = if (content.startsWith("---")) {
            val frontmatter = content.substringAfter("---").substringBefore("---")
            val nameLine = frontmatter.lines().firstOrNull { it.trim().startsWith("name:") }
            nameLine?.substringAfter("name:")?.trim()?.trim('"', '\'')
        } else null

        val finalName = extractedName?.takeIf { it.isNotBlank() }
            ?: fileName.removeSuffix(".md").removeSuffix(".markdown").ifBlank { "untitled_skill" }
        addSkill(finalName, content)
        finalName
    }

    suspend fun deleteSkill(name: String) = withContext(Dispatchers.IO) {
        val safeName = name.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file1 = File(skillsDir, "$name.md")
        if (file1.exists()) file1.delete()
        val file2 = File(skillsDir, "$safeName.md")
        if (file2.exists()) file2.delete()

        val jsonFile = File(skillsDir, "skills.json")
        if (jsonFile.exists()) {
            try {
                val text = jsonFile.readText()
                if (text.isNotBlank()) {
                    val metadata = json.decodeFromString<List<SkillMetadata>>(text)
                    val match = metadata.firstOrNull {
                        it.name.equals(name, ignoreCase = true) ||
                        it.fileName == name ||
                        it.fileName == "$name.md"
                    }
                    if (match != null) {
                        val file3 = File(skillsDir, match.fileName)
                        if (file3.exists()) file3.delete()
                    }
                }
            } catch (_: Exception) {
            }
        }
        syncSkillsJson()
    }

    /** All skills concatenated, ready to append to a system prompt. Empty string if none. */
    suspend fun getCombinedSkillsContent(): String = withContext(Dispatchers.IO) {
        val skills = listSkills()
        if (skills.isEmpty()) return@withContext ""
        buildString {
            append("\n\n---\n# Available Skills\n")
            append("The following skill files contain additional instructions/context. Apply the ones relevant to the current task.\n\n")
            skills.forEach { skill ->
                append("## Skill: ${skill.name}\n")
                append(skill.content.trim())
                append("\n\n")
            }
        }
    }
}

