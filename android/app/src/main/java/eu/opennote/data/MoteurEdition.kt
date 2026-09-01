package eu.opennote.data

/** Moteur retenu à la prochaine ouverture d'une note. */
enum class MoteurEdition(val valeurPersistante: String) {
    VIRTUALISE("virtualise"),
    NATIF("natif"),
    ;

    companion object {
        val DEFAUT = VIRTUALISE

        /** Une valeur absente ou venue d'une version inconnue garde le repli sûr. */
        fun depuis(valeur: String?): MoteurEdition =
            entries.firstOrNull { it.valeurPersistante == valeur } ?: DEFAUT
    }
}
