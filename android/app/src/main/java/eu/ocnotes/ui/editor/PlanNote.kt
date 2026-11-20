package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto

/**
 * Un titre du plan de la note.
 *
 * [niveau] part de 0 pour le plus petit niveau présent dans la note : une note
 * qui commence ses titres à `##` ne s'affiche pas décalée d'un cran pour rien.
 * [source] est la position UTF-16, dans le texte rendu, du premier caractère
 * du titre ; `null` quand Go ne la connaît pas (document Office).
 */
data class EntreePlan(
    val indiceBloc: Int,
    val niveau: Int,
    val texte: String,
    val source: Int?,
)

/**
 * Les titres de [blocs], dans l'ordre.
 *
 * Aucune règle de Markdown ici : un titre est un bloc que Go a rendu comme tel,
 * jamais une ligne qui commence par `#` — un `#` dans un bloc de code n'en est
 * pas un. Le texte est celui que Go renvoie, déjà raccourci
 * (`ShortenLongWords`), ramené à une ligne : un titre Setext ou de document
 * peut porter un retour à la ligne. Un titre vide n'a rien à montrer et
 * n'entre pas dans le plan.
 *
 * Niveaux irréguliers — un `###` sans `##` au-dessus : chaque titre est
 * indenté d'après son propre niveau, sans parent inventé.
 */
fun planDepuisBlocs(blocs: List<NoteBlockDto>): List<EntreePlan> {
    val titres = blocs.withIndex().filter { (_, bloc) ->
        bloc.kind == BlockKind.TITRE && bloc.text.isNotBlank()
    }
    if (titres.isEmpty()) return emptyList()
    // Un niveau hors de 1..6 viendrait d'un document mal formé : borné, il
    // garde au moins un ordre lisible.
    val minimum = titres.minOf { it.value.level.coerceIn(1, 6) }
    return titres.map { (indice, bloc) ->
        EntreePlan(
            indiceBloc = indice,
            niveau = bloc.level.coerceIn(1, 6) - minimum,
            texte = bloc.text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" "),
            source = bloc.source,
        )
    }
}

/**
 * L'indice, dans [reperes], de la section où se trouve [repere] : la dernière
 * entrée située au plus à [repere]. `-1` quand on est avant le premier titre.
 *
 * [reperes] est croissant — indices d'élément de l'aperçu, ou positions dans
 * le texte en saisie ; une valeur nulle (position inconnue) est sautée.
 */
fun sectionCourante(reperes: List<Int?>, repere: Int): Int {
    var courante = -1
    for ((index, valeur) in reperes.withIndex()) {
        if (valeur == null) continue
        if (valeur > repere) break
        courante = index
    }
    return courante
}

/** Le plan montré dans sa feuille. */
data class PlanOuvert(
    val entrees: List<EntreePlan>,
    /** L'entrée de la section où l'on se trouve ; `-1` avant le premier titre. */
    val courante: Int,
    /** Vrai : le saut fait défiler l'aperçu ; faux : il pose le curseur du champ. */
    val enApercu: Boolean,
    /**
     * Révision du champ — ou de l'aperçu — dont le plan est le rendu. Un saut
     * n'a lieu que si elle est encore courante : sinon on refait le plan.
     */
    val revision: Long,
)
