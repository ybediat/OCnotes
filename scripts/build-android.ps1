<#
.SYNOPSIS
    Chaîne complète : binding gomobile, APK debug ou release signé, install.

.DESCRIPTION
    Rejoue les étapes du build Android dans l'ordre, avec les variables
    d'environnement que la session neuve n'a jamais :

      1. PATH pour Go, ANDROID_HOME et ANDROID_NDK_HOME pour gomobile ;
      2. suite unitaire Go (`go test ./... -short`) — sautée avec -SansTests ;
      3. `gomobile bind` vers android/app/libs/opennote.aar — sauté avec
         -SansBind si mobile/ n'a pas bougé (l'étape coûte une minute) ;
      4a. debut : `./gradlew assembleDebug`, puis `adb install -r` ;
      4b. avec -Release : `./gradlew assembleRelease`, signature via
          scripts/sign-android-release.ps1, puis install de l'APK signé.

    Rien n'est persisté : les variables ne vivent que le temps du script.
    Toute étape qui échoue arrête la chaîne (`$ErrorActionPreference = Stop`
    pour les cmdlets, contrôle du code de sortie pour les exécutables).

.PARAMETER SansBind
    Ne relance pas `gomobile bind`. À n'utiliser que si aucun fichier de
    mobile/ n'a changé depuis le dernier binding — sinon Kotlin compile
    contre l'ancien .aar et se plaint d'un symbole que vous venez d'écrire.

.PARAMETER SansTests
    Saute `go test`. Pour une itération purement UI où le cœur n'a pas bougé.

.PARAMETER SansInstall
    Construit l'APK mais ne l'installe pas. Utile sans appareil branché.

.PARAMETER Serial
    Numéro de série adb, à passer si plusieurs appareils sont connectés.
    Par défaut, adb choisit seul quand il n'y en a qu'un.

.PARAMETER Release
    Construit l'APK release et le signe. Exige -Keystore. apksigner demande
    le mot de passe de la clé dans le terminal : ce script ne le lit jamais.

.PARAMETER Keystore
    Chemin du keystore de signature release. Obligatoire avec -Release.

.PARAMETER Alias
    Alias de la clé dans le keystore. Défaut : opennote-release.

.EXAMPLE
    .\scripts\build-android.ps1
    Build debug complet et installation.

.EXAMPLE
    .\scripts\build-android.ps1 -SansBind -SansTests
    Rebuild rapide de l'APK debug après une retouche d'écran Compose.

.EXAMPLE
    .\scripts\build-android.ps1 -Release -Keystore C:\cles\opennote.jks
    Build release, signature (mot de passe demandé par apksigner), install.
#>

[CmdletBinding()]
param(
    [switch] $SansBind,
    [switch] $SansTests,
    [switch] $SansInstall,
    [string] $Serial,
    [switch] $Release,
    [string] $Keystore,
    [string] $Alias = "opennote-release"
)

$ErrorActionPreference = "Stop"

if ($Release -and -not $Keystore) {
    throw "-Release exige -Keystore <chemin du .jks>."
}

# Racine du dépôt : le dossier parent de scripts/, quel que soit le cwd.
$racine = Split-Path -Parent $PSScriptRoot
Set-Location $racine

# --- 1. Environnement ---------------------------------------------------------
$env:PATH = "C:\Program Files\Go\bin;$env:USERPROFILE\go\bin;$env:PATH"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

$ndkRacine = Join-Path $env:ANDROID_HOME "ndk"
$ndk = Get-ChildItem $ndkRacine -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndk) {
    throw "Aucun NDK trouvé sous $ndkRacine. Installez-le via Android Studio."
}
$env:ANDROID_NDK_HOME = $ndk.FullName
Write-Host "NDK : $($env:ANDROID_NDK_HOME)" -ForegroundColor Cyan

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

# --- 2. Tests Go ------------------------------------------------------------
if ($SansTests) {
    Write-Host "Tests Go sautés (-SansTests)." -ForegroundColor Yellow
} else {
    Write-Host "go test ./... -short" -ForegroundColor Cyan
    go test ./... -short
    if ($LASTEXITCODE -ne 0) { throw "Les tests Go ont échoué." }
}

# --- 3. Binding gomobile -------------------------------------------------------
if ($SansBind) {
    Write-Host "gomobile bind sauté (-SansBind)." -ForegroundColor Yellow
} else {
    Write-Host "gomobile bind -> android/app/libs/opennote.aar" -ForegroundColor Cyan
    gomobile bind -target=android/arm64 -androidapi 26 -ldflags="-s -w" `
        -o android/app/libs/opennote.aar ./mobile
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind a échoué." }
}

# --- 4. APK --------------------------------------------------------------------
$tacheGradle = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Write-Host "gradlew $tacheGradle" -ForegroundColor Cyan
Push-Location (Join-Path $racine "android")
try {
    & .\gradlew.bat $tacheGradle
    if ($LASTEXITCODE -ne 0) { throw "$tacheGradle a échoué." }
} finally {
    Pop-Location
}

if ($Release) {
    $apkBrut = Join-Path $racine "android\app\build\outputs\apk\release\app-release-unsigned.apk"
    if (-not (Test-Path $apkBrut)) { throw "APK release introuvable : $apkBrut" }

    # --- 4 bis. Signature ---------------------------------------------------
    Write-Host "Signature via sign-android-release.ps1" -ForegroundColor Cyan
    Write-Host "Le mot de passe de la clé sera demandé par apksigner." -ForegroundColor Yellow
    & (Join-Path $PSScriptRoot "sign-android-release.ps1") `
        -Keystore $Keystore -Alias $Alias -UnsignedApk $apkBrut
    if ($LASTEXITCODE -ne 0) { throw "La signature a échoué." }

    $apk = Join-Path $racine "dist\OpenNote-release-signed.apk"
    $paquet = "eu.opennote"
} else {
    $apk = Join-Path $racine "android\app\build\outputs\apk\debug\app-debug.apk"
    $paquet = "eu.opennote.debug"
}

if (-not (Test-Path $apk)) { throw "APK introuvable : $apk" }
Write-Host "APK : $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) Mo)" -ForegroundColor Green

# --- 5. Installation --------------------------------------------------------
if ($SansInstall) {
    Write-Host "Installation sautée (-SansInstall)." -ForegroundColor Yellow
    return
}

$cible = @()
if ($Serial) { $cible = @("-s", $Serial) }

Write-Host "adb install -r ($paquet)" -ForegroundColor Cyan
& $adb @cible install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "Échec de l'installation incrémentale. Tentative après désinstallation..." -ForegroundColor Yellow
    & $adb @cible uninstall $paquet
    & $adb @cible install $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install a échoué." }
}
Write-Host "Installé : $paquet" -ForegroundColor Green
