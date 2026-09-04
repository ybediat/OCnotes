package eu.ocnotes

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * Refuse les textes d'interface écrits en dur dans le code Kotlin.
 *
 * # Pourquoi un test, et pas lint
 *
 * `HardcodedText`, la règle de lint prévue pour ça, ne lit que les
 * dispositions XML. Compose n'a pas de disposition XML : la règle ne voit
 * rien, et rien n'empêcherait un écran ajouté demain de repartir avec ses
 * phrases en dur. Ce test tient ce rôle, comme `mobile/gomobile_test.go` tient
 * celui du NDK absent : la contrainte est vérifiée à chaque `test`, sans outil
 * supplémentaire.
 *
 * # Ce qu'il détecte, et ce qu'il laisse passer
 *
 * Une « phrase » est ici un littéral d'au moins deux mots de trois lettres ou
 * plus. C'est volontairement grossier : cette règle n'a aucun faux positif sur
 * le dépôt actuel, et un garde-fou bruyant finit désactivé.
 *
 * Elle laisse donc passer les libellés d'un seul mot — « Annuler », « Créer ».
 * Ceux-là se voient autrement : en lançant l'application sous la pseudo-langue
 * `en-XA` des options développeur, où tout ce qui est traduit s'affiche
 * accentué et allongé. Ce qui reste lisible n'est pas traduit.
 *
 * Un littéral volontairement en dur — gabarit Markdown, clé technique — se
 * marque d'un `i18n-ok` en commentaire sur la même ligne.
 */
class ChainesEnDurTest {

    @Test
    fun aucuneNouvelleChaineEnDur() {
        val fautifs = mutableListOf<String>()

        for (fichier in sources()) {
            val chemin = cheminRelatif(fichier)
            if (chemin in HORS_INTERFACE || chemin in ECRANS_A_MIGRER) continue
            phrases(fichier).forEach { fautifs += chemin + " : " + it }
        }

        if (fautifs.isNotEmpty()) {
            fail(
                buildString {
                    append("Texte d'interface écrit en dur. À sortir vers strings.xml, ")
                    append("puis à lire avec stringResource (composable) ou Texte (ViewModel) :")
                    fautifs.forEach { append('\n').append("  ").append(it) }
                },
            )
        }
    }

    /**
     * La liste de migration doit rester honnête.
     *
     * Un fichier qui n'a plus de phrase en dur mais reste listé rend le
     * garde-fou aveugle pour ce fichier : la première régression y passerait
     * inaperçue. Le test échoue donc aussi dans ce sens-là.
     */
    @Test
    fun listeDeMigrationAJour() {
        val fichiers = sources().associateBy { cheminRelatif(it) }

        val inconnus = (ECRANS_A_MIGRER + HORS_INTERFACE) - fichiers.keys
        if (inconnus.isNotEmpty()) {
            fail("Fichiers listés mais absents du dépôt : $inconnus")
        }

        val propres = ECRANS_A_MIGRER.filter { phrases(fichiers.getValue(it)).isEmpty() }
        if (propres.isNotEmpty()) {
            fail("Ces fichiers sont migrés : les retirer d'ECRANS_A_MIGRER : $propres")
        }
    }

    // --- Mécanique ---------------------------------------------------------

    /**
     * Retrouve `src/main/java` sans dépendre du dossier de travail.
     *
     * Gradle lance les tests depuis le dossier du module, mais un lancement
     * depuis l'IDE ou depuis la racine du dépôt part d'ailleurs : on remonte
     * plutôt que de supposer.
     */
    private fun racineDesSources(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            File(dossier, "src/main/java").let { if (it.isDirectory) return it }
            File(dossier, "android/app/src/main/java").let { if (it.isDirectory) return it }
            dossier = dossier.parentFile
        }
        error("sources Kotlin introuvables depuis " + File("").absolutePath)
    }

    private fun sources(): List<File> =
        racineDesSources().walkTopDown()
            .filter { it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun cheminRelatif(fichier: File): String =
        fichier.path.replace('\\', '/').substringAfter("eu/ocnotes/")

    /** Phrases d'un fichier, lignes marquées `i18n-ok` exclues. */
    private fun phrases(fichier: File): List<String> {
        val source = fichier.readText()
        val ignorees = lignesMarquees(source)
        return litteraux(source)
            .filterNot { it.ligne in ignorees }
            .map { it.valeur }
            .filter { estPhrase(it) }
    }

    private fun lignesMarquees(source: String): Set<Int> =
        source.lines()
            .mapIndexedNotNull { i, ligne -> if (ligne.contains("i18n-ok")) i + 1 else null }
            .toSet()

    private data class Litteral(val valeur: String, val ligne: Int)

    /**
     * Littéraux de chaîne d'un source Kotlin, commentaires exclus.
     *
     * Le parcours doit distinguer un `//` de commentaire d'un `//` situé dans
     * une chaîne — sans quoi une URL ferait disparaître la fin du fichier de
     * l'analyse, et le garde-fou deviendrait silencieux sans le dire.
     */
    private fun litteraux(source: String): List<Litteral> {
        val out = mutableListOf<Litteral>()
        val n = source.length
        var i = 0
        var ligne = 1

        fun avanceJusqua(cible: Int) {
            val fin = minOf(cible, n)
            for (k in i until fin) if (source[k] == '\n') ligne++
            i = fin
        }

        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val j = source.indexOf('\n', i)
                    avanceJusqua(if (j < 0) n else j)
                }

                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val j = source.indexOf("*/", i + 2)
                    avanceJusqua(if (j < 0) n else j + 2)
                }

                source.startsWith(TRIPLE, i) -> {
                    val debut = ligne
                    val j = source.indexOf(TRIPLE, i + 3)
                    val fin = if (j < 0) n else j
                    out += Litteral(source.substring(i + 3, fin), debut)
                    avanceJusqua(if (j < 0) n else j + 3)
                }

                c == '"' -> {
                    val debut = ligne
                    val texte = StringBuilder()
                    var j = i + 1
                    while (j < n && source[j] != '"') {
                        if (source[j] == '\\' && j + 1 < n) {
                            texte.append(source[j + 1])
                            j += 2
                        } else {
                            texte.append(source[j])
                            j++
                        }
                    }
                    out += Litteral(texte.toString(), debut)
                    avanceJusqua(j + 1)
                }

                else -> avanceJusqua(i + 1)
            }
        }
        return out
    }

    /** Au moins deux mots de trois lettres ou plus. */
    private fun estPhrase(texte: String): Boolean =
        texte.split(' ', '\n', '\t').count { mot -> mot.count { it.isLetter() } >= 3 } >= 2

    private companion object {
        const val TRIPLE = "\"\"\""

        /**
         * Écrans dont les textes n'ont pas encore été sortis vers
         * `strings.xml`.
         *
         * **Elle est vide, et doit le rester.** Ce n'est pas un endroit où
         * ranger un écran neuf : y ajouter une ligne, c'est éteindre le
         * garde-fou là où il servirait le plus. Elle survit à la migration
         * parce que le jour où un écran arrivera avec ses phrases en dur, la
         * tentation sera de l'y inscrire — et le second test ci-dessus,
         * `listeDeMigrationAJour`, refusera qu'elle serve de tiroir.
         */
        val ECRANS_A_MIGRER = emptySet<String>()

        /**
         * Fichiers dont les chaînes ne s'affichent jamais : journalisation.
         *
         * Contrairement à la liste ci-dessus, celle-ci n'a pas vocation à se
         * vider — un message de `Log.w` n'a pas de lecteur à ménager.
         */
        val HORS_INTERFACE = setOf(
            "sync/SyncWorker.kt",
        )
    }
}
