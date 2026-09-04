<#
.SYNOPSIS
    Signe et vérifie l'APK release OpenNote avec apksigner 34.0.0.

.DESCRIPTION
    Par défaut, le script signe l'APK non signé produit par la CI Linux pour le
    commit courant, qu'il télécharge avec `gh`. C'est le seul APK que F-Droid
    puisse reconstruire à l'identique, donc le seul publiable.

    Mesuré sur la 0.1.2 : le même commit donne 18 187 434 octets sous Linux et
    19 306 710 sous Windows, jusqu'au libgojni.so. Signer un build local revient
    à publier un binaire irreproductible — et le mode 2 de F-Droid échoue fermé,
    c'est-à-dire sans aucune publication plutôt qu'avec une publication dégradée.

    -Source <chemin> impose un APK précis. -Local reprend le build local de
    android/app/build/outputs, avec un avertissement : à réserver à un essai
    d'installation sur appareil, jamais à une publication.

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

    [Alias('Source')]
    [string] $UnsignedApk,

    # Signe le build local au lieu de l'artefact de CI. Jamais pour publier.
    [switch] $Local,

    [string] $OutputApk
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-ReleasePath {
    param([Parameter(Mandatory = $true)][string] $Path)

    # [IO.Path]::GetFullPath() se base sur le répertoire interne du processus
    # PowerShell, qui peut être C:\Windows\System32 même si l'invite affiche la
    # racine du dépôt. Les chemins relatifs de ce script sont donc toujours
    # relatifs au dépôt, jamais à ce répertoire interne.
    # IsPathRooted est disponible sur Windows PowerShell/.NET Framework,
    # contrairement à IsPathFullyQualified qui n'existe qu'en .NET récent.
    if (-not [IO.Path]::IsPathRooted($Path)) {
        $Path = Join-Path $repoDirectory $Path
    }

    return [IO.Path]::GetFullPath($Path)
}

function Get-CiUnsignedApk {
    # Télécharge l'APK non signé produit par la CI Linux pour le commit
    # courant, et renvoie son chemin.
    #
    # F-Droid reconstruit sous Linux et compare octet à octet hors signature.
    # Un APK construit sur le poste de développement ne correspond jamais : la
    # divergence est mesurée dans l'en-tête de ce script. D'où le choix du
    # défaut — l'artefact de CI —, le build local restant accessible par
    # -Local, à la demande et avec un avertissement.

    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "gh est introuvable. Installez GitHub CLI, ou passez -Source <chemin>, ou -Local pour signer le build local."
    }

    $commit = (& git -C $repoDirectory rev-parse HEAD)
    if ($LASTEXITCODE -ne 0 -or -not $commit) {
        throw "Impossible de lire le commit courant."
    }
    $commit = $commit.Trim()

    $etat = (& git -C $repoDirectory status --porcelain)
    if ($etat) {
        Write-Warning "L'arbre de travail n'est pas propre. L'artefact de CI correspond au commit $commit, pas à vos modifications locales."
    }

    Write-Host "Commit         : $commit"

    $json = & gh run list --commit $commit --status success --json databaseId,workflowName --limit 10
    if ($LASTEXITCODE -ne 0) {
        throw "gh run list a échoué. Le commit est-il poussé sur GitHub ?"
    }

    $runs = @($json | ConvertFrom-Json)
    if ($runs.Count -eq 0) {
        throw "Aucune exécution de CI réussie pour $commit.`nPoussez le commit et attendez la fin du workflow. Ou passez -Local, en sachant que l'APK produit ne sera pas reproductible par F-Droid."
    }

    $run = $runs[0]
    Write-Host "Artefact de CI : run $($run.databaseId) — $($run.workflowName)"

    $dossier = Join-Path $repoDirectory ('dist\ci-' + $commit.Substring(0, 12))
    if (Test-Path -LiteralPath $dossier) {
        Remove-Item -Recurse -Force -LiteralPath $dossier
    }
    New-Item -ItemType Directory -Force -Path $dossier | Out-Null

    & gh run download $run.databaseId -D $dossier
    if ($LASTEXITCODE -ne 0) {
        throw "Le téléchargement de l'artefact a échoué."
    }

    $trouves = @(Get-ChildItem -Path $dossier -Recurse -Filter 'app-release-unsigned.apk')
    if ($trouves.Count -ne 1) {
        throw "Attendu un seul app-release-unsigned.apk dans l'artefact, trouvé $($trouves.Count)."
    }

    return $trouves[0].FullName
}

if (-not $UnsignedApk) {
    if ($Local) {
        $UnsignedApk = Join-Path $repoDirectory `
            'android\app\build\outputs\apk\release\app-release-unsigned.apk'
        Write-Warning 'Signature du build LOCAL. F-Droid ne pourra pas le reproduire : ne publiez pas cet APK.'
    } else {
        $UnsignedApk = Get-CiUnsignedApk
    }
}
if (-not $OutputApk) {
    $OutputApk = Join-Path $repoDirectory 'dist\OpenNote-release-signed.apk'
}

$keystorePath = Resolve-ReleasePath $Keystore
$unsignedPath = Resolve-ReleasePath $UnsignedApk
$outputPath = Resolve-ReleasePath $OutputApk

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
