package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.service.FileWatcherService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HiddenFilesPreservationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testHiddenConfigFilesIncludedInScan() {
        val projectDir = tempFolder.newFolder("MyProject")
        File(projectDir, ".gitignore").writeText("*.log\nbuild/\n")
        File(projectDir, ".editorconfig").writeText("root = true\n")
        File(projectDir, ".env.example").writeText("API_KEY=dummy\n")

        val githubDir = File(projectDir, ".github/workflows").apply { mkdirs() }
        File(githubDir, "ci.yml").writeText("name: CI\n")

        val vscodeDir = File(projectDir, ".vscode").apply { mkdirs() }
        File(vscodeDir, "settings.json").writeText("{}\n")

        val scanned = FileWatcherService.scanProjectFiles("MyProject", projectDir)
        val relativePaths = scanned.map { it.relativePath }.toSet()

        assertTrue("Expected .gitignore in scan", relativePaths.contains(".gitignore"))
        assertTrue("Expected .editorconfig in scan", relativePaths.contains(".editorconfig"))
        assertTrue("Expected .env.example in scan", relativePaths.contains(".env.example"))
        assertTrue("Expected .github/workflows/ci.yml in scan", relativePaths.contains(".github/workflows/ci.yml"))
        assertTrue("Expected .vscode/settings.json in scan", relativePaths.contains(".vscode/settings.json"))
    }

    @Test
    fun testInternalDirsExcludedFromScan() {
        val projectDir = tempFolder.newFolder("MyRepo")
        val gitDir = File(projectDir, ".git/objects").apply { mkdirs() }
        File(gitDir, "001122").writeText("git blob")

        val nodeModules = File(projectDir, "node_modules/express").apply { mkdirs() }
        File(nodeModules, "index.js").writeText("module.exports = {}")

        val buildDir = File(projectDir, "build/classes").apply { mkdirs() }
        File(buildDir, "Main.class").writeText("bytecode")

        val metadataDir = File(projectDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "meta.json").writeText("{}")

        val scanned = FileWatcherService.scanProjectFiles("MyRepo", projectDir)
        val relativePaths = scanned.map { it.relativePath }.toSet()

        assertFalse("Must NOT include .git internal files", relativePaths.any { it.startsWith(".git") })
        assertFalse("Must NOT include node_modules", relativePaths.any { it.startsWith("node_modules") })
        assertFalse("Must NOT include build directory", relativePaths.any { it.startsWith("build") })
        assertFalse("Must NOT include .metadata", relativePaths.any { it.startsWith(".metadata") })
    }
}
