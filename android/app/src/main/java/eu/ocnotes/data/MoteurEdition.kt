package eu.ocnotes.data

/** Moteur retenu à la prochaine ouverture d'une note. */
enum class MoteurEdition(val valeurPersistante: String) {
    VIRTUALISE("virtualise"),
    NATIF("natif"),
    ;

    companion object {
        /**
         * Moteur proposé à l'installation. Le natif obtient de meilleurs résultats
         * au banc ; le virtualisé reste disponible en repli via les réglages.
         */
        val DEFAUT = NATIF

        /** Une valeur absente ou venue d'une version inconnue prend le moteur par défaut. */
        fun depuis(valeur: String?): MoteurEdition =
            entries.firstOrNull { it.valeurPersistante == valeur } ?: DEFAUT
    }
}
