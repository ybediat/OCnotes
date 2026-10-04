package eu.ocnotes.ui.editor

import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.EditText

/**
 * Span posé par [colorerCode] et par lui seul : c'est ce qui permet de le
 * retirer sans toucher aux spans que le système ou le clavier posent sur le
 * même `Editable` (correcteur, composition).
 */
internal class SpanCode(couleur: Int) : ForegroundColorSpan(couleur)

/**
 * Repère, dans `[debut, fin)`, ce qui est du *code* Markdown ou HTML plutôt que
 * du texte : marques de titre, de liste et de citation, `*` `_` `~~` `` ` ``,
 * lien `](…)`, entité (`&nbsp;`) et balise (`<br>`). [plage] reçoit des
 * `[debut, fin)` en offsets UTF-16, dans l'ordre, jamais chevauchés ; deux
 * plages contiguës sont fusionnées.
 *
 * **C'est une approximation, pas un analyseur.** Elle lit une ligne à la fois,
 * sans contexte : un bloc de code clôturé n'est pas reconnu comme tel au-delà de
 * ses lignes de clôture. Elle ne sert qu'à *signaler* — la source n'est jamais
 * modifiée —, donc une erreur coûte une couleur de trop, jamais une donnée. La
 * vraie analyse reste celle de `markdown.Render`, côté Go.
 *
 * Linéaire en la taille de la fenêtre : un mot démesuré n'y coûte pas plus
 * qu'un autre.
 */
internal fun plagesDeCode(
    texte: CharSequence,
    debut: Int,
    fin: Int,
    plage: (Int, Int) -> Unit,
) {
    val bas = debut.coerceIn(0, texte.length)
    val haut = fin.coerceIn(bas, texte.length)
    var dernierDebut = -1
    var derniereFin = -1

    fun emettre(d: Int, f: Int) {
        if (f <= d) return
        if (d == derniereFin) {
            derniereFin = f
            return
        }
        if (dernierDebut >= 0) plage(dernierDebut, derniereFin)
        dernierDebut = d
        derniereFin = f
    }

    var ligne = bas
    while (ligne < haut) {
        var finLigne = ligne
        while (finLigne < haut && texte[finLigne] != '\n') finLigne++
        analyserLigne(texte, ligne, finLigne, ::emettre)
        ligne = finLigne + 1
    }
    if (dernierDebut >= 0) plage(dernierDebut, derniereFin)
}

private fun analyserLigne(
    texte: CharSequence,
    debut: Int,
    fin: Int,
    emettre: (Int, Int) -> Unit,
) {
    var i = debut
    while (i < fin && i - debut < 3 && texte[i] == ' ') i++

    // Clôture de bloc de code : toute la ligne est du code.
    if (i + 2 < fin &&
        (texte[i] == '`' || texte[i] == '~') &&
        texte[i + 1] == texte[i] && texte[i + 2] == texte[i]
    ) {
        emettre(i, fin)
        return
    }

    i = marqueDeLigne(texte, i, fin, emettre)

    while (i < fin) {
        val c = texte[i]
        when {
            c == '*' || c == '`' || c == '~' -> {
                val d = i
                while (i < fin && texte[i] == c) i++
                // Un `~` seul n'est pas du Markdown : « ~5 minutes ».
                if (c != '~' || i - d >= 2) emettre(d, i)
            }

            c == '_' -> {
                val d = i
                while (i < fin && texte[i] == '_') i++
                if (!estLettreOuChiffre(texte, d - 1, debut, fin) ||
                    !estLettreOuChiffre(texte, i, debut, fin)
                ) {
                    emettre(d, i)
                }
            }

            c == '&' -> {
                val f = finEntite(texte, i, fin)
                if (f > i) {
                    emettre(i, f)
                    i = f
                } else {
                    i++
                }
            }

            c == '<' -> {
                val f = finBalise(texte, i, fin)
                if (f > i) {
                    emettre(i, f)
                    i = f
                } else {
                    i++
                }
            }

            c == '!' && i + 1 < fin && texte[i + 1] == '[' -> {
                emettre(i, i + 2)
                i += 2
            }

            c == '[' -> {
                emettre(i, i + 1)
                i++
            }

            c == ']' -> {
                // `](cible)` : la cible est ce qu'on ne lit pas dans l'aperçu.
                val f = if (i + 1 < fin && texte[i + 1] == '(') finCible(texte, i + 1, fin) else i + 1
                emettre(i, f)
                i = f
            }

            else -> i++
        }
    }
}

/** Marque de début de ligne ; renvoie la position où reprend le texte. */
private fun marqueDeLigne(
    texte: CharSequence,
    debut: Int,
    fin: Int,
    emettre: (Int, Int) -> Unit,
): Int {
    var i = debut
    // Citations imbriquées : `> > texte`.
    while (i < fin && texte[i] == '>') {
        i++
        emettre(i - 1, i)
        while (i < fin && texte[i] == ' ') i++
    }
    if (i >= fin) return i

    val c = texte[i]
    var f = i
    when {
        c == '#' -> {
            while (f < fin && texte[f] == '#') f++
            if (f - i > 6 || (f < fin && texte[f] != ' ')) return i
        }

        c == '-' || c == '*' || c == '+' -> {
            f = i + 1
            if (f < fin && texte[f] != ' ') return i
        }

        c in '0'..'9' -> {
            while (f < fin && f - i < 9 && texte[f] in '0'..'9') f++
            if (f >= fin || (texte[f] != '.' && texte[f] != ')')) return i
            f++
            if (f < fin && texte[f] != ' ') return i
        }

        else -> return i
    }
    emettre(i, f)
    // Case à cocher d'une liste de tâches : `[ ]`, `[x]`.
    var suite = f
    while (suite < fin && texte[suite] == ' ') suite++
    if (suite + 2 < fin && texte[suite] == '[' && texte[suite + 2] == ']' &&
        (texte[suite + 1] == ' ' || texte[suite + 1] == 'x' || texte[suite + 1] == 'X')
    ) {
        emettre(suite, suite + 3)
        return suite + 3
    }
    return f
}

private fun estLettreOuChiffre(texte: CharSequence, position: Int, debut: Int, fin: Int): Boolean =
    position in debut until fin && texte[position].isLetterOrDigit()

/** `&nbsp;`, `&#160;`, `&#xA0;` : renvoie la fin de l'entité, ou [debut] si ce n'en est pas une. */
private fun finEntite(texte: CharSequence, debut: Int, fin: Int): Int {
    var i = debut + 1
    val limite = minOf(fin, debut + 33)
    while (i < limite && (texte[i].isLetterOrDigit() || (i == debut + 1 && texte[i] == '#'))) i++
    return if (i > debut + 1 && i < fin && texte[i] == ';') i + 1 else debut
}

/** `<br>`, `</div>`, `<!-- … -->` sur une seule ligne ; sinon [debut]. */
private fun finBalise(texte: CharSequence, debut: Int, fin: Int): Int {
    val suivant = debut + 1
    if (suivant >= fin) return debut
    val c = texte[suivant]
    if (!(c.isLetter() || c == '/' || c == '!')) return debut
    var i = suivant
    while (i < fin && texte[i] != '>') i++
    return if (i < fin) i + 1 else debut
}

/** `(cible)` : renvoie la fin, parenthèses emboîtées comprises, ou la position après `]`. */
private fun finCible(texte: CharSequence, ouvrante: Int, fin: Int): Int {
    var profondeur = 0
    var i = ouvrante
    while (i < fin) {
        when (texte[i]) {
            '(' -> profondeur++
            ')' -> {
                profondeur--
                if (profondeur == 0) return i + 1
            }
        }
        i++
    }
    return ouvrante
}

/**
 * Pose la coloration sur la fenêtre `[debut, fin)`, étendue aux lignes entières,
 * après avoir retiré celle qui s'y trouvait. Ne touche pas au texte : ni
 * sélection, ni défilement, ni pile d'annulation. Renvoie le nombre de spans
 * posés.
 */
internal fun colorerCode(editable: Editable, debut: Int, fin: Int, couleur: Int): Int {
    var bas = debut.coerceIn(0, editable.length)
    var haut = fin.coerceIn(bas, editable.length)
    while (bas > 0 && editable[bas - 1] != '\n') bas--
    while (haut < editable.length && editable[haut] != '\n') haut++

    // Aucune plage ne franchit un saut de ligne : la fenêtre, étendue aux lignes
    // entières, contient donc tout span qu'elle touche.
    for (ancien in editable.getSpans(bas, haut, SpanCode::class.java)) {
        editable.removeSpan(ancien)
    }
    var poses = 0
    plagesDeCode(editable, bas, haut) { d, f ->
        editable.setSpan(SpanCode(couleur), d, f, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        poses++
    }
    return poses
}

/**
 * Garde la coloration sur ce qui se voit, et seulement cela.
 *
 * **Colorer toute la note ne tient pas** (mesuré le 4 octobre 2026, 295 ko,
 * Redmi Note 12) : `setSpan` est quadratique — 124 ms pour 2 700 spans, 5,7 s
 * pour 19 000 —, et chaque span de plus alourdit le dessin, puisque le champ
 * ré-enregistre le document entier à chaque image. Quelques centaines de spans
 * sur la fenêtre visible coûtent des fractions de milliseconde.
 *
 * La fenêtre colorée est l'écran plus un écran de chaque côté ; elle ne se
 * recalcule que lorsque l'écran s'approche de son bord, pour qu'un défilement
 * continu ne relance pas l'analyse à chaque image.
 */
internal class ColorationFenetre(
    private val vue: EditText,
    private var couleur: Int,
    private val onColoration: (spans: Int, ms: Double) -> Unit = { _, _ -> },
) {
    /** Fenêtre actuellement colorée, `[debut, fin)` ; vide tant que `fin <= debut`. */
    private var debut = 0
    private var fin = 0

    /** Recale la fenêtre sur l'écran. [force] la recolore même si elle couvre déjà l'écran. */
    fun suivre(force: Boolean = false) {
        val layout = vue.layout ?: return
        val editable = vue.text ?: return
        if (layout.lineCount == 0) return

        val haut = vue.scrollY
        val bas = haut + vue.height
        val premiere = layout.getLineForVertical(haut)
        val derniere = layout.getLineForVertical(bas)
        val ecran = derniere - premiere + 1
        if (!force && couvre(layout, editable.length, premiere, derniere, ecran / 2)) return

        val dernierIndice = layout.lineCount - 1
        recolorer(
            editable,
            layout.getLineStart((premiere - ecran).coerceAtLeast(0)),
            layout.getLineEnd((derniere + ecran).coerceAtMost(dernierIndice)),
        )
    }

    /** La fenêtre colorée couvre-t-elle l'écran, avec au moins [marge] lignes de chaque côté ? */
    private fun couvre(layout: Layout, longueur: Int, premiere: Int, derniere: Int, marge: Int): Boolean {
        if (fin <= debut) return false
        val interieurDebut = layout.getLineStart((premiere - marge).coerceAtLeast(0))
        val interieurFin = layout.getLineEnd((derniere + marge).coerceAtMost(layout.lineCount - 1))
        return (debut <= interieurDebut || debut == 0) && (fin >= interieurFin || fin >= longueur)
    }

    /** À appeler pour chaque modification : décale la fenêtre et relit les lignes touchées. */
    fun apresModification(editable: Editable, depart: Int, avant: Int, apres: Int) {
        if (fin <= debut) return
        val delta = apres - avant
        when {
            depart + avant <= debut -> {
                debut += delta
                fin += delta
            }

            depart >= fin -> return
            else -> fin += delta
        }
        debut = debut.coerceIn(0, editable.length)
        fin = fin.coerceIn(debut, editable.length)
        mesurer { colorerCode(editable, depart, depart + apres, couleur) }
    }

    fun changerCouleur(nouvelle: Int) {
        if (nouvelle == couleur) return
        couleur = nouvelle
        suivre(force = true)
    }

    private fun recolorer(editable: Editable, a: Int, b: Int) {
        debut = a
        fin = b
        mesurer {
            for (ancien in editable.getSpans(0, editable.length, SpanCode::class.java)) {
                editable.removeSpan(ancien)
            }
            colorerCode(editable, a, b, couleur)
        }
    }

    private inline fun mesurer(travail: () -> Int) {
        val t0 = System.nanoTime()
        val poses = travail()
        onColoration(poses, (System.nanoTime() - t0) / 1_000_000.0)
    }
}
