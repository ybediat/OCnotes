package eu.opennote.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MoteurEditionTest {

    @Test
    fun valeurAbsenteOuInconnueRetombeSurLeMoteurVirtualise() {
        assertEquals(MoteurEdition.VIRTUALISE, MoteurEdition.depuis(null))
        assertEquals(MoteurEdition.VIRTUALISE, MoteurEdition.depuis("futur-moteur"))
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
        assertEquals(MoteurEdition.VIRTUALISE, premieresPreferences.moteurEdition.value)

        premieresPreferences.definirMoteurEdition(MoteurEdition.NATIF)

        val preferencesRecreees = PreferencesAffichage(stockage)
        assertEquals(MoteurEdition.NATIF, preferencesRecreees.moteurEdition.value)
    }
}

private class StockageMemoire : StockagePreferencesAffichage {
    private val valeurs = mutableMapOf<String, Any>()

    override fun lireChaine(cle: String): String? = valeurs[cle] as? String

    override fun lireLong(cle: String, defaut: Long): Long = valeurs[cle] as? Long ?: defaut

    override fun ecrireChaine(cle: String, valeur: String) {
        valeurs[cle] = valeur
    }

    override fun ecrireLong(cle: String, valeur: Long) {
        valeurs[cle] = valeur
    }
}
