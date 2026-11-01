#requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$AppDirectory,
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version = '0.1.0',
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$BuildName = 'windows-package',
    [string]$WixDirectory,
    [switch]$SkipInstaller
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'InstallerPayload.psm1') -Force
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$work = [IO.Path]::GetFullPath((Join-Path $workspace "build/$BuildName"))
if (!$work.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Package output must remain inside the workspace.'
}
if (Test-Path -LiteralPath $work) { throw 'Choose a new BuildName; release outputs are never overwritten.' }
$source = Get-NngGramPayload -AppDirectory $AppDirectory
if ($work.StartsWith($source.Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $source.Root.StartsWith($work + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Package output and input directories must be independent.'
}
$stage = Join-Path $work 'staged/nngGram'
[void][IO.Directory]::CreateDirectory($stage)

# The native directory is immutable. Only explicit non-runtime files are omitted from its copy.
# Never drop a TDLib manifest-owned file, even if its extension matches a symbol suffix.
$manifestOwned = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $source.Root 'manifest.properties')) {
    if ($line -match '^sha256\.([^=]+)=') { $manifestOwned[$Matches[1].Replace('\', '/')] = $true }
}
$removed = [Collections.Generic.List[object]]::new()
foreach ($file in $source.Files) {
    $reason = if ($file.Relative -ieq 'media/ffplay.exe') { 'unused-ffplay' }
        elseif ($file.Relative -match '(?i)\.(pdb|debug)$') { 'external-debug-symbols' }
        else { $null }
    if ($reason -and !$manifestOwned.ContainsKey($file.Relative)) {
        $removed.Add([pscustomobject]@{ file = $file.Relative; bytes = $file.Length; sha256 = $file.Sha256; reason = $reason })
        continue
    }
    $target = [IO.Path]::GetFullPath((Join-Path $stage $file.Relative))
    if (!$target.StartsWith($stage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'A staged file escaped the release directory.'
    }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
    [IO.File]::Copy($file.FullName, $target, $false)
}
$payload = Get-NngGramPayload -AppDirectory $stage
foreach ($file in $payload.Files) {
    $original = $source.Files | Where-Object Relative -EQ $file.Relative
    if (!$original -or $file.Length -ne $original.Length -or $file.Sha256 -ne $original.Sha256) {
        throw 'A staged runtime asset does not exactly match the original Native Image directory.'
    }
}
$payload.Files | Select-Object Relative, Length, Sha256 | ConvertTo-Json -Depth 3 |
    Set-Content -LiteralPath (Join-Path $work 'payload-sha256.json') -Encoding utf8NoBOM
$zipPath = Join-Path $work "nngGram-$Version-windows-x64.zip"
$archive = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $payload.Files) {
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName,
            ('nngGram/' + $file.Relative), [IO.Compression.CompressionLevel]::SmallestSize)
    }
} finally { $archive.Dispose() }

function Get-Artifact([string]$path) {
    $entry = Get-Item -LiteralPath $path
    [ordered]@{ file = $entry.Name; name = $entry.Name; bytes = $entry.Length;
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$artifacts = [ordered]@{ zip = (Get-Artifact $zipPath) }
if (!$SkipInstaller) {
    $installerArguments = @{ AppDirectory = $stage; ProductVersion = $Version; BuildName = "$BuildName-msi" }
    if ($WixDirectory) { $installerArguments.WixDirectory = $WixDirectory }
    $compiled = & (Join-Path $PSScriptRoot 'build-windows-installer.ps1') @installerArguments
    $msiPath = Join-Path $work ([IO.Path]::GetFileName($compiled.Msi))
    [IO.File]::Copy($compiled.Msi, $msiPath, $false)
    $artifacts.installer = Get-Artifact $msiPath
}

# Copy/archive/compiler races must never turn a changed input into an accepted release.
$after = Get-NngGramPayload -AppDirectory $source.Root
$beforeIdentities = @($source.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
$afterIdentities = @($after.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
if (Compare-Object -ReferenceObject $beforeIdentities -DifferenceObject $afterIdentities) {
    throw 'The original Native Image directory changed during packaging; release rejected.'
}
$afterStage = Get-NngGramPayload -AppDirectory $stage
$stagedIdentities = @($payload.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
$afterStageIdentities = @($afterStage.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
if (Compare-Object -ReferenceObject $stagedIdentities -DifferenceObject $afterStageIdentities) {
    throw 'The staged Native Image directory changed during packaging; release rejected.'
}
$info = [ordered]@{
    schemaVersion = 1
    status = $(if ($SkipInstaller) { 'ZIP_PACKAGED_INSTALLER_SKIPPED' } else { 'PACKAGED_NOT_INSTALLED' })
    projectName = 'nngGram'; versionName = $Version
    runNumber = $env:GITHUB_RUN_NUMBER; branch = $env:GITHUB_REF_NAME; commit = $env:GITHUB_SHA
    runUrl = $(if ($env:GITHUB_SERVER_URL -and $env:GITHUB_REPOSITORY -and $env:GITHUB_RUN_ID) {
        "$env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID"
    } else { $null })
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    stagedAppDirectory = 'staged/nngGram'
    sizes = [ordered]@{ sourceBytes = $source.TotalBytes; stagedBytes = $payload.TotalBytes;
        removedBytes = $source.TotalBytes - $payload.TotalBytes; sourceFiles = $source.Files.Count; stagedFiles = $payload.Files.Count }
    removedFiles = @($removed.ToArray())
    artifacts = $artifacts
}
$checksums = @($artifacts.Values | ForEach-Object { $_.sha256 + '  ' + $_.name })
[IO.File]::WriteAllLines((Join-Path $work 'SHA256SUMS.txt'), $checksums, [Text.UTF8Encoding]::new($false))
$infoPath = Join-Path $work 'build-info.json'
$info | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $infoPath -Encoding utf8NoBOM
[pscustomobject]@{ Status = $info.status; BuildInfo = $infoPath; Zip = $zipPath;
    Installer = $(if ($artifacts.Contains('installer')) { Join-Path $work $artifacts.installer.file } else { $null });
    SourceBytes = $source.TotalBytes; StagedBytes = $payload.TotalBytes; RemovedBytes = $source.TotalBytes - $payload.TotalBytes }
