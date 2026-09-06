package eu.ocnotes.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Capture le strict minimum permettant de diagnostiquer un arrêt inattendu.
 *
 * Le rapport reste dans le cache privé et n'est jamais envoyé par cette
 * classe. Les messages d'exception sont volontairement absents : ceux du cœur
 * Go peuvent contenir une URL ou un chemin de note. Seuls les types et les
 * cadres de pile, qui proviennent du programme, sont conservés.
 */
class CrashReporter private constructor(
    private val context: Context,
) {
    private val store = DiagnosticStore(File(context.cacheDir, REPORT_PATH))
    // Résolu pendant un démarrage sain : le handler de crash ne fait ainsi ni
    // appel Binder au PackageManager, ni collecte supplémentaire fragile.
    private val appMetadata = runCatching { metadata(context) }.getOrElse {
        fallbackMetadata()
    }

    /** Rapport du dernier crash seulement, ou `null` s'il n'y en a pas. */
    fun pendingReport(): String? = store.read()

    /** Efface immédiatement le rapport après le choix de l'utilisateur. */
    fun discardPending() = store.delete()

    private fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                store.replace(
                    DiagnosticReportFormatter.uncaughtException(
                        metadata = appMetadata,
                        timestampMillis = System.currentTimeMillis(),
                        mainThread = thread === Looper.getMainLooper().thread,
                        throwable = throwable,
                    ),
                )
            } catch (_: Throwable) {
                // Un rapport de diagnostic ne doit jamais masquer le crash.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    // Cas défensif : Android installe normalement toujours son
                    // propre handler, qui termine le processus.
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    /**
     * Récupère au lancement suivant les crashs natifs et ANR que le handler
     * Kotlin ne peut pas voir. Android conserve lui-même cet historique à
     * partir de l'API 30 ; aucune trace système brute n'est recopiée.
     */
    private fun collectLastSystemExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching { collectLastSystemExitApi30() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectLastSystemExitApi30() {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, EXIT_HISTORY_SIZE)
        if (exits.isEmpty()) return

        val preferences = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val alreadySeen = preferences.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val newest = exits.maxBy { it.timestamp }

        // Commit synchrone : si ce nouveau processus tombe lui aussi, le même
        // ancien incident ne doit pas être reproposé indéfiniment.
        preferences.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newest.timestamp).commit()

        if (store.read() != null) return
        if (newest.timestamp <= alreadySeen || newest.reason !in REPORTED_EXIT_REASONS) return

        store.replace(
            DiagnosticReportFormatter.systemExit(
                metadata = appMetadata,
                timestampMillis = newest.timestamp,
                reason = reasonName(newest.reason),
            ),
        )
    }

    companion object {
        /** À appeler immédiatement après `Application.onCreate`. */
        fun install(context: Context): CrashReporter =
            CrashReporter(context.applicationContext).also {
                // Le handler est posé avant toute lecture Android susceptible
                // d'échouer pendant l'initialisation de l'application.
                it.installHandler()
                it.collectLastSystemExit()
            }

        private const val REPORT_PATH = "diagnostic/last-crash.txt"
        private const val STATE_PREFS = "ocnotes_diagnostic_state"
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
        private const val EXIT_HISTORY_SIZE = 5

        private val REPORTED_EXIT_REASONS = setOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )

        private fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH -> "java_or_kotlin_crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
            else -> "unexpected_exit"
        }
    }
}

/** Écriture atomique d'un unique rapport, remplacé plutôt qu'accumulé. */
internal class DiagnosticStore(private val report: File) {

    fun read(): String? = runCatching {
        report.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @Synchronized
    fun replace(content: String) {
        report.parentFile?.mkdirs()
        val temporary = File(report.parentFile, "${report.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.take(MAX_REPORT_CHARS).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(report)) {
            temporary.copyTo(report, overwrite = true)
            temporary.delete()
        }
    }

    @Synchronized
    fun delete() {
        report.delete()
        File(report.parentFile, "${report.name}.tmp").delete()
        report.parentFile?.delete() // Ne réussit que si le dossier est vide.
    }

    private companion object {
        const val MAX_REPORT_CHARS = 48 * 1024
    }
}

internal data class DiagnosticMetadata(
    val versionName: String,
    val versionCode: Long,
    val androidVersion: String,
    val androidApi: Int,
    val device: String,
    val abis: String,
)

private fun metadata(context: Context): DiagnosticMetadata {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return DiagnosticMetadata(
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        androidApi = Build.VERSION.SDK_INT,
        device = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" "),
        abis = Build.SUPPORTED_ABIS.joinToString(","),
    )
}

private fun fallbackMetadata(): DiagnosticMetadata = DiagnosticMetadata(
    versionName = "unknown",
    versionCode = 0,
    androidVersion = Build.VERSION.RELEASE.orEmpty(),
    androidApi = Build.VERSION.SDK_INT,
    device = "unknown",
    abis = Build.SUPPORTED_ABIS.joinToString(","),
)

/** Pur et testé sans Android : aucun message arbitraire n'entre dans le texte. */
internal object DiagnosticReportFormatter {

    fun uncaughtException(
        metadata: DiagnosticMetadata,
        timestampMillis: Long,
        mainThread: Boolean,
        throwable: Throwable,
    ): String = buildString {
        appendHeader(metadata, timestampMillis, "uncaught_exception")
        append("thread: ").append(if (mainThread) "main" else "background").append('\n')
        appendThrowable(throwable)
    }

    fun systemExit(
        metadata: DiagnosticMetadata,
        timestampMillis: Long,
        reason: String,
    ): String = buildString {
        appendHeader(metadata, timestampMillis, "android_exit_history")
        append("reason: ").append(reason).append('\n')
        append("detail: unavailable_by_privacy_design\n")
    }

    private fun StringBuilder.appendHeader(
        metadata: DiagnosticMetadata,
        timestampMillis: Long,
        source: String,
    ) {
        append("OCnotes diagnostic v1\n")
        append("source: ").append(source).append('\n')
        append("timestamp_utc: ").append(Instant.ofEpochMilli(timestampMillis)).append('\n')
        append("app: ").append(metadata.versionName).append(" (").append(metadata.versionCode).append(")\n")
        append("android: ").append(metadata.androidVersion).append(" (API ").append(metadata.androidApi).append(")\n")
        append("device: ").append(metadata.device).append('\n')
        append("abis: ").append(metadata.abis).append('\n')
    }

    private fun StringBuilder.appendThrowable(root: Throwable) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = root
        var depth = 0

        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            append(if (depth == 0) "exception: " else "caused_by: ")
            append(current.javaClass.name).append('\n')
            current.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
                append("  at ")
                    .append(frame.className).append('.').append(frame.methodName)
                    .append('(').append(frame.fileName ?: "Unknown Source")
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                append(")\n")
            }
            current = current.cause
            depth++
        }
    }

    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_FRAMES_PER_CAUSE = 80
}
