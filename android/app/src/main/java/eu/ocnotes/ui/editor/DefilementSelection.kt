package eu.ocnotes.ui.editor

/**
 * Largeur de la bande, au bord de la zone de texte, où étendre une sélection
 * fait défiler. Un peu plus de trois lignes de l'éditeur.
 */
internal const val BANDE_DEFILEMENT_SELECTION_DP = 80f

/** Vitesse atteinte au bord même, et au-delà. */
internal const val VITESSE_DEFILEMENT_SELECTION_DP_S = 600f

/**
 * Vitesse du défilement automatique pendant qu'on étend une sélection, en
 * pixels par seconde : négative vers le haut, positive vers le bas, nulle hors
 * des bandes.
 *
 * Ni Android ni Compose ne défilent d'eux-mêmes quand le doigt qui sélectionne
 * atteint le bord : il fallait lâcher, défiler à la main, puis reprendre la
 * poignée. Ici, la vitesse croît linéairement sur la [bande] qui longe chaque
 * bord, de zéro à [vitesseMax], et plafonne au-delà du bord — sur la barre de
 * format ou le clavier, où le doigt finit souvent sa course.
 *
 * [haut] et [bas] bornent la zone visible, dans le même repère que [y]. Une zone
 * trop courte pour deux bandes pleines (clavier ouvert, paysage) en garde au
 * moins un tiers sans défilement au milieu : sinon, toute position ferait
 * défiler.
 */
internal fun vitesseDefilementBord(
    y: Float,
    haut: Float,
    bas: Float,
    bande: Float,
    vitesseMax: Float,
): Float {
    val hauteur = bas - haut
    if (hauteur <= 0f || bande <= 0f) return 0f
    val largeur = minOf(bande, hauteur / 3f)
    return when {
        y < haut + largeur -> -vitesseMax * ((haut + largeur - y) / largeur).coerceAtMost(1f)
        y > bas - largeur -> vitesseMax * ((y - (bas - largeur)) / largeur).coerceAtMost(1f)
        else -> 0f
    }
}

/**
 * Dans l'éditeur, un poser qui ferait un double toucher est retenu au plus
 * [DELAI_INTENTION_MS] ; si le doigt parcourt plus de [SEUIL_DEFILEMENT_DP]
 * entre-temps, c'est un défilement et non une sélection (voir `ChampEditeur`).
 */
internal const val DELAI_INTENTION_MS = 200L
internal const val SEUIL_DEFILEMENT_DP = 48f
