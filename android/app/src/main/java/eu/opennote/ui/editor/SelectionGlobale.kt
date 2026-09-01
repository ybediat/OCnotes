package eu.opennote.ui.editor

import kotlin.math.max
import kotlin.math.min

/**
 * Une sélection exprimée dans le référentiel du document complet.
 *
 * [ancre] ne bouge pas pendant un geste ; [mobile] suit le doigt ou la
 * poignée. Leur ordre reste intact afin qu'une sélection de droite à gauche ne
 * devienne pas indistinguable de la sélection inverse.
 */
@ConsistentCopyVisibility
internal data class SelectionGlobale private constructor(
    val ancre: Int,
    val mobile: Int,
) {
    init {
        require(ancre >= 0)
        require(mobile >= 0)
    }

    val debut: Int get() = min(ancre, mobile)
    val fin: Int get() = max(ancre, mobile)
    val inversee: Boolean get() = ancre > mobile
    val vide: Boolean get() = ancre == mobile

    /**
     * Rend l'unique offset qu'une fenêtre locale peut accepter à la sortie.
     *
     * Le côté mobile est le repli naturel après un geste. Un toucher ailleurs
     * fournit explicitement son propre [offsetSouhaite]. Dans les deux cas, la
     * borne est ramenée dans le document sans jamais couper un emoji.
     */
    fun offsetEffondre(
        document: String,
        offsetSouhaite: Int = mobile,
    ): Int = normaliserOffsetUtf16(document, offsetSouhaite)

    /** Intersection non vide, ramenée aux offsets locaux de [tranche]. */
    fun intersectionAvec(tranche: TrancheEditeur): PlageSelectionLocale? {
        val debutGlobal = max(debut, tranche.debut)
        val finGlobale = min(fin, tranche.fin)
        if (debutGlobal >= finGlobale) return null

        return PlageSelectionLocale(
            debut = debutGlobal - tranche.debut,
            fin = finGlobale - tranche.debut,
        )
    }

    companion object {
        /**
         * Crée une sélection valide pour [document].
         *
         * Les coordonnées venant d'un hit test ou d'un état restauré sont
         * bornées ici, une seule fois, avant d'entrer dans la machine globale.
         */
        fun creer(document: String, ancre: Int, mobile: Int): SelectionGlobale =
            SelectionGlobale(
                ancre = normaliserOffsetUtf16(document, ancre),
                mobile = normaliserOffsetUtf16(document, mobile),
            )
    }
}

/** Une plage locale non vide dont la fin est exclusive. */
internal data class PlageSelectionLocale(
    val debut: Int,
    val fin: Int,
) {
    init {
        require(debut >= 0)
        require(fin > debut)
    }
}

/** Borne un offset au document et le recule s'il fend une paire UTF-16. */
internal fun normaliserOffsetUtf16(document: String, offset: Int): Int {
    val borne = offset.coerceIn(0, document.length)
    return avantPaireUtf16Coupee(document, borne)
}

/** Place une borne avant une paire de substitution qu'elle couperait. */
internal fun avantPaireUtf16Coupee(document: String, borne: Int): Int {
    if (borne <= 0 || borne >= document.length) return borne
    return if (document[borne - 1].isHighSurrogate() && document[borne].isLowSurrogate()) {
        borne - 1
    } else {
        borne
    }
}
