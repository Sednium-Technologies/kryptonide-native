package com.sednium.kryptonide.utils

import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.model.ProjectTemplate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectDirectoryResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeStorageRoot: File
    private lateinit var kryptonideRoot: File
    private lateinit var projectsDir: File
    private lateinit var legacyRootDir: File

    @Before
    fun setUp() {
        fakeStorageRoot = tempFolder.newFolder("storage")
        kryptonideRoot = File(fakeStorageRoot, "Kryptonide").apply { mkdirs() }
        projectsDir = File(kryptonideRoot, "projects").apply { mkdirs() }
        legacyRootDir = File(fakeStorageRoot, "KryptonIDE").apply { mkdirs() }
    }

    @Test
    fun testRejectsPathTraversalInName() {
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val evilProjects = listOf(
            Project(id = "1", name = "../../../etc/passwd", template = ProjectTemplate.BLANK),
            Project(id = "2", name = "foo/bar", template = ProjectTemplate.BLANK),
            Project(id = "3", name = "foo\\bar", template = ProjectTemplate.BLANK),
            Project(id = "4", name = "..", template = ProjectTemplate.BLANK),
            Project(id = "5", name = "test\u0000bad", template = ProjectTemplate.BLANK)
        )

        evilProjects.forEach { evil ->
            val result = resolver.resolve(evil)
            assertTrue("Expected Invalid for ${evil.name}, got $result", result is ProjectDirResult.Invalid)
        }
    }

    @Test
    fun testRejectsInternalDirectories() {
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val reserved = listOf(".metadata", ".checkpoints", "projects", "plugins", ".git")
        reserved.forEach { name ->
            val result = resolver.resolve(Project(id = "res", name = name, template = ProjectTemplate.BLANK))
            assertTrue("Expected Invalid for internal directory $name", result is ProjectDirResult.Invalid)
        }
    }

    @Test
    fun testResolvesExplicitPath() {
        val customDir = tempFolder.newFolder("custom_project")
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val project = Project(id = "p1", name = "MyProject", template = ProjectTemplate.BLANK, path = customDir.absolutePath)
        val result = resolver.resolve(project)

        assertTrue(result is ProjectDirResult.Resolved)
        val resolved = result as ProjectDirResult.Resolved
        assertEquals(customDir.canonicalPath, resolved.directory.canonicalPath)
        assertEquals(ProjectDirSource.EXPLICIT_PATH, resolved.source)
    }

    @Test
    fun testResolvesPreferredProjectsDir() {
        val projDir = File(projectsDir, "AwesomeApp").apply { mkdirs() }
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val project = Project(id = "p2", name = "AwesomeApp", template = ProjectTemplate.BLANK)
        val result = resolver.resolve(project)

        assertTrue(result is ProjectDirResult.Resolved)
        val resolved = result as ProjectDirResult.Resolved
        assertEquals(projDir.canonicalPath, resolved.directory.canonicalPath)
        assertEquals(ProjectDirSource.PREFERRED_PROJECTS_DIR, resolved.source)
    }

    @Test
    fun testResolvesRootWorkspaceDirWithoutEagerRename() {
        val rootApp = File(kryptonideRoot, "RootApp").apply { mkdirs() }
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val project = Project(id = "p3", name = "RootApp", template = ProjectTemplate.BLANK)
        val result = resolver.resolve(project)

        assertTrue(result is ProjectDirResult.Resolved)
        val resolved = result as ProjectDirResult.Resolved
        assertEquals(rootApp.canonicalPath, resolved.directory.canonicalPath)
        assertEquals(ProjectDirSource.ROOT_WORKSPACE_DIR, resolved.source)
        // Ensure root app still exists and wasn't side-effect moved during resolve
        assertTrue(rootApp.exists())
    }

    @Test
    fun testResolvesLegacyKryptonIDEDir() {
        val legacyApp = File(legacyRootDir, "LegacyApp").apply { mkdirs() }
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val project = Project(id = "p4", name = "LegacyApp", template = ProjectTemplate.BLANK)
        val result = resolver.resolve(project)

        assertTrue(result is ProjectDirResult.Resolved)
        val resolved = result as ProjectDirResult.Resolved
        assertEquals(legacyApp.canonicalPath, resolved.directory.canonicalPath)
        assertEquals(ProjectDirSource.LEGACY_KRYPTON_IDE_DIR, resolved.source)
    }

    @Test
    fun testReturnsMissingWithoutAutoCreatingDirectory() {
        val resolver = ProjectDirectoryResolver(
            externalStorageDir = fakeStorageRoot,
            kryptonideRoot = kryptonideRoot,
            projectsDir = projectsDir,
            legacyRootDir = legacyRootDir
        )

        val project = Project(id = "p5", name = "NonExistentApp", template = ProjectTemplate.BLANK)
        val result = resolver.resolve(project)

        assertTrue("Expected Missing, got $result", result is ProjectDirResult.Missing)
        val missing = result as ProjectDirResult.Missing
        assertEquals("NonExistentApp", missing.preferredLocation.name)
        assertFalse("Missing dir must NOT be created automatically on resolve", missing.preferredLocation.exists())
    }
}
