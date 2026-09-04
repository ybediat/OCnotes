package eu.ocnotes.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Un texte destiné à l'écran, décrit mais pas encore rédigé.
 *
 * # Pourquoi ce détour
 *
 * Un ViewModel produit des messages — « 3 notes envoyées », « Identifiants
 * refusés » — mais n'a pas de `Context`, donc pas d'accès aux ressources.
 * Deux façons de s'en sortir, et une seule tient :
 *
 *  - **lui donner un `Context`** : le ViewModel devient intestable sans
 *    Robolectric, et surtout la langue est **figée au moment de l'émission**.
 *    Un `StateFlow` qui porte déjà « Tout est à jour » ne repasse pas en
 *    anglais parce que l'utilisateur a changé la langue de l'appareil ;
 *  - **différer la rédaction**, ce que fait ce type. Le ViewModel décrit
 *    (`R.string.sync_a_jour`), le composable rédige à chaque recomposition.
 *    La langue suit toujours, et le test du ViewModel porte sur l'identifiant
 *    plutôt que sur une phrase française qu'il faudrait recopier.
 *
 * # Ce qu'on y met
 *
 * [Brut] existe pour les textes qui ne sont pas des ressources : un nom de
 * note, un message d'erreur Go dont le code n'a pas encore de formulation. Il
 * n'est pas une porte de sortie pour du français en dur — le test
 * `ChainesEnDurTest` refuse les littéraux dans les écrans.
 */
@Immutable
sealed interface Texte {

    /** Texte déjà rédigé ailleurs : nom de fichier, repli d'erreur. */
    @Immutable
    data class Brut(val valeur: String) : Texte

    /** Ressource `<string>`, avec ses éventuels paramètres de format. */
    @Immutable
    data class Ressource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : Texte

    /**
     * Ressource `<plurals>`.
     *
     * [quantite] sert deux fois : à choisir la forme, et comme **premier
     * paramètre de format**. C'est ce que veulent toutes les formulations de
     * ce genre (« %d notes envoyées »), et l'oublier est l'erreur classique.
     * Les formes en trop sont ignorées par `String.format`, donc une
     * formulation sans `%d` reste correcte.
     *
     * Ne jamais retomber sur un `if (n > 1)` : le nombre de formes dépend de
     * la langue — trois en polonais, six en arabe — et cette décision
     * appartient au fichier de ressources, pas au code.
     */
    @Immutable
    data class Pluriel(
        @PluralsRes val id: Int,
        val quantite: Int,
        val args: List<Any> = emptyList(),
    ) : Texte

    /**
     * Énumération de morceaux, joints par un séparateur lui-même localisé.
     *
     * Assembler une phrase par concaténation reste une approximation — l'ordre
     * des propositions n'est pas universel. C'est un compromis assumé pour le
     * résumé de synchronisation, qui est une liste et pas une phrase.
     */
    @Immutable
    data class Liste(
        val morceaux: List<Texte>,
        @StringRes val separateur: Int,
    ) : Texte

    companion object {
        fun de(@StringRes id: Int, vararg args: Any): Texte = Ressource(id, args.toList())

        fun pluriel(@PluralsRes id: Int, quantite: Int, vararg args: Any): Texte =
            Pluriel(id, quantite, args.toList())

        fun brut(valeur: String): Texte = Brut(valeur)
    }
}

/**
 * Rédige le texte à partir de ressources données.
 *
 * Utilisée hors composition — une notification, par exemple, qui a un contexte
 * et rédige légitimement au moment où elle est postée.
 */
fun Texte.resoudre(res: Resources): String = when (this) {
    is Texte.Brut -> valeur
    is Texte.Ressource -> res.getString(id, *args.rediges(res))
    is Texte.Pluriel -> res.getQuantityString(id, quantite, quantite, *args.rediges(res))
    is Texte.Liste -> morceaux.joinToString(res.getString(separateur)) { it.resoudre(res) }
}

/**
 * Rédige le texte dans la langue courante de l'affichage.
 *
 * La lecture de `LocalConfiguration` n'est pas un oubli : elle abonne le
 * composable à la configuration, donc à la langue. Sans elle, un changement de
 * langue laisserait à l'écran un texte rédigé dans l'ancienne. `stringResource`
 * fait exactement la même chose en interne.
 */
@Composable
fun Texte.resoudre(): String {
    LocalConfiguration.current
    return resoudre(LocalContext.current.resources)
}

/** Un paramètre peut lui-même être un [Texte] : il est rédigé d'abord. */
private fun List<Any>.rediges(res: Resources): Array<Any> =
    Array(size) { i -> this[i].let { if (it is Texte) it.resoudre(res) else it } }
