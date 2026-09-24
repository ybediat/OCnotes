package eu.ocnotes.ui.editor

/** Borne un offset au document et le recule s'il fend une paire UTF-16. */
internal fun normaliserOffsetUtf16(document: String, offset: Int): Int {
    val borne = offset.coerceIn(0, document.length)
    return avantPaireUtf16Coupee(document, borne)
}

/** Place une borne avant une paire de substitution qu'elle couperait. */
private fun avantPaireUtf16Coupee(document: String, borne: Int): Int {
    if (borne <= 0 || borne >= document.length) return borne
    return if (document[borne - 1].isHighSurrogate() && document[borne].isLowSurrogate()) {
        borne - 1
    } else {
        borne
    }
}
