package eu.ocnotes.ui.editor

import kotlin.math.max
import kotlin.math.min

/** Une vue exacte du document, avec une borne de fin exclusive. */
data class TrancheEditeur(
    val debut: Int,
    val fin: Int,
) {
    init {
        require(debut >= 0)
        require(fin >= debut)
    }

    fun texteDe(document: String): String = document.substring(debut, fin)
}

/** Limite mesurée sur l'appareil de référence pour un champ actif. */
internal const val MAX_RETOURS_EDITEUR = 12

/** Protège aussi les paragraphes sans aucun retour à la ligne. */
internal const val MAX_UTF16_EDITEUR = 640

/*
 * La fenêtre s'ouvre plus petite que sa limite dure. Cette marge absorbe la
 * frappe au bord d'une tranche sans rematérialiser le document à chaque touche.
 */
private const val CIBLE_RETOURS_EDITEUR = 4
private const val CIBLE_UTF16_EDITEUR = 192
private const val MAX_AJUSTEMENT_MOT = 64

/** Résultat pur du montage d'une fenêtre autour d'une sélection globale. */
internal data class FenetreMontee(
    val tranches: List<TrancheEditeur>,
    val focus: Int,
    val selectionDebut: Int,
    val selectionFin: Int,
)

/**
 * Découpe tout le document en tranches bornées, sans interpréter le Markdown.
 *
 * Les tranches pavent toujours [document] exactement. Une coupure préfère un
 * retour à la ligne, puis une espace proche de la borne dure. Une paire de
 * substitution UTF-16 n'est jamais séparée.
 */
internal fun decouperDocument(
    document: String,
    maxRetours: Int = MAX_RETOURS_EDITEUR,
    maxUtf16: Int = MAX_UTF16_EDITEUR,
): List<TrancheEditeur> {
    require(maxRetours > 0)
    require(maxUtf16 >= 2)
    if (document.isEmpty()) return listOf(TrancheEditeur(0, 0))
    return decouperIntervalle(document, 0, document.length, maxRetours, maxUtf16)
}

/** Remplace le seul brouillon mutable dans l'instantané auquel il appartient. */
internal fun materialiserDocument(
    document: String,
    active: TrancheEditeur?,
    brouillon: String,
): String {
    if (active == null) return document
    require(active.fin <= document.length)
    return document.substring(0, active.debut) + brouillon + document.substring(active.fin)
}

/**
 * Monte une tranche active centrée sur la sélection, avec de la marge de part
 * et d'autre. Les morceaux avant et après restent découpés au budget normal.
 */
internal fun monterFenetre(
    document: String,
    selectionDebut: Int,
    selectionFin: Int = selectionDebut,
): FenetreMontee {
    if (document.isEmpty()) {
        return FenetreMontee(
            tranches = listOf(TrancheEditeur(0, 0)),
            focus = 0,
            selectionDebut = 0,
            selectionFin = 0,
        )
    }

    val ancreDebut = normaliserOffsetUtf16(document, selectionDebut)
    val ancreFin = normaliserOffsetUtf16(document, selectionFin)
    val debutSelection = min(ancreDebut, ancreFin)
    val finSelection = max(ancreDebut, ancreFin)
    val longueurSelection = finSelection - debutSelection

    // Une sélection vient toujours du champ actif, donc elle ne peut dépasser
    // sa limite. Échouer ici vaut mieux que perdre silencieusement sa fin.
    require(longueurSelection <= MAX_UTF16_EDITEUR)

    val budget = max(CIBLE_UTF16_EDITEUR, longueurSelection)
        .coerceAtMost(MAX_UTF16_EDITEUR)
    val marge = budget - longueurSelection
    val margeAvant = marge / 2
    val margeApres = marge - margeAvant

    // Le contexte en retours se compte sur ce qui RESTE après la sélection.
    // Sans cette soustraction, une sélection portant déjà douze retours en
    // recevait quatre de plus, et la fenêtre naissait au-dessus de sa limite.
    val budgetRetours = (MAX_RETOURS_EDITEUR - compterRetours(document, debutSelection, finSelection))
        .coerceAtLeast(0)
    val cibleRetours = min(CIBLE_RETOURS_EDITEUR, budgetRetours)
    val retoursAvant = cibleRetours / 2
    val retoursApres = cibleRetours - retoursAvant

    val debutBrut = etendreAvant(
        document = document,
        position = debutSelection,
        maxUtf16 = margeAvant,
        maxRetours = retoursAvant,
    )
    val finBrute = etendreApres(
        document = document,
        position = finSelection,
        maxUtf16 = margeApres,
        maxRetours = retoursApres,
    )

    // L'alignement sur les mots élargit la fenêtre, donc il peut la faire
    // sortir de ses budgets — c'est arrivé, et le symptôme n'était pas là où
    // on le cherchait : une fenêtre de 650 unités demande son propre
    // rééquilibrage au premier déplacement du curseur, ce qui la remonte, ce
    // qui vide l'historique d'annulation du champ. Un confort de découpe ne
    // passe donc jamais devant une borne : la borne brute reprend la main.
    val debutAligne = alignerDebutMot(document, debutBrut)
    val debutFenetre =
        if (depasseLesBudgets(document, debutAligne, finBrute)) debutBrut else debutAligne

    val finAlignee = alignerFinMot(document, finBrute)
    val finFenetre =
        if (depasseLesBudgets(document, debutFenetre, finAlignee)) finBrute else finAlignee

    return monterIntervalleActif(
        document = document,
        debutFenetre = debutFenetre,
        finFenetre = finFenetre,
        ancreDebut = ancreDebut,
        ancreFin = ancreFin,
    )
}

/**
 * Agrandit le champ actif lorsqu'une poignée de sélection atteint son bord.
 *
 * La sélection reste exprimée avec ses offsets globaux et conserve son sens.
 * Seul le contexte chargé autour d'elle grandit, jusqu'aux limites du champ.
 */
internal fun etendreFenetrePourSelection(
    document: String,
    debutActif: Int,
    finActif: Int,
    selectionDebut: Int,
    selectionFin: Int,
): FenetreMontee? {
    require(debutActif in 0..finActif)
    require(finActif <= document.length)

    val ancreDebut = normaliserOffsetUtf16(document, selectionDebut)
        .coerceIn(debutActif, finActif)
    val ancreFin = normaliserOffsetUtf16(document, selectionFin)
        .coerceIn(debutActif, finActif)
    val debutSelection = min(ancreDebut, ancreFin)
    val finSelection = max(ancreDebut, ancreFin)
    if (debutSelection == finSelection) return null

    val toucheDebut = debutSelection == debutActif
    val toucheFin = finSelection == finActif
    if (!toucheDebut && !toucheFin) return null

    val longueurActuelle = finActif - debutActif
    val budgetUtf16 = (MAX_UTF16_EDITEUR - longueurActuelle).coerceAtLeast(0)
    val retoursActuels = compterRetours(document, debutActif, finActif)
    val budgetRetours = (MAX_RETOURS_EDITEUR - retoursActuels).coerceAtLeast(0)

    val budgetAvantUtf16 = when {
        toucheDebut && toucheFin -> budgetUtf16 / 2
        toucheDebut -> budgetUtf16
        else -> 0
    }
    val budgetApresUtf16 = when {
        toucheDebut && toucheFin -> budgetUtf16 - budgetAvantUtf16
        toucheFin -> budgetUtf16
        else -> 0
    }
    val budgetAvantRetours = when {
        toucheDebut && toucheFin -> budgetRetours / 2
        toucheDebut -> budgetRetours
        else -> 0
    }
    val budgetApresRetours = when {
        toucheDebut && toucheFin -> budgetRetours - budgetAvantRetours
        toucheFin -> budgetRetours
        else -> 0
    }

    val debutBrut = etendreAvant(
        document = document,
        position = debutActif,
        maxUtf16 = budgetAvantUtf16,
        maxRetours = budgetAvantRetours,
    )
    val finBrute = etendreApres(
        document = document,
        position = finActif,
        maxUtf16 = budgetApresUtf16,
        maxRetours = budgetApresRetours,
    )
    val debutFenetre = alignerDebutMotVersInterieur(document, debutBrut, debutActif)
    val finFenetre = alignerFinMotVersInterieur(document, finBrute, finActif)
    if (debutFenetre == debutActif && finFenetre == finActif) return null

    return monterIntervalleActif(
        document = document,
        debutFenetre = debutFenetre,
        finFenetre = finFenetre,
        ancreDebut = ancreDebut,
        ancreFin = ancreFin,
    )
}

private fun monterIntervalleActif(
    document: String,
    debutFenetre: Int,
    finFenetre: Int,
    ancreDebut: Int,
    ancreFin: Int,
): FenetreMontee {
    require(debutFenetre in 0..finFenetre)
    require(finFenetre <= document.length)
    require(ancreDebut in debutFenetre..finFenetre)
    require(ancreFin in debutFenetre..finFenetre)

    val avant = decouperIntervalle(
        document = document,
        debutIntervalle = 0,
        finIntervalle = debutFenetre,
        maxRetours = MAX_RETOURS_EDITEUR,
        maxUtf16 = MAX_UTF16_EDITEUR,
    )
    val apres = decouperIntervalle(
        document = document,
        debutIntervalle = finFenetre,
        finIntervalle = document.length,
        maxRetours = MAX_RETOURS_EDITEUR,
        maxUtf16 = MAX_UTF16_EDITEUR,
    )
    val tranches = buildList(avant.size + apres.size + 1) {
        addAll(avant)
        add(TrancheEditeur(debutFenetre, finFenetre))
        addAll(apres)
    }

    return FenetreMontee(
        tranches = tranches,
        focus = avant.size,
        selectionDebut = ancreDebut - debutFenetre,
        selectionFin = ancreFin - debutFenetre,
    )
}

/** Le rééquilibrage est rare : uniquement après franchissement d'une borne. */
internal fun doitReequilibrer(texte: String): Boolean =
    depasseLesBudgets(texte, 0, texte.length)

/**
 * La règle des deux budgets, sur un intervalle du document.
 *
 * Elle vit ici, en un seul endroit, parce que le montage d'une fenêtre et la
 * décision de la rééquilibrer doivent être la **même** règle. Les écrire deux
 * fois, c'est laisser naître une fenêtre que le test suivant refuse aussitôt.
 */
private fun depasseLesBudgets(document: String, debut: Int, fin: Int): Boolean {
    if (fin - debut > MAX_UTF16_EDITEUR) return true
    return compterRetours(document, debut, fin) > MAX_RETOURS_EDITEUR
}

private fun compterRetours(document: String, debut: Int, fin: Int): Int {
    var retours = 0
    for (i in debut until fin) {
        if (document[i] == '\n') retours++
    }
    return retours
}

private fun decouperIntervalle(
    document: String,
    debutIntervalle: Int,
    finIntervalle: Int,
    maxRetours: Int,
    maxUtf16: Int,
): List<TrancheEditeur> {
    require(debutIntervalle in 0..finIntervalle)
    require(finIntervalle <= document.length)
    if (debutIntervalle == finIntervalle) return emptyList()

    val tranches = mutableListOf<TrancheEditeur>()
    var debut = debutIntervalle

    while (debut < finIntervalle) {
        var borneLongueur = min(debut + maxUtf16, finIntervalle)
        borneLongueur = avantPaireUtf16Coupee(document, borneLongueur)

        val borneLignes = borneApresRetours(
            document = document,
            debut = debut,
            limite = borneLongueur,
            maxRetours = maxRetours,
        )
        val fin = when {
            borneLignes != null -> borneLignes
            borneLongueur == finIntervalle -> finIntervalle
            else -> coupureNaturelle(document, debut, borneLongueur)
        }

        check(fin > debut)
        tranches += TrancheEditeur(debut, fin)
        debut = fin
    }

    return tranches
}

private fun etendreAvant(
    document: String,
    position: Int,
    maxUtf16: Int,
    maxRetours: Int,
): Int {
    var debut = position
    var longueur = 0
    var retours = 0

    while (debut > 0) {
        var suivant = debut - 1
        if (
            suivant > 0 &&
            document[suivant].isLowSurrogate() &&
            document[suivant - 1].isHighSurrogate()
        ) {
            suivant--
        }

        val cout = debut - suivant
        if (longueur + cout > maxUtf16) break
        if (document[suivant] == '\n' && retours == maxRetours) break

        if (document[suivant] == '\n') retours++
        longueur += cout
        debut = suivant
    }
    return debut
}

private fun etendreApres(
    document: String,
    position: Int,
    maxUtf16: Int,
    maxRetours: Int,
): Int {
    var fin = position
    var longueur = 0
    var retours = 0

    while (fin < document.length) {
        var suivant = fin + 1
        if (
            document[fin].isHighSurrogate() &&
            suivant < document.length &&
            document[suivant].isLowSurrogate()
        ) {
            suivant++
        }

        val cout = suivant - fin
        if (longueur + cout > maxUtf16) break
        if (document[fin] == '\n' && retours == maxRetours) break

        if (document[fin] == '\n') retours++
        longueur += cout
        fin = suivant
    }
    return fin
}

/** Recule au début du mot si une frontière naturelle est proche. */
private fun alignerDebutMot(document: String, borne: Int): Int {
    if (borne <= 0 || document[borne - 1].isWhitespace()) return borne

    val limite = max(0, borne - MAX_AJUSTEMENT_MOT)
    for (i in borne - 1 downTo limite) {
        if (document[i].isWhitespace()) return i + 1
    }
    return borne
}

/** Avance après le mot et son séparateur si cette frontière reste proche. */
private fun alignerFinMot(document: String, borne: Int): Int {
    if (borne >= document.length || document[borne - 1].isWhitespace()) return borne

    val limite = min(document.length - 1, borne + MAX_AJUSTEMENT_MOT)
    for (i in borne..limite) {
        if (document[i].isWhitespace()) return i + 1
    }
    return borne
}

/** Avance la borne extérieure sans rogner le champ déjà chargé. */
private fun alignerDebutMotVersInterieur(
    document: String,
    borne: Int,
    limite: Int,
): Int {
    if (borne <= 0 || document[borne - 1].isWhitespace()) return borne

    val finRecherche = min(limite, borne + MAX_AJUSTEMENT_MOT)
    for (i in borne until finRecherche) {
        if (document[i].isWhitespace()) return i + 1
    }
    return borne
}

/** Recule la borne extérieure sans rogner le champ déjà chargé. */
private fun alignerFinMotVersInterieur(
    document: String,
    borne: Int,
    limite: Int,
): Int {
    if (borne >= document.length || document[borne - 1].isWhitespace()) return borne

    val debutRecherche = max(limite - 1, borne - MAX_AJUSTEMENT_MOT)
    for (i in borne - 1 downTo debutRecherche) {
        if (document[i].isWhitespace()) return i + 1
    }
    return borne
}

private fun borneApresRetours(
    document: String,
    debut: Int,
    limite: Int,
    maxRetours: Int,
): Int? {
    var retours = 0
    for (i in debut until limite) {
        if (document[i] != '\n') continue
        retours++
        if (retours == maxRetours) return i + 1
    }
    return null
}

private fun coupureNaturelle(document: String, debut: Int, limite: Int): Int {
    val debutRecherche = max(debut + 1, limite - 256)

    for (i in limite - 1 downTo debutRecherche) {
        if (document[i] == '\n') return i + 1
    }
    for (i in limite - 1 downTo debutRecherche) {
        if (document[i].isWhitespace()) return i + 1
    }
    return avantPaireUtf16Coupee(document, limite)
}
