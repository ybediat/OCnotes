package eu.ocnotes.ui.browser

import eu.ocnotes.data.FolderEntryDto

/*
 * Règles des notes épinglées, sans dépendance Android : comme [classer], elles
 * se vérifient sur la JVM.
 *
 * Une épingle est un **chemin de note**, relatif au dossier de notes du profil
 * qui la porte — le même référentiel que tous les chemins de la façade. Elle
 * ne traverse jamais la frontière Go : c'est un état d'écran, comme le tri.
 *
 * Toutes ces fonctions rendent un nouvel ensemble et ne modifient jamais
 * celui qu'on leur passe : l'appelant compare l'ancien et le nouveau pour
 * savoir s'il doit écrire.
 */

/**
 * L'épingle suit sa note après un renommage ou un déplacement réussi.
 *
 * [de] et [vers] sont l'ancien et le nouveau chemin de l'élément touché.
 * Pour une note, seule l'épingle de ce chemin exact change. Pour un dossier,
 * toutes les épingles **sous** lui suivent — en comparant avec `de/`, pour
 * que renommer `Notes/a` ne touche pas `Notes/ab/x.md`.
 */
fun apresRenommage(
    epingles: Set<String>,
    de: String,
    vers: String,
    estDossier: Boolean,
): Set<String> {
    if (de == vers || epingles.isEmpty()) return epingles
    val prefixe = "$de/"
    return epingles.mapTo(LinkedHashSet()) { chemin ->
        when {
            chemin == de -> vers
            estDossier && chemin.startsWith(prefixe) -> vers + "/" + chemin.removePrefix(prefixe)
            else -> chemin
        }
    }
}

/**
 * Retire l'épingle d'une note supprimée, ou toutes celles d'un dossier
 * supprimé. Une note recréée ensuite sous le même nom n'est donc pas épinglée.
 */
fun apresSuppression(
    epingles: Set<String>,
    chemin: String,
    estDossier: Boolean,
): Set<String> {
    val prefixe = "$chemin/"
    return epingles.filterTo(LinkedHashSet()) { epingle ->
        epingle != chemin && !(estDossier && epingle.startsWith(prefixe))
    }
}

/**
 * Ne garde que les épingles dont la note figure dans l'inventaire.
 *
 * À n'appeler que sur un inventaire **complet** : venu du serveur, ou du mode
 * local où le cache est la seule vérité. Un inventaire de repli, servi sans
 * réseau, peut manquer des notes — élaguer dessus effacerait des épingles
 * valides.
 */
fun elaguer(epingles: Set<String>, cheminsConnus: Set<String>): Set<String> =
    epingles.filterTo(LinkedHashSet()) { it in cheminsConnus }

/**
 * Les notes à montrer dans la rubrique « Épinglées » du tiroir.
 *
 * Prises dans l'inventaire, qui seul porte le nom et la date des notes des
 * autres dossiers ; une épingle sans note correspondante (renommée depuis le
 * web, par exemple) est simplement masquée. L'ordre est celui du tri en
 * cours, sans filtre de recherche : la rubrique ne dépend pas de ce qu'on
 * tape dans la liste.
 */
fun sectionEpinglees(
    epingles: Set<String>,
    inventaire: List<FolderEntryDto>,
    tri: Tri,
): List<FolderEntryDto> {
    if (epingles.isEmpty()) return emptyList()
    val retenues = inventaire.filter { !it.isDir && it.path in epingles }
    return classer(retenues, recherche = "", tri = tri)
}

/**
 * Une ligne de la rubrique « Épinglées » du tiroir, déjà mise en forme : le
 * tiroir n'a qu'à la dessiner.
 */
data class EpingleeTiroir(
    /** Chemin de la note, à ouvrir tel quel. */
    val chemin: String,
    /** Nom affiché, sans extension Markdown. */
    val titre: String,
    /** Dossier de la note, ou le nom du dossier de notes pour une note à sa racine. */
    val dossier: String,
)

/**
 * Met la rubrique en forme pour le tiroir. Le dossier se lit dans le chemin,
 * dont il est le préfixe ; une note à la racine prend le nom du dossier de
 * notes plutôt qu'un sous-titre vide, pour que toutes les lignes aient la
 * même hauteur.
 */
fun lignesTiroir(section: List<FolderEntryDto>, nomRacine: String): List<EpingleeTiroir> =
    section.map { note ->
        EpingleeTiroir(
            chemin = note.path,
            titre = note.display,
            dossier = note.path.substringBeforeLast('/', "").ifBlank { nomRacine },
        )
    }
