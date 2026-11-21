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

    /**
     * Vrai si la plage repliée [debut, fin[ couvre des caractères d'origine
     * entiers. Une ligature produit plusieurs caractères repliés (« œ » →
     * « oe ») : chercher « e » ne doit pas surligner le « œ » entier.
     */
    fun alignee(debut: Int, fin: Int): Boolean =
        (debut == 0 || debuts[debut] != debuts[debut - 1]) &&
            (fin == texte.length || debuts[fin] != debuts[fin - 1])

    /**
     * Premier indice replié dont le caractère d'origine commence à [position]
     * ou après ; la longueur du texte replié s'il n'y en a pas. Les débuts
     * croissent avec l'indice : une dichotomie suffit.
     */
    fun indiceDepuis(position: Int): Int {
        var bas = 0
        var haut = texte.length
        while (bas < haut) {
            val milieu = (bas + haut) ushr 1
            if (debuts[milieu] < position) bas = milieu + 1 else haut = milieu
        }
        return bas
    }
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
 *
 * Une occurrence couvre toujours des caractères entiers : « coeur » trouve
 * « cœur », mais « e » seul ne trouve pas le « œ ».
 *
 * [depuis] est une position UTF-16 du texte d'origine : seules les
 * occurrences qui commencent là ou après sont retenues. Sert quand le plafond
 * est atteint, pour que la fenêtre de [limite] occurrences parte de là où
 * l'on lit et non du haut de la note.
 */
fun trouverOccurrences(
    texte: CharSequence,
    terme: String,
    limite: Int = MAX_OCCURRENCES,
    depuis: Int = 0,
): List<IntRange> {
    val cible = replie(terme)
    if (cible.isEmpty() || texte.isEmpty() || limite <= 0) return emptyList()
    val replieTexte = replieAvecPositions(texte)
    val occurrences = ArrayList<IntRange>()
    var curseur = if (depuis > 0) replieTexte.indiceDepuis(depuis) else 0
    while (occurrences.size < limite) {
        val trouve = replieTexte.texte.indexOf(cible, curseur)
        if (trouve < 0) break
        val fin = trouve + cible.length
        if (!replieTexte.alignee(trouve, fin)) {
            // À cheval sur une ligature : on retente un caractère plus loin.
            curseur = trouve + 1
            continue
        }
        occurrences += replieTexte.origine(trouve, fin)
        curseur = fin
    }
    return occurrences
}
