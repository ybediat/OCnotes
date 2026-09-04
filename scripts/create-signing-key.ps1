<#
.SYNOPSIS
    Crée la clé de signature Android de production sans exposer son mot de passe.

.DESCRIPTION
    La clé est créée hors du dépôt par défaut. keytool demande le mot de passe
    directement dans le terminal : il ne passe ni en argument, ni dans
    l'historique PowerShell, ni dans les journaux de ce script.

    Cette clé devra signer toutes les futures versions distribuées avec la
    signature OpenNote. Perdre la clé empêche de publier une mise à jour pour
    les installations existantes.
#>

[CmdletBinding()]
param(
    [string] $Keystore = (Join-Path `
        ([Environment]::GetFolderPath('UserProfile')) `
        '.opennote\signing\opennote-release.p12'),

    [string] $Alias = 'opennote-release',

    [string] $DistinguishedName = 'CN=OpenNote, O=OpenNote, C=FR'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    throw 'keytool.exe est introuvable. Installez Android Studio ou un JDK récent.'
}

$keystorePath = [IO.Path]::GetFullPath($Keystore)
$keystoreDirectory = Split-Path -Parent $keystorePath

if (Test-Path -LiteralPath $keystorePath) {
    throw "Refus d'écraser une clé existante : $keystorePath"
}

New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null
$keytool = Resolve-Keytool

Write-Host "Création de la clé : $keystorePath"
Write-Host "Alias : $Alias"
Write-Host 'Le mot de passe sera demandé par keytool et ne sera pas affiché.'
Write-Host ''

& $keytool `
    -genkeypair `
    -v `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -sigalg SHA256withRSA `
    -validity 36500 `
    -dname $DistinguishedName

if ($LASTEXITCODE -ne 0) {
    throw "keytool a échoué avec le code $LASTEXITCODE."
}

Write-Host ''
Write-Host 'Clé créée. Avant toute première publication :'
Write-Host '  1. faites deux sauvegardes chiffrées sur des supports distincts ;'
Write-Host '  2. conservez le mot de passe dans un gestionnaire de mots de passe ;'
Write-Host '  3. ne copiez jamais la clé ou le mot de passe dans le dépôt.'
Write-Host ''
Write-Host "Pour signer : .\scripts\sign-android-release.ps1 -Keystore `"$keystorePath`""
