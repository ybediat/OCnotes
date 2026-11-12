package eu.ocnotes.data

import android.app.backup.BackupAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SauvegardeProfilsTest {

    private val idLocal = "11111111-1111-1111-1111-111111111111"
    private val idServeur = "22222222-2222-2222-2222-222222222222"
    private val idLocal2 = "33333333-3333-3333-3333-333333333333"

    /** Crée un `filesDir` temporaire, le passe à [bloc] puis l'efface. */
    private fun avecDossier(bloc: (File) -> Unit) {
        val racine = Files.createTempDirectory("ocnotes-sauvegarde").toFile()
        try {
            bloc(racine)
        } finally {
            racine.deleteRecursively()
        }
    }

    private fun profilJson(id: String, kind: String, nom: String = ""): String =
        """{"id":"$id","kind":"$kind","serverUrl":"${if (kind == "server") "https://cloud.test" else ""}","displayName":"$nom"}"""

    private fun ecrireRegistre(racine: File, actif: String, vararg profils: String) {
        racine.resolve("accounts.json").writeText(
            """{"version":1,"activeAccountId":"$actif","accounts":[${profils.joinToString(",")}]}""",
        )
    }

    /** Écrit le `config.json` du cœur Go : c'est lui qui dit si un profil est local. */
    private fun ecrireConfig(racine: File, id: String, mode: String) {
        val fichier = racine.resolve("accounts/$id/config.json")
        fichier.parentFile!!.mkdirs()
        fichier.writeText("""{"mode":"$mode"}""")
    }

    private fun ecrireNote(racine: File, id: String, nom: String, contenu: String) {
        val fichier = racine.resolve("accounts/$id/cache/$nom")
        fichier.parentFile!!.mkdirs()
        fichier.writeText(contenu)
    }

    @Test
    fun `seuls les profils locaux partent dans la sauvegarde`() = avecDossier { racine ->
        ecrireRegistre(
            racine, idServeur,
            profilJson(idLocal, "local", "Perso"),
            profilJson(idServeur, "server"),
        )
        ecrireConfig(racine, idLocal, "local")
        ecrireConfig(racine, idServeur, "server")
        ecrireNote(racine, idLocal, "a.blob", "note locale")
        ecrireNote(racine, idServeur, "b.blob", "note du serveur")
        ecrireNote(racine, idLocal, "c.blob.tmp", "reste d'ecriture")

        val locaux = SauvegardeProfils.ecrireInstantane(racine)
        val fichiers = SauvegardeProfils.fichiersASauvegarder(racine, locaux)
            .map { it.relativeTo(racine).invariantSeparatorsPath }

        assertEquals(listOf(idLocal), locaux.map { it.id })
        assertEquals(
            listOf(
                "sauvegarde/profils-locaux.json",
                "accounts/$idLocal/cache/a.blob",
                "accounts/$idLocal/config.json",
            ),
            fichiers,
        )
        // Le registre complet ne part jamais : il listerait le profil serveur.
        assertFalse(fichiers.contains("accounts.json"))
        val instantane = SauvegardeProfils.instantane(racine).readText()
        assertTrue(instantane.contains(idLocal))
        assertFalse(instantane.contains(idServeur))
    }

    @Test
    fun `sans profil local l instantane est retire`() = avecDossier { racine ->
        ecrireRegistre(racine, idServeur, profilJson(idServeur, "server"))
        val ancien = SauvegardeProfils.instantane(racine)
        ancien.parentFile!!.mkdirs()
        ancien.writeText("ancien")

        assertTrue(SauvegardeProfils.ecrireInstantane(racine).isEmpty())
        assertFalse(ancien.exists())
        assertEquals(0L, SauvegardeProfils.tailleASauvegarder(racine))
    }

    @Test
    fun `registre illisible ne fait pas planter la sauvegarde`() = avecDossier { racine ->
        racine.resolve("accounts.json").writeText("{ pas du json")
        assertTrue(SauvegardeProfils.ecrireInstantane(racine).isEmpty())
    }

    @Test
    fun `restauration sur installation neuve recree le registre`() = avecDossier { racine ->
        // Préparation : sauvegarde d'un appareil, puis on ne garde que ce qui part.
        ecrireRegistre(racine, idLocal, profilJson(idLocal, "local", "Perso"), profilJson(idServeur, "server"))
        ecrireConfig(racine, idLocal, "local")
        ecrireNote(racine, idLocal, "a.blob", "note locale")
        SauvegardeProfils.ecrireInstantane(racine)
        racine.resolve("accounts.json").delete()
        racine.resolve("accounts/$idServeur").deleteRecursively()

        assertEquals(1, SauvegardeProfils.appliquerRestauration(racine))

        val registre = AccountRegistry(racine)
        assertEquals(idLocal, registre.active.id)
        assertEquals(listOf(idLocal), registre.accounts.map { it.id })
        assertEquals("Perso", registre.active.displayName)
        assertEquals("note locale", racine.resolve("accounts/$idLocal/cache/a.blob").readText())
        assertFalse(SauvegardeProfils.instantane(racine).exists())
    }

    @Test
    fun `restauration sur un appareil deja utilise ajoute sans ecraser`() = avecDossier { racine ->
        // Instantané restauré : deux profils locaux, dont un seul a son dossier.
        val instantane = SauvegardeProfils.instantane(racine)
        instantane.parentFile!!.mkdirs()
        instantane.writeText(
            """{"version":1,"activeAccountId":"$idLocal","accounts":[${profilJson(idLocal, "local")},${profilJson(idLocal2, "local")}]}""",
        )
        ecrireNote(racine, idLocal, "a.blob", "restauree")
        // Registre déjà présent : un profil serveur actif.
        ecrireRegistre(racine, idServeur, profilJson(idServeur, "server"))
        racine.resolve("accounts/$idServeur").mkdirs()

        assertEquals(1, SauvegardeProfils.appliquerRestauration(racine))

        val registre = AccountRegistry(racine)
        assertEquals(idServeur, registre.active.id)
        assertEquals(listOf(idServeur, idLocal), registre.accounts.map { it.id })
    }

    @Test
    fun `restauration repetee n ajoute pas de doublon`() = avecDossier { racine ->
        ecrireRegistre(racine, idLocal, profilJson(idLocal, "local"))
        ecrireConfig(racine, idLocal, "local")
        ecrireNote(racine, idLocal, "a.blob", "x")
        SauvegardeProfils.ecrireInstantane(racine)

        assertEquals(0, SauvegardeProfils.appliquerRestauration(racine))
        assertEquals(0, SauvegardeProfils.appliquerRestauration(racine))
        assertEquals(listOf(idLocal), AccountRegistry(racine).accounts.map { it.id })
    }

    @Test
    fun `avertissement a partir de 20 Mo ou apres un depassement`() {
        assertFalse(EtatSauvegarde(octets = 1024, quotaDepasseLe = null).avertir)
        assertTrue(EtatSauvegarde(octets = 20L * 1024 * 1024, quotaDepasseLe = null).avertir)
        assertTrue(EtatSauvegarde(octets = 1024, quotaDepasseLe = 1L).avertir)
    }

    @Test
    fun `le seuil d avertissement suit le quota du transport`() {
        val cent = 100L * 1024 * 1024
        assertFalse(EtatSauvegarde(octets = 20L * 1024 * 1024, quotaDepasseLe = null, quota = cent).avertir)
        assertTrue(EtatSauvegarde(octets = 80L * 1024 * 1024, quotaDepasseLe = null, quota = cent).avertir)
    }

    @Test
    fun `un refus faute de chiffrement avertit`() {
        assertTrue(EtatSauvegarde(octets = 1024, quotaDepasseLe = null, nonChiffreeLe = 1L).avertir)
    }

    // Le registre retarde parfois sur le cœur Go : c'est config.json qui
    // décide. Dans ce sens, se fier au registre enverrait le cache d'un
    // serveur privé dans la sauvegarde.
    @Test
    fun `un profil dit local au registre mais serveur au coeur ne part pas`() = avecDossier { racine ->
        ecrireRegistre(racine, idLocal, profilJson(idLocal, "local"))
        ecrireConfig(racine, idLocal, "server")
        ecrireNote(racine, idLocal, "a.blob", "copie du serveur")

        assertTrue(SauvegardeProfils.ecrireInstantane(racine).isEmpty())
        assertEquals(0L, SauvegardeProfils.tailleASauvegarder(racine))
    }

    // Dans l'autre sens, un registre reconstruit classe un profil local parmi
    // les serveurs : il cesserait d'être sauvegardé, sans aucun signal.
    @Test
    fun `un profil dit serveur au registre mais local au coeur part quand meme`() = avecDossier { racine ->
        ecrireRegistre(racine, idLocal, profilJson(idLocal, "server"))
        ecrireConfig(racine, idLocal, "local")
        ecrireNote(racine, idLocal, "a.blob", "seule copie")

        val locaux = SauvegardeProfils.ecrireInstantane(racine)

        assertEquals(listOf(idLocal), locaux.map { it.id })
        // L'instantané décrit un profil local, sans trace du serveur du registre.
        assertEquals("local", locaux.single().kind)
        assertEquals("", locaux.single().serverUrl)
        assertTrue(
            SauvegardeProfils.fichiersASauvegarder(racine, locaux)
                .any { it.relativeTo(racine).invariantSeparatorsPath == "accounts/$idLocal/cache/a.blob" },
        )
    }

    @Test
    fun `un profil sans configuration lisible ne part pas`() = avecDossier { racine ->
        ecrireRegistre(racine, idLocal, profilJson(idLocal, "local"))
        ecrireNote(racine, idLocal, "a.blob", "x")
        assertTrue(SauvegardeProfils.ecrireInstantane(racine).isEmpty())

        racine.resolve("accounts/$idLocal/config.json").writeText("{ pas du json")
        assertTrue(SauvegardeProfils.ecrireInstantane(racine).isEmpty())
    }

    @Test
    fun `seuls le chiffrement de bout en bout et le transfert direct autorisent l envoi`() {
        val chiffre = SauvegardeProfils.DRAPEAU_CHIFFREMENT
        val direct = SauvegardeProfils.DRAPEAU_TRANSFERT_DIRECT
        assertFalse(SauvegardeProfils.envoiAutorise(0))
        // Un drapeau inconnu ne vaut pas preuve de chiffrement.
        assertFalse(SauvegardeProfils.envoiAutorise(4))
        assertTrue(SauvegardeProfils.envoiAutorise(chiffre))
        assertTrue(SauvegardeProfils.envoiAutorise(direct))
        assertTrue(SauvegardeProfils.envoiAutorise(chiffre or direct))
        assertTrue(SauvegardeProfils.estTransfertDirect(direct))
        assertFalse(SauvegardeProfils.estTransfertDirect(chiffre))
    }

    // Le faux drapeau du transport de test n'ouvre la sauvegarde qu'à une
    // version de développement : jamais à une version publiée.
    @Test
    fun `le faux chiffrement du transport de test ne vaut qu en debug`() {
        val faux = SauvegardeProfils.DRAPEAU_FAUX_CHIFFREMENT
        assertEquals(Int.MIN_VALUE, faux)
        assertFalse(SauvegardeProfils.envoiAutorise(faux))
        assertFalse(SauvegardeProfils.envoiAutorise(faux, appliDeboguable = false))
        assertTrue(SauvegardeProfils.envoiAutorise(faux, appliDeboguable = true))
        // En debug, l'absence de tout drapeau reste un refus.
        assertFalse(SauvegardeProfils.envoiAutorise(0, appliDeboguable = true))
    }

    // Les drapeaux sont recopiés pour tester sans Android : ils doivent rester
    // ceux de la plateforme.
    @Test
    fun `les drapeaux sont ceux de BackupAgent`() {
        assertEquals(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED, SauvegardeProfils.DRAPEAU_CHIFFREMENT)
        assertEquals(BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER, SauvegardeProfils.DRAPEAU_TRANSFERT_DIRECT)
    }
}
