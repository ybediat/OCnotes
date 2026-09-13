package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MoteurEditionTest {

    @Test
    fun valeurAbsenteOuInconnueRetombeSurLeMoteurParDefaut() {
        assertEquals(MoteurEdition.NATIF, MoteurEdition.DEFAUT)
        assertEquals(MoteurEdition.NATIF, MoteurEdition.depuis(null))
        assertEquals(MoteurEdition.NATIF, MoteurEdition.depuis("futur-moteur"))
    }

    @Test
    fun valeursPersistantesSontStablesEtRelues() {
        MoteurEdition.entries.forEach { moteur ->
            assertEquals(moteur, MoteurEdition.depuis(moteur.valeurPersistante))
        }
    }

    @Test
    fun choixPersisteApresRecreationDesPreferences() {
        val stockage = StockageMemoire()
        val premieresPreferences = PreferencesAffichage(stockage)
        assertEquals(MoteurEdition.NATIF, premieresPreferences.moteurEdition.value)

        premieresPreferences.definirMoteurEdition(MoteurEdition.VIRTUALISE)

        val preferencesRecreees = PreferencesAffichage(stockage)
        assertEquals(MoteurEdition.VIRTUALISE, preferencesRecreees.moteurEdition.value)
    }

    @Test
    fun garderEcranAllumePersisteApresRecreation() {
        val stockage = StockageMemoire()
        val p1 = PreferencesAffichage(stockage)
        assertEquals(false, p1.garderEcranAllumeLecture.value)
        assertEquals(false, p1.garderEcranAllumeEdition.value)

        p1.definirGarderEcranAllumeLecture(true)
        p1.definirGarderEcranAllumeEdition(true)

        val p2 = PreferencesAffichage(stockage)
        assertEquals(true, p2.garderEcranAllumeLecture.value)
        assertEquals(true, p2.garderEcranAllumeEdition.value)
    }
}

private class StockageMemoire : StockagePreferencesAffichage {
    private val valeurs = mutableMapOf<String, Any>()

    override fun lireChaine(cle: String): String? = valeurs[cle] as? String

    override fun lireLong(cle: String, defaut: Long): Long = valeurs[cle] as? Long ?: defaut

    override fun lireBooleen(cle: String, defaut: Boolean): Boolean = valeurs[cle] as? Boolean ?: defaut

    override fun ecrireChaine(cle: String, valeur: String) {
        valeurs[cle] = valeur
    }

    override fun ecrireLong(cle: String, valeur: Long) {
        valeurs[cle] = valeur
    }

    override fun ecrireBooleen(cle: String, valeur: Boolean) {
        valeurs[cle] = valeur
    }
}
