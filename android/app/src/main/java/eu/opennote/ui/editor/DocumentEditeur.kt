package eu.opennote.ui.editor

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
private const val CIBLE_RETOURS_EDITEUR = 8
private const val CIBLE_UTF16_EDITEUR = 384

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

    val ancreDebut = normaliserOffset(document, selectionDebut)
    val ancreFin = normaliserOffset(document, selectionFin)
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
    val retoursAvant = CIBLE_RETOURS_EDITEUR / 2
    val retoursApres = CIBLE_RETOURS_EDITEUR - retoursAvant

    val debutFenetre = etendreAvant(
        document = document,
        position = debutSelection,
        maxUtf16 = margeAvant,
        maxRetours = retoursAvant,
    )
    val finFenetre = etendreApres(
        document = document,
        position = finSelection,
        maxUtf16 = margeApres,
        maxRetours = retoursApres,
    )

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
internal fun doitReequilibrer(texte: String): Boolean {
    if (texte.length > MAX_UTF16_EDITEUR) return true

    var retours = 0
    for (caractere in texte) {
        if (caractere == '\n' && ++retours > MAX_RETOURS_EDITEUR) return true
    }
    return false
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
        borneLongueur = avantPaireCoupee(document, borneLongueur)

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
    return avantPaireCoupee(document, limite)
}

private fun normaliserOffset(document: String, offset: Int): Int {
    val borne = offset.coerceIn(0, document.length)
    return avantPaireCoupee(document, borne)
}

private fun avantPaireCoupee(document: String, borne: Int): Int {
    if (borne <= 0 || borne >= document.length) return borne
    return if (document[borne - 1].isHighSurrogate() && document[borne].isLowSurrogate()) {
        borne - 1
    } else {
        borne
    }
}
