package eu.ocnotes.diagnostic

import org.junit.Assert.assertEquals
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
            arretsConsecutifs = 1,
            mainThread = true,
            throwable = crash,
        )

        assertFalse(report.contains(secret))
        assertFalse(report.contains(messageMarker))
        assertFalse(report.contains(causeMarker))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("caused_by: java.lang.IllegalStateException"))
        assertTrue(report.contains("consecutive_abnormal_exits: 1"))
    }

    @Test
    fun `une chaine de causes circulaire ne fait pas boucler le rapport`() {
        val premiere = RuntimeException("a")
        val seconde = RuntimeException("b", premiere)
        premiere.initCause(seconde)

        val report = DiagnosticReportFormatter.uncaughtException(
            metadata = metadata,
            timestampMillis = 0,
            arretsConsecutifs = 1,
            mainThread = false,
            throwable = premiere,
        )

        assertEquals(1, report.lines().count { it.startsWith("exception: ") })
        assertEquals(1, report.lines().count { it.startsWith("caused_by: ") })
    }

    @Test
    fun `la chaine de causes est bornee en profondeur`() {
        var courante = RuntimeException("racine")
        repeat(20) { courante = RuntimeException("cause", courante) }

        val report = DiagnosticReportFormatter.uncaughtException(
            metadata = metadata,
            timestampMillis = 0,
            arretsConsecutifs = 1,
            mainThread = false,
            throwable = courante,
        )

        // Huit maillons au total : une exception, sept causes.
        assertEquals(7, report.lines().count { it.startsWith("caused_by: ") })
    }

    @Test
    fun `la description systeme ne traverse jamais en texte libre`() {
        val fuite = "java.lang.IllegalStateException: opencloud: " +
            "https://cloud.example/Notes/secret.md?token=abc"

        assertEquals("other", DiagnosticReportFormatter.classifierDescription(fuite))
        assertEquals("", DiagnosticReportFormatter.classifierDescription(null))
        assertEquals(
            "input_dispatching_timeout",
            DiagnosticReportFormatter.classifierDescription(
                "Input dispatching timed out (Window{ab12 eu.ocnotes/eu.ocnotes.ui.MainActivity})",
            ),
        )
    }

    @Test
    fun `la trace ANR est reduite a ses cadres de pile`() {
        val nomDeFilPrive = "OCNOTES_PRIVATE_THREAD_NAME"
        val trace = """
            ----- pid 1234 at 2026-09-06 19:16:30 -----
            Cmd line: eu.ocnotes
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 obj=0x12345678
              at eu.ocnotes.ui.editor.EditorScreen.frappe(EditorScreen.kt:118)
              at android.os.Looper.loop(Looper.java:223)
              - waiting to lock <0x0000> held by thread 7
            "$nomDeFilPrive" prio=5 tid=7 Native
              at java.lang.Object.wait(Native Method)
        """.trimIndent()

        val filtree = DiagnosticReportFormatter.filtrerTraceAnr(trace.lineSequence())

        assertTrue(filtree.contains("at eu.ocnotes.ui.editor.EditorScreen.frappe(EditorScreen.kt:118)"))
        assertTrue(filtree.contains("at java.lang.Object.wait(Native Method)"))
        assertTrue(filtree.contains("--- thread 2 ---"))
        assertFalse(filtree.contains(nomDeFilPrive))
        assertFalse(filtree.contains("Cmd line"))
        assertFalse(filtree.contains("prio=5"))
        assertFalse(filtree.contains("waiting to lock"))
    }

    @Test
    fun `un arret systeme porte l ecran et la memoire sans texte libre`() {
        val report = DiagnosticReportFormatter.systemExit(
            metadata = metadata,
            timestampMillis = 0,
            arretsConsecutifs = 3,
            detail = DetailArretSysteme(
                reason = "native_crash",
                importance = "foreground",
                pssKo = 412_000,
                rssKo = 501_000,
                status = 0,
                description = "native_crash",
                filAriane = "editeur;lines=1633;chars=48210",
                traceAnr = "",
            ),
        )

        assertTrue(report.contains("OCnotes diagnostic v2"))
        assertTrue(report.contains("reason: native_crash"))
        assertTrue(report.contains("importance: foreground"))
        assertTrue(report.contains("memory_kb: pss=412000 rss=501000"))
        assertTrue(report.contains("last_screen: editeur;lines=1633;chars=48210"))
        assertTrue(report.contains("consecutive_abnormal_exits: 3"))
        assertTrue(report.contains("anr_trace: none"))
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

    @Test
    fun `un rapport demesure est tronque a l ecriture`() {
        val reportFile = File(temporaryFolder.root, "diagnostic/last-crash.txt")
        val store = DiagnosticStore(reportFile)

        store.replace("x".repeat(200 * 1024))

        assertEquals(48 * 1024, store.read()?.length)
    }
}
