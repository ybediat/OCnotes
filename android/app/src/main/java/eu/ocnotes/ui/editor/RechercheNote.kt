package eu.ocnotes.ui.editor

import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.ui.common.trouverOccurrences

/**
 * Une occurrence de l'aperçu : l'élément de la liste qui la dessine, et sa
 * plage UTF-16 dans le texte de cet élément.
 *
 * Un tableau est un seul élément : [ligne] et [colonne] désignent alors la
 * cellule, et valent -1 partout ailleurs. Les indices sont ceux des
 * **éléments** (voir [grouperPourApercu]), ceux qu'attend `scrollToItem`.
 */
data class OccurrenceApercu(
    val element: Int,
    val plage: IntRange,
    val ligne: Int = -1,
    val colonne: Int = -1,
)

/**
 * Les occurrences de [terme] dans l'aperçu, dans l'ordre de lecture, au plus
 * [limite].
 *
 * On cherche le texte que l'aperçu montre, élément par élément : celui de
 * `grouperPourApercu`, retour final retiré compris, pour que chaque plage
 * tombe juste dans le `Text` qui la dessine. Un bloc de code et le texte
 * alternatif d'une image sont cherchés comme les autres ; une occurrence ne
 * franchit jamais la limite d'un bloc ou d'une cellule.
 *
 * [depuis] est un repère (voir [repereApercu]) : seules les occurrences à
 * partir de lui sont retenues. Sert quand le plafond est atteint, pour que la
 * fenêtre parte de là où l'on lit. Dans un tableau, l'élément entier compte.
 */
internal fun occurrencesApercu(
    blocs: List<NoteBlockDto>,
    terme: String,
    limite: Int,
    depuis: Long = 0,
): List<OccurrenceApercu> {
    val resultat = ArrayList<OccurrenceApercu>()
    val elementDepart = (depuis ushr 32).toInt()
    val positionDepart = (depuis and 0xFFFFFFFFL).toInt()
    grouperPourApercu(blocs).forEachIndexed { indice, element ->
        if (resultat.size >= limite) return resultat
        if (indice < elementDepart) return@forEachIndexed
        when (element) {
            is ElementApercu.Bloc -> {
                val position = if (indice == elementDepart) positionDepart else 0
                trouverOccurrences(element.bloc.text, terme, limite - resultat.size, position).forEach {
                    resultat += OccurrenceApercu(indice, it)
                }
            }
            is ElementApercu.Tableau ->
                element.lignes.forEachIndexed { ligne, bloc ->
                    bloc.cells.forEachIndexed { colonne, cellule ->
                        if (resultat.size >= limite) return resultat
                        trouverOccurrences(cellule, terme, limite - resultat.size).forEach {
                            resultat += OccurrenceApercu(indice, it, ligne, colonne)
                        }
                    }
                }
        }
    }
    return resultat
}

/**
 * Indice de l'occurrence à montrer en premier : la première dont le
 * [repere] est au moins [depuis], sinon la première de la note — comme
 * « suivant » après la dernière. -1 sans occurrence.
 *
 * Le repère est le début de la plage en saisie, et en aperçu l'élément suivi
 * du début de la plage (voir [repereApercu]) : la recherche part de là où
 * l'on lit, pas du haut de la note.
 */
internal inline fun <T> premiereApres(occurrences: List<T>, depuis: Long, repere: (T) -> Long): Int {
    if (occurrences.isEmpty()) return -1
    // Les occurrences sont dans l'ordre du texte : une recherche par
    // dichotomie suffit, même sur 5 000.
    var bas = 0
    var haut = occurrences.size
    while (bas < haut) {
        val milieu = (bas + haut) ushr 1
        if (repere(occurrences[milieu]) < depuis) bas = milieu + 1 else haut = milieu
    }
    return if (bas < occurrences.size) bas else 0
}

/**
 * Repère d'une occurrence de l'aperçu, ordonné comme la lecture : l'élément,
 * puis la position dans son texte. Un élément seul ([plage] à 0) désigne sa
 * première occurrence. Sans la position, une rotation ramenait l'occurrence
 * courante à la première de son paragraphe (vu sur la note de 295 ko : 3799
 * devenait 3769).
 */
internal fun repereApercu(element: Int, plage: Int = 0): Long =
    (element.toLong() shl 32) or plage.toLong()

/** L'occurrence d'après [indice] parmi [total], la première après la dernière. */
internal fun indiceVoisin(indice: Int, total: Int, pas: Int): Int =
    if (total <= 0) -1 else Math.floorMod(indice + pas, total)

/**
 * Les marques d'un `Text` de l'aperçu : toutes ses occurrences, et la
 * courante si elle y tombe. Rien n'est calculé pour un élément hors de
 * l'écran : la liste de l'aperçu ne compose que ce qu'elle montre.
 */
data class MarquesTexte(
    val plages: List<IntRange>,
    val courante: IntRange?,
)

/** Ce que l'aperçu reçoit d'une recherche : les occurrences par élément, et la courante. */
data class SurlignageApercu(
    val parElement: Map<Int, List<OccurrenceApercu>>,
    val courante: OccurrenceApercu?,
) {
    /** Les marques du texte d'un bloc, ou d'une cellule quand [ligne] et [colonne] la désignent. */
    fun marques(element: Int, ligne: Int = -1, colonne: Int = -1): MarquesTexte? {
        val occurrences = parElement[element] ?: return null
        val plages = occurrences.filter { it.ligne == ligne && it.colonne == colonne }.map { it.plage }
        if (plages.isEmpty()) return null
        val courante = courante?.takeIf {
            it.element == element && it.ligne == ligne && it.colonne == colonne
        }?.plage
        return MarquesTexte(plages, courante)
    }
}
