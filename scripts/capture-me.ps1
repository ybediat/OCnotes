<#
.SYNOPSIS
    Capture la reponse de /graph/v1.0/me comme fixture de test, anonymisee.

.DESCRIPTION
    Le coeur Go lit le nom du compte dans LibreGraph (opencloud.Client.Me).
    TestMe s'appuie sur la forme de la reponse : ce script la capture sur un
    vrai serveur, remplace les valeurs personnelles et ecrit le resultat dans
    internal/opencloud/testdata/me.json. Toutes les autres proprietes sont
    gardees telles quelles : c'est leur presence qui fait la valeur de la
    fixture.

    La reponse brute, non anonymisee, reste dans scripts/out (ignore par git).

.EXAMPLE
    .\capture-me.ps1 -ServerUrl https://opencloud.exemple.ovh -Username test
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ServerUrl,
    [Parameter(Mandatory = $true)][string] $Username
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir 'lib\opencloud-http.ps1')

$outDir = Join-Path $scriptDir 'out'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$fixture = Join-Path $scriptDir '..\internal\opencloud\testdata\me.json'

$ServerUrl = $ServerUrl.TrimEnd('/')
if ($ServerUrl -notmatch '^https://') { throw "ServerUrl doit commencer par https://" }

$cfg = New-CurlAuthConfig -Username $Username
try {
    $me = Invoke-Dav -ConfigPath $cfg -Method GET -Url "$ServerUrl/graph/v1.0/me" `
        -OutFile 'me.json' -OutDir $outDir
    if ($me.Status -ne 200) { throw "GET /graph/v1.0/me : HTTP $($me.Status)" }

    $json = $me.Body | ConvertFrom-Json

    # Memes valeurs que le serveur factice de mobile/ et que drives.json.
    $remplacements = @{
        'id'                       = '44444444-4444-4444-8444-444444444444'
        'displayName'              = 'Alice Martin'
        'mail'                     = 'alice@example.test'
        'onPremisesSamAccountName' = 'alice'
    }
    # Valeurs sans rien de personnel, gardees telles quelles.
    $conserves = @('preferredLanguage', 'userType')

    foreach ($p in $json.PSObject.Properties) {
        if ($remplacements.ContainsKey($p.Name)) {
            $p.Value = $remplacements[$p.Name]
        }
        elseif ($p.Name -eq 'identities') {
            # L'emetteur est l'hote reel, l'identifiant attribue le subject
            # OIDC du compte : les deux partent.
            foreach ($identite in @($p.Value)) {
                $identite.issuer           = 'https://cloud.example.test'
                $identite.issuerAssignedId = '55555555-5555-4555-8555-555555555555'
            }
        }
        elseif ($conserves -notcontains $p.Name -and $p.Value -is [string] -and $p.Value -ne '') {
            # Toute autre valeur texte peut porter un nom ou une adresse : on
            # ne garde que sa presence.
            $p.Value = 'anonymise'
        }
    }

    $texte = $json | ConvertTo-Json -Depth 10 -Compress
    [IO.File]::WriteAllText($fixture, $texte + "`n", (New-Object System.Text.UTF8Encoding($false)))

    Write-Host "Proprietes renvoyees : $(($json.PSObject.Properties.Name) -join ', ')"
    Write-Host "Fixture ecrite : $((Resolve-Path $fixture).Path)" -ForegroundColor Green
}
finally {
    Remove-Item $cfg -Force -ErrorAction SilentlyContinue
}
