package eu.ocnotes.sync

import eu.ocnotes.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Le mode inconnu du lancement ne coupe pas la synchro ; une déconnexion, si.
class FiltreModeSynchroTest {

    @Test
    fun leModeInconnuDuLancementNeCoupeRien() {
        val filtre = FiltreModeSynchro()
        assertNull(filtre.serveurActif(AppMode.UNSET))
        assertEquals(true, filtre.serveurActif(AppMode.SERVER))
    }

    @Test
    fun uneDeconnexionCoupeLaSynchro() {
        val filtre = FiltreModeSynchro()
        assertEquals(true, filtre.serveurActif(AppMode.SERVER))
        assertEquals(false, filtre.serveurActif(AppMode.UNSET))
    }

    @Test
    fun unProfilLocalNeSynchronisePas() {
        val filtre = FiltreModeSynchro()
        assertNull(filtre.serveurActif(AppMode.UNSET))
        assertEquals(false, filtre.serveurActif(AppMode.LOCAL))
    }
}
