package eu.ocnotes.ui.editor

/**
 * Mises en forme déjà présentes autour de la sélection : les boutons
 * correspondants sont surlignés dans la barre.
 *
 * **Un bouton n'est surligné que si l'appuyer retire la mise en forme.** Les
 * règles recopient donc celles du cœur Go (`internal/markdown/format.go`) :
 * `wrap` + `enclosingSpan` pour le texte en ligne, `linePrefix` pour les
 * lignes, `codeBlock` pour le bloc de code. Toute évolution d'un côté doit
 * être reportée de l'autre.
 *
 * Appelée à chaque mouvement du curseur, sur le thread principal : elle ne lit
 * que les lignes de la sélection, sans copier le document.
 */
internal fun misesEnFormeActives(texte: CharSequence, debut: Int, fin: Int): Set<String> {
    val bas = minOf(debut, fin).coerceIn(0, texte.length)
    val haut = maxOf(debut, fin).coerceIn(0, texte.length)
    val actives = mutableSetOf<String>()

    // Texte en ligne.
    for ((id, marqueur) in MARQUEURS_EN_LIGNE) {
        // Le code en ligne garde ses espaces de bord, comme côté Go.
        val (b, h) = if (id == "code") bas to haut else rognerBords(texte, bas, haut)
        if (enLigneActive(texte, b, h, marqueur)) actives += id
    }

    // Lignes touchées par la sélection, comme `selectedLines` côté Go.
    val lignes = lignesSelectionnees(texte, bas, haut) ?: return actives

    // Préfixes de ligne : actif si toutes les lignes non vides le portent.
    val prefixes = lignes.map { (d, f) -> prefixeDe(texte.subSequence(d, f).toString()) }
    val significatifs = prefixes.filterNotNull()
    if (significatifs.isNotEmpty()) {
        for ((id, prefixe) in PREFIXES_LIGNE) {
            if (significatifs.all { it == prefixe }) actives += id
        }
        if (suiteNumerotee(prefixes)) actives += "numbered"
    }

    // Bloc de code : délimiteur juste au-dessus et juste en dessous.
    val (premierDebut, _) = lignes.first()
    val (_, dernierFin) = lignes.last()
    if (premierDebut > 0 && dernierFin < texte.length &&
        estDelimiteur(texte, debutLigne(texte, premierDebut - 1), premierDebut - 1) &&
        estDelimiteur(texte, dernierFin + 1, finLigne(texte, dernierFin + 1))
    ) {
        actives += "codeblock"
    }

    return actives
}

/** Identifiant d'action → marqueur, pour les mises en forme en ligne. */
private val MARQUEURS_EN_LIGNE = listOf(
    "bold" to "**",
    "italic" to "*",
    "strikethrough" to "~~",
    "code" to "`",
)

/** Identifiant d'action → préfixe exact posé par le cœur Go. */
private val PREFIXES_LIGNE = listOf(
    "h1" to "# ",
    "h2" to "## ",
    "h3" to "### ",
    "bullet" to "- ",
    "task" to "- [ ] ",
    "quote" to "> ",
)

/**
 * Même expression que `linePrefixRe` côté Go : indentation puis préfixe de
 * style. Un numéro a au plus neuf chiffres et `.` ou `)` pour délimiteur.
 */
private val PREFIXE = Regex("""^([ \t]*)((?:#{1,6} )|(?:[-*+] \[[ xX]\] )|(?:[-*+] )|(?:\d{1,9}[.)] )|(?:> ))?""")

/** Préfixe numéroté découpé en numéro et délimiteur (`parseNumbered` côté Go). */
private val NUMERO = Regex("""(\d{1,9})([.)]) """)

/** Au-delà, la sélection couvre une bonne part de la note : on ne surligne rien. */
private const val LIGNES_MAX = 200

/**
 * Préfixe de style d'une ligne : "" s'il n'y en a pas, `null` si la ligne est
 * vide (une ligne vide ne décide pas de la bascule, comme côté Go).
 */
private fun prefixeDe(ligne: String): String? {
    val m = PREFIXE.find(ligne) ?: return null
    val prefixe = m.groupValues[2]
    val reste = ligne.substring(m.value.length)
    return if (prefixe.isEmpty() && reste.isEmpty()) null else prefixe
}

/**
 * Les lignes forment-elles déjà une suite numérotée (`numberedRun` côté Go) ?
 * C'est le seul cas où le bouton retire au lieu de renuméroter.
 *
 * Le numéro attendu suit le rang de la ligne, lignes vides comprises, à partir
 * du premier numéro rencontré ; le délimiteur reste le même d'un bout à
 * l'autre. « 1. a », « 3. b » est renuméroté, pas retiré.
 */
private fun suiteNumerotee(prefixes: List<String?>): Boolean {
    var base = 0
    var delimiteur = ' '
    var significatives = 0
    for ((rang, prefixe) in prefixes.withIndex()) {
        // Ligne vide : ne décide rien, mais compte dans le rang.
        if (prefixe == null) continue
        val m = NUMERO.matchEntire(prefixe) ?: return false
        val numero = m.groupValues[1].toInt()
        val delim = m.groupValues[2][0]
        if (significatives == 0) {
            base = numero - rang
            delimiteur = delim
        } else if (numero != base + rang || delim != delimiteur) {
            return false
        }
        significatives++
    }
    return significatives > 0
}

/** Bornes `[début, fin)` des lignes touchées, ou `null` si elles sont trop nombreuses. */
private fun lignesSelectionnees(texte: CharSequence, bas: Int, haut: Int): List<Pair<Int, Int>>? {
    val lignes = mutableListOf<Pair<Int, Int>>()
    var d = debutLigne(texte, bas)
    while (true) {
        val f = finLigne(texte, d)
        lignes += d to f
        if (lignes.size > LIGNES_MAX) return null
        // Une sélection qui s'arrête au tout début d'une ligne ne la prend pas.
        if (f >= haut || f >= texte.length || (f + 1 == haut && bas != haut)) break
        d = f + 1
    }
    return lignes
}

private fun estDelimiteur(texte: CharSequence, debut: Int, fin: Int): Boolean =
    texte.subSequence(debut, fin).trim().startsWith("```")

/**
 * Sélection privée de ses blancs de bord (`rogneBords` côté Go) :
 * « **printemps** » sélectionné avec l'espace ou le saut de ligne qui suit
 * reste du gras.
 */
private fun rognerBords(texte: CharSequence, bas: Int, haut: Int): Pair<Int, Int> {
    var b = bas
    var h = haut
    while (b < h && estBordRognable(texte[b])) b++
    while (h > b && estBordRognable(texte[h - 1])) h--
    return if (b == h) bas to haut else b to h
}

/**
 * Blancs retirés d'un bord de sélection (`bordRognable` côté Go) : plus large
 * qu'[estBlanc], qui reste la règle de [paireEnglobante]. Sauts de ligne et
 * espaces insécables (U+00A0, U+202F) compris.
 */
private fun estBordRognable(c: Char) =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == ' '

/** Équivalent de `wrap` : marqueurs dans la sélection, autour, ou plus loin sur la ligne. */
private fun enLigneActive(texte: CharSequence, bas: Int, haut: Int, marqueur: String): Boolean {
    val n = marqueur.length
    if (haut - bas >= 2 * n && marqueurA(texte, bas, marqueur, +1) && marqueurA(texte, haut - n, marqueur, -1)) {
        return true
    }
    return paireEnglobante(texte, bas, haut, marqueur)
}

/**
 * Le marqueur commence à [position] et n'appartient pas à une suite plus
 * longue du même caractère (`markerAt` côté Go).
 */
private fun marqueurA(texte: CharSequence, position: Int, marqueur: String, sens: Int): Boolean {
    if (position < 0 || position + marqueur.length > texte.length) return false
    for (i in marqueur.indices) if (texte[position + i] != marqueur[i]) return false
    val c = marqueur[0]
    return if (sens > 0) {
        val suivant = position + marqueur.length
        suivant >= texte.length || texte[suivant] != c
    } else {
        position == 0 || texte[position - 1] != c
    }
}

/**
 * Une paire de marqueurs entoure-t-elle la sélection sur sa ligne ?
 * Recopie de `enclosingSpan` côté Go : appariement de gauche à droite, un
 * marqueur n'ouvre que suivi d'un non-blanc et ne ferme que précédé d'un
 * non-blanc, et une suite plus longue du même caractère ne compte pas.
 */
private fun paireEnglobante(texte: CharSequence, bas: Int, haut: Int, marqueur: String): Boolean {
    val n = marqueur.length
    val c = marqueur[0]
    val debut = debutLigne(texte, bas)
    val fin = finLigne(texte, bas)
    if (haut > fin) return false

    var ouverture = -1
    var i = debut
    while (i < fin) {
        if (texte[i] != c) {
            i++
            continue
        }
        var suite = i
        while (suite < fin && texte[suite] == c) suite++
        if (suite - i == n) {
            if (ouverture < 0) {
                if (suite < fin && !estBlanc(texte[suite])) ouverture = i
            } else if (!estBlanc(texte[i - 1])) {
                if (ouverture + n <= bas && haut <= i) return true
                ouverture = -1
            }
        }
        i = suite
    }
    return false
}

private fun estBlanc(c: Char) = c == ' ' || c == '\t'

private fun debutLigne(texte: CharSequence, position: Int): Int {
    var p = position
    while (p > 0 && texte[p - 1] != '\n') p--
    return p
}

private fun finLigne(texte: CharSequence, position: Int): Int {
    var p = position
    while (p < texte.length && texte[p] != '\n') p++
    return p
}
