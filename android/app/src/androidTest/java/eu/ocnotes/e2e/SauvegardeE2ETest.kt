package eu.ocnotes.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sauvegarde Android d'un profil local, puis restauration sur des données
 * effacées.
 *
 * Le test est coupé en deux étapes, parce que la restauration tue le processus
 * de l'application (et donc l'instrumentation). Le script
 * scripts/e2e-emulateur.sh enchaîne :
 *   1. `-e etape preparer` : profil local + une note ;
 *   2. `bmgr backupnow`, `pm clear`, `bmgr restore` ;
 *   3. `-e etape verifier` : la note est revenue.
 * Sans l'argument `etape`, les deux méthodes sont ignorées : un lancement
 * complet des tests ne doit pas les faire échouer hors de ce scénario.
 */
@RunWith(AndroidJUnit4::class)
class SauvegardeE2ETest {

    private val parcours = Parcours()
    private val etape: String? = InstrumentationRegistry.getArguments().getString("etape")

    @Test
    fun preparer() {
        assumeTrue(etape == "preparer")
        parcours.lancer()
        parcours.allerALExplorateur(nomSiPremierLancement = PROFIL)
        val champ = parcours.creerNote(TITRE)
        parcours.allerALaFin(champ)
        parcours.taper(CONTENU)
        parcours.revenirALExplorateur()
        // L'explorateur liste bien la note avant la sauvegarde.
        parcours.attendre(By.text(TITRE), "note dans l'explorateur")
    }

    @Test
    fun verifier() {
        assumeTrue(etape == "verifier")
        parcours.lancer()
        // Données effacées puis restaurées : l'application doit rouvrir le
        // profil local restauré, pas l'écran de premier lancement.
        val relu = parcours.rouvrir(TITRE)
        assertTrue("contenu non restauré : « $relu »", relu.contains(CONTENU))
    }

    private companion object {
        const val PROFIL = "Profil sauvegarde"
        const val TITRE = "Note sauvegardee"
        const val CONTENU = "contenu a restaurer"
    }
}
