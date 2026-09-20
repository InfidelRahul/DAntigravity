package com.droidantigravity.diagnostics

import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ExportLogManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var paths: AppPaths

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        paths = AppPaths.forTesting(filesDir, cacheDir)
        DiagnosticLogger.init(paths.diagnosticsLogsDir)
    }

    @Test
    fun testExportDiagnosticsZipGeneration() = runBlocking {
        // Create sample diagnostic logs
        paths.diagnosticLogFile.writeText("{\"timestamp\":\"2026-09-20T16:24:00Z\",\"level\":\"INFO\",\"message\":\"Test log\"}\n")
        paths.stdoutLogFile.writeText("[2026-09-20T16:24:00Z] AGY-123 STDOUT pid=100 Started\n")
        paths.stderrLogFile.writeText("[2026-09-20T16:24:00Z] AGY-123 STDERR pid=100 Warning\n")

        val opId = "AGY-BUNDLE-TEST"
        val zipFile = ExportLogManager.exportDiagnosticsZip(paths, opId)

        assertTrue("ZIP bundle file must exist", zipFile.exists())
        assertTrue("ZIP bundle file must not be empty", zipFile.length() > 0)
        assertTrue("Filename must contain Diagnostics", zipFile.name.contains("DroidAntigravity-Diagnostics"))

        val zip = ZipFile(zipFile)
        val entryNames = zip.entries().asSequence().map { it.name }.toSet()

        assertTrue("app.log must be in zip", entryNames.contains("diagnostics/app.log"))
        assertTrue("stdout.log must be in zip", entryNames.contains("diagnostics/stdout.log"))
        assertTrue("stderr.log must be in zip", entryNames.contains("diagnostics/stderr.log"))
        assertTrue("system.txt must be in zip", entryNames.contains("diagnostics/system.txt"))
        assertTrue("environment.txt must be in zip", entryNames.contains("diagnostics/environment.txt"))
        assertTrue("metadata.json must be in zip", entryNames.contains("diagnostics/metadata.json"))

        // Verify metadata.json
        val metaEntry = zip.getEntry("diagnostics/metadata.json")
        assertNotNull(metaEntry)
        val metaContent = zip.getInputStream(metaEntry).bufferedReader().readText()
        assertTrue("metadata must contain operationId", metaContent.contains(opId))
        assertTrue("metadata must contain appVersion", metaContent.contains("appVersion"))
        assertTrue("metadata must contain runtime PRoot", metaContent.contains("PRoot"))

        // Verify no secrets in metadata or environment
        assertFalse("Metadata must not contain tokens", metaContent.contains("ya29."))
        assertFalse("Metadata must not contain passwords", metaContent.contains("password"))

        zip.close()
    }

    @Test
    fun testCreateFailureSnapshot() {
        val opId = "AGY-FAIL-SNAPSHOT"
        paths.diagnosticLogFile.writeText("Error event before crash\n")
        paths.stdoutLogFile.writeText("stdout before crash\n")

        val snapshotFile = ExportLogManager.createFailureSnapshot(
            paths,
            opId,
            "Timed out waiting for URL",
            "Stack trace details here with Bearer ya29.SECRET"
        )

        assertNotNull(snapshotFile)
        assertTrue(snapshotFile!!.exists())

        val content = snapshotFile.readText()
        assertTrue(content.contains(opId))
        assertTrue(content.contains("Timed out waiting for URL"))
        assertFalse("Sensitive token in snapshot details must be redacted", content.contains("ya29.SECRET"))

        val failureDir = paths.failureSnapshotDir
        assertTrue(File(failureDir, "app.log").exists())
        assertTrue(File(failureDir, "stdout.log").exists())
    }
}
