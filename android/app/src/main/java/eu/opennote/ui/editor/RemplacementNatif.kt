package eu.opennote.ui.editor

/** Différence unique à appliquer à l'Editable pour conserver l'annulation. */
data class RemplacementNatif(
    val debut: Int,
    val fin: Int,
    val texte: String,
) {
    val vide: Boolean get() = debut == fin && texte.isEmpty()
}

/**
 * Retire le plus grand préfixe et suffixe communs sans couper une paire UTF-16.
 * Le résultat s'applique par un unique `Editable.replace`.
 */
internal fun calculerRemplacementNatif(avant: String, apres: String): RemplacementNatif {
    var debut = 0
    val limite = minOf(avant.length, apres.length)
    while (debut < limite && avant[debut] == apres[debut]) debut++
    debut = minOf(
        normaliserOffsetUtf16(avant, debut),
        normaliserOffsetUtf16(apres, debut),
    )

    var suffixe = 0
    val suffixeMax = minOf(avant.length - debut, apres.length - debut)
    while (
        suffixe < suffixeMax &&
        avant[avant.lastIndex - suffixe] == apres[apres.lastIndex - suffixe]
    ) {
        suffixe++
    }
    while (
        suffixe > 0 && (
            paireUtf16Coupee(avant, avant.length - suffixe) ||
                paireUtf16Coupee(apres, apres.length - suffixe)
            )
    ) {
        suffixe--
    }

    return RemplacementNatif(
        debut = debut,
        fin = avant.length - suffixe,
        texte = apres.substring(debut, apres.length - suffixe),
    )
}

private fun paireUtf16Coupee(texte: String, borne: Int): Boolean =
    borne in 1 until texte.length &&
        texte[borne - 1].isHighSurrogate() &&
        texte[borne].isLowSurrogate()

internal fun revisionNativeToujoursCourante(attendue: Long, courante: Long): Boolean =
    attendue == courante
