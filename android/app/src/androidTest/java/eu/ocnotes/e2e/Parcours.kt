package eu.ocnotes.e2e

import android.content.Intent
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import eu.ocnotes.R
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Gestes communs des tests de bout en bout : l'application est pilotée comme
 * par un utilisateur, à travers UI Automator (Compose et `EditText` natif
 * passent tous deux par l'accessibilité).
 *
 * Les libellés sont lus dans les ressources de l'application, jamais écrits
 * en dur : le test marche quelle que soit la langue de l'émulateur.
 */
internal class Parcours {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val appareil: UiDevice = UiDevice.getInstance(instrumentation)

    /** Contexte de l'application testée (eu.ocnotes.debug), pas du test. */
    private val contexte = instrumentation.targetContext
    val paquet: String = contexte.packageName

    /** Libellé traduit de l'application. */
    fun libelle(id: Int): String = contexte.getString(id)

    /** Ouvre l'application sur son écran de départ, tâche précédente fermée. */
    fun lancer() {
        val intention = contexte.packageManager.getLaunchIntentForPackage(paquet)
            ?: error("pas d'activité de lancement pour $paquet")
        intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        contexte.startActivity(intention)
        attendre(By.pkg(paquet).depth(0), "fenêtre de l'application")
    }

    /** Attend un élément et le renvoie, ou échoue avec [quoi] dans le message. */
    fun attendre(selecteur: BySelector, quoi: String, delaiMs: Long = DELAI_MS): UiObject2 =
        appareil.wait(Until.findObject(selecteur), delaiMs)
            ?: error("introuvable après ${delaiMs / 1000} s : $quoi\n" + ecranActuel())

    /**
     * Textes et descriptions visibles à l'écran, pour comprendre un échec
     * depuis le seul journal de la CI. Une capture est aussi déposée dans
     * /sdcard/Download, que le script récupère.
     */
    fun ecranActuel(): String {
        runCatching {
            appareil.takeScreenshot(java.io.File("/sdcard/Download/e2e-${System.currentTimeMillis()}.png"))
        }
        val elements = appareil.findObjects(By.pkg(java.util.regex.Pattern.compile(".*")))
        val lignes = elements.mapNotNull { e ->
            val texte = e.text?.takeIf { it.isNotBlank() }
            val desc = e.contentDescription?.takeIf { it.isNotBlank() }
            if (texte == null && desc == null) null
            else "  [${e.className?.substringAfterLast('.')}] ${texte ?: ""}${if (desc != null) " (desc: $desc)" else ""}"
        }.distinct()
        // Arbre complet, toutes fenêtres : ce que voit vraiment UI Automator.
        val arbre = runCatching {
            java.io.ByteArrayOutputStream().also { appareil.dumpWindowHierarchy(it) }.toString("UTF-8")
        }.getOrDefault("")
        val noeuds = Regex("""<node [^>]*>""").findAll(arbre).map { n ->
            fun attr(nom: String) = Regex("""$nom="([^"]*)"""").find(n.value)?.groupValues?.get(1) ?: ""
            "  ${attr("class").substringAfterLast('.')} text=${attr("text")} desc=${attr("content-desc")} " +
                "clic=${attr("clickable")} ${attr("bounds")}"
        }.filter { !it.contains("text= desc= clic=false") }.joinToString("\n")
        return "Écran (${appareil.currentPackageName}) :\n" + lignes.joinToString("\n") +
            "\nArbre :\n" + noeuds
    }

    /** Vrai si l'élément apparaît avant [delaiMs]. */
    fun apparait(selecteur: BySelector, delaiMs: Long): Boolean =
        appareil.wait(Until.hasObject(selecteur), delaiMs) == true

    fun cliquerTexte(texte: String) = attendre(By.text(texte), "« $texte »").click()

    /**
     * Premier lancement : « Continuer en local », puis nom du profil dans la
     * boîte de dialogue. Revient quand l'explorateur de notes est affiché.
     */
    fun creerProfilLocal(nom: String) {
        cliquerTexte(libelle(R.string.login_continuer_local))
        val creer = attendre(By.text(libelle(R.string.action_creer)), "dialogue de nom du profil")
        premierChampAutourDe(creer, "champ du nom du profil").text = nom
        creer.click()
        attendre(By.text(libelle(R.string.browser_nouvelle_note)), "explorateur de notes")
    }

    /**
     * Attend l'écran de départ, qui est lent au premier démarrage à froid sur
     * émulateur : écran de connexion (on crée alors le profil local
     * [nomSiPremierLancement]) ou explorateur (profil déjà là).
     */
    fun allerALExplorateur(nomSiPremierLancement: String) {
        val local = By.text(libelle(R.string.login_continuer_local))
        val explorateur = By.text(libelle(R.string.browser_nouvelle_note))
        val fin = System.currentTimeMillis() + DELAI_MS
        while (System.currentTimeMillis() < fin) {
            if (appareil.hasObject(explorateur)) return
            if (appareil.hasObject(local)) {
                creerProfilLocal(nomSiPremierLancement)
                return
            }
            // Sur un petit écran, « Continuer en local » est sous le
            // formulaire de connexion : on fait défiler la page.
            appareil.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
            Thread.sleep(250)
        }
        error("ni écran de connexion ni explorateur\n" + ecranActuel())
    }

    /**
     * Crée une note depuis l'explorateur et renvoie le champ de l'éditeur,
     * qui s'ouvre sur « # titre ».
     */
    fun creerNote(titre: String): UiObject2 {
        cliquerTexte(libelle(R.string.browser_nouvelle_note))
        val creer = attendre(By.text(libelle(R.string.action_creer)), "dialogue de nouvelle note")
        // Le premier champ de la boîte est le titre.
        premierChampAutourDe(creer, "champ du titre").text = titre
        creer.click()
        return champEditeur(titre)
    }

    /**
     * Premier champ de saisie de la boîte de dialogue qui contient [bouton].
     *
     * On remonte depuis le bouton : un `By.clazz(EditText)` global trouverait
     * aussi les champs de l'écran derrière la boîte (connexion, recherche).
     */
    private fun premierChampAutourDe(bouton: UiObject2, quoi: String): UiObject2 {
        var parent: UiObject2? = bouton.parent
        while (parent != null) {
            parent.findObject(By.clazz(EDIT_TEXT))?.let { return it }
            parent = parent.parent
        }
        error("introuvable : $quoi")
    }

    /** Le champ de l'éditeur ouvert sur la note de titre [titre]. */
    fun champEditeur(titre: String): UiObject2 = attendre(
        By.clazz(EDIT_TEXT).text(Pattern.compile("# " + Pattern.quote(titre) + ".*", Pattern.DOTALL)),
        "éditeur de « $titre »",
    )

    /** Place le curseur à la toute fin du texte (Ctrl+Fin), comme au clavier. */
    fun allerALaFin(champ: UiObject2) {
        champ.click()
        appareil.waitForIdle()
        appareil.pressKeyCode(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)
    }

    /**
     * Tape [texte] touche par touche, comme un clavier physique. ASCII
     * seulement : la table de touches virtuelle n'a pas les accents.
     */
    fun taper(texte: String) {
        instrumentation.sendStringSync(texte)
        appareil.waitForIdle()
    }

    /**
     * Quitte l'éditeur par « retour » jusqu'à l'explorateur. Le premier appui
     * peut seulement fermer le clavier ; le retour enregistre la note.
     */
    fun revenirALExplorateur() {
        val explorateur = By.text(libelle(R.string.browser_nouvelle_note))
        repeat(3) {
            if (apparait(explorateur, 1_000)) return
            appareil.pressBack()
        }
        attendre(explorateur, "explorateur après retour")
    }

    /** Rouvre une note de l'explorateur et renvoie le texte de son éditeur. */
    fun rouvrir(titre: String): String {
        cliquerTexte(titre)
        return normaliser(champEditeur(titre).text ?: "")
    }

    companion object {
        const val EDIT_TEXT = "android.widget.EditText"
        const val DELAI_MS = 20_000L

        /** NFC et fins de ligne unifiées, pour comparer sans surprise. */
        fun normaliser(texte: String): String =
            Normalizer.normalize(texte, Normalizer.Form.NFC).replace("\r\n", "\n").replace(' ', ' ')
    }
}
