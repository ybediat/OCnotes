package eu.opennote.ui.browser

import eu.opennote.data.FolderEntryDto
import java.text.Normalizer

/**
 * Ordre d'affichage des notes.
 *
 * Les dossiers n'y sont **jamais** soumis : ils restent en tête et toujours
 * alphabétiques. La façade ne leur donne pas de `modTime` — il est vide pour
 * une entrée de type dossier — donc les trier par date les tasserait tous à
 * une extrémité de la liste, ce qui n'est pas un classement mais un accident.
 */
enum class Tri {
    NOM,
    DATE,
    ;

    /** L'autre état du bouton. */
    fun suivant(): Tri = if (this == NOM) DATE else NOM

    companion object {
        /**
         * Une liste de notes se consulte d'abord par récence : ce qu'on vient
         * d'écrire est ce qu'on rouvre. Le classement alphabétique reste à un
         * appui, et le choix est mémorisé.
         */
        val DEFAUT = DATE

        /** Relit une préférence enregistrée, sans jamais échouer dessus. */
        fun depuis(valeur: String?): Tri = entries.firstOrNull { it.name == valeur } ?: DEFAUT
    }
}

/**
 * Forme de la page d'accueil.
 *
 * Deux modes plutôt qu'un remplacement : l'arborescence reste ce qu'elle
 * était — chemin courant, remontée, retour arrière — et la liste plate se
 * pose à côté. C'est ce qui rend le second mode peu coûteux, et ce qui
 * permet de préférer l'un ou l'autre sans que le choix soit définitif.
 */
enum class ModeAffichage {
    /** Un dossier à la fois, avec ses sous-dossiers. */
    ARBORESCENCE,

    /** Toutes les notes de la bibliothèque, sans aucun dossier. */
    LISTE,
    ;

    fun suivant(): ModeAffichage = if (this == ARBORESCENCE) LISTE else ARBORESCENCE

    companion object {
        /**
         * L'arborescence reste le défaut : c'est le comportement que
         * l'application avait, et changer la page d'accueil de tout le monde
         * sans le demander serait présomptueux. La liste plate est à un appui
         * dans le tiroir.
         */
        val DEFAUT = ARBORESCENCE

        fun depuis(valeur: String?): ModeAffichage =
            entries.firstOrNull { it.name == valeur } ?: DEFAUT
    }
}

/**
 * Filtre puis ordonne un listing pour l'affichage.
 *
 * Volontairement sans dépendance Android : c'est une fonction de son entrée,
 * elle se vérifie sur la JVM sans appareil ni Robolectric.
 *
 * Le tri se fait ici et non côté Go. C'est de la présentation, la liste est
 * déjà entièrement en mémoire, et le contrat de la façade n'a pas à s'ouvrir
 * pour un ordre d'affichage. `notes.Library.List` garde donc son ordre
 * alphabétique comme valeur par défaut.
 */
fun classer(
    entrees: List<FolderEntryDto>,
    recherche: String,
    tri: Tri,
): List<FolderEntryDto> {
    val retenues = if (recherche.isBlank()) {
        entrees
    } else {
        val cible = replie(recherche)
        // Les dossiers sont filtrés comme les notes : dans un dossier qui en
        // contient vingt, laisser passer les vingt noierait les deux notes
        // qui correspondent.
        entrees.filter { replie(it.display).contains(cible) }
    }

    val (dossiers, notes) = retenues.partition { it.isDir }

    return dossiers.sortedWith(PAR_NOM) + when (tri) {
        Tri.NOM -> notes.sortedWith(PAR_NOM)
        Tri.DATE -> notes.sortedWith(PAR_DATE)
    }
}

/**
 * Classement par nom, insensible à la casse.
 *
 * Même règle que `lessName` côté Go, y compris sa limite : les accents ne
 * suivent pas la collation française — « élan » se range après « zèbre ». La
 * corriger demanderait un `Collator`, et surtout de la corriger des deux
 * côtés de la frontière en même temps.
 */
private val PAR_NOM = compareBy<FolderEntryDto>({ it.display.lowercase() }, { it.display })

/**
 * Classement par date, la plus récente d'abord.
 *
 * `modTime` est en RFC 3339 UTC, de largeur fixe et toujours suffixé `Z` :
 * l'ordre lexicographique y est l'ordre chronologique, sans passer par une
 * analyse de date. Une date absente vaut chaîne vide, donc se range en fin de
 * liste — c'est ce qu'on veut d'une entrée dont on ignore l'âge.
 *
 * Hors connexion, la façade renvoie la date de modification **locale** et non
 * celle du serveur : la liste peut se réordonner en perdant le réseau. C'est
 * assumé — le cache ne connaît pas d'autre date.
 */
private val PAR_DATE = compareByDescending<FolderEntryDto> { it.modTime }
    .thenBy { it.display.lowercase() }

/**
 * Forme repliée d'un texte pour la comparaison : sans casse ni accents.
 *
 * Chercher « resume » doit trouver « Résumé ». La décomposition NFD sépare la
 * lettre de son accent, qui devient un caractère de catégorie « marque sans
 * chasse » — il suffit alors de les retirer.
 *
 * `lowercase()` sans argument travaille en `Locale.ROOT`, donc à l'abri du
 * « i » turc : la langue de l'appareil ne doit pas changer ce qu'une
 * recherche trouve.
 */
private fun replie(texte: String): String =
    Normalizer.normalize(texte, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .lowercase()
