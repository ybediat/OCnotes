package eu.ocnotes.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import eu.ocnotes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saisie dans l'éditeur, de bout en bout : création d'un profil local, d'une
 * note, frappe au clavier, mise en forme par la barre, suite de liste, puis
 * retour à l'explorateur et réouverture. Ce qui est relu vient du stockage,
 * pas de l'écran : si l'enregistrement perd quelque chose, le test échoue.
 *
 * Prérequis : données de l'application effacées (le script
 * scripts/e2e-emulateur.sh fait `pm clear` avant de lancer cette classe).
 */
@RunWith(AndroidJUnit4::class)
class EditeurE2ETest {

    private val parcours = Parcours()

    /** Au premier test, crée le profil local ; aux suivants, il existe déjà. */
    @Before
    fun ouvrirLExplorateur() {
        parcours.lancer()
        parcours.allerALExplorateur(nomSiPremierLancement = "Profil E2E")
    }

    @Test
    fun saisieEtMiseEnFormeSurviventALaReouverture() {
        val titre = "Note E2E"
        val champ = parcours.creerNote(titre)

        parcours.allerALaFin(champ)
        parcours.taper("Bonjour ")

        // Gras sur un simple curseur : « **‸** », puis la frappe va dedans.
        parcours.attendre(By.desc(parcours.libelle(R.string.format_gras)), "bouton Gras").click()
        parcours.taper("monde")

        val attendu = "# $titre\n\nBonjour **monde**"
        // Avant de quitter : l'écran montre bien ce qu'on a tapé.
        assertEquals(attendu, Parcours.normaliser(parcours.champEditeur(titre).text ?: "").trimEnd())

        parcours.revenirALExplorateur()
        assertEquals(attendu, parcours.rouvrir(titre).trimEnd())
    }

    @Test
    fun entreePoursuitUneListe() {
        val titre = "Liste E2E"
        val champ = parcours.creerNote(titre)

        parcours.allerALaFin(champ)
        parcours.taper("- un")
        parcours.appareil.pressEnter()
        parcours.taper("deux")

        val attendu = "# $titre\n\n- un\n- deux"
        assertEquals(attendu, Parcours.normaliser(parcours.champEditeur(titre).text ?: "").trimEnd())

        parcours.revenirALExplorateur()
        assertEquals(attendu, parcours.rouvrir(titre).trimEnd())
    }

    @Test
    fun uneNoteQuitteeSansRetourEstQuandMemeEnregistree() {
        val titre = "Note sans retour"
        val champ = parcours.creerNote(titre)

        parcours.allerALaFin(champ)
        parcours.taper("ecrit puis abandonne")
        // Enregistrement après 700 ms de calme : on laisse passer ce délai,
        // puis on quitte l'application par l'accueil, sans « retour ».
        Thread.sleep(2_000)
        parcours.appareil.pressHome()

        parcours.lancer()
        parcours.allerALExplorateur(nomSiPremierLancement = "Profil E2E")
        val relu = parcours.rouvrir(titre)

        assertTrue("texte perdu : « $relu »", relu.contains("ecrit puis abandonne"))
    }
}
