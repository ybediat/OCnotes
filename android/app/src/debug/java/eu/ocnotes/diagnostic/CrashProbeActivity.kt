package eu.ocnotes.diagnostic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.OsConstants
import androidx.activity.ComponentActivity

/**
 * Sonde manuelle de bout en bout, absente de l'APK release.
 *
 * Invocation :
 *
 *     adb shell am start -n \
 *       eu.ocnotes.debug/eu.ocnotes.diagnostic.CrashProbeActivity \
 *       -e mode kotlin
 *
 * Cinq modes, un par chemin de collecte. Le gestionnaire Kotlin n'en voit que
 * deux ; les trois autres ne remontent qu'au lancement suivant, par
 * `ApplicationExitInfo`, et c'est précisément ce qu'ils servent à éprouver.
 *
 * | `mode`   | ce qui se passe             | ce qu'on doit lire ensuite            |
 * |----------|-----------------------------|---------------------------------------|
 * | `kotlin` | `IllegalStateException`     | `source: uncaught_exception`          |
 * | `oom`    | `OutOfMemoryError`          | `source: uncaught_exception`          |
 * | `native` | `SIGABRT`                   | `reason: native_crash`, `anr_trace: none` |
 * | `kill`   | `SIGKILL`                   | `reason: killed_by_signal`            |
 * | `anr`    | fil principal gelé 30 s     | `reason: anr`, puis des cadres `at …` |
 *
 * `kotlin` par défaut. **Le mode `anr` demande de toucher l'écran pendant le
 * gel** : sans événement d'entrée, le système n'a rien à faire expirer.
 *
 * Le mode `native` est celui qui compte le plus : il produit une tombstone,
 * que le rapport ne doit surtout pas recopier — elle porte des registres et
 * des extraits de mémoire. `anr_trace: none` est la vérification.
 *
 * Le marqueur ressemble volontairement à une donnée qu'un rapport ne doit
 * jamais conserver. Aucun mode ne doit le faire apparaître.
 */
class CrashProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_OOM -> gonflerJusquAuManqueDeMemoire()

            // Le signal est envoyé à ce processus-ci. `SIGABRT` passe par
            // debuggerd et compte comme crash natif ; `SIGKILL` non.
            MODE_NATIVE -> Process.sendSignal(Process.myPid(), OsConstants.SIGABRT)
            MODE_KILL -> Process.sendSignal(Process.myPid(), OsConstants.SIGKILL)

            // Posté plutôt qu'exécuté ici : la fenêtre doit exister pour que
            // le système ait une entrée à faire expirer.
            MODE_ANR -> Handler(Looper.getMainLooper()).post { Thread.sleep(GEL_MS) }

            else -> error(PROBE_PRIVATE_MARKER)
        }
    }

    /**
     * Un `OutOfMemoryError` emprunte le même gestionnaire qu'une exception —
     * c'est un `Throwable`. Les blocs sont retenus dans une liste, sans quoi
     * le ramasse-miettes déferait le travail au fur et à mesure.
     */
    private fun gonflerJusquAuManqueDeMemoire() {
        val blocs = mutableListOf<ByteArray>()
        while (true) {
            blocs += ByteArray(TAILLE_BLOC)
        }
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_OOM = "oom"
        const val MODE_NATIVE = "native"
        const val MODE_KILL = "kill"
        const val MODE_ANR = "anr"

        /** Au-delà du seuil d'ANR, pour que le gel se termine en incident. */
        const val GEL_MS = 30_000L
        const val TAILLE_BLOC = 8 * 1024 * 1024

        const val PROBE_PRIVATE_MARKER =
            "OCNOTES_PRIVATE_PROBE https://private.invalid/Notes/secret.md?token=never-store"
    }
}
