<#
.SYNOPSIS
    Met en état un fichier de traduction fraîchement rendu par un traducteur.

.DESCRIPTION
    Un traducteur part d'une copie de `values/strings.xml` et n'y remplace que
    les valeurs. C'est ce qu'on lui demande, et ça laisse quatre défauts que
    l'œil ne voit pas mais que la compilation ou l'affichage attrapent :

      1. les entrées `translatable="false"` sont recopiées — une clé en trop
         est une erreur de lint (`ExtraTranslation`) au même titre qu'une clé
         manquante ;
      2. une chaîne dont les espaces de bord comptent perd ses guillemets
         protecteurs, et aapt supprime les espaces sans rien dire ;
      3. une apostrophe non échappée fait échouer la compilation des
         ressources sur « Invalid unicode escape sequence », un message qui
         ne désigne pas sa cause ;
      4. l'en-tête annonce encore « la langue de référence, le français ».

    Ce script corrige les quatre et rend compte de ce qu'il a touché. Il est
    idempotent : le repasser sur un fichier déjà traité ne change rien.

    Ce qu'il ne fait pas, parce que lint le fait mieux : juger la traduction.
    Après passage, `./gradlew lintDebug` reste le contrôle qui compte.

.PARAMETER Fichier
    Le `values-<langue>/strings.xml` à normaliser, en place.

.PARAMETER Langue
    Le nom de la langue tel qu'il doit apparaître dans l'en-tête, accordé au
    féminin : « espagnole », « allemande ».

.EXAMPLE
    .\scripts\normalise-traduction.ps1 `
        -Fichier android/app/src/main/res/values-de/strings.xml -Langue allemande
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Fichier,
    [Parameter(Mandatory = $true)] [string] $Langue
)

$ErrorActionPreference = 'Stop'

$racine = Split-Path -Parent $PSScriptRoot
$reference = Join-Path $racine 'android/app/src/main/res/values/strings.xml'

if (-not (Test-Path $Fichier)) { throw "Fichier introuvable : $Fichier" }
if (-not (Test-Path $reference)) { throw "Référence introuvable : $reference" }

# `values-en/` porte l'en-tête long qui sert de modèle aux autres langues.
# Le passer ici le remplacerait par l'en-tête court, et le modèle serait
# perdu sans que personne ne s'en aperçoive avant d'en avoir besoin.
if ((Resolve-Path $Fichier).Path -match '[\\/]values-en[\\/]') {
    throw "values-en/ est le modèle : son en-tête ne doit pas être réécrit. " +
          "Ce script est fait pour les langues suivantes."
}

$ref = Get-Content -Raw -Encoding UTF8 $reference
$src = (Get-Content -Raw -Encoding UTF8 $Fichier) -replace "`r`n", "`n"
$journal = New-Object System.Collections.Generic.List[string]

# --- 1. L'en-tête : ce fichier n'est pas la référence ------------------
#
# La consigne de traduction complète vit dans `values-en/strings.xml`, qui
# sert de modèle. La recopier dans chaque langue garantirait qu'elle devienne
# fausse quelque part.
$enTete = @"
<!--
    Traduction $Langue.

    La référence est ``values/strings.xml``, en français : elle seule fait foi,
    elle seule est touchée par le code. Les règles de traduction — clés
    recopiées telles quelles, paramètres de format conservés, apostrophe
    échappée, formes de pluriel propres à la langue — sont écrites en tête de
    ``values-en/strings.xml``, qui sert de modèle.
-->
"@

$debut = $src.IndexOf('<!--')
$fin = $src.IndexOf('-->')
if ($debut -lt 0 -or $fin -lt 0) { throw "En-tête introuvable dans $Fichier" }

$ancien = $src.Substring($debut, $fin - $debut + 3)
if ($ancien -ne $enTete.TrimEnd("`r", "`n")) {
    $src = $src.Substring(0, $debut) + $enTete.TrimEnd("`r", "`n") + $src.Substring($fin + 3)
    $journal.Add('en-tête réécrit')
}

# --- 2. Les non traduisibles n'ont pas leur place dans une traduction --
foreach ($m in [regex]::Matches($ref, '<string name="([^"]+)" translatable="false">')) {
    $cle = $m.Groups[1].Value
    $motif = '[ \t]*<string name="' + [regex]::Escape($cle) + '"[^>]*>.*?</string>\r?\n'
    if ($src -match $motif) {
        $src = [regex]::Replace($src, $motif, '')
        $journal.Add("retiré (non traduisible) : $cle")
    }
}

# Le commentaire du tiret cadratin décrivait une entrée qui vient de partir.
$src = [regex]::Replace(
    $src,
    '[ \t]*<!-- Valeur absente dans une fiche de réglages.*?-->\n\n?',
    '',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
$src = $src -replace '<resources>\n\n', "<resources>`n"

# --- 3. Espaces de bord : sans guillemets, aapt les supprime -----------
$src = [regex]::Replace($src, '(<string name="[^"]+"[^>]*>)([^<]*)(</string>)', {
        param($m)
        $valeur = $m.Groups[2].Value
        $nu = $valeur.Trim()
        if ($valeur -ne $nu -and -not ($valeur.StartsWith('"') -and $valeur.EndsWith('"'))) {
            $script:journal.Add("espaces protégés : $($m.Groups[1].Value)")
            return $m.Groups[1].Value + '"' + $valeur + '"' + $m.Groups[3].Value
        }
        return $m.Value
    })

# --- 4. Apostrophes : sans antislash, aapt échoue ----------------------
$src = [regex]::Replace($src, '(<(?:string|item)[^>]*>)([^<]*)(</(?:string|item)>)', {
        param($m)
        $valeur = $m.Groups[2].Value
        $corrige = [regex]::Replace($valeur, "(?<!\\)'", "\'")
        if ($corrige -ne $valeur) {
            $script:journal.Add("apostrophe échappée : $($corrige.Substring(0, [Math]::Min(50, $corrige.Length)))")
            return $m.Groups[1].Value + $corrige + $m.Groups[3].Value
        }
        return $m.Value
    })

# --- Écriture en UTF-8 sans BOM et en LF, comme tout le dépôt ----------
$utf8SansBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $Fichier), $src, $utf8SansBom)

# --- Compte rendu ------------------------------------------------------
Write-Output $Fichier
if ($journal.Count -eq 0) {
    Write-Output '  rien à corriger'
}
else {
    $journal | ForEach-Object { Write-Output "  $_" }
}

# Un mot sur ce que ce script ne sait pas voir.
$clesRef = [regex]::Matches($ref, '<(?:string|plurals) name="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value }
$clesTra = [regex]::Matches($src, '<(?:string|plurals) name="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value }
$manquantes = $clesRef | Where-Object { $clesTra -notcontains $_ -and $ref -notmatch ('<string name="' + [regex]::Escape($_) + '" translatable="false"') }
$enTrop = $clesTra | Where-Object { $clesRef -notcontains $_ }

if ($manquantes) { Write-Output "  MANQUANTES ($($manquantes.Count)) : $($manquantes -join ', ')" }
if ($enTrop) { Write-Output "  EN TROP ($($enTrop.Count)) : $($enTrop -join ', ')" }
if (-not $manquantes -and -not $enTrop) { Write-Output '  toutes les clés sont là' }
