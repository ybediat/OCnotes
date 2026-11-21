package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.ui.browser.replie
import eu.ocnotes.ui.common.replieAvecPositions
import eu.ocnotes.ui.common.trouverOccurrences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recherche dans la note ouverte : le repli qui garde les positions, tenu à la
 * même table de cas que le repli des noms et que Go, et le parcours des
 * occurrences en saisie comme en aperçu.
 */
class RechercheNoteTest {

    /** `internal/search/testdata/cas-repli.txt`, cherché depuis android/app ou plus haut. */
    private fun fichierCas(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            val candidat = File(dossier, "internal/search/testdata/cas-repli.txt")
            if (candidat.isFile) return candidat
            dossier = dossier.parentFile
        }
        error("cas-repli.txt introuvable")
    }

    private fun deChappe(s: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""")
            .replace(s.replace("\\t", "\t")) { it.groupValues[1].toInt(16).toChar().toString() }

    /**
     * Le repli caractère par caractère rend exactement le repli de la chaîne
     * entière, sur chaque cas de la table commune : sans quoi la recherche dans
     * la note et celle des noms ne trouveraient pas la même chose.
     */
    @Test
    fun repliAvecPositionsCommunAvecLaTable() {
        var cas = 0
        fichierCas().readLines().forEachIndexed { i, ligne ->
            if (ligne.isBlank() || ligne.startsWith("#")) return@forEachIndexed
            val champs = ligne.split('¦')
            val entree = deChappe(champs[0].trim(' '))
            val attendu = deChappe(champs[1].trim(' '))
            assertEquals("ligne ${i + 1} : $entree", attendu, replieAvecPositions(entree).texte)
            assertEquals("ligne ${i + 1} : $entree", replie(entree), replieAvecPositions(entree).texte)
            cas++
        }
        assertTrue("aucun cas lu", cas > 0)
    }

    /** Chaque cas de la table, cherché par sa forme repliée, se retrouve en entier. */
    @Test
    fun chaqueCasSeRetrouveParSonRepli() {
        fichierCas().readLines().forEach { ligne ->
            if (ligne.isBlank() || ligne.startsWith("#")) return@forEach
            val champs = ligne.split('¦')
            val entree = deChappe(champs[0].trim(' '))
            val attendu = deChappe(champs[1].trim(' '))
            assertEquals(entree, listOf(0 until entree.length), trouverOccurrences(entree, attendu))
        }
    }

    @Test
    fun casseEtAccentsIgnores() {
        val texte = "Résumé : le RESUME du résumé."
        val trouve = trouverOccurrences(texte, "resume").map { texte.substring(it) }
        assertEquals(listOf("Résumé", "RESUME", "résumé"), trouve)
    }

    /** « é » précomposé ou décomposé, dans le texte comme dans le terme : mêmes occurrences. */
    @Test
    fun accentPrecomposeOuDecompose() {
        val compose = "café"
        val decompose = "café"
        // La plage couvre l'accent décomposé : aucune borne au milieu d'une lettre.
        assertEquals(listOf(0 until 4), trouverOccurrences(compose, "café"))
        assertEquals(listOf(0 until 5), trouverOccurrences(decompose, "café"))
        assertEquals(listOf(0 until 4), trouverOccurrences(compose, decompose))
        assertEquals(listOf(0 until 5), trouverOccurrences(decompose, decompose))
        assertEquals(listOf(3 until 5), trouverOccurrences(decompose, "e"))
    }

    /** Un emoji compte deux unités UTF-16 : la plage d'après en tient compte. */
    @Test
    fun emojiAvantLOccurrence() {
        val texte = "😀 lorem 😀😀 Lorem"
        val plages = trouverOccurrences(texte, "lorem")
        assertEquals(listOf(3 until 8, 14 until 19), plages)
        plages.forEach { assertEquals("lorem", texte.substring(it).lowercase()) }
    }

    @Test
    fun esszettEtLigatures() {
        val texte = "Straße, strasse"
        assertEquals(listOf("ß", "ss"), trouverOccurrences(texte, "ss").map { texte.substring(it) })
        assertEquals(listOf("Straße", "strasse"), trouverOccurrences(texte, "straße").map { texte.substring(it) })
        assertEquals(listOf("cœur"), trouverOccurrences("un cœur", "coeur").map { "un cœur".substring(it) })
    }

    @Test
    fun espacesInsecables() {
        val texte = "10 € et 10 €"
        assertEquals(2, trouverOccurrences(texte, "10 €").size)
        assertEquals(2, trouverOccurrences(texte, "10 €").size)
    }

    @Test
    fun termeVideOuPlusLongQueLeTexte() {
        assertTrue(trouverOccurrences("lorem", "").isEmpty())
        // Une marque seule ne garde rien une fois repliée.
        assertTrue(trouverOccurrences("lorem", "́").isEmpty())
        assertTrue(trouverOccurrences("lor", "lorem").isEmpty())
        assertTrue(trouverOccurrences("", "lorem").isEmpty())
    }

    @Test
    fun occurrencesSansChevauchement() {
        assertEquals(listOf(0 until 2, 2 until 4), trouverOccurrences("aaaa", "aa"))
        assertEquals(listOf(0 until 2), trouverOccurrences("aaa", "aa"))
    }

    @Test
    fun limiteRespectee() {
        assertEquals(5000, trouverOccurrences("e".repeat(20_000), "e").size)
        assertEquals(3, trouverOccurrences("e".repeat(20_000), "e", limite = 3).size)
    }

    /** 300 ko de texte : la durée est affichée, sans seuil qui rendrait le test fragile. */
    @Test
    fun troisCentsKilooctets() {
        val ligne = "Lorem ipsum dolor sit amet, consectetur adipiscing élit, sed do eiusmod tempor.\n"
        val texte = ligne.repeat(300_000 / ligne.length)
        trouverOccurrences(texte, "lorem") // chauffe
        val debut = System.nanoTime()
        val lorem = trouverOccurrences(texte, "lorem")
        val e = trouverOccurrences(texte, "e")
        val ms = (System.nanoTime() - debut) / 1_000_000
        println("recherche sur ${texte.length} caractères : $ms ms, ${lorem.size} « lorem », ${e.size} « e »")
        assertEquals(texte.length / ligne.length, lorem.size)
        assertEquals(5000, e.size)
    }

    private fun paragraphe(texte: String, cont: Boolean = false) =
        NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = texte, cont = cont)

    private fun ligne(vararg cellules: String) =
        NoteBlockDto(kind = BlockKind.LIGNE_TABLEAU, cells = cellules.toList())

    /** Les indices sont ceux des éléments : un tableau de deux lignes n'en fait qu'un. */
    @Test
    fun occurrencesApercuParElement() {
        val blocs = listOf(
            paragraphe("lorem un"),
            ligne("Lorem", "b"),
            ligne("c", "lorem lorem"),
            paragraphe("après lorem"),
            NoteBlockDto(kind = BlockKind.CODE, text = "val lorem = 1"),
            NoteBlockDto(kind = BlockKind.IMAGE, text = "Lorem en image"),
        )
        val occ = occurrencesApercu(blocs, "lorem", 100)
        assertEquals(
            listOf(
                OccurrenceApercu(0, 0 until 5),
                OccurrenceApercu(1, 0 until 5, ligne = 0, colonne = 0),
                OccurrenceApercu(1, 0 until 5, ligne = 1, colonne = 1),
                OccurrenceApercu(1, 6 until 11, ligne = 1, colonne = 1),
                OccurrenceApercu(2, 6 until 11),
                OccurrenceApercu(3, 4 until 9),
                OccurrenceApercu(4, 0 until 5),
            ),
            occ,
        )
        assertEquals(3, occurrencesApercu(blocs, "lorem", 3).size)
    }

    /** Un morceau suivi d'une suite perd son retour final à l'affichage : la plage reste dans son texte. */
    @Test
    fun occurrenceApercuDansUnMorceau() {
        val blocs = listOf(paragraphe("lorem\n"), paragraphe("lorem", cont = true))
        val occ = occurrencesApercu(blocs, "lorem", 100)
        assertEquals(listOf(OccurrenceApercu(0, 0 until 5), OccurrenceApercu(1, 0 until 5)), occ)
    }

    @Test
    fun premiereApresLaPositionVisible() {
        val plages = listOf(2 until 4, 10 until 12, 20 until 22)
        assertEquals(0, premiereApres(plages, 0) { it.first.toLong() })
        assertEquals(1, premiereApres(plages, 5) { it.first.toLong() })
        assertEquals(1, premiereApres(plages, 10) { it.first.toLong() })
        // Rien après : on repart du début, comme « suivant » après la dernière.
        assertEquals(0, premiereApres(plages, 21) { it.first.toLong() })
        assertEquals(-1, premiereApres(emptyList<IntRange>(), 0) { it.first.toLong() })
    }

    /** En aperçu, l'occurrence courante se retrouve au caractère près, pas seulement à son élément. */
    @Test
    fun premiereApresDansLeMemeElement() {
        val occ = listOf(
            OccurrenceApercu(3, 0 until 4),
            OccurrenceApercu(98, 10 until 14),
            OccurrenceApercu(98, 50 until 54),
            OccurrenceApercu(99, 0 until 4),
        )
        val repere = { o: OccurrenceApercu -> repereApercu(o.element, o.plage.first) }
        assertEquals(2, premiereApres(occ, repereApercu(98, 50), repere))
        assertEquals(1, premiereApres(occ, repereApercu(98), repere))
        assertEquals(1, premiereApres(occ, repereApercu(4), repere))
    }

    @Test
    fun suivantEtPrecedentBouclent() {
        assertEquals(1, indiceVoisin(0, 3, 1))
        assertEquals(0, indiceVoisin(2, 3, 1))
        assertEquals(2, indiceVoisin(0, 3, -1))
        assertEquals(-1, indiceVoisin(0, 0, 1))
    }

    @Test
    fun marquesDUnTexte() {
        val occ = listOf(
            OccurrenceApercu(1, 0 until 5, ligne = 0, colonne = 0),
            OccurrenceApercu(1, 2 until 4, ligne = 1, colonne = 1),
            OccurrenceApercu(2, 6 until 11),
        )
        val surlignage = SurlignageApercu(occ.groupBy { it.element }, courante = occ[1])
        assertEquals(MarquesTexte(listOf(0 until 5), null), surlignage.marques(1, 0, 0))
        assertEquals(MarquesTexte(listOf(2 until 4), 2 until 4), surlignage.marques(1, 1, 1))
        assertEquals(MarquesTexte(listOf(6 until 11), null), surlignage.marques(2))
        assertEquals(null, surlignage.marques(0))
        assertEquals(null, surlignage.marques(1))
    }
}
