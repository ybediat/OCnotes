package eu.ocnotes.ui.editor

/**
 * Ce que la touche Entrée doit faire de plus qu'un saut de ligne, une fois ce
 * saut inséré en [position] : poursuivre la liste, la terminer, ou rien
 * (`null`). Le résultat s'applique par un unique `Editable.replace`.
 *
 *  - Sur un élément qui a du contenu, la nouvelle ligne reçoit le même retrait
 *    et le préfixe suivant : `3. ` donne `4. `, une puce reste la même puce,
 *    une case cochée ou non donne une case vide, une citation reste une
 *    citation.
 *  - Sur un élément vide — le préfixe seul —, Entrée termine la liste : le
 *    préfixe et le saut de ligne qu'on vient de taper disparaissent, et la
 *    ligne redevient vide.
 *
 * Seulement en fin de ligne : un saut de ligne tapé au milieu d'un élément le
 * coupe sans rien ajouter. Jamais dans un bloc de code clôturé, où un `- `
 * n'est que du texte.
 *
 * Les préfixes reconnus sont ceux de `linePrefixRe`, côté Go
 * (`internal/markdown/format.go`) : un préfixe que la barre de mise en forme
 * sait écrire, Entrée sait le poursuivre.
 */
internal fun suiteDeListe(texte: CharSequence, position: Int): RemplacementNatif? {
    if (position !in 0 until texte.length || texte[position] != '\n') return null
    val apres = position + 1
    if (apres < texte.length && texte[apres] != '\n') return null

    var debutLigne = position
    while (debutLigne > 0 && texte[debutLigne - 1] != '\n') debutLigne--

    val prefixe = prefixeDeListe(texte, debutLigne, position) ?: return null
    // Le balayage du bloc de code parcourt la note depuis le début : on ne le
    // paie que sur une ligne qui porte vraiment un préfixe.
    if (dansBlocDeCode(texte, debutLigne)) return null

    val vide = (prefixe.fin until position).all { texte[it] == ' ' || texte[it] == '\t' }
    return if (vide) {
        RemplacementNatif(debutLigne, apres, "")
    } else {
        RemplacementNatif(apres, apres, prefixe.suivant)
    }
}

/** Préfixe de liste lu sur une ligne : où il finit, et celui de l'élément suivant. */
internal data class PrefixeListe(val fin: Int, val suivant: String)

/**
 * Lit le préfixe de liste de la ligne `[debut, fin)` : retrait, puis citation,
 * case à cocher, puce ou numéro, toujours suivi d'une espace. `null` si la
 * ligne n'en porte pas — un titre n'en est pas un.
 */
internal fun prefixeDeListe(texte: CharSequence, debut: Int, fin: Int): PrefixeListe? {
    var i = debut
    while (i < fin && (texte[i] == ' ' || texte[i] == '\t')) i++
    val retrait = texte.subSequence(debut, i).toString()
    if (i >= fin) return null

    val c = texte[i]
    return when {
        c == '>' -> if (espaceEn(texte, i + 1, fin)) PrefixeListe(i + 2, "$retrait> ") else null

        c == '-' || c == '*' || c == '+' -> {
            if (!espaceEn(texte, i + 1, fin)) return null
            val case = i + 2
            if (case + 3 < fin &&
                texte[case] == '[' &&
                texte[case + 1] in " xX" &&
                texte[case + 2] == ']' &&
                espaceEn(texte, case + 3, fin)
            ) {
                // Une case cochée ne se poursuit pas cochée : l'élément
                // suivant reste à faire.
                PrefixeListe(case + 4, "$retrait$c [ ] ")
            } else {
                PrefixeListe(i + 2, "$retrait$c ")
            }
        }

        c in '0'..'9' -> {
            var f = i
            while (f < fin && f - i < MAX_CHIFFRES_NUMERO && texte[f] in '0'..'9') f++
            if (f >= fin || (texte[f] != '.' && texte[f] != ')')) return null
            if (!espaceEn(texte, f + 1, fin)) return null
            // Neuf chiffres au plus, comme CommonMark : `N + 1` tient dans un Long.
            val suivant = texte.subSequence(i, f).toString().toLong() + 1
            PrefixeListe(f + 2, "$retrait$suivant${texte[f]} ")
        }

        else -> null
    }
}

private fun espaceEn(texte: CharSequence, position: Int, fin: Int): Boolean =
    position < fin && texte[position] == ' '

/**
 * Vrai si la ligne qui commence en [debutLigne] est à l'intérieur d'un bloc de
 * code clôturé.
 *
 * Approximation, comme la coloration : toute ligne qui commence — à trois
 * espaces près — par ` ``` ` ou `~~~` ouvre ou ferme un bloc, sans apparier la
 * longueur ni le caractère de la clôture. Une erreur coûte une suite de liste
 * de trop ou de moins, jamais une donnée.
 */
internal fun dansBlocDeCode(texte: CharSequence, debutLigne: Int): Boolean {
    var dedans = false
    var ligne = 0
    while (ligne < debutLigne) {
        var i = ligne
        while (i < debutLigne && i - ligne < 3 && texte[i] == ' ') i++
        if (i + 2 < debutLigne &&
            (texte[i] == '`' || texte[i] == '~') &&
            texte[i + 1] == texte[i] && texte[i + 2] == texte[i]
        ) {
            dedans = !dedans
        }
        while (ligne < debutLigne && texte[ligne] != '\n') ligne++
        ligne++
    }
    return dedans
}

/** Nombre maximal de chiffres d'un numéro de liste, selon CommonMark. */
private const val MAX_CHIFFRES_NUMERO = 9
