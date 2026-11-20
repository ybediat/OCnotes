package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanNoteTest {

    private fun titre(texte: String, niveau: Int, source: Int? = null) =
        NoteBlockDto(kind = BlockKind.TITRE, text = texte, level = niveau, source = source)

    private fun paragraphe(texte: String = "texte") = NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = texte)

    private fun ligne(vararg cellules: String) =
        NoteBlockDto(kind = BlockKind.LIGNE_TABLEAU, cells = cellules.toList())

    // L'élément désigné par `indiceElement` doit être celui qui dessine le
    // bloc : on le vérifie contre `grouperPourApercu` lui-même, pour chaque
    // bloc, pas contre une valeur recopiée à la main.
    private fun verifierContreLeGroupement(blocs: List<NoteBlockDto>) {
        val elements = grouperPourApercu(blocs)
        for ((indice, bloc) in blocs.withIndex()) {
            val element = elements[indiceElement(blocs, indice)]
            val contient = when (element) {
                is ElementApercu.Bloc -> element.bloc === bloc
                is ElementApercu.Tableau -> element.lignes.any { it === bloc }
            }
            assertTrue("bloc $indice mal placé", contient)
        }
    }

    @Test
    fun sansTableauLIndiceEstLeMeme() {
        val blocs = listOf(titre("A", 1), paragraphe(), titre("B", 2))
        assertEquals(2, indiceElement(blocs, 2))
        verifierContreLeGroupement(blocs)
    }

    @Test
    fun tableauAvantLeTitre() {
        val blocs = listOf(
            paragraphe(), ligne("a", "b"), ligne("1", "2"), ligne("3", "4"), titre("Après", 1),
        )
        // Trois lignes, un seul élément : le titre est le troisième élément.
        assertEquals(2, indiceElement(blocs, 4))
        verifierContreLeGroupement(blocs)
    }

    @Test
    fun deuxTableaux() {
        val blocs = listOf(
            ligne("a"), ligne("1"), titre("Entre", 2), ligne("b"), ligne("2"), ligne("3"),
            paragraphe(), titre("Fin", 1),
        )
        assertEquals(1, indiceElement(blocs, 2))
        assertEquals(4, indiceElement(blocs, 7))
        verifierContreLeGroupement(blocs)
    }

    @Test
    fun tableauEnDernier() {
        val blocs = listOf(titre("Début", 1), paragraphe(), ligne("a"), ligne("1"))
        assertEquals(2, indiceElement(blocs, 3))
        verifierContreLeGroupement(blocs)
    }

    @Test
    fun indiceHorsBornesRameneAuxExtremites() {
        val blocs = listOf(titre("A", 1), ligne("a"), ligne("1"))
        assertEquals(0, indiceElement(blocs, -3))
        assertEquals(1, indiceElement(blocs, 99))
        assertEquals(0, indiceElement(emptyList(), 0))
    }

    @Test
    fun planNeRetientQueLesTitres() {
        val blocs = listOf(
            paragraphe("# pas un titre"),
            titre("Un", 1, source = 2),
            NoteBlockDto(kind = BlockKind.CODE, text = "# commentaire"),
            titre("Deux", 2, source = 20),
        )
        assertEquals(
            listOf(EntreePlan(1, 0, "Un", 2), EntreePlan(3, 1, "Deux", 20)),
            planDepuisBlocs(blocs),
        )
    }

    // Une note qui ne commence qu'au `##`, avec un `####` sans `###` au-dessus :
    // indentée par rapport au plus petit niveau, sans parent inventé.
    @Test
    fun niveauxIrreguliers() {
        val plan = planDepuisBlocs(listOf(titre("A", 2), titre("B", 4), titre("C", 3), titre("D", 2)))
        assertEquals(listOf(0, 2, 1, 0), plan.map { it.niveau })
    }

    @Test
    fun titreVideEcarteEtTexteSurUneLigne() {
        val plan = planDepuisBlocs(listOf(titre("  ", 1), titre("Sur\ndeux  lignes ", 2)))
        assertEquals(listOf(EntreePlan(1, 0, "Sur deux  lignes", null)), plan)
    }

    @Test
    fun noteSansTitre() {
        assertEquals(emptyList<EntreePlan>(), planDepuisBlocs(listOf(paragraphe())))
    }

    @Test
    fun sectionCouranteEstLaDerniereAtteinte() {
        val reperes = listOf(0, 10, 25)
        assertEquals(0, sectionCourante(reperes, 0))
        assertEquals(0, sectionCourante(reperes, 9))
        assertEquals(1, sectionCourante(reperes, 10))
        assertEquals(2, sectionCourante(reperes, 1000))
    }

    @Test
    fun avantLePremierTitre() {
        assertEquals(-1, sectionCourante(listOf(5, 10), 2))
        assertEquals(-1, sectionCourante(emptyList(), 2))
    }

    @Test
    fun positionInconnueSautee() {
        assertEquals(0, sectionCourante(listOf(3, null, 20), 15))
    }
}
