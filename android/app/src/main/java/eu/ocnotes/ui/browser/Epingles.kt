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
 * Jeton remis au lancement d'une lecture d'inventaire : il dit **quand** la
 * requête est partie, et après quel geste local.
 */
data class JetonInventaire(
    /** Compteur de gestes locaux au moment du lancement. */
    val generation: Long,
    /** Instant du lancement, en millisecondes d'une horloge monotone. */
    val debut: Long,
)

/**
 * Décide, inventaire après inventaire, quelles épingles absentes retirer.
 *
 * Un inventaire « venu du serveur » n'est pas une preuve d'absence, pour
 * trois raisons constatées :
 *
 * 1. le service de recherche retarde d'environ 1,3 s sur une écriture : juste
 *    après un renommage, il ne connaît pas encore le nouveau nom ;
 * 2. le parcours de repli saute en silence un sous-dossier qui n'a pas
 *    répondu : l'inventaire est alors incomplet ;
 * 3. une lecture lancée **avant** un renommage peut arriver **après** lui.
 *
 * D'où deux règles :
 *
 * - un inventaire lancé avant le dernier geste local (renommage, déplacement,
 *   suppression) n'élague rien : il décrit un état dépassé ;
 * - une épingle absente n'est que **suspecte** au premier inventaire. Elle
 *   n'est retirée que si elle manque encore à un inventaire lancé au moins
 *   [delaiMs] plus tard. Revue entre-temps, elle redevient insoupçonnable.
 *
 * Les suspicions vivent en mémoire : après un redémarrage, il faut de nouveau
 * deux inventaires. Mieux vaut une épingle orpheline masquée un peu plus
 * longtemps qu'une épingle valide effacée.
 */
class ElagageEpingles(private val delaiMs: Long = DELAI_CONFIRMATION_MS) {

    private var generation = 0L

    /** Chemin suspect → lancement de l'inventaire où il manquait pour la première fois. */
    private val suspectes = HashMap<String, Long>()

    /** À appeler à chaque renommage, déplacement ou suppression réussi. */
    fun gesteLocal() {
        generation++
    }

    /** Jeton à prendre **juste avant** de lancer la lecture de l'inventaire. */
    fun jeton(maintenant: Long): JetonInventaire = JetonInventaire(generation, maintenant)

    /** Vrai si aucun geste local n'a eu lieu depuis la prise du jeton. */
    fun estAJour(jeton: JetonInventaire): Boolean = jeton.generation == generation

    /**
     * Rend les épingles à garder après l'inventaire [connues], lu avec
     * [jeton]. Ne retire que les épingles absentes deux fois, à [delaiMs]
     * d'écart au moins, et rien du tout si l'inventaire est dépassé.
     */
    fun appliquer(
        epingles: Set<String>,
        connues: Set<String>,
        jeton: JetonInventaire,
    ): Set<String> {
        if (!estAJour(jeton)) return epingles
        // Une épingle retirée entre-temps n'a plus à être surveillée.
        suspectes.keys.retainAll(epingles)
        val gardees = LinkedHashSet<String>()
        for (chemin in epingles) {
            if (chemin in connues) {
                suspectes.remove(chemin)
                gardees += chemin
                continue
            }
            val depuis = suspectes[chemin]
            when {
                depuis == null -> {
                    suspectes[chemin] = jeton.debut
                    gardees += chemin
                }
                jeton.debut - depuis >= delaiMs -> suspectes.remove(chemin)
                else -> gardees += chemin
            }
        }
        return gardees
    }

    companion object {
        /**
         * Écart minimal entre les deux constats d'absence : largement plus que
         * le retard de l'index de recherche, et assez pour qu'un sous-dossier
         * qui n'a pas répondu ait eu le temps de revenir.
         */
        const val DELAI_CONFIRMATION_MS = 10_000L
    }
}

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
