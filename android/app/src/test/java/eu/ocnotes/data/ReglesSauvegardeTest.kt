package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cohérence entre les règles XML de la sauvegarde Android et le code.
 *
 * À la restauration, Android refuse tout fichier que ces règles n'incluent
 * pas. Si l'on renomme un dossier côté Kotlin (`sauvegarde/`, `accounts/`) sans
 * toucher au XML, la sauvegarde part toujours mais ne se restaure plus, et
 * personne ne le voit avant d'avoir perdu son téléphone. Ce test lit les XML
 * réels et les compare aux constantes et aux fichiers que produit
 * [SauvegardeProfils].
 */
class ReglesSauvegardeTest {

    /** Une règle `<include>` : domaine et chemin. */
    private data class Regle(val domaine: String, val chemin: String)

    /** Règles attendues, tirées des constantes du code et non recopiées à la main. */
    private val attendues = setOf(
        Regle("file", AccountRegistry.ACCOUNTS_DIR + "/"),
        Regle("file", SauvegardeProfils.DOSSIER_SAUVEGARDE + "/"),
        Regle("sharedpref", PreferencesAffichage.FICHIER + ".xml"),
    )

    @Test
    fun `les regles jusqu a Android 11 couvrent exactement ce que le code sauvegarde`() {
        val racine = lireXml("res/xml/backup_rules.xml")
        assertEquals("full-backup-content", racine.tagName)
        assertEquals(attendues, inclusions(racine))
        assertAucuneExclusion(racine)
    }

    @Test
    fun `les regles a partir d Android 12 couvrent la sauvegarde et le transfert`() {
        val racine = lireXml("res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", racine.tagName)

        val cloud = enfantUnique(racine, "cloud-backup")
        val transfert = enfantUnique(racine, "device-transfer")
        assertEquals(attendues, inclusions(cloud))
        assertEquals(attendues, inclusions(transfert))
        assertAucuneExclusion(racine)
        // L'intention « pas de sauvegarde en clair » reste écrite, même si
        // c'est SauvegardeAgent qui refuse réellement.
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
    }

    @Test
    fun `le registre complet n est jamais inclus`() {
        // Il listerait les profils serveur ; l'instantané le remplace.
        for (fichier in listOf("res/xml/backup_rules.xml", "res/xml/data_extraction_rules.xml")) {
            val chemins = inclusions(lireXml(fichier)).map { it.chemin }
            assertTrue(
                "$fichier inclut ${AccountRegistry.REGISTRY_FILE}",
                chemins.none { it.trimEnd('/') == AccountRegistry.REGISTRY_FILE },
            )
        }
    }

    @Test
    fun `chaque fichier envoye par l agent est restaurable`() {
        val idLocal = "11111111-1111-1111-1111-111111111111"
        val racine = Files.createTempDirectory("ocnotes-regles").toFile()
        try {
            // Un profil local avec une note, comme dans SauvegardeProfilsTest.
            racine.resolve(AccountRegistry.REGISTRY_FILE).writeText(
                """{"version":1,"activeAccountId":"$idLocal","accounts":[{"id":"$idLocal","kind":"local","displayName":"Perso"}]}""",
            )
            racine.resolve("accounts/$idLocal/config.json").apply { parentFile!!.mkdirs() }
                .writeText("""{"mode":"local"}""")
            racine.resolve("accounts/$idLocal/cache/a.blob").apply { parentFile!!.mkdirs() }
                .writeText("note locale")

            val locaux = SauvegardeProfils.ecrireInstantane(racine)
            val envoyes = SauvegardeProfils.fichiersASauvegarder(racine, locaux)
                .map { it.relativeTo(racine).invariantSeparatorsPath }
            assertTrue("rien n'a été préparé pour la sauvegarde", envoyes.isNotEmpty())

            val dossiersInclus = inclusions(lireXml("res/xml/backup_rules.xml"))
                .filter { it.domaine == "file" }
                .map { it.chemin }
            val nonRestaurables = envoyes.filter { chemin -> dossiersInclus.none { chemin.startsWith(it) } }
            assertEquals("fichiers envoyés mais refusés à la restauration", emptyList<String>(), nonRestaurables)
        } finally {
            racine.deleteRecursively()
        }
    }

    @Test
    fun `le manifeste branche l agent et les deux fichiers de regles`() {
        val manifeste = lireXml("AndroidManifest.xml")
        val application = enfantUnique(manifeste, "application")

        assertEquals("true", application.getAttribute("android:allowBackup"))
        assertEquals(".data.SauvegardeAgent", application.getAttribute("android:backupAgent"))
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        // Sans fullBackupOnly, Android appellerait onBackup (clé-valeur) au lieu d'onFullBackup.
        assertEquals("true", application.getAttribute("android:fullBackupOnly"))
    }

    // --- Mécanique ---------------------------------------------------------

    /** Les règles `<include>` sous [parent], à toute profondeur. */
    private fun inclusions(parent: Element): Set<Regle> {
        val noeuds = parent.getElementsByTagName("include")
        return (0 until noeuds.length).map {
            val e = noeuds.item(it) as Element
            Regle(e.getAttribute("domain").trim(), e.getAttribute("path").trim())
        }.toSet()
    }

    /** Aucune règle `<exclude>` : la sélection se fait par inclusion seulement. */
    private fun assertAucuneExclusion(racine: Element) {
        assertEquals(0, racine.getElementsByTagName("exclude").length)
    }

    private fun enfantUnique(parent: Element, nom: String): Element {
        val noeuds = parent.getElementsByTagName(nom)
        assertEquals("un seul <$nom> attendu", 1, noeuds.length)
        return noeuds.item(0) as Element
    }

    /** Lit un fichier de `src/main` (chemin relatif) et renvoie sa racine XML. */
    private fun lireXml(cheminRelatif: String): Element {
        val fichier = File(dossierMain(), cheminRelatif)
        if (!fichier.isFile) fail("fichier introuvable : ${fichier.path}")
        // Sans espaces de noms : on lit « android:backupAgent » tel qu'écrit.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fichier).documentElement
    }

    /**
     * Retrouve `src/main` sans dépendre du dossier de travail : Gradle lance
     * les tests depuis le module, l'IDE parfois depuis la racine du dépôt.
     */
    private fun dossierMain(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            File(dossier, "src/main/AndroidManifest.xml").let { if (it.isFile) return it.parentFile!! }
            File(dossier, "android/app/src/main/AndroidManifest.xml").let { if (it.isFile) return it.parentFile!! }
            dossier = dossier.parentFile
        }
        error("src/main introuvable depuis " + File("").absolutePath)
    }
}
