<#
.SYNOPSIS
    Banc de mesure de l'éditeur : combien coûte une image, et dans quelle phase.

.DESCRIPTION
    Ce script existe parce qu'un chiffre de performance sans protocole ne vaut
    rien, et parce que le protocole a trois pièges qui produisent des résultats
    faux mais plausibles. Les relevés qu'il produit sont ceux de la section
    7 bis de docs/ARCHITECTURE.md.

    Ce qu'il mesure, et pourquoi ces colonnes-là :

        PerformTraversalsStart -> DrawStart   mesure et layout
        DrawStart              -> SyncQueued  enregistrement de la display list

    C'est la seconde qui coûte. Sur une note de 295 ko, la première tient
    0,11 ms et la seconde 505 ms — d'où le chantier docs/CHANTIER-EDITEUR.md.

    Les trois pièges, chacun payé une fois :

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

.PARAMETER Note
    Nom de la note à mesurer, tel qu'il s'affiche dans la liste.

.PARAMETER Frappe
    Mesure aussi le coût de la frappe. ATTENTION : cela MODIFIE la note.
    À n'utiliser que sur une note jetable d'un environnement de test.

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
# des deux : le champ de saisie de l'editeur occupe l'ecran, celui de la
# recherche est une barre mince.
function Test-DansApp {
    param([string] $Xml)
    return ($Xml -like "*package=""$Paquet""*")
}

function Test-DansEditeur {
    param([string] $Xml, [int] $Hauteur)
    $motif = '<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        $h = [int]$m.Groups[2].Value - [int]$m.Groups[1].Value
        if ($h -gt ($Hauteur * 0.4)) { return $true }
    }
    return $false
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

# Champ de recherche : le seul EditText mince de l'ecran. L'editeur, lui, en a
# un qui occupe la hauteur — c'est le meme critere geometrique que plus haut.
function Get-ChampRecherche {
    param([string] $Xml, [int] $Hauteur)
    $motif = '<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $motif)) {
        $h = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
        if ($h -lt ($Hauteur * 0.4)) {
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
        # En apercu il n'y a plus de champ de saisie : c'est le critere.
        if ((Test-DansApp -Xml $xml) -and -not (Test-DansEditeur -Xml $xml -Hauteur $script:Hauteur)) {
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
        if ((Get-Ecran) -match '<node[^>]*class="android\.widget\.EditText"[^>]*focused="true"') {
            $focalise = $true
            break
        }
    }
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
        if (Test-DansEditeur -Xml (Get-Ecran) -Hauteur $script:Hauteur) { return }
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

    $layout = ($images | Measure-Object Layout -Average).Average
    $dessin = ($images | Measure-Object Dessin -Average).Average
    $dmax   = ($images | Measure-Object Dessin -Maximum).Maximum
    $rendu  = ($images | Measure-Object Rendu  -Average).Average

    Write-Host ""
    Write-Host "  $Titre"
    Write-Host ("    images rendues     : {0}" -f $total)
    Write-Host ("    images en retard   : {0} %" -f $jank)
    Write-Host ("    mediane par image  : {0} ms" -f $median)
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
        # L'editeur s'ouvre curseur en fin de note, l'apercu en haut : on
        # defile dans le sens ou il y a quelque chose a voir, sinon rien ne
        # bouge et aucune image n'est rendue.
        if ($Apercu) {
            Invoke-Adb shell input swipe 540 1700 540 700 250 | Out-Null
        } else {
            Invoke-Adb shell input swipe 540 700 540 1700 250 | Out-Null
        }
    }
    Start-Sleep -Seconds 1
    $xml = Get-Ecran
    if (-not (Test-DansApp -Xml $xml)) { throw "Sorti de l'application — mesure jetee." }
    if (-not $Apercu -and -not (Test-DansEditeur -Xml $xml -Hauteur $script:Hauteur)) {
        throw "Sorti de l'editeur pendant le defilement — mesure jetee."
    }
    $titre = "DEFILEMENT (6 balayages)"
    if ($Apercu) { $titre += " — APERCU" }
    Show-Mesure -Titre $titre -Agg (Get-Agg) -Frames (Get-Frames)

    if ($Frappe -and -not $Apercu) {
        Invoke-Adb shell input touchscreen tap 540 900 | Out-Null   # curseur + clavier
        Start-Sleep -Seconds 4
        Reset-Gfx
        foreach ($c in @("a", "b", "c", "d", "e")) {
            Invoke-Adb shell input text $c | Out-Null
            Start-Sleep -Milliseconds 400
        }
        Start-Sleep -Seconds 2
        Show-Mesure -Titre "FRAPPE (5 caracteres) — la note a ete modifiee" -Agg (Get-Agg) -Frames (Get-Frames)
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
