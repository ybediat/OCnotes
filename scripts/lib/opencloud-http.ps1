# Helpers partages par les scripts de spike (dot-source ce fichier).
#
# Le token n'est jamais passe en argument de ligne de commande : il transite par
# un fichier de configuration curl temporaire, donc il n'apparait pas dans la
# liste des processus.

function New-CurlAuthConfig {
    <#
    .SYNOPSIS
        Recupere l'App Token et ecrit un fichier de config curl temporaire.
    .OUTPUTS
        Le chemin du fichier de config. A supprimer par l'appelant (finally).
    #>
    param(
        [Parameter(Mandatory = $true)][string] $Username
    )

    # OPENNOTE_APP_TOKEN permet un lancement non interactif (tests). Les
    # variables d'environnement n'apparaissent pas dans la liste des processus.
    if ($env:OPENNOTE_APP_TOKEN) {
        Write-Host "Token lu depuis OPENNOTE_APP_TOKEN." -ForegroundColor DarkGray
        $token = $env:OPENNOTE_APP_TOKEN
    }
    else {
        $secure = Read-Host -Prompt "App Token pour $Username" -AsSecureString
        $bstr   = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try   { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }

    if ([string]::IsNullOrWhiteSpace($token)) { throw "Token vide, abandon." }

    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:${token}"))
    $cfg = Join-Path ([IO.Path]::GetTempPath()) ("opennote-spike-" + [Guid]::NewGuid().ToString('N') + ".cfg")
    Set-Content -Path $cfg -Value "header = `"Authorization: Basic $b64`"" -Encoding ascii

    return $cfg
}

function Invoke-Dav {
    <#
    .SYNOPSIS
        Un appel HTTP via curl.exe, avec capture du corps et des en-tetes.
    .NOTES
        Invoke-WebRequest de PowerShell 5.1 refuse les verbes WebDAV
        (PROPFIND, MKCOL, MOVE) : son parametre -Method est un enum ferme.
        D'ou l'usage de curl.exe, present nativement sur Windows 10/11.
    #>
    param(
        [Parameter(Mandatory = $true)][string] $ConfigPath,
        [Parameter(Mandatory = $true)][string] $Method,
        [Parameter(Mandatory = $true)][string] $Url,
        [Parameter(Mandatory = $true)][string] $OutFile,
        [Parameter(Mandatory = $true)][string] $OutDir,

        # En-tetes sous la forme 'Nom: valeur'. Toujours passer par ici plutot
        # que par -H dans ExtraArgs : voir la note ci-dessous.
        [string[]] $HeaderLines = @(),

        [string[]] $ExtraArgs = @()
    )

    $bodyPath = Join-Path $OutDir $OutFile
    $hdrPath  = "$bodyPath.headers"

    # PowerShell 5.1 mange les guillemets doubles contenus dans un argument
    # destine a un executable natif : 'If-Match: "abc"' arrive a curl sous la
    # forme 'If-Match: abc'. Un ETag perdrait donc ses guillemets et le serveur
    # repondrait 412 en permanence -- ce qui ferait passer un test de conflit
    # pour la mauvaise raison. Les en-tetes transitent donc par un fichier de
    # configuration curl, ou l'echappement est explicite et fiable.
    $hdrCfg = $null
    if ($HeaderLines.Count -gt 0) {
        $hdrCfg = Join-Path ([IO.Path]::GetTempPath()) ("opennote-hdr-" + [Guid]::NewGuid().ToString('N') + ".cfg")
        $lines = foreach ($h in $HeaderLines) {
            'header = "' + $h.Replace('\', '\\').Replace('"', '\"') + '"'
        }
        Set-Content -Path $hdrCfg -Value $lines -Encoding ascii
    }

    try {
        $curlArgs = @('-K', $ConfigPath)
        if ($hdrCfg) { $curlArgs += @('-K', $hdrCfg) }
        $curlArgs += @(
            '-s', '-S'
            '--max-time', '30'
            '-X', $Method
            '-o', $bodyPath
            '-D', $hdrPath
            '-w', '%{http_code}'
        ) + $ExtraArgs + @($Url)

        $status = & curl.exe @curlArgs
        if ($LASTEXITCODE -ne 0) {
            throw "curl a echoue (code $LASTEXITCODE) sur $Method $Url"
        }
    }
    finally {
        if ($hdrCfg) { Remove-Item $hdrCfg -Force -ErrorAction SilentlyContinue }
    }

    return [pscustomobject]@{
        Status   = [int] $status
        BodyPath = $bodyPath
        HdrPath  = $hdrPath
        Body     = (Get-Content $bodyPath -Raw -ErrorAction SilentlyContinue)
        Headers  = (Get-Content $hdrPath  -Raw -ErrorAction SilentlyContinue)
    }
}

function Write-Result {
    <#
    .SYNOPSIS
        Affiche le verdict d'un test et incremente $script:Failures si echec.
    #>
    param(
        [Parameter(Mandatory = $true)][string] $Label,
        [Parameter(Mandatory = $true)][int]    $Status,
        [Parameter(Mandatory = $true)][int[]]  $Expected
    )

    if ($Expected -contains $Status) {
        Write-Host ("  [OK]   {0} -> HTTP {1}" -f $Label, $Status) -ForegroundColor Green
        return $true
    }
    Write-Host ("  [FAIL] {0} -> HTTP {1} (attendu: {2})" -f $Label, $Status, ($Expected -join '/')) -ForegroundColor Red
    $script:Failures++
    return $false
}

function Get-HeaderValue {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string] $Headers,
        [Parameter(Mandatory = $true)][string] $Name
    )
    foreach ($line in ($Headers -split "`r?`n")) {
        if ($line -match "^\s*$([regex]::Escape($Name))\s*:\s*(.+?)\s*$") { return $Matches[1] }
    }
    return $null
}

function Get-PersonalDrive {
    <#
    .SYNOPSIS
        Renvoie l'espace personnel depuis une reponse /graph/v1.0/me/drives.
    .NOTES
        driveType peut valoir personal, project, virtual ou mountpoint.
        On ne veut jamais 'virtual' (l'espace Shares) comme racine de notes.
    #>
    param([Parameter(Mandatory = $true)][string] $DrivesJson)

    $parsed = $DrivesJson | ConvertFrom-Json
    foreach ($d in $parsed.value) {
        if ($d.driveType -eq 'personal') { return $d }
    }
    foreach ($d in $parsed.value) {
        if ($d.driveType -ne 'virtual') { return $d }
    }
    return $null
}
