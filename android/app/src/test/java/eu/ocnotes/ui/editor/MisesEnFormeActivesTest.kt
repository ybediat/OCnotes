package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Un bouton surligné doit être un bouton qui retire : ces cas reprennent ceux
 * de `TestMiseEnFormeEnLigne` et `TestPrefixesDeLigne` côté Go.
 *
 * Même notation que côté Go : ‸ pour le curseur, ‹…› pour une sélection.
 */
class MisesEnFormeActivesTest {

    private fun actives(avecMarques: String): Set<String> {
        val curseur = avecMarques.indexOf('‸')
        return if (curseur >= 0) {
            val texte = avecMarques.removeRange(curseur, curseur + 1)
            misesEnFormeActives(texte, curseur, curseur)
        } else {
            val debut = avecMarques.indexOf('‹')
            val fin = avecMarques.indexOf('›') - 1
            val texte = avecMarques.replace("‹", "").replace("›", "")
            misesEnFormeActives(texte, debut, fin)
        }
    }

    @Test
    fun grasAutourDuCurseur() {
        assertEquals(setOf("bold"), actives("Un **gr‸as** ici"))
        assertEquals(setOf("bold"), actives("Bonjour **‹monde›**"))
        assertEquals(setOf("bold"), actives("Bonjour ‹**monde**›"))
    }

    @Test
    fun apresLaFermetureCeNEstPlusDuGras() {
        assertEquals(emptySet<String>(), actives("Un **gras**‸ ici"))
        assertEquals(emptySet<String>(), actives("**a** et‸ **b**"))
    }

    @Test
    fun italiqueEtGrasNeSeConfondentPas() {
        assertEquals(setOf("bold"), actives("**gr‸as**"))
        assertEquals(setOf("italic"), actives("un *mo‸t*"))
    }

    @Test
    fun asterisquesIsolesNeFontPasDItalique() {
        assertEquals(emptySet<String>(), actives("2 * 3‸ * 4"))
    }

    @Test
    fun barreEtCode() {
        assertEquals(setOf("strikethrough"), actives("~~ba‸rré~~"))
        assertEquals(setOf("code"), actives("a `x‸y` b"))
    }

    @Test
    fun uneMiseEnFormeNeTraversePasLesLignes() {
        assertEquals(emptySet<String>(), actives("**a\nb‸**"))
    }

    @Test
    fun prefixesDeLigne() {
        assertEquals(setOf("h1"), actives("# Ti‸tre"))
        assertEquals(setOf("h2"), actives("## Ti‸tre"))
        assertEquals(setOf("bullet"), actives("- pom‸me"))
        assertEquals(setOf("task"), actives("- [ ] pain‸"))
        assertEquals(setOf("numbered"), actives("3. lait‸"))
        assertEquals(setOf("quote"), actives("> cit‸ation"))
        // « - [x] » n'est pas le préfixe posé par le bouton : il ne le retirerait pas.
        assertEquals(emptySet<String>(), actives("- [x] fait‸"))
    }

    @Test
    fun plusieursLignesToutesPrefixees() {
        assertEquals(setOf("bullet"), actives("- ‹a\n\n- b›"))
        assertEquals(emptySet<String>(), actives("- ‹a\nb›"))
        // La sélection qui s'arrête au début d'une ligne ne la prend pas.
        assertEquals(setOf("bullet"), actives("- ‹a\n›b"))
    }

    @Test
    fun blocDeCodeEntreDeuxDelimiteurs() {
        assertEquals(setOf("codeblock"), actives("```\nco‸de\n```"))
        assertEquals(emptySet<String>(), actives("```\na\nco‸de\n```"))
    }

    @Test
    fun documentVide() {
        assertEquals(emptySet<String>(), actives("‸"))
    }
}
