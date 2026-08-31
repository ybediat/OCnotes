package eu.opennote.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditeurTest {

    @Test
    fun documentVideResteEditable() {
        val tranches = decouperDocument("")
        val montage = monterFenetre("", 0)

        assertEquals(listOf(TrancheEditeur(0, 0)), tranches)
        assertEquals(0, montage.focus)
        assertEquals(0, montage.selectionDebut)
        assertEquals(0, montage.selectionFin)
    }

    @Test
    fun tranchesPaventLeDocumentExactement() {
        val document = buildString {
            repeat(80) { index ->
                append("paragraphe ").append(index).append(" avec un emoji 😀\n\n")
            }
        }
        val tranches = decouperDocument(document, maxRetours = 7, maxUtf16 = 80)

        assertPavage(document, tranches)
    }

    @Test
    fun chaqueTrancheRespecteLesDeuxBudgets() {
        val document = buildString {
            repeat(120) { append("ligne courte\n") }
            append("mot ".repeat(1_000))
        }
        val tranches = decouperDocument(document, maxRetours = 5, maxUtf16 = 70)

        tranches.forEach { tranche ->
            val texte = tranche.texteDe(document)
            assertTrue(texte.length <= 70)
            assertTrue(texte.count { it == '\n' } <= 5)
        }
        assertPavage(document, tranches)
    }

    @Test
    fun paragrapheSansRetourEstDecoupe() {
        val document = "mot ".repeat(2_000)
        val tranches = decouperDocument(document, maxUtf16 = 200)

        assertTrue(tranches.size > 1)
        assertTrue(tranches.all { it.fin - it.debut <= 200 })
        assertPavage(document, tranches)
    }

    @Test
    fun paireUtf16JamaisCoupee() {
        val document = "a".repeat(9) + "😀" + "b".repeat(20)
        val tranches = decouperDocument(document, maxUtf16 = 10)

        tranches.dropLast(1).forEach { tranche ->
            assertFalse(document[tranche.fin - 1].isHighSurrogate())
            assertFalse(document[tranche.fin].isLowSurrogate())
        }
        assertPavage(document, tranches)
    }

    @Test
    fun coupureNaturellePrefereePresDeLaBorne() {
        val document = "a".repeat(50) + " " + "b".repeat(100)
        val premiere = decouperDocument(document, maxUtf16 = 80).first()

        assertEquals(51, premiere.fin)
    }

    @Test
    fun materialisationRemplaceSeulementLaFenetre() {
        val document = "avant MILIEU après"
        val active = TrancheEditeur(6, 12)

        assertEquals("avant centre après", materialiserDocument(document, active, "centre"))
        assertEquals(document, materialiserDocument(document, null, "ignoré"))
    }

    @Test
    fun fenetreEstCentreeAvecDeLaMarge() {
        val document = "x".repeat(2_000)
        val montage = monterFenetre(document, 1_000)
        val active = montage.tranches[montage.focus]

        assertEquals(192, active.fin - active.debut)
        assertEquals(96, montage.selectionDebut)
        assertEquals(96, montage.selectionFin)
        assertPavage(document, montage.tranches)
    }

    @Test
    fun fenetreNaturelleNeCoupePasLesMots() {
        val document = "alpha anticonstitutionnel bravo charlie delta echo foxtrot ".repeat(30)
        val montage = monterFenetre(document, 215)
        val active = montage.tranches[montage.focus]

        assertTrue(active.debut == 0 || document[active.debut - 1].isWhitespace())
        assertTrue(active.fin == document.length || document[active.fin - 1].isWhitespace())
        assertPavage(document, montage.tranches)
    }

    @Test
    fun fenetreEnFinDeDocumentGardeUneMargeDeFrappe() {
        val document = "x".repeat(2_000)
        val montage = monterFenetre(document, document.length)
        val active = montage.tranches[montage.focus]

        assertEquals(document.length, active.fin)
        assertTrue(active.fin - active.debut < MAX_UTF16_EDITEUR)
        assertEquals(active.fin - active.debut, montage.selectionDebut)
    }

    @Test
    fun reequilibrageApresLongueInsertionNePerdRien() {
        val document = "avant\n" + "x".repeat(1_000) + "\naprès"
        val premierMontage = monterFenetre(document, 500)
        val active = premierMontage.tranches[premierMontage.focus]
        val insertion = "NOUVEAU ".repeat(100)
        val positionRelative = premierMontage.selectionFin
        val brouillon = active.texteDe(document).let {
            it.substring(0, positionRelative) + insertion + it.substring(positionRelative)
        }
        val documentModifie = materialiserDocument(document, active, brouillon)
        val curseurGlobal = active.debut + positionRelative + insertion.length
        val secondMontage = monterFenetre(documentModifie, curseurGlobal)

        assertTrue(doitReequilibrer(brouillon))
        assertEquals(
            insertion,
            documentModifie.substring(curseurGlobal - insertion.length, curseurGlobal),
        )
        assertPavage(documentModifie, secondMontage.tranches)
    }

    @Test
    fun seuilDeReequilibrageCombineLongueurEtRetours() {
        assertFalse(doitReequilibrer("x".repeat(MAX_UTF16_EDITEUR)))
        assertTrue(doitReequilibrer("x".repeat(MAX_UTF16_EDITEUR + 1)))
        assertFalse(doitReequilibrer("ligne\n".repeat(MAX_RETOURS_EDITEUR)))
        assertTrue(doitReequilibrer("ligne\n".repeat(MAX_RETOURS_EDITEUR + 1)))
    }

    @Test
    fun materialiserEtatUtiliseLeBrouillonActif() {
        val etat = EditorUiState(
            document = "avant MILIEU après",
            tranches = listOf(TrancheEditeur(6, 12)),
            focus = 0,
            valeur = TextFieldValue("centre", TextRange(6)),
        )

        assertEquals("avant centre après", materialiser(etat))
    }

    @Test
    fun sensDeSelectionEstConserve() {
        val document = "0123456789".repeat(100)
        val montage = monterFenetre(document, selectionDebut = 520, selectionFin = 500)

        assertTrue(montage.selectionDebut > montage.selectionFin)
        val active = montage.tranches[montage.focus]
        assertEquals(520, active.debut + montage.selectionDebut)
        assertEquals(500, active.debut + montage.selectionFin)
    }

    @Test
    fun changementDeFenetreCommitteEtAjusteLOffsetSuivant() {
        val document = "0123456789".repeat(200)
        val montage = monterFenetre(document, 300)
        val active = montage.tranches[montage.focus]
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(
                active.texteDe(document),
                TextRange(montage.selectionFin),
            ),
        )
        val insertion = "AJOUT"
        val texteActif = etat.valeur.text
        val curseur = etat.valeur.selection.end
        val modifie = modifierFenetre(
            etat,
            TextFieldValue(
                texteActif.substring(0, curseur) + insertion + texteActif.substring(curseur),
                TextRange(curseur + insertion.length),
            ),
        )
        val suivant = activerFenetre(modifie, document.length)
        val nouvelleActive = suivant.tranches[suivant.focus]

        assertEquals(document.length + insertion.length, suivant.document.length)
        assertEquals(document.length + insertion.length, nouvelleActive.debut + suivant.valeur.selection.end)
        assertTrue(suivant.document.contains(insertion))
        assertPavage(suivant.document, suivant.tranches)
    }

    @Test
    fun depassementRecentreAutomatiquementLeChamp() {
        val document = "x".repeat(2_000)
        val montage = monterFenetre(document, 1_000)
        val active = montage.tranches[montage.focus]
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(active.texteDe(document), TextRange(montage.selectionFin)),
        )
        val insertion = "y".repeat(500)
        val texte = etat.valeur.text
        val curseur = etat.valeur.selection.end
        val apres = modifierFenetre(
            etat,
            TextFieldValue(
                texte.substring(0, curseur) + insertion + texte.substring(curseur),
                TextRange(curseur + insertion.length),
            ),
        )

        assertEquals(1, apres.revision)
        assertEquals(0, apres.activation)
        assertTrue(apres.valeur.text.length <= 256)
        assertEquals(document.length + insertion.length, materialiser(apres).length)
        assertPavage(apres.document, apres.tranches)
    }

    @Test
    fun finDeCompositionDeclencheLeReequilibrageDiffere() {
        val document = "x".repeat(1_000)
        val montage = monterFenetre(document, 500)
        val active = montage.tranches[montage.focus]
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(active.texteDe(document), TextRange(montage.selectionFin)),
        )
        val tropLong = etat.valeur.text + "z".repeat(500)
        val enComposition = modifierFenetre(
            etat,
            TextFieldValue(
                text = tropLong,
                selection = TextRange(tropLong.length),
                composition = TextRange(tropLong.length - 1, tropLong.length),
            ),
        )
        val compositionTerminee = modifierFenetre(
            enComposition,
            enComposition.valeur.copy(composition = null),
        )

        assertTrue(enComposition.valeur.text.length > MAX_UTF16_EDITEUR)
        assertTrue(compositionTerminee.valeur.text.length <= 256)
        assertEquals(enComposition.revision, compositionTerminee.revision)
        assertEquals(0, compositionTerminee.activation)
        assertEquals(document.length + 500, materialiser(compositionTerminee).length)
    }

    @Test
    fun selectionAuBordDroitChargeLeContexteJusquAuBudget() {
        val document = "alpha bravo charlie delta echo foxtrot golf hotel ".repeat(80)
        val montage = monterFenetre(document, 1_000)
        val active = montage.tranches[montage.focus]
        val valeur = TextFieldValue(
            text = active.texteDe(document),
            selection = TextRange(40, active.fin - active.debut),
        )
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(active.texteDe(document), TextRange(40)),
            activation = 7,
        )
        val apres = modifierFenetre(etat, valeur)
        val nouvelleActive = apres.tranches[apres.focus]

        assertTrue(apres.valeur.text.length > valeur.text.length)
        assertTrue(apres.valeur.text.length <= MAX_UTF16_EDITEUR)
        assertEquals(active.debut + 40, nouvelleActive.debut + apres.valeur.selection.start)
        assertEquals(active.fin, nouvelleActive.debut + apres.valeur.selection.end)
        assertEquals(7, apres.activation)
        assertEquals(0, apres.revision)
        assertEquals(document, materialiser(apres))
        assertTrue(document[nouvelleActive.fin - 1].isWhitespace())
    }

    @Test
    fun selectionAuBordGaucheChargeLeContexteSansChangerLesOffsetsGlobaux() {
        val document = "alpha bravo charlie delta echo foxtrot golf hotel ".repeat(80)
        val montage = monterFenetre(document, 1_000)
        val active = montage.tranches[montage.focus]
        val valeur = TextFieldValue(
            text = active.texteDe(document),
            selection = TextRange(active.fin - active.debut - 30, 0),
        )
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(active.texteDe(document), TextRange(80)),
            activation = 3,
        )
        val apres = modifierFenetre(etat, valeur)
        val nouvelleActive = apres.tranches[apres.focus]

        assertTrue(nouvelleActive.debut < active.debut)
        assertTrue(apres.valeur.text.length <= MAX_UTF16_EDITEUR)
        assertEquals(active.fin - 30, nouvelleActive.debut + apres.valeur.selection.start)
        assertEquals(active.debut, nouvelleActive.debut + apres.valeur.selection.end)
        assertEquals(3, apres.activation)
        assertEquals(document, materialiser(apres))
        assertTrue(nouvelleActive.debut == 0 || document[nouvelleActive.debut - 1].isWhitespace())
    }

    @Test
    fun selectionInterieureNeChangePasLaFenetre() {
        val document = "mot ".repeat(1_000)
        val montage = monterFenetre(document, 1_000)
        val active = montage.tranches[montage.focus]
        val etat = EditorUiState(
            document = document,
            tranches = montage.tranches,
            focus = montage.focus,
            valeur = TextFieldValue(active.texteDe(document), TextRange(50)),
        )
        val apres = modifierFenetre(
            etat,
            etat.valeur.copy(selection = TextRange(50, 80)),
        )

        assertEquals(etat.tranches, apres.tranches)
        assertEquals(TextRange(50, 80), apres.valeur.selection)
    }

    /*
     * Les trois tests qui suivent portent la même règle : une fenêtre qui vient
     * d'être montée ne doit jamais demander son propre rééquilibrage. Sinon le
     * moindre déplacement du curseur la remonte, et l'historique d'annulation du
     * champ est vidé à chaque fois.
     */

    @Test
    fun fenetreMonteeRespecteLaLongueurDure() {
        // Des mots de cinquante caractères : toute borne tombe au milieu d'un
        // mot, donc l'alignement cherche un séparateur de part et d'autre.
        val document = ("m".repeat(49) + " ").repeat(200)
        val montage = monterFenetre(document, 1_000, 1_000 + MAX_UTF16_EDITEUR)
        val active = montage.tranches[montage.focus]

        assertTrue(
            "fenêtre de ${active.fin - active.debut} unités",
            active.fin - active.debut <= MAX_UTF16_EDITEUR,
        )
        assertPavage(document, montage.tranches)
    }

    @Test
    fun fenetreMonteeRespecteLeBudgetDeRetours() {
        val document = "l\n".repeat(400)
        // Une sélection de douze retours, soit tout ce qu'un champ peut porter.
        val debut = 100
        val montage = monterFenetre(document, debut, debut + 2 * MAX_RETOURS_EDITEUR)
        val active = montage.tranches[montage.focus]
        val retours = active.texteDe(document).count { it == '\n' }

        assertTrue("fenêtre de $retours retours", retours <= MAX_RETOURS_EDITEUR)
        assertPavage(document, montage.tranches)
    }

    @Test
    fun fenetreMonteeNeSeReequilibrePasAussitot() {
        val motsLongs = ("m".repeat(49) + " ").repeat(200)
        val lignesCourtes = "l\n".repeat(400)

        val cas = listOf(
            Triple(motsLongs, 1_000, 1_000 + MAX_UTF16_EDITEUR),
            Triple(lignesCourtes, 100, 100 + 2 * MAX_RETOURS_EDITEUR),
            Triple(motsLongs, 512, 512),
            Triple(lignesCourtes, 0, 0),
        )

        cas.forEach { (document, debut, fin) ->
            val montage = monterFenetre(document, debut, fin)
            val texte = montage.tranches[montage.focus].texteDe(document)
            assertFalse(
                "fenêtre de ${texte.length} unités, ${texte.count { it == '\n' }} retours",
                doitReequilibrer(texte),
            )
        }
    }

    @Test
    fun agrandissementNeDepassePasLesBudgets() {
        listOf(("m".repeat(49) + " ").repeat(200), "l\n".repeat(400)).forEach { document ->
            val montage = monterFenetre(document, 1_000)
            val active = montage.tranches[montage.focus]

            // Les deux poignées sur les deux bords : le cas qui charge le plus
            // de contexte d'un coup.
            val agrandi = etendreFenetrePourSelection(
                document = document,
                debutActif = active.debut,
                finActif = active.fin,
                selectionDebut = active.debut,
                selectionFin = active.fin,
            )

            val texte = if (agrandi == null) {
                active.texteDe(document)
            } else {
                agrandi.tranches[agrandi.focus].texteDe(document)
            }
            assertFalse(
                "fenêtre de ${texte.length} unités, ${texte.count { it == '\n' }} retours",
                doitReequilibrer(texte),
            )
        }
    }

    private fun assertPavage(document: String, tranches: List<TrancheEditeur>) {
        assertTrue(tranches.isNotEmpty())
        assertEquals(0, tranches.first().debut)
        assertEquals(document.length, tranches.last().fin)
        tranches.zipWithNext().forEach { (gauche, droite) ->
            assertEquals(gauche.fin, droite.debut)
        }
        assertEquals(document, tranches.joinToString("") { it.texteDe(document) })
    }
}
