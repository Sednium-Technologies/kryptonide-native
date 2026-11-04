package com.sednium.kryptonide.agent.checkpoint

import com.sednium.kryptonide.utils.StorageUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TurnCheckpoint(
    val turnId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val originalFiles: MutableMap<String, String?> = ConcurrentHashMap()
)

object CheckpointManager {

    private val checkpoints = ConcurrentHashMap<String, TurnCheckpoint>()
    private val revertedTurns = ConcurrentHashMap.newKeySet<String>()

    private val checkpointDir: File by lazy {
        val dir = File(StorageUtils.getKryptonideRoot(), ".checkpoints")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    init {
        loadPersistedCheckpoints()
    }

    private fun loadPersistedCheckpoints() {
        try {
            val dir = checkpointDir
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                    val turnId = file.nameWithoutExtension
                    loadFromFile(file)?.let { cp ->
                        checkpoints.putIfAbsent(turnId, cp)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromFile(file: File): TurnCheckpoint? {
        return try {
            val raw = file.readText()
            val json = JSONObject(raw)
            val turnId = json.getString("turnId")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val isReverted = json.optBoolean("isReverted", false)
            if (isReverted) {
                revertedTurns.add(turnId)
            }
            val filesObj = json.optJSONObject("originalFiles")
            val filesMap = ConcurrentHashMap<String, String?>()
            filesObj?.keys()?.forEach { key ->
                val content = if (filesObj.isNull(key) || filesObj.optString(key) == "__NULL_NEW_FILE__") {
                    null
                } else {
                    filesObj.getString(key)
                }
                filesMap[key] = content
            }
            TurnCheckpoint(turnId, timestamp, filesMap)
        } catch (e: Exception) {
            null
        }
    }

    private fun persistCheckpoint(cp: TurnCheckpoint) {
        try {
            val dir = checkpointDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${cp.turnId}.json")
            val json = JSONObject()
            json.put("turnId", cp.turnId)
            json.put("timestamp", cp.timestamp)
            json.put("isReverted", isReverted(cp.turnId))
            val filesObj = JSONObject()
            cp.originalFiles.forEach { (path, content) ->
                if (content == null) {
                    filesObj.put(path, "__NULL_NEW_FILE__")
                } else {
                    filesObj.put(path, content)
                }
            }
            json.put("originalFiles", filesObj)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startTurn(turnId: String): TurnCheckpoint {
        val cp = TurnCheckpoint(turnId)
        checkpoints[turnId] = cp
        persistCheckpoint(cp)
        return cp
    }

    /**
     * Records the state of a file BEFORE the agent modifies it in the current turn.
     * If already recorded for this turn, keeps the oldest (pre-turn) version.
     */
    fun recordPreEdit(turnId: String, relativePath: String, currentContent: String?) {
        val cp = checkpoints.getOrPut(turnId) {
            val loaded = File(checkpointDir, "$turnId.json").takeIf { it.exists() }?.let { loadFromFile(it) }
            loaded ?: TurnCheckpoint(turnId)
        }
        val isNew = cp.originalFiles.putIfAbsent(relativePath, currentContent) == null
        if (isNew) {
            persistCheckpoint(cp)
        }
    }

    fun getModifiedFiles(turnId: String): List<String> {
        val cp = checkpoints[turnId] ?: File(checkpointDir, "$turnId.json").takeIf { it.exists() }?.let { loadFromFile(it) }?.also { checkpoints[turnId] = it }
        return cp?.originalFiles?.keys?.toList() ?: emptyList()
    }

    fun hasCheckpoint(turnId: String): Boolean {
        val cp = checkpoints[turnId] ?: File(checkpointDir, "$turnId.json").takeIf { it.exists() }?.let { loadFromFile(it) }?.also { checkpoints[turnId] = it }
        return cp != null && cp.originalFiles.isNotEmpty() && !isReverted(turnId)
    }

    fun isReverted(turnId: String): Boolean {
        if (revertedTurns.contains(turnId)) return true
        val file = File(checkpointDir, "$turnId.json")
        if (file.exists()) {
            loadFromFile(file)
        }
        return revertedTurns.contains(turnId)
    }

    /**
     * Restores all files touched during the specified turn to their pre-turn state.
     */
    fun revertTurn(turnId: String, restoreCallback: (path: String, content: String?) -> Unit): Boolean {
        val cp = checkpoints[turnId] ?: File(checkpointDir, "$turnId.json").takeIf { it.exists() }?.let { loadFromFile(it) } ?: return false
        if (isReverted(turnId)) return false

        cp.originalFiles.forEach { (path, originalContent) ->
            restoreCallback(path, originalContent)
        }
        revertedTurns.add(turnId)
        persistCheckpoint(cp)
        return true
    }

    fun clear() {
        checkpoints.clear()
        revertedTurns.clear()
        try {
            checkpointDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
