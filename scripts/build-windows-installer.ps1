param(
    [Parameter(Mandatory)][string]$AppDirectory,
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$ProductVersion = '0.1.0',
    [string]$WixDirectory,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$BuildName = 'windows-installer',
    [switch]$ValidateOnly,
    [switch]$SkipMsiValidation
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$versionParts = $ProductVersion.Split('.') | ForEach-Object { [long]$_ }
if ($versionParts[0] -gt 255 -or $versionParts[1] -gt 255 -or $versionParts[2] -gt 65535) { throw 'MSI versions require major/minor <=255 and build <=65535.' }
Import-Module (Join-Path $PSScriptRoot 'InstallerPayload.psm1') -Force
$payload = Get-NngGramPayload -AppDirectory $AppDirectory
$work = [IO.Path]::GetFullPath((Join-Path $workspace "build/$BuildName"))
if (!$work.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Installer output must remain inside the workspace.' }
if (Test-Path -LiteralPath $work) { throw 'Choose a new BuildName; installer outputs are never overwritten.' }
New-Item -ItemType Directory -Path $work | Out-Null
$payloadSource = Join-Path $work 'Payload.wxs'
New-NngGramPayloadSource -Payload $payload -OutputFile $payloadSource
$manifestPath = Join-Path $work 'payload-sha256.json'
$payload.Files | Select-Object Relative, Length, Sha256 | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $manifestPath -Encoding utf8
if ($ValidateOnly) {
    [pscustomobject]@{ Status = 'PAYLOAD_VALIDATED'; Files = $payload.Files.Count; Bytes = $payload.TotalBytes; Manifest = $manifestPath }
    return
}
if (!$WixDirectory) { $WixDirectory = Join-Path $workspace 'build/wix311' }
$wix = (Resolve-Path -LiteralPath $WixDirectory).Path
foreach ($tool in @('candle.exe', 'light.exe', 'WixUIExtension.dll')) {
    if (!(Test-Path -LiteralPath (Join-Path $wix $tool) -PathType Leaf)) { throw "An existing WiX 3.11 installation is required: $tool" }
}
$installerDirectory = Join-Path $workspace 'installer/windows'
$sources = @((Join-Path $installerDirectory 'Product.wxs'), (Join-Path $installerDirectory 'NngGramUI.wxs'), $payloadSource)
$objects = @()
$installerTemp = Join-Path $work 'tmp'
New-Item -ItemType Directory -Path $installerTemp | Out-Null
$installerPreviousTemp = $env:TEMP
$installerPreviousTmp = $env:TMP
try {
    # Compiler children use only this build's temporary files; never alter machine settings.
    $env:TEMP = $installerTemp
    $env:TMP = $installerTemp
    foreach ($source in $sources) {
        $object = Join-Path $work ([IO.Path]::GetFileNameWithoutExtension($source) + '.wixobj')
        & (Join-Path $wix 'candle.exe') '-nologo' '-arch' 'x64' "-dProductVersion=$ProductVersion" "-dInstallerSources=$installerDirectory" '-out' $object $source 2>&1 |
            Tee-Object -FilePath (Join-Path $work ([IO.Path]::GetFileNameWithoutExtension($source) + '-candle.log')) | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "WiX source compilation failed ($LASTEXITCODE)." }
        $objects += $object
    }
    $msi = Join-Path $work "nngGram-$ProductVersion-windows-x64.msi"
    $linkArguments = @('-nologo', '-ext', (Join-Path $wix 'WixUIExtension.dll'), '-cultures:zh-cn', '-out', $msi)
    if ($SkipMsiValidation) { $linkArguments += '-sval' }
    & (Join-Path $wix 'light.exe') @linkArguments @objects 2>&1 |
        Tee-Object -FilePath (Join-Path $work 'light.log') | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "MSI linking/validation failed ($LASTEXITCODE)." }
} finally {
    $env:TEMP = $installerPreviousTemp
    $env:TMP = $installerPreviousTmp
}
# Native Image inputs must remain immutable throughout packaging. Never report a changed payload as complete.
$after = Get-NngGramPayload -AppDirectory $payload.Root
$beforeIdentities = @($payload.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
$afterIdentities = @($after.Files | ForEach-Object { $_.Relative + '|' + $_.Length + '|' + $_.Sha256 })
if (Compare-Object -ReferenceObject $beforeIdentities -DifferenceObject $afterIdentities) { throw 'The Native Image directory changed while packaging; the MSI is not accepted.' }
$report = [pscustomobject]@{ Status = 'MSI_COMPILED_NOT_INSTALLED'; Version = $ProductVersion; Files = $payload.Files.Count;
    Bytes = $payload.TotalBytes; IceValidation = $(if ($SkipMsiValidation) { 'SKIPPED_EXPLICIT_DIAGNOSTIC_ONLY' } else { 'PASSED' });
    Msi = $msi; Sha256 = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant() }
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'installer-result.json') -Encoding utf8
$report
