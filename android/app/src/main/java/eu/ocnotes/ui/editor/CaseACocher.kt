package eu.ocnotes.ui.editor

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
