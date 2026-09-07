package eu.ocnotes.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * Deux textes échappent à cette règle et sont donc filtrés au lieu d'être
 * écartés : la description que le système compose lui-même, ramenée à un
 * vocabulaire fermé, et la trace d'un ANR, réduite à ses cadres de pile. Voir
 * [DiagnosticReportFormatter.classifierDescription] et
 * [DiagnosticReportFormatter.filtrerTraceAnr].
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

    // Achevé dès que l'historique système a été dépouillé. La collecte ne
    // bloque pas `Application.onCreate`, mais l'interface doit l'attendre :
    // sans cela, un crash natif ne s'afficherait qu'au lancement d'après.
    private val collecte = CompletableDeferred<Unit>()

    // Cette seule lecture reste sur le fil principal, et c'est délibéré : le
    // handler doit connaître le compteur sans ouvrir de fichier dans un
    // processus qui meurt, et une boucle de crashs tombe précisément avant
    // qu'un chargement asynchrone ait eu le temps d'aboutir. Un petit XML se
    // lit vite ; l'appel Binder et le commit, eux, sont bien partis en fond.
    @Volatile
    private var arretsConsecutifs = runCatching {
        preferences().getInt(KEY_ARRETS_CONSECUTIFS, 0)
    }.getOrDefault(0)

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /**
     * Rapport du dernier arrêt seulement, ou `null` s'il n'y en a pas.
     *
     * Suspend à double titre : la collecte de l'historique système peut être
     * encore en cours, et la lecture du fichier n'a rien à faire sur le fil
     * principal pendant une composition.
     */
    suspend fun pendingReport(): String? {
        collecte.join()
        return withContext(Dispatchers.IO) { store.read() }
    }

    /** Efface immédiatement le rapport après le choix de l'utilisateur. */
    fun discardPending() = store.delete()

    /**
     * L'interface est composée : la série d'arrêts est rompue.
     *
     * C'est le seul marqueur qui distingue un vrai démarrage d'un réveil de
     * `SyncWorker`, qui passe lui aussi par `Application.onCreate`.
     */
    fun signalerDemarrageSain() {
        if (arretsConsecutifs == 0) return
        arretsConsecutifs = 0
        runCatching { preferences().edit().putInt(KEY_ARRETS_CONSECUTIFS, 0).apply() }
    }

    /**
     * Fil d'Ariane : ce que faisait l'application au moment où elle est morte.
     *
     * Android conserve ces quelques octets avec l'`ApplicationExitInfo` du
     * processus, y compris quand celui-ci est tué sans préavis. C'est le seul
     * moyen de relier un crash natif à ce qui était ouvert — le handler Kotlin
     * ne voit rien de cette classe d'incidents. Les mesures sont des nombres :
     * ni nom de note, ni contenu.
     */
    fun noterEcran(ecran: String, lignes: Int = -1, caracteres: Int = -1) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching { noterEcranApi30(ecran, lignes, caracteres) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun noterEcranApi30(ecran: String, lignes: Int, caracteres: Int) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val fil = buildString {
            append(ecran.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_ECRAN_CHARS))
            if (lignes >= 0) append(";lines=").append(lignes)
            if (caracteres >= 0) append(";chars=").append(caracteres)
        }
        // Le contenu est purement ASCII après filtrage : la troncature en
        // caractères borne donc aussi la taille en octets, sous les 128 que
        // le système accepte.
        activityManager.setProcessStateSummary(
            fil.take(MAX_FIL_ARIANE_CHARS).toByteArray(Charsets.UTF_8),
        )
    }

    private fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val arrets = arretsConsecutifs + 1
                arretsConsecutifs = arrets

                // Le rapport d'abord : c'est lui qui compte, et le compteur
                // qu'il porte est déjà calculé en mémoire.
                store.replace(
                    DiagnosticReportFormatter.uncaughtException(
                        metadata = appMetadata,
                        timestampMillis = System.currentTimeMillis(),
                        arretsConsecutifs = arrets,
                        mainThread = thread === Looper.getMainLooper().thread,
                        throwable = throwable,
                    ),
                )

                // Commit et non `apply` : le processus ne survivra pas à une
                // écriture différée.
                preferences().edit().putInt(KEY_ARRETS_CONSECUTIFS, arrets).commit()
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
     * Récupère au lancement suivant les crashs natifs, les ANR et les mises à
     * mort par le système, que le handler Kotlin ne peut pas voir. Android
     * conserve lui-même cet historique à partir de l'API 30.
     *
     * Tourne hors du fil principal : un appel Binder à `ActivityManager`, un
     * chargement de préférences et une écriture synchrone n'ont pas leur place
     * dans `Application.onCreate`.
     */
    private fun collectLastSystemExitEnFond() {
        Thread(
            {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { collectLastSystemExitApi30() }
                    }
                } finally {
                    collecte.complete(Unit)
                }
            },
            COLLECTOR_THREAD_NAME,
        ).start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectLastSystemExitApi30() {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, EXIT_HISTORY_SIZE)
        if (exits.isEmpty()) return

        val preferences = preferences()
        // L'historique d'Android est antérieur à ce dispositif : au tout
        // premier lancement, il décrit une version que l'utilisateur ne peut
        // plus relier à ce qu'il vient de faire. On l'amorce sans rien dire.
        val premierLancement = !preferences.contains(KEY_LAST_EXIT_TIMESTAMP)
        val alreadySeen = preferences.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)

        // Commit synchrone, et avant tout examen : si ce nouveau processus
        // tombe lui aussi, le même ancien incident ne doit pas être reproposé
        // indéfiniment. C'est aussi ce qui empêche de resignaler un incident
        // dont le handler Kotlin a déjà écrit le rapport, plus riche.
        preferences.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exits.maxOf { it.timestamp }).commit()

        if (premierLancement || store.read() != null) return

        // Retenir le plus récent arrêt *signalable*, et non le plus récent
        // tout court : `SyncWorker` démarre le processus en arrière-plan, et
        // sa fin normale masquerait le crash qui la précède.
        val signalable = exits
            .filter { it.timestamp > alreadySeen && estSignalable(it) }
            .maxByOrNull { it.timestamp }
            ?: return

        val arrets = arretsConsecutifs + 1
        arretsConsecutifs = arrets
        preferences.edit().putInt(KEY_ARRETS_CONSECUTIFS, arrets).commit()

        store.replace(
            DiagnosticReportFormatter.systemExit(
                metadata = appMetadata,
                timestampMillis = signalable.timestamp,
                arretsConsecutifs = arrets,
                detail = detail(signalable),
            ),
        )
    }

    /**
     * Un défaut du programme se signale où qu'il survienne. Une mise à mort
     * par le système ne se signale que si l'application était visible : en
     * arrière-plan, certaines ROM — celle de l'appareil de test la première —
     * tuent les processus en permanence, et un garde-fou qu'on apprend à
     * ignorer est un garde-fou en trop.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun estSignalable(exit: ApplicationExitInfo): Boolean = when (exit.reason) {
        in MOTIFS_DEFAUT_PROGRAMME -> true
        in MOTIFS_SI_VISIBLE ->
            exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun detail(exit: ApplicationExitInfo) = DetailArretSysteme(
        reason = reasonName(exit.reason),
        importance = importanceName(exit.importance),
        pssKo = exit.pss,
        rssKo = exit.rss,
        status = exit.status,
        description = DiagnosticReportFormatter.classifierDescription(exit.description),
        filAriane = filAriane(exit),
        traceAnr = traceAnr(exit),
    )

    /** Relu avec la même prudence qu'un texte étranger, bien qu'il vienne de nous. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun filAriane(exit: ApplicationExitInfo): String =
        exit.processStateSummary
            ?.toString(Charsets.UTF_8)
            ?.filter { it.isLetterOrDigit() || it in CARACTERES_FIL_ARIANE }
            ?.take(MAX_FIL_ARIANE_CHARS)
            .orEmpty()

    /**
     * La trace n'est lue que pour un ANR.
     *
     * Le même appel sur un crash natif rendrait la tombstone : registres et
     * extraits de mémoire, donc possiblement des fragments de note. C'est la
     * seule ligne de ce fichier qui sépare une trace exploitable d'une fuite.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun traceAnr(exit: ApplicationExitInfo): String {
        if (exit.reason != ApplicationExitInfo.REASON_ANR) return ""

        return runCatching {
            exit.traceInputStream?.bufferedReader()?.use { lecteur ->
                DiagnosticReportFormatter.filtrerTraceAnr(lecteur.lineSequence())
            }
        }.getOrNull().orEmpty()
    }

    companion object {
        /** À appeler immédiatement après `Application.onCreate`. */
        fun install(context: Context): CrashReporter =
            CrashReporter(context.applicationContext).also {
                // Le handler est posé avant toute lecture Android susceptible
                // d'échouer pendant l'initialisation de l'application, et sur
                // ce fil-ci : il doit couvrir la construction du dépôt Go.
                it.installHandler()
                it.collectLastSystemExitEnFond()
            }

        private const val REPORT_PATH = "diagnostic/last-crash.txt"
        private const val STATE_PREFS = "ocnotes_diagnostic_state"
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
        private const val KEY_ARRETS_CONSECUTIFS = "consecutive_abnormal_exits"
        private const val EXIT_HISTORY_SIZE = 5
        private const val COLLECTOR_THREAD_NAME = "ocnotes-diagnostic"
        private const val MAX_ECRAN_CHARS = 32
        private const val MAX_FIL_ARIANE_CHARS = 96
        private const val CARACTERES_FIL_ARIANE = "_;="

        /** Des défauts du programme : signalés quel que soit le premier plan. */
        private val MOTIFS_DEFAUT_PROGRAMME = setOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )

        /**
         * Des mises à mort par le système. C'est là que tombe la mort de
         * processus muette d'une note trop lourde, que rien d'autre n'attrape.
         */
        private val MOTIFS_SI_VISIBLE = setOf(
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        )

        private fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH -> "java_or_kotlin_crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
            ApplicationExitInfo.REASON_SIGNALED -> "killed_by_signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
            else -> "unexpected_exit"
        }

        private fun importanceName(importance: Int): String = when {
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            else -> "background"
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

/** Ce qu'`ApplicationExitInfo` sait d'un arrêt, une fois expurgé. */
internal data class DetailArretSysteme(
    val reason: String,
    val importance: String,
    val pssKo: Long,
    val rssKo: Long,
    val status: Int,
    val description: String,
    val filAriane: String,
    val traceAnr: String,
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
        arretsConsecutifs: Int,
        mainThread: Boolean,
        throwable: Throwable,
    ): String = buildString {
        appendHeader(metadata, timestampMillis, "uncaught_exception", arretsConsecutifs)
        append("thread: ").append(if (mainThread) "main" else "background").append('\n')
        appendThrowable(throwable)
    }

    fun systemExit(
        metadata: DiagnosticMetadata,
        timestampMillis: Long,
        arretsConsecutifs: Int,
        detail: DetailArretSysteme,
    ): String = buildString {
        appendHeader(metadata, timestampMillis, "android_exit_history", arretsConsecutifs)
        append("reason: ").append(detail.reason).append('\n')
        append("importance: ").append(detail.importance).append('\n')
        append("memory_kb: pss=").append(detail.pssKo)
            .append(" rss=").append(detail.rssKo).append('\n')
        append("status: ").append(detail.status).append('\n')
        if (detail.description.isNotEmpty()) {
            append("description: ").append(detail.description).append('\n')
        }
        append("last_screen: ")
            .append(detail.filAriane.ifEmpty { "unknown" })
            .append('\n')
        if (detail.traceAnr.isEmpty()) {
            append("anr_trace: none\n")
        } else {
            append("anr_trace:\n").append(detail.traceAnr)
        }
    }

    /**
     * Ramène la description composée par le système à un vocabulaire fermé.
     *
     * C'est le seul champ du rapport dont le contenu ne soit pas décidé ici,
     * et rien ne garantit qu'Android n'y recopiera jamais un message
     * d'exception — lequel, venu du cœur Go, porterait une URL ou un chemin de
     * note. Un filtre de caractères ne suffirait pas : `cloud.example`
     * survivrait à la suppression des `:` et des `/`. On n'en garde donc que
     * la catégorie, qui distingue déjà un ANR de saisie d'un ANR de service.
     * Une formulation inconnue devient `other`, jamais son texte.
     */
    fun classifierDescription(brut: String?): String {
        val texte = brut?.lowercase().orEmpty().trim()
        if (texte.isEmpty()) return ""

        return VOCABULAIRE_DESCRIPTION.firstOrNull { (motif, _) -> motif in texte }
            ?.second
            ?: "other"
    }

    /**
     * Ne garde d'un vidage de fils que ses cadres de pile.
     *
     * Tout le reste est écarté : noms de fils, états, verrous, en-têtes,
     * cadres natifs. La structure suffit à lire un blocage — quel fil, quelle
     * pile — et aucun texte libre ne traverse la frontière. Les cadres Java
     * ne portent que des noms venus du programme.
     */
    fun filtrerTraceAnr(lignes: Sequence<String>): String = buildString {
        var fils = 0
        var cadres = 0
        var dansUnFil = false

        for (ligne in lignes) {
            val net = ligne.trim()
            when {
                // Un vidage de fils ouvre chaque bloc par le nom du fil entre
                // guillemets. On en garde la frontière, pas le nom.
                net.startsWith('"') -> {
                    if (fils >= MAX_FILS_ANR) break
                    fils++
                    dansUnFil = true
                    append("--- thread ").append(fils).append(" ---\n")
                }

                dansUnFil && net.startsWith("at ") && cadres < MAX_CADRES_ANR -> {
                    cadres++
                    append("  ").append(net).append('\n')
                }
            }
        }
    }

    private fun StringBuilder.appendHeader(
        metadata: DiagnosticMetadata,
        timestampMillis: Long,
        source: String,
        arretsConsecutifs: Int,
    ) {
        append("OCnotes diagnostic v2\n")
        append("source: ").append(source).append('\n')
        append("timestamp_utc: ").append(Instant.ofEpochMilli(timestampMillis)).append('\n')
        append("app: ").append(metadata.versionName).append(" (").append(metadata.versionCode).append(")\n")
        append("android: ").append(metadata.androidVersion).append(" (API ").append(metadata.androidApi).append(")\n")
        append("device: ").append(metadata.device).append('\n')
        append("abis: ").append(metadata.abis).append('\n')
        append("consecutive_abnormal_exits: ").append(arretsConsecutifs).append('\n')
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
    private const val MAX_FILS_ANR = 12
    private const val MAX_CADRES_ANR = 300

    /**
     * Formulations qu'Android emploie, et le mot qu'on en retient. L'ordre
     * compte : la première qui correspond gagne.
     */
    private val VOCABULAIRE_DESCRIPTION = listOf(
        "input dispatching timed out" to "input_dispatching_timeout",
        "broadcast of intent" to "broadcast_timeout",
        "executing service" to "service_timeout",
        "contentprovider not responding" to "content_provider_timeout",
        "startforegroundservice" to "foreground_service_timeout",
        "native crash" to "native_crash",
        "low memory" to "low_memory",
        "user request" to "user_request",
    )
}
