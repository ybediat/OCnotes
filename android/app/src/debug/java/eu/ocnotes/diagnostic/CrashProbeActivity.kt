package eu.ocnotes.diagnostic

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Sonde manuelle de bout en bout, absente de l'APK release.
 *
 * Invocation :
 *
 *     adb shell am start -n \
 *       eu.ocnotes.debug/eu.ocnotes.diagnostic.CrashProbeActivity
 *
 * Le marqueur ressemble volontairement à une donnée qu'un rapport ne doit
 * jamais conserver. [DiagnosticReportFormatter] retire le message complet.
 */
class CrashProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        error(PROBE_PRIVATE_MARKER)
    }

    private companion object {
        const val PROBE_PRIVATE_MARKER =
            "OCNOTES_PRIVATE_PROBE https://private.invalid/Notes/secret.md?token=never-store"
    }
}
