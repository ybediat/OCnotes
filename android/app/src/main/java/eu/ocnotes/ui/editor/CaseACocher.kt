package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto

/**
 * Remplacement qui coche ou décoche la case dont le « [ » est à [position].
 *
 * Renvoie `null` si le texte ne porte pas exactement `[`, puis ` `, `x` ou `X`,
 * puis `]` à cet endroit : la position vient d'un rendu, et si le texte a bougé
 * depuis, on n'écrit rien plutôt que d'écrire à côté.
 *
 * Un caractère pour un caractère, toujours : la longueur du texte ne change
 * pas, donc les positions des autres blocs de l'aperçu restent justes sans
 * nouveau rendu. Un `X` majuscule se décoche en ` ` mais ne se normalise
 * jamais en `x`.
 */
internal fun basculerCase(texte: CharSequence, position: Int): RemplacementNatif? {
    // Il faut les trois caractères « [ », l'état, « ] » dans le texte.
    if (position < 0 || position + 2 >= texte.length) return null
    if (texte[position] != '[' || texte[position + 2] != ']') return null
    val nouvelEtat = when (texte[position + 1]) {
        ' ' -> "x"
        'x', 'X' -> " "
        else -> return null
    }
    return RemplacementNatif(position + 1, position + 2, nouvelEtat)
}

/**
 * Les tâches de l'aperçu qui portent une case cliquable, une par tâche : le
 * premier morceau seulement d'une tâche coupée par une image (ses morceaux
 * partagent la même position source).
 */
internal fun tachesCliquables(blocs: List<NoteBlockDto>): List<NoteBlockDto> =
    blocs.filter { it.kind == BlockKind.TACHE && it.source != null }.distinctBy { it.source }

/**
 * Retrouve, dans un aperçu refait, la tâche touchée dans l'aperçu périmé
 * ([avant]) : même rang parmi les tâches, même texte, même état. `null` si
 * rien ne correspond sûrement — on préfère alors ne pas cocher que cocher la
 * mauvaise case.
 *
 * Le même état est exigé : si la case a déjà l'état voulu dans le nouveau
 * rendu, rejouer le toucher la remettrait comme avant.
 */
internal fun retrouverTache(
    avant: List<NoteBlockDto>,
    touchee: NoteBlockDto,
    apres: List<NoteBlockDto>,
): NoteBlockDto? {
    val rang = tachesCliquables(avant).indexOfFirst { it.source == touchee.source }
    if (rang < 0) return null
    return tachesCliquables(apres).getOrNull(rang)
        ?.takeIf { it.text == touchee.text && it.checked == touchee.checked }
}
