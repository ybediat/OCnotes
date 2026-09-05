# Helper partage par les scripts de release (dot-source ce fichier).
#
# Lit versionName directement dans un APK avec aapt2, plutot que de le deviner
# a partir du nom des fichiers deja presents dans dist/. C'est la seule source
# qui ne peut pas diverger de ce qui sera reellement publie : deduire le nom de
# sortie du contenu de dist/ reste "correct" meme si un fichier y manque ou si
# build.gradle.kts n'a pas ete rebump avant le build, et produit alors un nom
# qui ne correspond a rien de ce que l'APK contient reellement.

function Get-ApkVersionName {
    param([Parameter(Mandatory = $true)][string] $ApkPath)

    $androidSdk = $env:ANDROID_SDK_ROOT
    if (-not $androidSdk) { $androidSdk = $env:ANDROID_HOME }
    if (-not $androidSdk) { $androidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $aapt2 = Join-Path $androidSdk 'build-tools\34.0.0\aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "aapt2 34.0.0 est introuvable : $aapt2"
    }

    $badging = & $aapt2 dump badging $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 dump badging a echoue sur $ApkPath :`n$badging"
    }

    $ligne = $badging | Select-String -Pattern "versionName='([^']*)'" | Select-Object -First 1
    if (-not $ligne) {
        throw "versionName introuvable dans le badging de $ApkPath."
    }
    return $ligne.Matches[0].Groups[1].Value
}
