<#
.SYNOPSIS
    Signe et vérifie l'APK release OpenNote avec apksigner 34.0.0.

.DESCRIPTION
    apksigner demande le mot de passe directement dans le terminal. Aucun mot
    de passe n'est accepté par ce script, afin qu'il ne puisse pas finir dans
    l'historique du shell ou dans la liste des processus.

    Build Tools 34 est volontairement utilisé : les versions plus récentes ont
    eu des incompatibilités avec la copie de signatures des builds
    reproductibles F-Droid.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Keystore,

    [string] $Alias = 'opennote-release',

    [string] $UnsignedApk,

    [string] $OutputApk
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if (-not $UnsignedApk) {
    $UnsignedApk = Join-Path $repoDirectory `
        'android\app\build\outputs\apk\release\app-release-unsigned.apk'
}
if (-not $OutputApk) {
    $OutputApk = Join-Path $repoDirectory 'dist\OpenNote-release-signed.apk'
}

$keystorePath = [IO.Path]::GetFullPath($Keystore)
$unsignedPath = [IO.Path]::GetFullPath($UnsignedApk)
$outputPath = [IO.Path]::GetFullPath($OutputApk)

if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw "Clé introuvable : $keystorePath"
}
if (-not (Test-Path -LiteralPath $unsignedPath -PathType Leaf)) {
    throw "APK non signé introuvable : $unsignedPath`nLancez d'abord le build release."
}
if ($unsignedPath -eq $outputPath) {
    throw "L'APK signé doit avoir un chemin différent de l'APK non signé."
}

$androidSdk = $env:ANDROID_SDK_ROOT
if (-not $androidSdk) { $androidSdk = $env:ANDROID_HOME }
if (-not $androidSdk) {
    $androidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$apksigner = Join-Path $androidSdk 'build-tools\34.0.0\apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "apksigner 34.0.0 est introuvable : $apksigner"
}

$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

Write-Host "APK source : $unsignedPath"
Write-Host "APK signé  : $outputPath"
Write-Host "Clé        : $keystorePath"
Write-Host "Alias      : $Alias"
Write-Host 'Le mot de passe sera demandé par apksigner et ne sera pas affiché.'
Write-Host ''

& $apksigner sign `
    --ks $keystorePath `
    --ks-key-alias $Alias `
    --v4-signing-enabled false `
    --out $outputPath `
    $unsignedPath

if ($LASTEXITCODE -ne 0) {
    throw "La signature a échoué avec le code $LASTEXITCODE."
}

Write-Host ''
Write-Host 'Vérification de la signature :'
& $apksigner verify --verbose --print-certs $outputPath
if ($LASTEXITCODE -ne 0) {
    throw "La vérification a échoué avec le code $LASTEXITCODE."
}

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath
Write-Host ''
Write-Host "SHA-256 APK : $($hash.Hash.ToLowerInvariant())"
Write-Host "APK signé prêt : $outputPath"
