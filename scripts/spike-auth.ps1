<#
.SYNOPSIS
    Brique 1a — valide qu'un App Token OpenCloud donne acces au WebDAV.

.DESCRIPTION
    Enchaine quatre appels contre un serveur OpenCloud reel :
      1. GET      /ocs/v1.php/cloud/capabilities   -> l'auth Basic passe le proxy
      2. GET      /graph/v1.0/me/drives            -> decouverte des espaces
      3. PROPFIND /dav/spaces/{id}/                -> listing WebDAV
      4. PUT + DELETE d'un fichier temporaire      -> ecriture + ETag

    Les reponses brutes sont ecrites dans scripts/out/ et servent de fixtures
    aux tests unitaires de la brique 1b.

    Ce script ne valide que l'acces. Pour les operations completes (MKCOL,
    MOVE, detection de conflit par ETag), voir spike-webdav.ps1.

.EXAMPLE
    .\spike-auth.ps1 -ServerUrl https://cloud.exemple.fr -Username monlogin
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ServerUrl,
    [Parameter(Mandatory = $true)][string] $Username,

    # Resolu dans le corps : $PSScriptRoot n'est pas disponible dans les
    # valeurs par defaut du bloc param avec PowerShell 5.1 et -File.
    [string] $OutDir
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir 'lib\opencloud-http.ps1')

if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $scriptDir 'out' }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

$ServerUrl = $ServerUrl.TrimEnd('/')
if ($ServerUrl -notmatch '^https?://') {
    throw "ServerUrl doit commencer par http:// ou https:// (recu: $ServerUrl)"
}

$script:Failures = 0
$cfg = New-CurlAuthConfig -Username $Username

function Dav {
    param(
        [string] $Method, [string] $Url, [string] $OutFile,
        [string[]] $HeaderLines = @(), [string[]] $ExtraArgs = @()
    )
    return Invoke-Dav -ConfigPath $cfg -Method $Method -Url $Url -OutFile $OutFile `
                      -OutDir $OutDir -HeaderLines $HeaderLines -ExtraArgs $ExtraArgs
}

try {
    Write-Host ""
    Write-Host "Spike auth OpenCloud - $ServerUrl (utilisateur: $Username)" -ForegroundColor Cyan
    Write-Host ""

    # 1. Capabilities ---------------------------------------------------------
    Write-Host "1. Capabilities (l'auth Basic passe-t-elle le proxy ?)"
    $caps = Dav -Method GET -Url "$ServerUrl/ocs/v1.php/cloud/capabilities" -OutFile 'capabilities.xml'
    $ok = Write-Result -Label 'GET /ocs/v1.php/cloud/capabilities' -Status $caps.Status -Expected @(200)

    if (-not $ok -and $caps.Status -eq 401) {
        Write-Host ""
        Write-Host "  401 -> l'App Token est rejete. Causes probables :" -ForegroundColor Yellow
        Write-Host "    - le service auth-app n'est pas actif cote serveur"
        Write-Host "    - PROXY_ENABLE_BASIC_AUTH est desactive"
        Write-Host "    - l'IdP est en autoprovisioning : reessayer avec l'UUID du compte"
        Write-Host "      comme -Username (visible dans Preferences)"
        Write-Host ""
        Write-Host "  => si aucune piste n'aboutit, basculer sur OIDC des la v1." -ForegroundColor Yellow
        Write-Host ""
    }

    # 2. Drives ---------------------------------------------------------------
    Write-Host ""
    Write-Host "2. Decouverte des espaces (LibreGraph)"
    $drives = Dav -Method GET -Url "$ServerUrl/graph/v1.0/me/drives" -OutFile 'drives.json'
    Write-Result -Label 'GET /graph/v1.0/me/drives' -Status $drives.Status -Expected @(200) | Out-Null

    $personal = $null
    if ($drives.Status -eq 200) {
        try {
            $parsed = $drives.Body | ConvertFrom-Json
            foreach ($d in $parsed.value) {
                $wd = $null
                if ($d.root) { $wd = $d.root.webDavUrl }
                Write-Host ("         - {0,-10} {1,-24} {2}" -f $d.driveType, $d.name, $wd)
            }
            $personal = Get-PersonalDrive -DrivesJson $drives.Body
        }
        catch {
            Write-Host "  [WARN] reponse non parsable en JSON, voir $($drives.BodyPath)" -ForegroundColor Yellow
        }
    }

    if (-not $personal -or -not $personal.root.webDavUrl) {
        Write-Host ""
        Write-Host "Aucun espace exploitable : les tests WebDAV sont ignores." -ForegroundColor Yellow
        Write-Host "Repli possible : saisie manuelle de l'URL WebDAV dans l'app." -ForegroundColor Yellow
        $script:Failures++
        return
    }

    $davBase = $personal.root.webDavUrl.TrimEnd('/')
    Write-Host ""
    Write-Host "   Espace retenu : $($personal.name)  [$($personal.driveType)]"
    Write-Host "   WebDAV        : $davBase"

    # 3. PROPFIND -------------------------------------------------------------
    Write-Host ""
    Write-Host "3. PROPFIND (listing WebDAV)"
    $propfindBody = @'
<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:getetag/>
    <d:resourcetype/>
    <oc:fileid/>
    <oc:permissions/>
  </d:prop>
</d:propfind>
'@
    $pfFile = Join-Path $OutDir 'propfind-request.xml'
    [IO.File]::WriteAllText($pfFile, $propfindBody, (New-Object System.Text.UTF8Encoding($false)))

    $pf = Dav -Method PROPFIND -Url "$davBase/" -OutFile 'propfind-root.xml' `
              -HeaderLines @('Depth: 1', 'Content-Type: application/xml') `
              -ExtraArgs @('--data-binary', "@$pfFile")
    Write-Result -Label "PROPFIND (racine de l'espace)" -Status $pf.Status -Expected @(207) | Out-Null

    if ($pf.Status -eq 405) {
        Write-Host "  405 -> endpoint absent, essayer /remote.php/dav/spaces/{id}/" -ForegroundColor Yellow
    }
    if ($pf.Status -eq 207) {
        $count = ([regex]::Matches($pf.Body, '<[a-zA-Z0-9]+:response[\s>]')).Count
        Write-Host "         $count entree(s) retournee(s)"
    }

    # Le serveur annonce-t-il le verrouillage WebDAV (classe 2) ?
    $davClasses = Get-HeaderValue -Headers $pf.Headers -Name 'Dav'
    if ($davClasses) {
        Write-Host "         classes DAV annoncees : $davClasses"
        if ($davClasses -notmatch '(^|[\s,])2([\s,]|$)') {
            Write-Host "         -> pas de classe 2 : LOCK/UNLOCK indisponible." -ForegroundColor DarkGray
            Write-Host "            La concurrence repose uniquement sur les ETag." -ForegroundColor DarkGray
        }
    }

    # 4. PUT + DELETE ---------------------------------------------------------
    Write-Host ""
    Write-Host "4. Ecriture (PUT puis DELETE)"
    $probeName = "opennote-spike-$(Get-Date -Format 'yyyyMMdd-HHmmss').md"
    $probeFile = Join-Path $OutDir 'probe.md'
    [IO.File]::WriteAllText($probeFile,
        "# OpenNote spike`n`nFichier de test, supprime automatiquement.`n",
        (New-Object System.Text.UTF8Encoding($false)))

    $put = Dav -Method PUT -Url "$davBase/$probeName" -OutFile 'put.txt' `
               -HeaderLines @('Content-Type: text/markdown') `
               -ExtraArgs @('--data-binary', "@$probeFile")
    Write-Result -Label "PUT $probeName" -Status $put.Status -Expected @(201, 204) | Out-Null

    $etag = Get-HeaderValue -Headers $put.Headers -Name 'ETag'
    if ($etag) {
        Write-Host "         ETag = $etag"
        Write-Host "         -> la detection de conflit par If-Match est possible." -ForegroundColor Green
    }
    else {
        Write-Host "  [WARN] pas d'ETag renvoye au PUT : verifier avec un PROPFIND." -ForegroundColor Yellow
        Write-Host "         sans ETag, la strategie de sync de la brique 3 doit changer." -ForegroundColor Yellow
    }

    if ($put.Status -in @(201, 204)) {
        $del = Dav -Method DELETE -Url "$davBase/$probeName" -OutFile 'delete.txt'
        Write-Result -Label "DELETE $probeName" -Status $del.Status -Expected @(204, 200) | Out-Null
    }
}
finally {
    Remove-Item $cfg -Force -ErrorAction SilentlyContinue

    Write-Host ""
    if ($script:Failures -eq 0) {
        Write-Host "Tous les tests sont passes. L'auth par App Token est viable." -ForegroundColor Green
        Write-Host "Les fixtures sont dans $OutDir (a relire avant tout commit)." -ForegroundColor Green
    }
    else {
        Write-Host "$script:Failures test(s) en echec - voir les reponses dans $OutDir" -ForegroundColor Red
    }
    Write-Host ""
}
