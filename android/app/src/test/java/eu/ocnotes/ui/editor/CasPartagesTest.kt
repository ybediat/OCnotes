package eu.ocnotes.ui.editor

import java.io.File
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rejoue `internal/markdown/testdata/cas-mise-en-forme.txt`, le fichier de cas
 * commun avec Go : pour chaque texte de départ, les boutons surlignés doivent
 * être exactement ceux qu'indique le fichier, c'est-à-dire ceux qu'un appui
 * retire côté Go (`TestCasPartages` vérifie ce côté-là).
 */
class CasPartagesTest {

    /** Le fichier, cherché depuis le dossier de travail de Gradle (android/app) ou plus haut. */
    private fun fichier(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            val candidat = File(dossier, "internal/markdown/testdata/cas-mise-en-forme.txt")
            if (candidat.isFile) return candidat
            dossier = dossier.parentFile
        }
        error("cas-mise-en-forme.txt introuvable")
    }

    /** Texte, début et fin de sélection, avec la notation ‸ et ‹…› du fichier. */
    private fun lire(avecMarques: String): Triple<String, Int, Int> {
        val curseur = avecMarques.indexOf('‸')
        if (curseur >= 0) return Triple(avecMarques.removeRange(curseur, curseur + 1), curseur, curseur)
        val debut = avecMarques.indexOf('‹')
        val fin = avecMarques.indexOf('›') - 1
        return Triple(avecMarques.replace("‹", "").replace("›", ""), debut, fin)
    }

    @Test
    fun lesBoutonsSurlignesSontCeuxQuiRetirent() {
        var cas = 0
        fichier().readLines().forEachIndexed { i, brute ->
            // Normalisation NFC : un éditeur de texte peut avoir décomposé les accents.
            val ligne = Normalizer.normalize(brute, Normalizer.Form.NFC)
            if (ligne.isBlank() || ligne.startsWith("#")) return@forEachIndexed
            val champs = ligne.split('¦').map { it.trim(' ').replace("\\n", "\n") }
            assertEquals("ligne ${i + 1} : 4 champs attendus", 4, champs.size)
            val attendus = champs[3].lowercase().let { if (it == "-") emptySet() else it.split(',').map(String::trim).toSet() }
            val (texte, debut, fin) = lire(champs[1])
            assertEquals("ligne ${i + 1} : ${champs[1]}", attendus, misesEnFormeActives(texte, debut, fin))
            cas++
        }
        assertTrue("aucun cas lu", cas > 0)
    }
}
