package com.sednium.kryptonide.ui.projects

import android.content.Context
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Result data class for cloud backup and restore operations.
 */
data class BackupResult(
    val success: Boolean,
    val filesCount: Int = 0,
    val sizeBytes: Long = 0L,
    val message: String = ""
)

/**
 * Enterprise-grade Google Drive Cloud Backup & Restore engine for KryptonIDE.
 *
 * Packages the entire KryptonIDE workspace:
 * - Project source code & assets across all workspaces
 * - Project metadata, FileNode database, and chat history (.metadata/)
 * - AI Skills (*.md, skills.json)
 * - Model Context Protocol (MCP) server definitions (mcp_servers.json, mcp_servers.md)
 *
 * Compresses into a portable zip archive and synchronizes with Google Drive,
 * with full end-to-end restore capabilities.
 */
object DriveSyncHelper {

    private const val BACKUP_FOLDER_NAME = "KryptonIDE Backups"
    private const val BACKUP_ZIP_NAME = "kryptonide_workspace_backup.zip"
    private const val PREFS_NAME = "krypton_drive_backup"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
    private const val KEY_LAST_BACKUP_COUNT = "last_backup_count"
    private const val KEY_LAST_BACKUP_SIZE = "last_backup_size"
    private const val KEY_LAST_RESTORE_TIME = "last_restore_time"

    private val EXCLUDE_DIR_NAMES = setOf(
        "packages",
        "node_modules",
        ".gradle",
        "build",
        "dist",
        ".cache",
        "tmp",
        "models",
        ".git"
    )

    private val EXCLUDE_FILE_EXTENSIONS = setOf(
        "gguf",
        "bin",
        "onnx",
        "tflite",
        "apk",
        "aab",
        "zip"
    )

    fun getLastBackupTimestamp(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
    }

    fun getLastBackupInfo(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
        if (time == 0L) return "Never backed up"
        val count = prefs.getInt(KEY_LAST_BACKUP_COUNT, 0)
        val size = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L)
        val dateStr = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(time))
        val sizeStr = formatBytes(size)
        return if (count > 0) "$dateStr ($count files, $sizeStr)" else dateStr
    }

    /**
     * Packages the workspace into a compressed zip and writes to any output stream.
     * Can be used for local file export or Google Drive upload.
     */
    suspend fun createWorkspaceBackupZip(
        context: Context,
        outputStream: java.io.OutputStream,
        accountEmail: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val baseDir = resolveWorkspaceBaseDir(context)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val metadataDir = AppDatabase.getInstance(context).metadataDir()
            val filesToArchive = mutableListOf<Pair<File, String>>()

            // 1. Collect all files in KryptonIDE workspace directory
            collectFiles(baseDir, "", filesToArchive)

            // 2. Ensure .metadata files are included if located outside baseDir
            if (metadataDir.exists() && !metadataDir.absolutePath.startsWith(baseDir.absolutePath)) {
                collectFiles(metadataDir, ".metadata", filesToArchive)
            }

            if (filesToArchive.isEmpty()) {
                return@withContext BackupResult(
                    success = false,
                    message = "No files found in workspace to backup. Create or open a project first."
                )
            }

            var totalFilesZipped = 0
            var totalBytesWritten = 0L
            val countingOut = object : java.io.FilterOutputStream(outputStream) {
                override fun write(b: Int) {
                    super.write(b)
                    totalBytesWritten++
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    totalBytesWritten += len
                }
            }

            ZipOutputStream(BufferedOutputStream(countingOut)).use { zos ->
                // Write backup manifest
                val manifestJson = JSONObject().apply {
                    put("version", 2)
                    put("timestamp", System.currentTimeMillis())
                    put("account", accountEmail ?: "Local Workspace Backup")
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("totalFiles", filesToArchive.size)
                }.toString(2)

                val manifestEntry = ZipEntry("backup_manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                for ((file, relPath) in filesToArchive) {
                    if (!file.exists() || !file.isFile) continue
                    val entryName = relPath.replace(File.separatorChar, '/')
                    val entry = ZipEntry(entryName)
                    entry.time = file.lastModified()
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos, bufferSize = 32 * 1024)
                    }
                    zos.closeEntry()
                    totalFilesZipped++
                }
                zos.finish()
                zos.flush()
            }
            countingOut.flush()
            outputStream.flush()

            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_BACKUP_TIME, now)
                .putInt(KEY_LAST_BACKUP_COUNT, totalFilesZipped)
                .putLong(KEY_LAST_BACKUP_SIZE, totalBytesWritten)
                .apply()

            BackupResult(
                success = true,
                filesCount = totalFilesZipped,
                sizeBytes = totalBytesWritten,
                message = "Workspace successfully backed up ($totalFilesZipped files, ${formatBytes(totalBytesWritten)})"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BackupResult(
                success = false,
                message = e.localizedMessage ?: "Backup failed with unknown error"
            )
        }
    }

    /**
     * Restores workspace files from a zip input stream.
     * Can be used for local file import or Google Drive download.
     */
    suspend fun restoreWorkspaceFromStream(
        context: Context,
        inputStream: java.io.InputStream
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val targetDir = resolveWorkspaceBaseDir(context)
            if (!targetDir.exists()) targetDir.mkdirs()

            var extractedCount = 0
            var totalBytes = 0L
            val canonicalTarget = runCatching { targetDir.canonicalFile }.getOrNull()
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name != "backup_manifest.json") {
                        val destFile = File(targetDir, name)
                        val canonicalDest = runCatching { destFile.canonicalFile }.getOrNull()
                        if (canonicalTarget != null && canonicalDest != null && canonicalDest.startsWith(canonicalTarget)) {
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { fos ->
                                totalBytes += zis.copyTo(fos, bufferSize = 32 * 1024)
                            }
                            extractedCount++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extractedCount == 0) {
                return@withContext BackupResult(
                    success = false,
                    message = "No valid project files found in backup archive."
                )
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RESTORE_TIME, System.currentTimeMillis())
                .apply()

            BackupResult(
                success = true,
                filesCount = extractedCount,
                sizeBytes = totalBytes,
                message = "Successfully restored $extractedCount files into workspace!"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BackupResult(
                success = false,
                message = e.localizedMessage ?: "Restore failed with unknown error"
            )
        }
    }

    /**
     * Backs up the entire workspace into a compressed zip and uploads it to Google Drive.
     */
    suspend fun backupWorkspace(context: Context, account: GoogleSignInAccount): BackupResult = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("KryptonIDE Workspace").build()

            // Create temporary zip archive
            val tempZip = File(context.cacheDir, "krypton_backup_${System.currentTimeMillis()}.zip")
            if (tempZip.exists()) tempZip.delete()

            val zipResult = FileOutputStream(tempZip).use { fos ->
                createWorkspaceBackupZip(context, fos, account.email)
            }

            if (!zipResult.success) {
                tempZip.delete()
                return@withContext zipResult
            }

            val zipSize = tempZip.length()
            val folderId = findOrCreateBackupFolder(driveService)
            uploadOrUpdateZip(driveService, folderId, tempZip, BACKUP_ZIP_NAME)

            // Also upload metadata json files directly for rapid granular sync
            val metadataDir = AppDatabase.getInstance(context).metadataDir()
            val jsonFiles = metadataDir.listFiles { f -> f.isFile && f.extension == "json" }?.toList().orEmpty()
            for (jsonFile in jsonFiles) {
                runCatching { uploadOrUpdateJson(driveService, folderId, jsonFile) }
            }

            tempZip.delete()

            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_BACKUP_TIME, now)
                .putInt(KEY_LAST_BACKUP_COUNT, zipResult.filesCount)
                .putLong(KEY_LAST_BACKUP_SIZE, zipSize)
                .apply()

            BackupResult(
                success = true,
                filesCount = zipResult.filesCount,
                sizeBytes = zipSize,
                message = "Workspace successfully backed up to Google Drive (${zipResult.filesCount} files, ${formatBytes(zipSize)})"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BackupResult(
                success = false,
                message = e.localizedMessage ?: "Backup failed with unknown error"
            )
        }
    }

    /**
     * Restores the workspace from the latest Google Drive backup zip.
     */
    suspend fun restoreWorkspace(context: Context, account: GoogleSignInAccount): BackupResult = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("KryptonIDE Workspace").build()

            val folderId = findBackupFolder(driveService)
                ?: return@withContext BackupResult(
                    success = false,
                    message = "No KryptonIDE backup folder found in this Google Drive account."
                )

            val zipDriveFile = driveService.files().list()
                .setQ("name='$BACKUP_ZIP_NAME' and '$folderId' in parents and trashed=false")
                .setSpaces("drive")
                .setFields("files(id, name, size)")
                .execute()
                .files
                .firstOrNull()

            if (zipDriveFile == null) {
                return@withContext BackupResult(
                    success = false,
                    message = "No workspace backup archive ($BACKUP_ZIP_NAME) found in Google Drive."
                )
            }

            val tempZip = File(context.cacheDir, "krypton_restore_${System.currentTimeMillis()}.zip")
            if (tempZip.exists()) tempZip.delete()

            // Download archive from Google Drive
            FileOutputStream(tempZip).use { fos ->
                driveService.files().get(zipDriveFile.id).executeMediaAndDownloadTo(fos)
            }

            val restoreResult = FileInputStream(tempZip).use { fis ->
                restoreWorkspaceFromStream(context, fis)
            }

            tempZip.delete()
            restoreResult
        } catch (e: Exception) {
            e.printStackTrace()
            BackupResult(
                success = false,
                message = e.localizedMessage ?: "Restore failed with unknown error"
            )
        }
    }

    /**
     * Backward-compatible delegation for existing sync callers.
     */
    suspend fun syncWorkspace(context: Context, account: GoogleSignInAccount): Int {
        val result = backupWorkspace(context, account)
        if (!result.success) throw IllegalStateException(result.message)
        return result.filesCount
    }

    private fun resolveWorkspaceBaseDir(context: Context): File {
        return try {
            val isManager = StorageUtils.isManagerSafe()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isManager) {
                File(StorageUtils.getExternalStorageDir(), "KryptonIDE")
            } else {
                File(context.filesDir, "KryptonIDE")
            }
        } catch (e: Exception) {
            File(context.filesDir, "KryptonIDE")
        }
    }

    private fun collectFiles(dir: File, relPrefix: String, outList: MutableList<Pair<File, String>>) {
        val items = dir.listFiles() ?: return
        for (item in items) {
            if (item.isDirectory) {
                if (EXCLUDE_DIR_NAMES.contains(item.name.lowercase())) continue
                val nextRel = if (relPrefix.isEmpty()) item.name else "$relPrefix/${item.name}"
                collectFiles(item, nextRel, outList)
            } else if (item.isFile) {
                if (EXCLUDE_FILE_EXTENSIONS.contains(item.extension.lowercase())) continue
                val relPath = if (relPrefix.isEmpty()) item.name else "$relPrefix/${item.name}"
                outList.add(item to relPath)
            }
        }
    }

    private fun findBackupFolder(driveService: Drive): String? {
        return driveService.files().list()
            .setQ("name='$BACKUP_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            .firstOrNull()?.id
    }

    private fun findOrCreateBackupFolder(driveService: Drive): String {
        val existing = findBackupFolder(driveService)
        if (existing != null) return existing

        val folderMetadata = DriveFile().apply {
            name = BACKUP_FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }
        return driveService.files().create(folderMetadata).setFields("id").execute().id
    }

    private fun uploadOrUpdateZip(driveService: Drive, folderId: String, zipFile: File, driveFileName: String) {
        val existing = driveService.files().list()
            .setQ("name='$driveFileName' and '$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            .firstOrNull()

        val mediaContent = FileContent("application/zip", zipFile)

        if (existing != null) {
            driveService.files().update(existing.id, DriveFile(), mediaContent).execute()
        } else {
            val metadata = DriveFile().apply {
                name = driveFileName
                parents = listOf(folderId)
            }
            driveService.files().create(metadata, mediaContent).execute()
        }
    }

    private fun uploadOrUpdateJson(driveService: Drive, folderId: String, localFile: File) {
        val existing = driveService.files().list()
            .setQ("name='${localFile.name}' and '$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            .firstOrNull()

        val mediaContent = FileContent("application/json", localFile)

        if (existing != null) {
            driveService.files().update(existing.id, DriveFile(), mediaContent).execute()
        } else {
            val metadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(folderId)
            }
            driveService.files().create(metadata, mediaContent).execute()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
