package eu.ocnotes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Copie de notes vers un autre profil (menu « ⋮ » d'une note et copie groupée).
 *
 * Le profil cible est un faux en mémoire : on vérifie la logique de
 * [copierNotes] (relecture, noms refusés, erreurs, synchronisation) sans cœur
 * Go. Le verrou du profil et la cible disparue restent dans OCnotesApplication.
 */
class CopieEntreComptesTest {

    /** Profil cible en mémoire, qui imite les réponses du cœur Go. */
    private class FausseCible : CibleCopie {
        /** Notes créées : chemin → texte. */
        val notes = linkedMapOf<String, String>()

        /** Noms que la création refuse avec une erreur NAME_. */
        val nomsRefuses = mutableSetOf<String>()

        /** Assainissement de nom renvoyé par suggestName. */
        var assainir: (String) -> String = { it.replace(":", "-") }

        /** Erreur levée par createNote pour ce nom, autre qu'un refus de nom. */
        val erreurs = mutableMapOf<String, Exception>()

        /** Altère le texte relu, pour simuler une copie abîmée. */
        var alterer: (String) -> String = { it }

        override suspend fun createNote(dir: String, name: String, content: String): NoteRefDto {
            erreurs[name]?.let { throw it }
            if (name in nomsRefuses) {
                throw OCnotesException(ErrorCategory.LOCAL, "NAME_INVALID", "nom refusé")
            }
            // Comme le cœur : un nom pris reçoit un suffixe, rien n'est écrasé.
            var chemin = "$dir/$name"
            var n = 2
            while (chemin in notes) chemin = "$dir/$name ($n)".also { n++ }
            notes[chemin] = content
            return NoteRefDto(path = chemin, name = chemin.substringAfterLast('/'))
        }

        override suspend fun readNote(notePath: String): String = alterer(notes.getValue(notePath))

        override suspend fun suggestName(title: String): String = assainir(title)
    }

    /** Source en mémoire : chemin → texte. */
    private val source = mapOf(
        "/Perso/courses.md" to "# Courses\n- pain",
        "/Perso/idée.txt" to "Une idée",
        "/Perso/a:b.md" to "nom accepté par le serveur, refusé à la création",
    )

    private suspend fun lire(chemin: String): String =
        source[chemin] ?: throw OCnotesException(ErrorCategory.NOT_FOUND, "NOT_FOUND", "absente")

    @Test
    fun `chaque note est copiee avec son nom et son extension`() = runBlocking {
        val cible = FausseCible()
        val bilan = copierNotes(listOf("/Perso/courses.md", "/Perso/idée.txt"), "/Dest", ::lire, cible)

        assertEquals(ResultatCopie(2, 0, null), bilan)
        assertEquals("# Courses\n- pain", cible.notes["/Dest/courses.md"])
        // Copie, pas conversion : un .txt reste un .txt.
        assertEquals("Une idée", cible.notes["/Dest/idée.txt"])
    }

    @Test
    fun `un nom pris recoit un suffixe sans ecraser la note existante`() = runBlocking {
        val cible = FausseCible()
        cible.notes["/Dest/courses.md"] = "déjà là"

        val bilan = copierNotes(listOf("/Perso/courses.md"), "/Dest", ::lire, cible)

        assertEquals(1, bilan.copiees)
        assertEquals("déjà là", cible.notes["/Dest/courses.md"])
        assertEquals("# Courses\n- pain", cible.notes["/Dest/courses.md (2)"])
    }

    @Test
    fun `un nom refuse est assaini puis la creation retentee une fois`() = runBlocking {
        val cible = FausseCible()
        cible.nomsRefuses += "a:b.md"

        val bilan = copierNotes(listOf("/Perso/a:b.md"), "/Dest", ::lire, cible)

        assertEquals(ResultatCopie(1, 0, null), bilan)
        assertTrue("/Dest/a-b.md" in cible.notes)
    }

    @Test
    fun `un nom refuse que l assainissement ne change pas est un echec`() = runBlocking {
        val cible = FausseCible()
        cible.nomsRefuses += "a:b.md"
        cible.assainir = { it }

        val bilan = copierNotes(listOf("/Perso/a:b.md"), "/Dest", ::lire, cible)

        assertEquals(0, bilan.copiees)
        assertEquals(1, bilan.echecs)
        assertEquals("NAME_INVALID", bilan.premiereErreur?.code)
        assertTrue(cible.notes.isEmpty())
    }

    @Test
    fun `une copie relue differente compte comme un echec sans erreur`() = runBlocking {
        val cible = FausseCible()
        cible.alterer = { it + " (abîmée)" }

        val bilan = copierNotes(listOf("/Perso/courses.md"), "/Dest", ::lire, cible)

        assertEquals(ResultatCopie(0, 1, null), bilan)
    }

    @Test
    fun `une erreur n arrete pas les notes suivantes et la premiere est gardee`() = runBlocking {
        val cible = FausseCible()
        val quota = OCnotesException(ErrorCategory.LOCAL, "QUOTA", "plein")
        cible.erreurs["idée.txt"] = quota

        val bilan = copierNotes(
            listOf("/Perso/absente.md", "/Perso/idée.txt", "/Perso/courses.md"),
            "/Dest", ::lire, cible,
        )

        assertEquals(1, bilan.copiees)
        assertEquals(2, bilan.echecs)
        // La lecture de la source a échoué en premier : c'est elle qu'on garde.
        assertEquals("NOT_FOUND", bilan.premiereErreur?.code)
        assertTrue("/Dest/courses.md" in cible.notes)
    }

    @Test
    fun `la synchronisation est demandee avec le nombre de copies ecrites`() = runBlocking {
        val cible = FausseCible()
        var signale: Int? = null

        copierNotes(listOf("/Perso/courses.md", "/Perso/absente.md"), "/Dest", ::lire, cible) {
            signale = it
        }

        assertEquals(1, signale)
    }

    @Test
    fun `une annulation remonte mais les copies deja ecrites partent`() = runBlocking {
        val cible = FausseCible()
        val annulation = CancellationException("écran fermé")
        cible.erreurs["idée.txt"] = annulation
        var signale: Int? = null

        // Rattrapée ici, dans la coroutine : on vérifie seulement que
        // copierNotes la laisse passer au lieu de la compter comme un échec.
        try {
            copierNotes(listOf("/Perso/courses.md", "/Perso/idée.txt"), "/Dest", ::lire, cible) {
                signale = it
            }
            fail("l'annulation doit remonter, pas être comptée comme un échec")
        } catch (e: CancellationException) {
            assertSame(annulation, e)
        }
        // La première note est écrite : elle doit quand même être synchronisée.
        assertEquals(1, signale)
    }

    @Test
    fun `sans note a copier rien n est cree`() = runBlocking {
        val cible = FausseCible()
        var signale: Int? = null

        val bilan = copierNotes(emptyList(), "/Dest", ::lire, cible) { signale = it }

        assertEquals(ResultatCopie(0, 0, null), bilan)
        assertEquals(0, signale)
        assertTrue(cible.notes.isEmpty())
        assertNull(bilan.premiereErreur)
    }
}
