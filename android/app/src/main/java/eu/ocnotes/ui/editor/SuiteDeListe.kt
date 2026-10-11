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
 *  - Les numéros qui suivent sont décalés d'un cran : Entrée en fin de
 *    `1. a`, au-dessus de `2. b`, donne `2. ` puis `3. b` (voir
 *    [renumeroterSuite]).
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
internal fun suiteDeListe(texte: CharSequence, position: Int): SuiteListe? {
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
    if (vide) return SuiteListe(RemplacementNatif(debutLigne, apres, ""), debutLigne)

    val curseur = apres + prefixe.suivant.length
    val numero = prefixe.numero
        ?: return SuiteListe(RemplacementNatif(apres, apres, prefixe.suivant), curseur)
    // Le décalage des éléments suivants fait partie du même `replace` : une
    // seule modification, donc une seule entrée dans la pile d'annulation.
    val (fin, decales) = renumeroterSuite(texte, apres, prefixe.retrait, numero)
    return SuiteListe(RemplacementNatif(apres, fin, prefixe.suivant + decales), curseur)
}

/** Ce que fait Entrée : un remplacement, et où poser le curseur ensuite. */
internal data class SuiteListe(val remplacement: RemplacementNatif, val curseur: Int)

/**
 * Préfixe de liste lu sur une ligne : où il finit, et celui de l'élément
 * suivant. [numero] et [retrait] ne sont posés que pour une liste numérotée
 * hors citation, la seule que [renumeroterSuite] sait décaler.
 */
internal data class PrefixeListe(
    val fin: Int,
    val suivant: String,
    val numero: NumeroListe? = null,
    val retrait: String = "",
)

/** Numéro d'un élément de liste ordonnée et son délimiteur, `.` ou `)`. */
internal data class NumeroListe(val valeur: Long, val delimiteur: Char)

/**
 * Décale d'un cran les éléments qui suivent [depuis] dans la même liste
 * numérotée, l'élément [numero] venant d'y être suivi de `numero + 1`.
 *
 * Ne touche qu'une suite **consécutive** : la ligne suivante doit porter
 * `numero + 1` — le numéro que le nouvel élément vient de prendre —, la
 * suivante `numero + 2`, et ainsi de suite. Une liste numérotée à la main
 * autrement (`1.` partout, par exemple) est laissée telle quelle. Les lignes
 * plus en retrait (sous-éléments, suite de paragraphe) sont enjambées ; une
 * ligne vide, un autre retrait ou un autre délimiteur arrêtent la suite.
 *
 * Rend la fin de la zone réécrite et son nouveau texte, qui commence en
 * [depuis] ; `(depuis, "")` si rien n'est à décaler.
 */
internal fun renumeroterSuite(
    texte: CharSequence,
    depuis: Int,
    retrait: String,
    numero: NumeroListe,
): Pair<Int, String> {
    val decale = StringBuilder()
    var copie = depuis
    var attendu = numero.valeur + 1
    var ligne = depuis
    while (ligne < texte.length && texte[ligne] == '\n') {
        val debut = ligne + 1
        var finLigne = debut
        while (finLigne < texte.length && texte[finLigne] != '\n') finLigne++
        var i = debut
        while (i < finLigne && (texte[i] == ' ' || texte[i] == '\t')) i++
        if (i == finLigne) break
        if (i - debut > retrait.length) {
            ligne = finLigne
            continue
        }
        if (texte.subSequence(debut, i).toString() != retrait) break
        val element = prefixeDeListe(texte, debut, finLigne)?.numero ?: break
        if (element.valeur != attendu || element.delimiteur != numero.delimiteur) break
        // Le numéro occupe `[i, i + chiffres)` : seul lui change.
        val chiffres = element.valeur.toString().length
        decale.append(texte, copie, i).append(attendu + 1)
        copie = i + chiffres
        attendu++
        ligne = finLigne
    }
    return copie to decale.toString()
}

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
        c == '>' -> {
            if (!espaceEn(texte, i + 1, fin)) return null
            // Une citation peut en contenir une autre, ou une liste :
            // `> > ` reste `> > `, `> - a` donne `> - `. Une liste citée
            // n'est pas renumérotée : son numéro ne remonte pas.
            val interieur = prefixeDeListe(texte, i + 2, fin)
            if (interieur != null) {
                PrefixeListe(interieur.fin, "$retrait> ${interieur.suivant}") // i18n-ok : balisage Markdown.
            } else {
                PrefixeListe(i + 2, "$retrait> ")
            }
        }

        c == '-' || c == '*' || c == '+' -> {
            if (!espaceEn(texte, i + 1, fin)) return null
            // `* * *` ou `- - -` : une ligne de séparation, pas une puce.
            if (estSeparation(texte, i, fin)) return null
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
            val valeur = texte.subSequence(i, f).toString().toLong()
            PrefixeListe(
                f + 2,
                "$retrait${valeur + 1}${texte[f]} ", // i18n-ok : balisage Markdown.
                NumeroListe(valeur, texte[f]),
                retrait,
            )
        }

        else -> null
    }
}

/**
 * Vrai si `[debut, fin)` est une ligne de séparation CommonMark : trois
 * marques identiques ou plus, `-`, `*` ou `_`, et rien d'autre que des espaces.
 */
private fun estSeparation(texte: CharSequence, debut: Int, fin: Int): Boolean {
    val marque = texte[debut]
    if (marque != '-' && marque != '*' && marque != '_') return false
    var marques = 0
    for (k in debut until fin) {
        when (texte[k]) {
            marque -> marques++
            ' ', '\t' -> {}
            else -> return false
        }
    }
    return marques >= 3
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
