package eu.ocnotes.ui.common

import eu.ocnotes.ui.browser.replie

/**
 * Nombre d'occurrences retenues au plus par une recherche dans une note.
 *
 * Au-delà, le compteur dit « 5000+ » : chercher « e » dans 295 ko en trouve
 * des dizaines de milliers, qu'aucun lecteur ne parcourra une à une, et dont
 * la liste n'a pas à vivre en mémoire.
 */
const val MAX_OCCURRENCES = 5000

/**
 * Texte replié (voir [replie]) qui se souvient d'où vient chacun de ses
 * caractères.
 *
 * [replie] change la longueur du texte — « œ » devient « oe », un accent
 * décomposé disparaît — et ne peut donc pas dire où se trouve, dans la note,
 * ce qu'on y a trouvé. Ici, chaque caractère replié garde les bornes UTF-16 du
 * caractère d'origine qui l'a produit.
 */
internal class TexteReplie(
    val texte: String,
    private val debuts: IntArray,
    private val fins: IntArray,
) {
    /** Bornes UTF-16, dans le texte d'origine, de la plage repliée [debut, fin[. */
    fun origine(debut: Int, fin: Int): IntRange = debuts[debut] until fins[fin - 1]
}

/**
 * Replie [texte] comme [replie], caractère d'origine par caractère d'origine.
 *
 * Replier code point par code point donne le même texte que replier la chaîne
 * entière : la décomposition NFD d'un caractère ne dépend pas de ses voisins,
 * et le seul réordonnancement qu'elle fait porte sur les marques combinantes,
 * que le repli retire de toute façon. `RechercheNoteTest` le vérifie sur la
 * table de cas commune avec Go.
 *
 * Une marque combinante (l'accent d'un « é » décomposé) ne produit rien : elle
 * est rattachée au caractère qui la précède, pour qu'une occurrence de « e »
 * couvre aussi son accent et qu'aucune borne ne tombe au milieu d'une lettre.
 */
internal fun replieAvecPositions(texte: CharSequence): TexteReplie {
    val sortie = StringBuilder(texte.length)
    var debuts = IntArray(texte.length + 1)
    var fins = IntArray(texte.length + 1)
    var i = 0
    while (i < texte.length) {
        val code = Character.codePointAt(texte, i)
        val largeur = Character.charCount(code)
        val avant = sortie.length
        if (code < 0x80) {
            // Chemin rapide : l'essentiel d'une note est en ASCII, et
            // `Normalizer` alloue une chaîne par appel.
            sortie.append(
                when (code) {
                    in 'A'.code..'Z'.code -> (code + 32).toChar()
                    '\t'.code -> ' '
                    else -> code.toChar()
                },
            )
        } else {
            sortie.append(replie(String(Character.toChars(code))))
        }
        val produits = sortie.length - avant
        if (produits == 0) {
            // Marque combinante : elle prolonge le dernier caractère produit.
            if (avant > 0) fins[avant - 1] = i + largeur
        } else {
            // Une ligature peut produire deux caractères : « œ » → « oe ».
            if (sortie.length > debuts.size) {
                debuts = debuts.copyOf(sortie.length * 2)
                fins = fins.copyOf(sortie.length * 2)
            }
            for (k in avant until sortie.length) {
                debuts[k] = i
                fins[k] = i + largeur
            }
        }
        i += largeur
    }
    return TexteReplie(sortie.toString(), debuts, fins)
}

/**
 * Occurrences de [terme] dans [texte], sans casse, sans accents et sans
 * distinguer les espaces insécables, comme la recherche d'un navigateur.
 *
 * Bornes UTF-16 **dans le texte d'origine**, celles de `Editable` et de
 * `AnnotatedString`. Les occurrences ne se chevauchent pas (« aa » dans
 * « aaaa » en donne deux) et s'arrêtent à [limite]. Un terme vide, ou qui ne
 * garde rien une fois replié, ne trouve rien.
 */
fun trouverOccurrences(
    texte: CharSequence,
    terme: String,
    limite: Int = MAX_OCCURRENCES,
): List<IntRange> {
    val cible = replie(terme)
    if (cible.isEmpty() || texte.isEmpty() || limite <= 0) return emptyList()
    val replieTexte = replieAvecPositions(texte)
    val occurrences = ArrayList<IntRange>()
    var depuis = 0
    while (occurrences.size < limite) {
        val trouve = replieTexte.texte.indexOf(cible, depuis)
        if (trouve < 0) break
        occurrences += replieTexte.origine(trouve, trouve + cible.length)
        depuis = trouve + cible.length
    }
    return occurrences
}
