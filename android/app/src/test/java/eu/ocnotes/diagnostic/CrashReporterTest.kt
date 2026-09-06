package eu.ocnotes.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val metadata = DiagnosticMetadata(
        versionName = "0.1.2",
        versionCode = 3,
        androidVersion = "15",
        androidApi = 35,
        device = "Test Phone",
        abis = "arm64-v8a",
    )

    @Test
    fun `le rapport omet tous les messages d exception`() {
        val secret = "https://cloud.example/Notes/journal-prive.md?token=tres-secret"
        val causeMarker = "CONFIDENTIAL_CAUSE_TEXT"
        val messageMarker = "CONFIDENTIAL_EXCEPTION_TEXT"
        val cause = IllegalStateException("$causeMarker $secret")
        val crash = IllegalArgumentException("$messageMarker $secret", cause)

        val report = DiagnosticReportFormatter.uncaughtException(
            metadata = metadata,
            timestampMillis = 0,
            mainThread = true,
            throwable = crash,
        )

        assertFalse(report.contains(secret))
        assertFalse(report.contains(messageMarker))
        assertFalse(report.contains(causeMarker))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("caused_by: java.lang.IllegalStateException"))
    }

    @Test
    fun `un nouveau crash remplace le precedent et la suppression est definitive`() {
        val reportFile = File(temporaryFolder.root, "diagnostic/last-crash.txt")
        val store = DiagnosticStore(reportFile)

        store.replace("premier")
        store.replace("second")

        assertTrue(store.read() == "second")
        store.delete()
        assertTrue(store.read() == null)
        assertFalse(reportFile.exists())
    }
}
