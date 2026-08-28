<#
.SYNOPSIS
    Brique 1b/3 — valide les operations WebDAV et la detection de conflit.

.DESCRIPTION
    Cree une arborescence temporaire sur l'espace personnel, y execute toutes
    les operations dont l'application aura besoin, capture les reponses comme
    fixtures de test, puis supprime tout.

    Deux tests portent des decisions d'architecture :

      * If-Match avec un ETag perime doit renvoyer 412. C'est l'unique
        mecanisme de detection de conflit de la brique 3 (le serveur
        n'annonce pas le verrouillage WebDAV : en-tete "Dav: 1, 3").

      * Un nom de fichier accentue et espace doit survivre a l'aller-retour.
        Les notes en francais en dependent.

    Le script nettoie derriere lui, y compris en cas d'erreur.

.EXAMPLE
    .\spike-webdav.ps1 -ServerUrl https://opencloud.a.rsrh.ovh -Username admin
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
if ($ServerUrl -notmatch '^https?://') { throw "ServerUrl doit commencer par http:// ou https://" }

$script:Failures = 0
$cfg      = New-CurlAuthConfig -Username $Username
$treeBase = $null

# --- Helpers locaux ---------------------------------------------------------

function New-TempMarkdown {
    param([string] $Path, [string] $Text)
    # Sans BOM : un fichier Markdown doit rester du texte brut.
    [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
    return $Path
}

function Join-DavUrl {
    <#
    .SYNOPSIS
        Concatene un segment de chemin en l'encodant pour l'URL.
    .NOTES
        EscapeDataString encode l'UTF-8 en pourcent, ce qui couvre les accents.
        Le '$' present dans les identifiants d'espace OpenCloud
        (storageId$spaceId) fait partie de $Base et n'est jamais re-encode :
        le serveur l'attend litteralement.
    #>
    param([string] $Base, [string] $Segment)
    return ($Base.TrimEnd('/') + '/' + [uri]::EscapeDataString($Segment))
}

function Dav {
    param(
        [string] $Method, [string] $Url, [string] $OutFile,
        [string[]] $HeaderLines = @(), [string[]] $ExtraArgs = @()
    )
    return Invoke-Dav -ConfigPath $cfg -Method $Method -Url $Url -OutFile $OutFile `
                      -OutDir $OutDir -HeaderLines $HeaderLines -ExtraArgs $ExtraArgs
}

# --- Deroule ----------------------------------------------------------------

try {
    Write-Host ""
    Write-Host "Spike WebDAV OpenCloud - $ServerUrl (utilisateur: $Username)" -ForegroundColor Cyan
    Write-Host ""

    # 0. Espace personnel -----------------------------------------------------
    $drives = Dav -Method GET -Url "$ServerUrl/graph/v1.0/me/drives" -OutFile 'drives.json'
    if ($drives.Status -ne 200) { throw "Impossible de lister les espaces (HTTP $($drives.Status))" }

    $personal = Get-PersonalDrive -DrivesJson $drives.Body
    if (-not $personal) { throw "Aucun espace personnel trouve." }

    $davBase  = $personal.root.webDavUrl.TrimEnd('/')
    $treeName = "opennote-spike-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    $treeBase = Join-DavUrl -Base $davBase -Segment $treeName

    Write-Host "Espace  : $($personal.name)  [$($personal.driveType)]"
    Write-Host "Racine  : $davBase"
    Write-Host "Bac a sable : $treeName/  (supprime a la fin)"
    Write-Host ""

    # 1. MKCOL ----------------------------------------------------------------
    Write-Host "1. Creation de dossiers (MKCOL)"
    $r = Dav -Method MKCOL -Url "$treeBase/" -OutFile 'mkcol-root.txt'
    Write-Result -Label "MKCOL $treeName/" -Status $r.Status -Expected @(201) | Out-Null

    $subUrl = Join-DavUrl -Base $treeBase -Segment 'Sous-dossier'
    $r = Dav -Method MKCOL -Url "$subUrl/" -OutFile 'mkcol-sub.txt'
    Write-Result -Label "MKCOL Sous-dossier/ (sous-dossier imbrique)" -Status $r.Status -Expected @(201) | Out-Null

    # 2. PUT ------------------------------------------------------------------
    Write-Host ""
    Write-Host "2. Ecriture de notes (PUT)"
    $f1 = New-TempMarkdown -Path (Join-Path $OutDir 'note-1.md') -Text "# Note 1`n`nContenu initial.`n"

    $note1Url = Join-DavUrl -Base $treeBase -Segment 'note-1.md'
    $r = Dav -Method PUT -Url $note1Url -OutFile 'put-note1.txt' `
             -HeaderLines @('Content-Type: text/markdown') -ExtraArgs @('--data-binary', "@$f1")
    Write-Result -Label 'PUT note-1.md' -Status $r.Status -Expected @(201, 204) | Out-Null

    $etag1 = Get-HeaderValue -Headers $r.Headers -Name 'ETag'
    Write-Host "         ETag = $etag1"

    # Nom accentue avec espaces : le cas normal pour des notes en francais.
    # Construit a partir de points de code afin que ce script reste en ASCII
    # pur : il se comporte alors pareil quel que soit l'encodage de lecture.
    $eAcute = [char]0x00E9
    $aGrave = [char]0x00E0
    $cCed   = [char]0x00E7
    $euro   = [char]0x20AC

    $accentName = "R$($eAcute)union du 15 - notes $aGrave relire.md"
    $accentBody = "# R$($eAcute)union`n`nAccents : $eAcute $aGrave $cCed $euro`n"
    $f2 = New-TempMarkdown -Path (Join-Path $OutDir 'note-accents.md') -Text $accentBody

    $accentUrl = Join-DavUrl -Base $treeBase -Segment $accentName
    $r = Dav -Method PUT -Url $accentUrl -OutFile 'put-accents.txt' `
             -HeaderLines @('Content-Type: text/markdown') -ExtraArgs @('--data-binary', "@$f2")
    Write-Result -Label "PUT '$accentName' (accents + espaces)" -Status $r.Status -Expected @(201, 204) | Out-Null

    $f3 = New-TempMarkdown -Path (Join-Path $OutDir 'note-2.md') -Text "# Note 2`n`nDans un sous-dossier.`n"
    $r = Dav -Method PUT -Url (Join-DavUrl -Base $subUrl -Segment 'note-2.md') -OutFile 'put-note2.txt' `
             -HeaderLines @('Content-Type: text/markdown') -ExtraArgs @('--data-binary', "@$f3")
    Write-Result -Label 'PUT Sous-dossier/note-2.md' -Status $r.Status -Expected @(201, 204) | Out-Null

    # 3. PROPFIND -------------------------------------------------------------
    Write-Host ""
    Write-Host "3. Listing d'une arborescence reelle (PROPFIND Depth 1)"
    $pfBody = @'
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
    [IO.File]::WriteAllText($pfFile, $pfBody, (New-Object System.Text.UTF8Encoding($false)))

    $pf = Dav -Method PROPFIND -Url "$treeBase/" -OutFile 'propfind-tree.xml' `
              -HeaderLines @('Depth: 1', 'Content-Type: application/xml') `
              -ExtraArgs @('--data-binary', "@$pfFile")
    Write-Result -Label 'PROPFIND Depth 1' -Status $pf.Status -Expected @(207) | Out-Null

    if ($pf.Status -eq 207) {
        $n = ([regex]::Matches($pf.Body, '<[a-zA-Z0-9]+:response[\s>]')).Count
        Write-Host "         $n entree(s) : le dossier lui-meme + son contenu direct"
    }

    # 4. Detection de conflit -------------------------------------------------
    Write-Host ""
    Write-Host "4. Detection de conflit par ETag (le coeur de la brique 3)"
    $f1b = New-TempMarkdown -Path (Join-Path $OutDir 'note-1-v2.md') -Text "# Note 1`n`nContenu modifie.`n"

    $r = Dav -Method PUT -Url $note1Url -OutFile 'put-ifmatch-ok.txt' `
             -HeaderLines @("If-Match: $etag1", 'Content-Type: text/markdown') `
             -ExtraArgs @('--data-binary', "@$f1b")
    Write-Result -Label 'PUT avec If-Match a jour' -Status $r.Status -Expected @(200, 204) | Out-Null
    $etag2 = Get-HeaderValue -Headers $r.Headers -Name 'ETag'
    Write-Host "         nouvel ETag = $etag2"

    $r = Dav -Method PUT -Url $note1Url -OutFile 'put-ifmatch-stale.txt' `
             -HeaderLines @('If-Match: "0000000000000000000000000000dead"',
                            'Content-Type: text/markdown') `
             -ExtraArgs @('--data-binary', "@$f1b")
    $ok = Write-Result -Label 'PUT avec If-Match perime (doit echouer)' -Status $r.Status -Expected @(412)

    if ($ok) {
        Write-Host "         -> 412 confirme : la strategie de sync de la brique 3 tient." -ForegroundColor Green
    }
    else {
        Write-Host "         -> le serveur accepte une ecriture sur un ETag perime." -ForegroundColor Red
        Write-Host "            Sans If-Match fiable, la brique 3 doit changer de" -ForegroundColor Red
        Write-Host "            strategie (comparaison de contenu avant push)." -ForegroundColor Red
    }

    # 5. MOVE et aller-retour -------------------------------------------------
    Write-Host ""
    Write-Host "5. Renommage (MOVE) et integrite du contenu (GET)"
    $renamedUrl = Join-DavUrl -Base $treeBase -Segment 'note-1-renommee.md'
    $r = Dav -Method MOVE -Url $note1Url -OutFile 'move.txt' `
             -HeaderLines @("Destination: $renamedUrl", 'Overwrite: F')
    Write-Result -Label 'MOVE note-1.md -> note-1-renommee.md' -Status $r.Status -Expected @(201, 204) | Out-Null

    $r = Dav -Method GET -Url $accentUrl -OutFile 'get-accents.md'
    Write-Result -Label 'GET du fichier accentue' -Status $r.Status -Expected @(200) | Out-Null

    if ($r.Status -eq 200) {
        $sent = [IO.File]::ReadAllBytes($f2)
        $back = [IO.File]::ReadAllBytes($r.BodyPath)
        if ([Convert]::ToBase64String($sent) -eq [Convert]::ToBase64String($back)) {
            Write-Host "         -> aller-retour identique octet pour octet (UTF-8 preserve)." -ForegroundColor Green
        }
        else {
            Write-Host "  [FAIL] le contenu differe apres aller-retour ($($sent.Length) -> $($back.Length) octets)" -ForegroundColor Red
            $script:Failures++
        }
    }
}
finally {
    # Nettoyage : supprime le bac a sable meme en cas d'erreur.
    if ($treeBase) {
        Write-Host ""
        Write-Host "Nettoyage..."
        try {
            $r = Dav -Method DELETE -Url "$treeBase/" -OutFile 'delete-tree.txt'
            if ($r.Status -in @(204, 200, 404)) {
                Write-Host "  Bac a sable supprime (HTTP $($r.Status))." -ForegroundColor DarkGray
            }
            else {
                Write-Host "  ATTENTION : suppression en HTTP $($r.Status). A retirer a la main." -ForegroundColor Yellow
            }
        }
        catch {
            Write-Host "  ATTENTION : nettoyage impossible. Dossier a retirer a la main." -ForegroundColor Yellow
        }
    }

    Remove-Item $cfg -Force -ErrorAction SilentlyContinue

    Write-Host ""
    if ($script:Failures -eq 0) {
        Write-Host "Tous les tests sont passes." -ForegroundColor Green
        Write-Host "Fixtures dans $OutDir - propfind-tree.xml est la plus utile." -ForegroundColor Green
    }
    else {
        Write-Host "$script:Failures test(s) en echec - voir $OutDir" -ForegroundColor Red
    }
    Write-Host ""
}
