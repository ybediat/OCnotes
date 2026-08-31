<#
.SYNOPSIS
    Banc de mesure de l'éditeur : combien coûte une image, et dans quelle phase.

.DESCRIPTION
    Ce script existe parce qu'un chiffre de performance sans protocole ne vaut
    rien, et parce que le protocole a trois pièges qui produisent des résultats
    faux mais plausibles. Les relevés qu'il produit sont ceux de la section
    7 bis de docs/ARCHITECTURE.md.

    Ce qu'il mesure, et pourquoi ces colonnes-là :

        IntendedVsync          -> PerformTraversalsStart  avant l'interface
        PerformTraversalsStart -> DrawStart               mesure et layout
        DrawStart              -> SyncQueued              display list
        IntendedVsync          -> FrameCompleted          l'image entière

    Avant la virtualisation, la troisième était tout : 0,11 ms de layout contre
    505 ms de display list sur une note de 295 ko — d'où le chantier
    docs/CHANTIER-EDITEUR.md. Après, elle tient 4,5 ms en frappe et 0,8 ms en
    défilement, et ce sont les deux autres qui décident. D'où la première et la
    dernière, ajoutées le 31 août 2026 : sans elles, on lit « 25 ms par image »
    sans pouvoir dire si l'application y est pour quelque chose.

    Les noms des colonnes sont dans la ligne d'en-tête de
    `dumpsys gfxinfo <paquet> framestats`. Les lire plutôt que les deviner :
    la colonne 1 est un identifiant de vsync, pas un horodatage, et la prendre
    pour IntendedVsync donne des durées de quarante heures.

    Les cinq pièges, chacun payé une fois :

    1. Un tap perdu ne se voit pas dans les résultats. Le thread UI reste
       bloqué assez longtemps pour que l'appui soit avalé ; on mesure alors le
       défilement de la LISTE au lieu de celui de l'éditeur, et le chiffre
       obtenu est parfaitement crédible. D'où la vérification d'écran par
       uiautomator avant et après chaque mesure.

    2. Se fier au processus ne suffit pas. MIUI garde l'application en vie
       alors qu'elle est passée en arrière-plan : `pidof` répond, et les
       retours arrière partent dans l'écran d'accueil du téléphone.

    3. La synchronisation fausse la mesure. Le script coupe le réseau et le
       rétablit à la fin, y compris en cas d'échec.

    4. Un curseur qui clignote rend uiautomator aveugle. La fenêtre n'est alors
       jamais « au repos », et le dump échoue sur « null root node returned by
       UiTestAutomationBridge » — au moment précis où on a besoin de savoir où
       l'on est. Le script masque donc le clavier avant d'ouvrir la note, pour
       que le champ ne prenne pas le focus.

       Ce n'est pas qu'un détail d'outillage : sur la note de 295 ko, le seul
       clignotement du curseur fait dessiner 2 images par seconde à 550 ms
       pièce. L'éditeur sature le thread UI **sans que personne ne touche à
       rien**.

       5. Le champ de saisie de l'éditeur ressemble à la barre de recherche.
          Depuis la virtualisation, l'éditeur compose un petit EditText dès
          qu'on touche le texte — mince, comme une barre de recherche. Le banc
          a pris l'éditeur pour la liste, y a joué « tout sélectionner,
          supprimer », puis tapé son mot-clé : deux cents caractères détruits
          dans un vrai document, enregistrés et poussés sur le serveur. Voir
          Get-ChampRecherche et Test-RechercheFocalisee.

.PARAMETER Note
    Nom de la note à mesurer, tel qu'il s'affiche dans la liste.

.PARAMETER Frappe
    Mesure aussi le coût de la frappe. ATTENTION : cela MODIFIE la note.
    À n'utiliser que sur une note jetable d'un environnement de test.

.PARAMETER Caracteres
    Nombre de caractères injectés par -Frappe. Cinq suffisaient tant que la
    frappe coûtait 750 ms par image ; à 25 ms, vingt images ne font plus une
    mesure. Quarante en donnent une centaine, comparable au défilement.

.PARAMETER Apercu
    Mesure l'aperçu au lieu de la saisie. L'aperçu s'appuie sur une LazyColumn
    et ne dessine que le visible — sauf pour un fichier texte brut, dont
    RenderPlain ne fait qu'un seul bloc. C'est ce cas-là qu'il faut mesurer.

.PARAMETER Prechauffer
    Ouvre la note **sans couper le reseau** et sans rien mesurer, pour que son
    contenu descende dans le cache. A lancer une fois quand le banc se plaint
    que la note n'est pas chargee : hors connexion, une note dont le cache n'a
    que l'inventaire ne peut pas s'ouvrir, et la mesure n'a rien a mesurer.

.PARAMETER Paquet
    Nom du paquet. Par défaut la variante debug.

.EXAMPLE
    ./scripts/banc-editeur.ps1 -Note "scolarisation des enfants rrom"

.EXAMPLE
    ./scripts/banc-editeur.ps1 -Note "bench-200k" -Frappe
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Note,

    [switch] $Frappe,

    [int] $Caracteres = 5,

    [switch] $Apercu,

    [switch] $Prechauffer,

    [string] $Paquet = "eu.opennote.debug"
)

$ErrorActionPreference = "Stop"

# adb n'est pas dans le PATH d'une session neuve.
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Arguments)
    & adb @Arguments
}

<#
    Capture l'arbre d'accessibilite, avec deux precautions qui ne sont pas du
    luxe.

    uiautomator refuse de produire un dump tant que la fenetre n'est pas au
    repos — « ERROR: null root node returned by UiTestAutomationBridge » — et
    c'est precisement ce qui arrive quand l'editeur bloque le thread UI en
    ouvrant une grosse note, donc au moment ou on a le plus besoin de savoir ou
    on est. Le fichier precedent restant en place, `cat` rendait alors l'ecran
    d'AVANT et le banc concluait « tap avale » sur une note pourtant ouverte.

    D'ou : on efface le fichier avant, et on reessaie tant que le dump n'a pas
    abouti.
#>
function Get-Ecran {
    for ($i = 0; $i -lt 6; $i++) {
        Invoke-Adb shell rm -f /sdcard/ui.xml | Out-Null
        # Un dump refuse ecrit sur la sortie d'erreur. On l'avale : ces lignes
        # sont attendues pendant les reprises, et les laisser passer ferait
        # croire a une panne alors que le banc se rattrape. Redirection sous
        # ErrorActionPreference relache, sinon PowerShell 5.1 transforme la
        # sortie d'erreur d'un exe natif en exception.
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $sortie = & adb shell uiautomator dump /sdcard/ui.xml 2>$null
        $ErrorActionPreference = $eap
        if ($sortie -match 'dumped to') {
            $xml = & adb exec-out cat /sdcard/ui.xml
            if (-not [string]::IsNullOrWhiteSpace($xml)) { return $xml }
        }
        Start-Sleep -Milliseconds 1500
    }
    throw "uiautomator n'a pas rendu d'arbre apres 6 tentatives (interface bloquee ?)."
}

# L'ecran est identifie par la GEOMETRIE, pas par un libelle.
#
# Deux raisons, toutes deux payees : un marqueur textuel depend de la langue de
# l'application, et la liste a deux formes — « ce dossier » et « toutes les
# notes » — dont les libelles different. Le critere retenu ne depend d'aucune
# des deux : l'editeur occupe l'ecran d'une zone defilante, celui de la
# recherche est une barre mince.
function Test-DansApp {
    param([string] $Xml)
    return ($Xml -like "*package=""$Paquet""*")
}

<#
    L'editeur virtualise n'a plus de champ de saisie a demeure.

    L'ancien critere — un EditText occupant 40 % de la hauteur — designait le
    champ monolithique, qui portait tout le document. Il n'existe plus : la
    note s'ouvre sans aucun EditText, et il n'en apparait un, borne a quelques
    lignes, qu'apres un toucher. Laisse tel quel, le banc concluait « l'editeur
    ne s'est pas ouvert » sur un editeur parfaitement ouvert.

    Le marqueur de remplacement reste geometrique : la liste virtualisee de
    l'editeur occupe la hauteur, et la liste de notes se distingue toujours par
    son champ de recherche mince.
#>
function Test-SurEcranEditeur {
    param([string] $Xml, [int] $Hauteur)
    if (-not (Test-DansApp -Xml $Xml)) { return $false }
    # L'ecran « Note non chargee » n'a pas de zone defilante : c'est ce qui
    # l'exclut ici, apres avoir deja piege Enter-Liste une fois.
    if ($null -ne (Get-ChampRecherche -Xml $Xml -Hauteur $Hauteur)) { return $false }
    $motif = '<node[^>]*scrollable="true"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        $h = [int]$m.Groups[2].Value - [int]$m.Groups[1].Value
        if ($h -gt ($Hauteur * 0.4)) { return $true }
    }
    return $false
}

<#
    Saisie et apercu se distinguent par la barre de mise en forme.

    Limite assumee : un fichier texte brut n'a pas de barre — l'application la
    masque, ses marqueurs n'y voudraient rien dire — et les deux modes sont
    alors indiscernables dans l'arbre. Une mesure -Apercu sur un .txt doit donc
    etre confirmee a l'oeil.
#>
function Test-EnSaisie {
    param([string] $Xml, [int] $Hauteur)
    if (-not (Test-SurEcranEditeur -Xml $Xml -Hauteur $Hauteur)) { return $false }
    return ($Xml -match 'class="android\.widget\.HorizontalScrollView"')
}

function Get-HauteurEcran {
    $m = [regex]::Match((Invoke-Adb shell wm size), 'Physical size: \d+x(\d+)')
    if (-not $m.Success) { return 2400 }
    return [int]$m.Groups[1].Value
}

function Enter-Liste {
    # « Dans l'application et pas dans l'editeur » ne suffit PAS a dire qu'on
    # est dans la liste : l'ecran « Note non chargee » n'a aucun champ de
    # saisie, et passait donc pour la liste — le banc cherchait ensuite un
    # champ de recherche qui n'y etait pas. Le marqueur de la liste est la
    # presence de son champ de recherche, le seul EditText mince.
    for ($i = 0; $i -lt 5; $i++) {
        $xml = Get-Ecran
        if (Test-DansApp -Xml $xml) {
            if ($null -ne (Get-ChampRecherche -Xml $xml -Hauteur $script:Hauteur)) { return }
            # Editeur, apercu, ecran d'erreur : on remonte.
            Invoke-Adb shell input keyevent 4 | Out-Null
            Start-Sleep -Seconds 3
            continue
        }
        # Se fier a `pidof` ne suffirait pas : MIUI garde le processus en vie
        # alors que l'application est en arriere-plan.
        Invoke-Adb shell am start -n "$Paquet/eu.opennote.ui.MainActivity" | Out-Null
        Start-Sleep -Seconds 5
    }
    throw "Liste introuvable apres 5 tentatives."
}

<#
    Champ de recherche : un EditText mince, et SURTOUT au-dessus du contenu.

    « Le seul EditText mince de l'ecran » a suffi tant que l'editeur portait un
    champ plein ecran. L'editeur virtualise en compose un petit, borne a
    quelques lignes, des qu'on touche le texte — mince, donc, exactement comme
    une barre de recherche.

    Ce que ce defaut a coute, une fois : le banc a pris l'editeur pour la liste,
    a fait « tout selectionner » puis « supprimer » pour vider une recherche qui
    n'existait pas, et a tape son mot-cle dans la note. Deux cents caracteres
    detruits dans un vrai document, enregistres et pousses sur le serveur avant
    que rien ne se voie. Le message d'echec, lui, parlait d'une note absente des
    resultats.

    Le critere corrige reste geometrique et ne depend d'aucun libelle : la barre
    de recherche est au-dessus de la zone defilante, le champ de l'editeur est
    dedans.
#>
# Haut de la premiere grande zone defilante. Tout EditText situe plus bas
# appartient au contenu, jamais a la barre de recherche.
function Get-PlafondContenu {
    param([string] $Xml, [int] $Hauteur)
    $plafond = $Hauteur
    $zones = '<node[^>]*scrollable="true"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"'
    foreach ($z in [regex]::Matches($Xml, $zones)) {
        $haut = [int]$z.Groups[1].Value
        $bas  = [int]$z.Groups[2].Value
        if ((($bas - $haut) -gt ($Hauteur * 0.4)) -and ($haut -lt $plafond)) {
            $plafond = $haut
        }
    }
    return $plafond
}

# Le champ focalise est-il bien celui de la recherche, et pas celui de
# l'editeur ? C'est la question qui a coute deux cents caracteres.
function Test-RechercheFocalisee {
    param([string] $Xml, [int] $Hauteur)
    $plafond = Get-PlafondContenu -Xml $Xml -Hauteur $Hauteur
    $motif = '<node[^>]*class="android\.widget\.EditText"[^>]*focused="true"[^>]*bounds="\[\d+,\d+\]\[\d+,(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        if ([int]$m.Groups[1].Value -le $plafond) { return $true }
    }
    return $false
}

function Get-ChampRecherche {
    param([string] $Xml, [int] $Hauteur)

    $plafond = Get-PlafondContenu -Xml $Xml -Hauteur $Hauteur

    $motif = '<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        $h = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
        if (($h -lt ($Hauteur * 0.4)) -and ([int]$m.Groups[4].Value -le $plafond)) {
            return @{
                X = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
                Y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
            }
        }
    }
    return $null
}

function Get-BoutonApercu {
    param([string] $Xml)
    # L'icone la plus a droite de la barre du haut. Les bornes de TAILLE ne sont
    # pas decoratives : sans elles, le champ de saisie de l'editeur — cliquable,
    # pleine largeur, et dont le haut est aussi dans la bande — gagnait le
    # « plus a droite », et le tap atterrissait dans le texte. Un bouton d'icone
    # Material fait environ 130x130.
    $motif = '<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $meilleur = $null
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        $gauche = [int]$m.Groups[1].Value
        $haut   = [int]$m.Groups[2].Value
        $droite = [int]$m.Groups[3].Value
        $bas    = [int]$m.Groups[4].Value
        if ($haut -gt 400) { continue }
        if (($droite - $gauche) -gt 250) { continue }
        if (($bas - $haut) -gt 250) { continue }
        if (($null -eq $meilleur) -or ($droite -gt $meilleur.Droite)) {
            $meilleur = @{
                X      = [int](($gauche + $droite) / 2)
                Y      = [int](($haut + $bas) / 2)
                Droite = $droite
            }
        }
    }
    return $meilleur
}

function Enter-Apercu {
    $b = Get-BoutonApercu -Xml (Get-Ecran)
    if ($null -eq $b) { throw "Bouton d'apercu introuvable dans la barre du haut." }
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb shell input touchscreen tap $b.X $b.Y | Out-Null
        Start-Sleep -Seconds 5
        $xml = Get-Ecran
        # En apercu la barre de mise en forme disparait : c'est le critere.
        if (
            (Test-SurEcranEditeur -Xml $xml -Hauteur $script:Hauteur) -and
            -not (Test-EnSaisie -Xml $xml -Hauteur $script:Hauteur)
        ) {
            return
        }
    }
    throw "La bascule vers l'apercu n'a pas pris."
}

function Enter-Note {
    param([string] $Nom)

    # On passe par la recherche, pas par le defilement : uiautomator ne voit
    # que ce qui est rendu, et le dossier de test porte plusieurs milliers de
    # notes. Chercher est aussi ce que ferait l'utilisateur.
    $champ = Get-ChampRecherche -Xml (Get-Ecran) -Hauteur $script:Hauteur
    if ($null -eq $champ) { throw "Champ de recherche introuvable sur cet ecran." }

    # Taper puis esperer ne suffit pas : un tap peut etre purement avale — c'est
    # frequent juste apres un demarrage de l'application — et les touches
    # suivantes partent alors dans le vide. La recherche reste vierge et le banc
    # conclut « note absente », ce qui ne designe pas la cause. On verifie donc
    # que le champ a reellement pris le focus.
    $focalise = $false
    for ($i = 0; $i -lt 4; $i++) {
        Invoke-Adb shell input touchscreen tap $champ.X $champ.Y | Out-Null
        Start-Sleep -Milliseconds 1500
        if (Test-RechercheFocalisee -Xml (Get-Ecran) -Hauteur $script:Hauteur) {
            $focalise = $true
            break
        }
    }
    # Ne JAMAIS taper sans cette certitude : les touches suivantes sont
    # « tout selectionner », « supprimer », puis un mot. Dans un champ de note,
    # c'est une destruction silencieuse.
    if (-not $focalise) { throw "Le champ de recherche n'a pas pris le focus." }

    # Vider d'abord : le script doit etre rejouable quel que soit l'etat laisse
    # par la mesure precedente. 113 = CTRL gauche, 29 = A, 67 = SUPPR.
    Invoke-Adb shell input keycombination 113 29 | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-Adb shell input keyevent 67 | Out-Null
    Start-Sleep -Milliseconds 400

    # Le premier mot suffit a filtrer, et evite d'avoir a echapper les espaces
    # que `input text` interprete.
    $jeton = ($Nom -split '\s+')[0]
    Invoke-Adb shell input text $jeton | Out-Null
    Start-Sleep -Seconds 3

    # Masquer le clavier AVANT d'ouvrir la note, et ce n'est pas cosmetique.
    # Si l'IME est encore leve au moment du tap, l'editeur s'ouvre avec le champ
    # focalise ; le curseur se met a clignoter, la fenetre n'est plus jamais au
    # repos, et uiautomator ne rend plus aucun arbre — donc le banc ne peut plus
    # savoir ou il est. 111 = ECHAP, qui masque l'IME sans naviguer.
    Invoke-Adb shell input keyevent 111 | Out-Null
    Start-Sleep -Milliseconds 1200

    $motif = 'text="' + [regex]::Escape($Nom) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match((Get-Ecran), $motif)
    if (-not $m.Success) { throw "Note '$Nom' absente des resultats pour « $jeton »." }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)

    for ($essai = 0; $essai -lt 3; $essai++) {
        Invoke-Adb shell input touchscreen tap $x $y | Out-Null
        Start-Sleep -Seconds 6
        if (Test-SurEcranEditeur -Xml (Get-Ecran) -Hauteur $script:Hauteur) { return }
    }
    throw ("L'editeur ne s'est pas ouvert sur '$Nom'. Deux causes : un tap avale, " +
        "ou le contenu absent du cache — hors connexion l'ecran affiche alors " +
        "« Note non chargee ». Dans le doute, relancez une fois avec -Prechauffer.")
}

<#
    Decompose les framestats en phases. Une ligne par image ; les colonnes sont
    des horodatages en nanosecondes.
#>
function Measure-Frames {
    param([string[]] $Lignes)

    $dedans = $false
    $images = @()
    foreach ($l in $Lignes) {
        if ($l -match '^---PROFILEDATA---') { $dedans = -not $dedans; continue }
        if (-not $dedans) { continue }
        if ($l -match '^Flags') { continue }

        $c = $l.Split(',')
        if ($c.Count -lt 21) { continue }
        # Bit 0 arme = image ecartee par le systeme, pas une mesure.
        if (([int64]$c[0] -band 1) -ne 0) { continue }

        $ms = 1000000.0
        $images += [pscustomobject]@{
            # IntendedVsync -> PerformTraversalsStart : tout ce qui precede le
            # travail de l'interface — attente du vsync, remise de l'evenement
            # d'entree, animations. Cette phase n'etait pas decomposee tant que
            # le dessin coutait 400 ms et l'ecrasait ; a 5 ms elle domine.
            #
            # La colonne 1 est FrameTimelineVsyncId, un identifiant et non un
            # horodatage : la prendre pour IntendedVsync donne des durees de
            # quarante heures. Les noms sont dans la ligne d'en-tete de
            # `dumpsys gfxinfo <paquet> framestats`, il n'y a rien a deviner.
            Reveil = ([int64]$c[7]  - [int64]$c[2])  / $ms
            Image  = ([int64]$c[16] - [int64]$c[2])  / $ms
            Layout = ([int64]$c[8]  - [int64]$c[7])  / $ms
            Dessin = ([int64]$c[12] - [int64]$c[8])  / $ms
            Sync   = ([int64]$c[14] - [int64]$c[12]) / $ms
            Rendu  = ([int64]$c[15] - [int64]$c[14]) / $ms
        }
    }
    return $images
}

# Un motif absent rend « ? » plutot que de faire echouer la mesure en cours.
function Get-Champ {
    param([string[]] $Texte, [string] $Motif)
    $m = $Texte | Select-String $Motif | Select-Object -First 1
    if ($null -eq $m) { return "?" }
    return $m.Matches[0].Groups[1].Value
}

function Show-Mesure {
    param([string] $Titre, [string[]] $Agg, [string[]] $Frames)

    $images = Measure-Frames -Lignes $Frames
    if ($images.Count -eq 0) { Write-Host "  $Titre : aucune image mesuree"; return }

    $total  = Get-Champ -Texte $Agg -Motif 'Total frames rendered: (\d+)'
    $jank   = Get-Champ -Texte $Agg -Motif 'Janky frames: \d+ \(([0-9.]+)'
    $median = Get-Champ -Texte $Agg -Motif '^50th percentile: (\d+)ms'

    $reveil = ($images | Measure-Object Reveil -Average).Average
    $limage = ($images | Measure-Object Image  -Average).Average
    $layout = ($images | Measure-Object Layout -Average).Average
    $dessin = ($images | Measure-Object Dessin -Average).Average
    $dmax   = ($images | Measure-Object Dessin -Maximum).Maximum
    $rendu  = ($images | Measure-Object Rendu  -Average).Average

    Write-Host ""
    Write-Host "  $Titre"
    Write-Host ("    images rendues     : {0}" -f $total)
    Write-Host ("    images en retard   : {0} %" -f $jank)
    Write-Host ("    mediane par image  : {0} ms" -f $median)
    Write-Host ("    avant traversals   : {0:N2} ms" -f $reveil)
    Write-Host ("    image complete     : {0:N2} ms" -f $limage)
    Write-Host ("    mesure + layout    : {0:N2} ms" -f $layout)
    Write-Host ("    DESSIN             : {0:N2} ms   (max {1:N2})" -f $dessin, $dmax)
    Write-Host ("    RenderThread       : {0:N2} ms" -f $rendu)
}

function Get-Agg    { return (Invoke-Adb shell dumpsys gfxinfo $Paquet) }
function Get-Frames { return (Invoke-Adb shell dumpsys gfxinfo $Paquet framestats) }
function Reset-Gfx  { Invoke-Adb shell dumpsys gfxinfo $Paquet reset | Out-Null }

# --- Mesure ------------------------------------------------------------------

$reseauCoupe = $false
try {
    if (-not (Invoke-Adb devices | Select-String '\sdevice$')) {
        throw "Aucun appareil connecte."
    }

    if ($Prechauffer) {
        Write-Host "Prechauffage : reseau laisse actif, aucune mesure."
    } else {
        Write-Host "Reseau coupe (la synchronisation fausserait la mesure)."
        Invoke-Adb shell svc wifi disable | Out-Null
        Invoke-Adb shell svc data disable | Out-Null
        $reseauCoupe = $true
    }

    $script:Hauteur = Get-HauteurEcran

    Enter-Liste
    Reset-Gfx
    Enter-Note -Nom $Note

    if ($Prechauffer) {
        Write-Host "Note ouverte, contenu en cache. Relancez sans -Prechauffer pour mesurer."
        return
    }

    Show-Mesure -Titre "OUVERTURE" -Agg (Get-Agg) -Frames (Get-Frames)

    if ($Apercu) {
        Enter-Apercu
        Show-Mesure -Titre "BASCULE VERS L'APERCU" -Agg (Get-Agg) -Frames (Get-Frames)
    }

    Reset-Gfx
    for ($i = 0; $i -lt 6; $i++) {
        # Les deux modes ouvrent la note en haut : on defile vers le bas.
        #
        # L'editeur monolithique posait le curseur en fin de note et s'ouvrait
        # donc en bas, d'ou un balayage inverse ici. L'editeur virtualise
        # n'active aucun champ tant qu'on ne l'a pas touche : il ouvre au
        # debut, comme l'apercu.
        Invoke-Adb shell input swipe 540 1700 540 700 250 | Out-Null
    }
    Start-Sleep -Seconds 1
    $xml = Get-Ecran
    if (-not (Test-DansApp -Xml $xml)) { throw "Sorti de l'application — mesure jetee." }
    if (-not $Apercu -and -not (Test-SurEcranEditeur -Xml $xml -Hauteur $script:Hauteur)) {
        throw "Sorti de l'editeur pendant le defilement — mesure jetee."
    }

    # Un balayage dans le vide ne fait rien dessiner, et « 3 images a 2 ms » se
    # lit comme un excellent resultat. C'est le meme piege que le tap avale,
    # par l'autre bout : verifier qu'il s'est passe quelque chose.
    $agg = Get-Agg
    $rendues = Get-Champ -Texte $agg -Motif 'Total frames rendered: (\d+)'
    if (($rendues -eq "?") -or ([int]$rendues -lt 20)) {
        throw "Moins de 20 images rendues : le defilement n'a rien fait bouger — mesure jetee."
    }

    $titre = "DEFILEMENT (6 balayages)"
    if ($Apercu) { $titre += " — APERCU" }
    Show-Mesure -Titre $titre -Agg $agg -Frames (Get-Frames)

    if ($Frappe -and -not $Apercu) {
        Invoke-Adb shell input touchscreen tap 540 900 | Out-Null   # curseur + clavier
        Start-Sleep -Seconds 4
        Reset-Gfx

        # Cinq caracteres ne font que 17 a 20 images. Tant que la frappe coutait
        # 750 ms, la mediane d'un si petit echantillon suffisait a conclure ;
        # a 25 ms elle ne suffit plus, car les premieres images d'une rafale
        # paient le reveil du pipeline et pesent lourd sur vingt valeurs.
        # -Caracteres 40 donne un echantillon comparable a celui du defilement.
        $lettres = "abcdefghijklmnopqrstuvwxyz".ToCharArray()
        for ($i = 0; $i -lt $Caracteres; $i++) {
            Invoke-Adb shell input text ([string]$lettres[$i % $lettres.Count]) | Out-Null
            Start-Sleep -Milliseconds 400
        }
        Start-Sleep -Seconds 2
        $titre = "FRAPPE ($Caracteres caracteres) — la note a ete modifiee"
        Show-Mesure -Titre $titre -Agg (Get-Agg) -Frames (Get-Frames)
    }
}
finally {
    if ($reseauCoupe) {
        Invoke-Adb shell svc wifi enable | Out-Null
        Invoke-Adb shell svc data enable | Out-Null
        Write-Host ""
        Write-Host "Reseau retabli."
    }
    Invoke-Adb shell rm -f /sdcard/ui.xml | Out-Null
}
