<#
.SYNOPSIS
    Télécharge le corpus d'images réelles (fantômes et patientes virtuelles, aucune donnée de patient) depuis l'API
    publique NBIA v1 de TCIA, dans $env:MEDIMAGE_CORPUS_DIR, hors du dépôt. Équivalent Windows de fetch-corpus.sh.

.DESCRIPTION
    Le manifeste est le tableau de docs/CORPUS.md. Chaque ligne est validée par des expressions strictes avant usage
    (nom de fichier [a-z0-9-]+.dcm, UID de chiffres et de points, SHA-256 de 64 hexadécimaux, collection de lettres,
    chiffres, espaces et tirets) ; les paramètres de requête sont encodés ; chaque série doit appartenir à la collection
    déclarée (getSeries). Un fichier dont la SHA-256 diffère est supprimé et le script échoue. Rien n'est exécuté à
    partir d'un fichier téléchargé.

.PARAMETER AllowNew
    Premier téléchargement : conserve les fichiers dont la somme est « à calculer » et affiche leur SHA-256.

.PARAMETER Select
    SeriesInstanceUID : affiche les instances à 20, 35, 65 et 80 % de la série (tri sur le dernier composant de l'UID).

.PARAMETER Doi
    SeriesInstanceUID : affiche la collection et le DOI de description de la série.

.EXAMPLE
    $env:MEDIMAGE_CORPUS_DIR = "$HOME\medimage-corpus"; .\scripts\corpus\fetch-corpus.ps1
#>
[CmdletBinding(DefaultParameterSetName = 'Fetch')]
param(
    [Parameter(ParameterSetName = 'Fetch')] [switch] $AllowNew,
    [Parameter(ParameterSetName = 'Select', Mandatory = $true)] [string] $Select,
    [Parameter(ParameterSetName = 'Doi', Mandatory = $true)] [string] $Doi
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 : TLS 1.2 explicitement (l'API refuse les versions antérieures).
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Api = 'https://services.cancerimagingarchive.net/nbia-api/services/v1'
$MaxBytes = 80MB
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Manifest = Join-Path $RepoRoot 'docs\CORPUS.md'
$Pending = 'à calculer'

function Stop-Corpus([string] $Message) {
    # Message fixe : aucune valeur lue dans un fichier n'y est recopiée.
    Write-Error "ERREUR : $Message" -ErrorAction Continue
    exit 1
}

function Test-Uid([string] $s) { return ($s -cmatch '^[0-9]+(\.[0-9]+)*$') -and $s.Length -le 64 }
function Test-FileName([string] $s) { return $s -cmatch '^[a-z0-9-]+\.dcm$' }
function Test-Sha([string] $s) { return $s -cmatch '^[0-9a-f]{64}$' }
function Test-Collection([string] $s) { return ($s -cmatch '^[A-Za-z0-9][A-Za-z0-9 _-]*$') -and $s.Length -le 64 }

# Requête GET en HTTPS, réponse écrite dans un fichier, taille bornée.
function Invoke-Fetch([string] $Url, [string] $OutFile) {
    $attempt = 0
    while ($true) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 600 -MaximumRedirection 0
            break
        } catch {
            $attempt++
            if ($attempt -ge 5) { Stop-Corpus 'requête vers l''API TCIA en échec' }
            Start-Sleep -Seconds 5
        }
    }
    if ((Get-Item $OutFile).Length -gt $MaxBytes) {
        Remove-Item $OutFile -Force
        Stop-Corpus 'réponse au-delà de 80 Mio'
    }
}

function Get-Sha256([string] $Path) { return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant() }

# Lignes du manifeste : | fichier | collection | fabricant | série | instance | licence | SHA-256 | …
function Read-Manifest {
    if (-not (Test-Path $Manifest)) { Stop-Corpus 'manifeste absent : docs/CORPUS.md' }
    $entries = @()
    foreach ($line in [IO.File]::ReadAllLines($Manifest, [Text.Encoding]::UTF8)) {
        if ($line -cnotmatch '^\| [a-z0-9-]+\.dcm \|') { continue }
        $cols = $line.Split('|')
        if ($cols.Count -lt 8) { Stop-Corpus 'ligne de manifeste invalide' }
        $e = [pscustomobject]@{
            File = $cols[1].Trim(); Collection = $cols[2].Trim(); Series = $cols[4].Trim()
            Sop = $cols[5].Trim(); Sha = $cols[7].Trim()
        }
        if (-not (Test-FileName $e.File)) { Stop-Corpus 'nom de fichier invalide dans le manifeste' }
        if (-not (Test-Collection $e.Collection)) { Stop-Corpus 'collection invalide dans le manifeste' }
        if (-not (Test-Uid $e.Series)) { Stop-Corpus 'SeriesInstanceUID invalide dans le manifeste' }
        if (-not (Test-Uid $e.Sop)) { Stop-Corpus 'SOPInstanceUID invalide dans le manifeste' }
        if ($e.Sha -ne $Pending -and -not (Test-Sha $e.Sha)) { Stop-Corpus 'SHA-256 invalide dans le manifeste' }
        $entries += $e
    }
    if ($entries.Count -eq 0) { Stop-Corpus 'manifeste vide' }
    return $entries
}

$tmp = [IO.Path]::GetTempFileName()
try {
    if ($PSCmdlet.ParameterSetName -eq 'Select') {
        if (-not (Test-Uid $Select)) { Stop-Corpus 'SeriesInstanceUID invalide' }
        Invoke-Fetch "$Api/getSOPInstanceUIDs?SeriesInstanceUID=$([uri]::EscapeDataString($Select))" $tmp
        # UID validés un par un ; tri sur le dernier composant, complété à gauche (ordre numérique exact).
        $uids = [regex]::Matches([IO.File]::ReadAllText($tmp), '"SOPInstanceUID":"([0-9.]{1,64})"') |
            ForEach-Object { $_.Groups[1].Value } | Where-Object { Test-Uid $_ } |
            Sort-Object { $_.Split('.')[-1].PadLeft(64) }
        $uids = @($uids)
        if ($uids.Count -lt 5) { Stop-Corpus 'série trop courte' }
        foreach ($p in 20, 35, 65, 80) {
            $i = [math]::Floor(($uids.Count - 1) * $p / 100)
            "{0} %`t{1}/{2}`t{3}" -f $p, ($i + 1), $uids.Count, $uids[$i]
        }
        exit 0
    }

    if ($PSCmdlet.ParameterSetName -eq 'Doi') {
        if (-not (Test-Uid $Doi)) { Stop-Corpus 'SeriesInstanceUID invalide' }
        Invoke-Fetch "$Api/getSeriesMetaData?SeriesInstanceUID=$([uri]::EscapeDataString($Doi))" $tmp
        $json = [IO.File]::ReadAllText($tmp).Replace('\/', '/')
        $c = [regex]::Match($json, '"Collection":"([A-Za-z0-9 _-]{1,64})"')
        $d = [regex]::Match($json, '"Data Description URI":"(https://doi\.org/10\.[0-9]{4,9}/[A-Za-z0-9._/-]{1,64})"')
        if (-not $c.Success -or -not $d.Success) { Stop-Corpus 'métadonnées absentes ou hors format' }
        "{0}`t{1}" -f $c.Groups[1].Value, $d.Groups[1].Value
        exit 0
    }

    if ([string]::IsNullOrWhiteSpace($env:MEDIMAGE_CORPUS_DIR)) { Stop-Corpus 'MEDIMAGE_CORPUS_DIR non défini' }
    # Chemin absolu vérifié avant toute création : rien n'est jamais écrit dans le dépôt.
    $dir = [IO.Path]::GetFullPath($env:MEDIMAGE_CORPUS_DIR)
    if (($dir.TrimEnd('\') + '\').StartsWith($RepoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        Stop-Corpus 'MEDIMAGE_CORPUS_DIR doit être hors du dépôt'
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $entries = Read-Manifest

    # Chaque série doit appartenir à la collection déclarée.
    foreach ($g in ($entries | Group-Object Collection)) {
        Invoke-Fetch "$Api/getSeries?Collection=$([uri]::EscapeDataString($g.Name))" $tmp
        $text = [IO.File]::ReadAllText($tmp)
        foreach ($series in ($g.Group | Select-Object -ExpandProperty Series -Unique)) {
            if (-not $text.Contains("`"SeriesInstanceUID`":`"$series`"")) {
                Stop-Corpus 'une série du manifeste n''appartient pas à la collection déclarée'
            }
        }
    }

    $present = 0; $downloaded = 0; $failed = 0; $new = @()
    foreach ($e in $entries) {
        $target = Join-Path $dir $e.File
        if ((Test-Path $target) -and $e.Sha -ne $Pending) {
            if ((Get-Sha256 $target) -eq $e.Sha) { $present++; continue }
            Remove-Item $target -Force
        }
        $part = Join-Path $dir ".$($e.File).part"
        $url = "$Api/getSingleImage?SeriesInstanceUID=$([uri]::EscapeDataString($e.Series))" +
               "&SOPInstanceUID=$([uri]::EscapeDataString($e.Sop))"
        Invoke-Fetch $url $part
        $actual = Get-Sha256 $part
        if ($e.Sha -eq $Pending) {
            $new += "$($e.File) $actual"
            if ($AllowNew) { Move-Item -Force $part $target; $downloaded++ } else { Remove-Item $part -Force; $failed++ }
        } elseif ($actual -eq $e.Sha) {
            Move-Item -Force $part $target; $downloaded++
        } else {
            Remove-Item $part -Force
            Write-Error "ERREUR : SHA-256 différent pour $($e.File) : fichier supprimé" -ErrorAction Continue
            $failed++
        }
    }

    "corpus : $present déjà présents, $downloaded téléchargés, $failed en échec, dans $dir"
    if ($new.Count -gt 0) {
        'nouveaux fichiers (SHA-256 « à calculer » dans docs/CORPUS.md) :'
        $new | ForEach-Object { "  $_" }
    }
    if ($failed -gt 0) { exit 1 }
} finally {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}
