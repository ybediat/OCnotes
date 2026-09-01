<#
.SYNOPSIS
    Compare un android.widget.EditText monolithique à l'éditeur Compose mesuré
    par banc-editeur.ps1.

.DESCRIPTION
    La sonde charge la même note par le vrai dépôt OpenNote et lui applique le
    même PrepareEdit. Elle ne sauvegarde jamais : frappe, sélection et copie
    restent confinées à l'activité debug.

    Une passe -Prechauffer doit précéder les mesures après installation. Elle
    laisse le réseau actif afin de remplir le cache. Les mesures suivantes
    coupent Wi-Fi et données, comme le banc historique.

.EXAMPLE
    ./scripts/banc-edittext-natif.ps1 -Prechauffer

.EXAMPLE
    ./scripts/banc-edittext-natif.ps1 -Frappe -Selection
#>

[CmdletBinding()]
param(
    [string] $Note = "scolarisation des enfants rrom",
    [string] $Dossier = "env test",
    [switch] $Prechauffer,
    [switch] $Frappe,
    [switch] $Selection,
    [int] $Caracteres = 40,
    [string] $Paquet = "eu.opennote.debug",
    [string] $Passe = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$ActivitePrincipale = "$Paquet/eu.opennote.ui.MainActivity"
$ActiviteSonde = "$Paquet/eu.opennote.ui.editor.NativeEditTextProbeActivity"
$Tag = "OpenNoteNativeProbe"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Arguments)
    & adb @Arguments
}

function Get-JournalSonde {
    # Les options sont passees explicitement dans le tableau : sinon PowerShell
    # absorbe `-d` comme abreviation de son parametre commun `-Debug`, et logcat
    # reste attache au flux au lieu de rendre la main.
    return (Invoke-Adb -Arguments @('logcat', '-d', '-s', "${Tag}:I", '*:S'))
}

function Wait-SondePrete {
    for ($i = 0; $i -lt 30; $i++) {
        $journal = Get-JournalSonde
        if ($journal -match ' READY ') { return $journal }
        if ($journal -match ' ERROR ') {
            throw (($journal | Select-String ' ERROR ' | Select-Object -First 1).Line)
        }
        Start-Sleep -Milliseconds 500
    }
    throw "La sonde n'a pas atteint READY en 15 secondes."
}

function Test-SondeAuPremierPlan {
    $activites = Invoke-Adb shell dumpsys activity activities
    return ($activites -match 'topResumedActivity=.*NativeEditTextProbeActivity')
}

function Start-Sonde {
    Invoke-Adb logcat -c | Out-Null
    # `adb shell` reçoit une commande unique afin que les espaces des extras
    # restent protégés jusqu'au parseur `am` exécuté sur l'appareil.
    $commande = "am start -n $ActiviteSonde --es note '$Note' --es dossier '$Dossier'"
    Invoke-Adb shell $commande | Out-Null
    Write-Verbose "Activite sonde lancee, attente de READY."
    $journal = Wait-SondePrete
    Write-Verbose "READY recu, verification de l'activite au premier plan."
    if (-not (Test-SondeAuPremierPlan)) {
        throw "NativeEditTextProbeActivity n'est pas l'activité au premier plan."
    }
    Write-Verbose "Activite sonde confirmee au premier plan."
    return $journal
}

function Measure-Frames {
    param([string[]] $Lignes)

    $dedans = $false
    $images = @()
    foreach ($ligne in $Lignes) {
        if ($ligne -match '^---PROFILEDATA---') { $dedans = -not $dedans; continue }
        if (-not $dedans -or $ligne -match '^Flags') { continue }

        $c = $ligne.Split(',')
        if ($c.Count -lt 21) { continue }
        if (([int64]$c[0] -band 1) -ne 0) { continue }

        $ms = 1000000.0
        $images += [pscustomobject]@{
            Reveil = ([int64]$c[7]  - [int64]$c[2])  / $ms
            Image  = ([int64]$c[16] - [int64]$c[2])  / $ms
            Layout = ([int64]$c[8]  - [int64]$c[7])  / $ms
            Dessin = ([int64]$c[12] - [int64]$c[8])  / $ms
            Rendu  = ([int64]$c[15] - [int64]$c[14]) / $ms
        }
    }
    return $images
}

function Get-Champ {
    param([string[]] $Texte, [string] $Motif)
    $match = $Texte | Select-String $Motif | Select-Object -First 1
    if ($null -eq $match) { return "?" }
    return $match.Matches[0].Groups[1].Value
}

function Show-Mesure {
    param([string] $Titre)

    $agg = Invoke-Adb shell dumpsys gfxinfo $Paquet
    $frames = Invoke-Adb shell dumpsys gfxinfo $Paquet framestats
    $images = Measure-Frames -Lignes $frames
    if ($images.Count -eq 0) {
        Write-Host "  $Titre : aucune image mesuree"
        return
    }

    $total = Get-Champ -Texte $agg -Motif 'Total frames rendered: (\d+)'
    $jank = Get-Champ -Texte $agg -Motif 'Janky frames: \d+ \(([0-9.]+)'
    $mediane = Get-Champ -Texte $agg -Motif '^50th percentile: (\d+)ms'
    $p90 = Get-Champ -Texte $agg -Motif '^90th percentile: (\d+)ms'
    $reveil = ($images | Measure-Object Reveil -Average).Average
    $image = ($images | Measure-Object Image -Average).Average
    $layout = ($images | Measure-Object Layout -Average).Average
    $dessin = ($images | Measure-Object Dessin -Average).Average
    $dmax = ($images | Measure-Object Dessin -Maximum).Maximum
    $rendu = ($images | Measure-Object Rendu -Average).Average

    Write-Host ""
    Write-Host "  $Titre"
    Write-Host ("    images rendues     : {0}" -f $total)
    Write-Host ("    images en retard   : {0} %" -f $jank)
    Write-Host ("    mediane / p90      : {0} / {1} ms" -f $mediane, $p90)
    Write-Host ("    avant traversals   : {0:N2} ms" -f $reveil)
    Write-Host ("    image complete     : {0:N2} ms" -f $image)
    Write-Host ("    mesure + layout    : {0:N2} ms" -f $layout)
    Write-Host ("    DESSIN             : {0:N2} ms   (max {1:N2})" -f $dessin, $dmax)
    Write-Host ("    RenderThread       : {0:N2} ms" -f $rendu)
}

function Reset-Gfx {
    Invoke-Adb shell dumpsys gfxinfo $Paquet reset | Out-Null
}

function Show-Memoire {
    $memoire = Invoke-Adb shell dumpsys meminfo $Paquet
    $pss = Get-Champ -Texte $memoire -Motif '^\s*TOTAL\s+(\d+)'
    Write-Host ""
    Write-Host "  MEMOIRE"
    Write-Host "    TOTAL PSS          : $pss KB"
}

$reseauCoupe = $false
try {
    if (-not (Invoke-Adb devices | Select-String '\sdevice$')) {
        throw "Aucun appareil connecté."
    }

    $modeleAppareil = (Invoke-Adb shell getprop ro.product.model | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($modeleAppareil)) {
        throw "Le modèle de l'appareil n'a pas pu être lu."
    }
    Write-Host "Passe : $Passe"
    Write-Host "Appareil : $modeleAppareil"

    if ($Prechauffer) {
        Write-Host "Préchauffage : réseau actif, aucune mesure retenue."
    } else {
        Write-Host "Réseau coupé pendant la mesure."
        Invoke-Adb shell svc wifi disable | Out-Null
        Invoke-Adb shell svc data disable | Out-Null
        $reseauCoupe = $true
    }

    # Comme le banc historique : le processus et la session sont déjà montés,
    # puis gfxinfo est remis à zéro juste avant l'ouverture de la note.
    Invoke-Adb shell am force-stop $Paquet | Out-Null
    Invoke-Adb shell am start -n $ActivitePrincipale | Out-Null
    Start-Sleep -Seconds 5
    Reset-Gfx
    Write-Verbose "Gfxinfo remis a zero, lancement de la sonde."
    $journal = Start-Sonde
    Write-Verbose "Sonde rendue au pilote."

    $ready = ($journal | Select-String ' READY ' | Select-Object -First 1).Line
    Write-Host $ready
    if ($Prechauffer) {
        Write-Host "Note chargée. Relancez sans -Prechauffer pour mesurer."
        return
    }

    Show-Mesure -Titre "OUVERTURE"
    Show-Memoire

    Reset-Gfx
    for ($i = 0; $i -lt 6; $i++) {
        Invoke-Adb shell input swipe 540 1700 540 700 250 | Out-Null
    }
    Start-Sleep -Seconds 1
    if (-not (Test-SondeAuPremierPlan)) { throw "Sonde quittée pendant le défilement." }
    $aggDefilement = Invoke-Adb shell dumpsys gfxinfo $Paquet
    $rendues = Get-Champ -Texte $aggDefilement -Motif 'Total frames rendered: (\d+)'
    if (($rendues -eq "?") -or ([int]$rendues -lt 20)) {
        throw "Moins de 20 images : le champ natif n'a pas défilé."
    }
    Show-Mesure -Titre "DEFILEMENT (6 balayages)"

    # Repos focalisé : clavier masqué mais curseur conservé, huit secondes.
    Invoke-Adb shell input touchscreen tap 540 900 | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell input keyevent 111 | Out-Null
    Start-Sleep -Seconds 1
    Reset-Gfx
    Start-Sleep -Seconds 8
    Show-Mesure -Titre "REPOS FOCALISE (8 secondes)"

    if ($Frappe) {
        Invoke-Adb shell input touchscreen tap 540 900 | Out-Null
        Start-Sleep -Seconds 3
        Reset-Gfx
        $lettres = "abcdefghijklmnopqrstuvwxyz".ToCharArray()
        for ($i = 0; $i -lt $Caracteres; $i++) {
            Invoke-Adb shell input text ([string]$lettres[$i % $lettres.Count]) | Out-Null
            Start-Sleep -Milliseconds 400
        }
        Start-Sleep -Seconds 2
        Show-Mesure -Titre "FRAPPE ($Caracteres caractères, non sauvegardés)"
    }

    if ($Selection) {
        Reset-Gfx
        Invoke-Adb shell input keycombination 113 29 | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Adb shell input keycombination 113 31 | Out-Null
        Start-Sleep -Seconds 2
        Show-Mesure -Titre "TOUT SELECTIONNER + COPIER"

        $journal = Get-JournalSonde
        $ligneSelection = ($journal | Select-String ' SELECT_ALL ' | Select-Object -Last 1).Line
        $ligneCopie = ($journal | Select-String ' COPY ' | Select-Object -Last 1).Line
        if ([string]::IsNullOrWhiteSpace($ligneSelection)) {
            throw "Aucun SELECT_ALL exact journalisé par le champ natif."
        }
        if ([string]::IsNullOrWhiteSpace($ligneCopie)) {
            throw "Aucune copie journalisée par le champ natif."
        }
        Write-Host ""
        Write-Host "  EXACTITUDE"
        Write-Host "    $ligneSelection"
        Write-Host "    $ligneCopie"
    }
}
finally {
    if ($reseauCoupe) {
        Invoke-Adb shell svc wifi enable | Out-Null
        Invoke-Adb shell svc data enable | Out-Null
        Write-Host ""
        Write-Host "Réseau rétabli."
    }
}
