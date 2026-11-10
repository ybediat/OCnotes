package eu.ocnotes.ui.editor

/**
 * Portion du document confiée à Go pour une mise en forme, en offsets UTF-16.
 *
 * [texte] est le contenu de `[debut, fin)` ; [selection] reste exprimée dans
 * le document entier.
 */
data class FenetreNatif(
    val texte: String,
    val debut: Int,
    val fin: Int,
    val selection: SelectionEditeurNatif,
    val revision: Long,
)

/**
 * Délimite la fenêtre de mise en forme : du début de la ligne qui précède celle
 * de la borne basse de la sélection, à la fin de la ligne qui suit celle de la
 * borne haute. Les sauts de ligne de bord restent hors de la fenêtre.
 *
 * Envoyer le document entier coûtait, sur 285 ko, un encodage JSON, deux
 * traversées gomobile, un décodage complet côté Go et un diff : pour poser un
 * `# ` en tête de ligne.
 *
 * **La règle n'est pas libre.** Toute action ne lit que les lignes
 * sélectionnées, sauf le bloc de code, qui regarde la ligne d'avant et la
 * ligne d'après pour savoir s'il doit retirer ses délimiteurs. Sans cette ligne
 * de contexte, il ajouterait un bloc au lieu de le retirer.
 * `TestFenetreEquivautAuDocumentEntier`, côté Go, prouve l'égalité exacte avec
 * le document entier pour cette règle, et la voit tomber sans contexte ; il en
 * porte une copie, `fenetreLignes`, qui doit rester identique à celle-ci.
 *
 * Seconde règle, pour le bloc de code : si la ligne au-dessus de la sélection,
 * ou sa première ligne, est un délimiteur, Go doit savoir s'il ouvre ou ferme
 * un bloc, et compte pour cela les délimiteurs depuis le début de la fenêtre.
 * La fenêtre recule alors jusqu'à la dernière ligne hors de tout bloc qui le
 * précède ([debutHorsBloc]). Le cas est rare : un parcours du texte, sans
 * copie, seulement quand un délimiteur touche la sélection.
 *
 * Les bornes tombent toujours à côté d'un saut de ligne : elles ne coupent
 * jamais une paire UTF-16.
 */
internal fun fenetreMiseEnForme(texte: CharSequence, debut: Int, fin: Int): BornesFenetre {
    val bas = minOf(debut, fin).coerceIn(0, texte.length)
    val haut = maxOf(debut, fin).coerceIn(0, texte.length)

    var gauche = debutLigne(texte, bas)
    if (gauche > 0) gauche = debutLigne(texte, gauche - 1)

    var droite = finLigne(texte, haut)
    if (droite < texte.length) droite = finLigne(texte, droite + 1)

    return BornesFenetre(minOf(gauche, debutHorsBloc(texte, bas)), droite)
}

/**
 * Début de la dernière ligne hors de tout bloc de code qui précède le
 * délimiteur touchant la sélection (ligne au-dessus, sinon première ligne) ;
 * le début de la ligne de [bas] s'il n'y en a pas. Même règle de délimiteur
 * que `codeBlock` côté Go : une ligne qui commence par ` ``` `, blancs de bord
 * ignorés. Copie conforme de `debutHorsBloc` dans `window_test.go`.
 */
internal fun debutHorsBloc(texte: CharSequence, bas: Int): Int {
    val premiere = debutLigne(texte, bas)
    val cible = when {
        premiere > 0 && estDelimiteurBloc(texte, debutLigne(texte, premiere - 1)) -> debutLigne(texte, premiere - 1)
        estDelimiteurBloc(texte, premiere) -> premiere
        else -> return premiere
    }
    var hors = 0
    var dedans = false
    var ligne = 0
    while (ligne <= cible) {
        if (!dedans) hors = ligne
        if (estDelimiteurBloc(texte, ligne)) dedans = !dedans
        ligne = finLigne(texte, ligne) + 1
    }
    return hors
}

/**
 * La ligne qui commence en [debut] est-elle un délimiteur de bloc de code ?
 * Comme `isFence` côté Go : ` ``` ` en tête, blancs de bord ignorés.
 */
internal fun estDelimiteurBloc(texte: CharSequence, debut: Int): Boolean =
    texte.subSequence(debut, finLigne(texte, debut)).trim().startsWith("```")

/**
 * Le délimiteur de la ligne qui commence en [debut] ouvre-t-il un bloc ?
 * Vrai si un nombre pair de délimiteurs le précède depuis le début du texte
 * (`opens` dans `codeBlock` côté Go).
 */
internal fun delimiteurOuvre(texte: CharSequence, debut: Int): Boolean {
    var compte = 0
    var ligne = 0
    while (ligne < debut) {
        if (estDelimiteurBloc(texte, ligne)) compte++
        ligne = finLigne(texte, ligne) + 1
    }
    return compte % 2 == 0
}

/** Bornes d'une fenêtre, [fin] exclue, comme `substring`. */
internal data class BornesFenetre(val debut: Int, val fin: Int)

private fun debutLigne(texte: CharSequence, position: Int): Int {
    var p = position
    while (p > 0 && texte[p - 1] != '\n') p--
    return p
}

private fun finLigne(texte: CharSequence, position: Int): Int {
    var p = position
    while (p < texte.length && texte[p] != '\n') p++
    return p
}
