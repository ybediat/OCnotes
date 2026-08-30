<#
.SYNOPSIS
    Fabrique les fixtures .docx et .odt du chantier « lecture seule des
    documents bureautiques ».

.DESCRIPTION
    Les fixtures de ce dépôt viennent d'outils réels : celles de
    `internal/opencloud/testdata/` ont été capturées sur un vrai serveur, et
    celles-ci sortent d'un vrai traitement de texte. Un XML écrit à la main
    testerait notre idée du format, pas le format.

    Le script écrit deux documents HTML source, puis les fait convertir par
    LibreOffice — deux fois chacun, en .docx et en .odt. Les deux fixtures d'un
    même document décrivent alors le même contenu, ce qui permet à
    `TestDocxEtOdtConvergent` de vérifier que les deux analyseurs arrivent aux
    mêmes blocs. C'est le test le plus rentable du chantier : un test format par
    format ne compare l'analyseur qu'à lui-même.

    Trois précautions, chacune payée une fois :

      1. On appelle `soffice.com`, pas `soffice.exe`. Sur Windows, le `.exe` se
         détache immédiatement : le script croirait avoir converti alors que
         rien n'est encore écrit.
      2. Le profil utilisateur est isolé (`-env:UserInstallation`). Sans ça, la
         conversion échoue sans un mot si une fenêtre LibreOffice est ouverte.
      3. Les deux formats partent du **même HTML**, chacun par sa propre
         conversion. Convertir le .docx en .odt ferait passer une erreur
         d'import pour une convergence.

    Le script est idempotent : il écrase les fixtures existantes.

.PARAMETER Sortie
    Dossier où ranger les fixtures. Par défaut `internal/documents/testdata`.

.PARAMETER LibreOffice
    Chemin de `soffice.com`. Par défaut l'installation standard.

.EXAMPLE
    powershell -File scripts/fixtures-documents.ps1
#>
[CmdletBinding()]
param(
    [string] $Sortie,
    [string] $LibreOffice = 'C:\Program Files\LibreOffice\program\soffice.com'
)

$ErrorActionPreference = 'Stop'

# `$PSScriptRoot` est vide au moment où PowerShell 5.1 évalue les valeurs par
# défaut d'un `param()` lancé via `-File` : la valeur se calcule donc ici, dans
# le corps, où `$MyInvocation` est renseigné. Le symptôme — « Impossible de
# lier l'argument au paramètre Path, car il s'agit d'une chaîne vide » — ne
# désigne pas sa cause.
if (-not $Sortie) {
    $Sortie = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\internal\documents\testdata'
}

# --- Sources ----------------------------------------------------------------

# La vitrine de structure. Chaque élément est là pour une ligne du tableau de
# correspondance du chantier : six niveaux de titre, les quatre mises en forme
# en ligne, un lien, une liste à puces, une liste numérotée imbriquée, et un
# tableau à en-tête.
#
# Les espaces autour des passages mis en forme comptent : ce sont elles qui
# obligent LibreOffice à poser `xml:space="preserve"` sur les `w:t` concernés,
# et donc l'analyseur à le lire.
$ExempleHtml = @'
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Exemple</title></head>
<body>
<h1>Titre de niveau 1</h1>
<p>Un paragraphe sans aucune mise en forme, pour la ligne de base.</p>
<h2>Titre de niveau 2</h2>
<p>Du texte <b>en gras</b> puis <i>en italique</i>, du <u>souligne</u> et du
<s>barre</s>, enfin un <a href="https://opencloud.eu/">lien vers OpenCloud</a>
suivi d'un point final.</p>
<h3>Titre de niveau 3</h3>
<h4>Titre de niveau 4</h4>
<h5>Titre de niveau 5</h5>
<h6>Titre de niveau 6</h6>
<h2>Listes</h2>
<ul>
<li>Premiere puce</li>
<li>Deuxieme puce</li>
</ul>
<ol>
<li>Premier point
  <ol>
  <li>Sous-point A</li>
  <li>Sous-point B</li>
  </ol>
</li>
<li>Deuxieme point</li>
</ol>
<h2>Tableau</h2>
<table border="1">
<thead><tr><th>Brique</th><th>Role</th></tr></thead>
<tbody>
<tr><td>Go</td><td>analyse</td></tr>
<tr><td>Compose</td><td>dessin</td></tr>
</tbody>
</table>
</body>
</html>
'@

# La fixture du piège du mot sans espace. Un document réel peut porter une
# suite de caractères qu'aucun moteur de retour à la ligne ne sait couper ;
# sans `markdown.ShortenLongWords`, elle fait tuer l'application par le
# système — constaté sur appareil, voir CLAUDE.md.
#
# 5 000 caractères : bien au-delà de `maxEditableWord` (2 000), bien en deçà
# d'une image en base64. Le mot doit traverser la conversion intact.
$MotLong = 'opennote' * 625

$MotLongHtml = @"
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Mot long</title></head>
<body>
<p>Avant le mot.</p>
<p>$MotLong</p>
<p>Apres le mot.</p>
</body>
</html>
"@

# --- Outils -----------------------------------------------------------------

function Ecrire-Utf8 {
    param([string] $Chemin, [string] $Contenu)

    # Sans BOM : c'est du HTML qui déclare son encodage, et LibreOffice lit le
    # `meta charset`. Un BOM ici finirait dans le premier paragraphe.
    [System.IO.File]::WriteAllText($Chemin, $Contenu, (New-Object System.Text.UTF8Encoding $false))
}

function Convertir {
    param(
        [string] $Source,
        [string] $Filtre,
        [string] $Extension,
        [string] $Dossier,
        [string] $Profil
    )

    $arguments = @(
        '--headless'
        '--norestore'
        "-env:UserInstallation=file:///$($Profil -replace '\\', '/')"
        '--convert-to', $Filtre
        '--outdir', $Dossier
        $Source
    )

    & $LibreOffice @arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "LibreOffice a rendu $LASTEXITCODE en convertissant $Source vers $Extension"
    }

    # Un code de retour nul ne prouve pas qu'un fichier est sorti : la
    # conversion peut échouer en silence sur un filtre mal nommé.
    $attendu = Join-Path $Dossier ([System.IO.Path]::GetFileNameWithoutExtension($Source) + $Extension)
    if (-not (Test-Path $attendu)) {
        throw "LibreOffice n'a produit aucun $Extension pour $Source"
    }
    return $attendu
}

# --- Travail ----------------------------------------------------------------

if (-not (Test-Path $LibreOffice)) {
    throw "soffice.com introuvable : $LibreOffice. Passez son chemin par -LibreOffice."
}

if (-not (Test-Path $Sortie)) {
    New-Item -ItemType Directory -Path $Sortie -Force | Out-Null
}
$Sortie = (Resolve-Path $Sortie).Path

$travail = Join-Path ([System.IO.Path]::GetTempPath()) ("opennote-fixtures-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$profil = Join-Path $travail 'profil'
New-Item -ItemType Directory -Path $travail -Force | Out-Null
New-Item -ItemType Directory -Path $profil -Force | Out-Null

try {
    $sources = @(
        @{ Nom = 'exemple';  Html = $ExempleHtml }
        @{ Nom = 'mot-long'; Html = $MotLongHtml }
    )
    $formats = @(
        @{ Filtre = 'docx:MS Word 2007 XML'; Extension = '.docx' }
        @{ Filtre = 'odt:writer8';           Extension = '.odt'  }
    )

    $produits = @()
    foreach ($source in $sources) {
        $html = Join-Path $travail ($source.Nom + '.html')
        Ecrire-Utf8 -Chemin $html -Contenu $source.Html

        foreach ($format in $formats) {
            Write-Host ("Conversion de {0} vers {1}..." -f $source.Nom, $format.Extension)
            $produit = Convertir -Source $html -Filtre $format.Filtre -Extension $format.Extension -Dossier $travail -Profil $profil
            $cible = Join-Path $Sortie ([System.IO.Path]::GetFileName($produit))
            Copy-Item -Path $produit -Destination $cible -Force
            $produits += $cible
        }
    }

    Write-Host ''
    Write-Host "Fixtures écrites dans $Sortie :"
    foreach ($p in $produits) {
        $taille = [math]::Round((Get-Item $p).Length / 1KB, 1)
        Write-Host ("  {0,-16} {1,7} ko" -f [System.IO.Path]::GetFileName($p), $taille)
    }
}
finally {
    # Le bac à sable disparaît même en cas d'échec, comme les tests
    # d'intégration du dépôt.
    Remove-Item -Path $travail -Recurse -Force -ErrorAction SilentlyContinue
}
