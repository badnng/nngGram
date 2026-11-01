param([string]$FfmpegDirectory)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $FfmpegDirectory) {
    $command = Get-Command ffmpeg.exe -ErrorAction Stop
    $FfmpegDirectory = Split-Path -Parent $command.Source
}
$source = (Resolve-Path -LiteralPath $FfmpegDirectory).Path
$executable = Join-Path $source 'ffmpeg.exe'
$executables = @('ffmpeg.exe', 'ffprobe.exe') | ForEach-Object {
    $candidate = Join-Path $source $_
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$_ is missing." }
    Get-Item -LiteralPath $candidate
}
# Gyan's pinned shared ZIP uses LICENSE; existing local bundles may use LICENSE.txt.
$license = @('LICENSE.txt', 'LICENSE') | ForEach-Object { Join-Path (Split-Path -Parent $source) $_ } |
    Where-Object { (Test-Path -LiteralPath $_ -PathType Leaf) -and (Get-Item -LiteralPath $_).Length -gt 0 } |
    Select-Object -First 1
if (!$license) { throw 'Keep the original nonempty FFmpeg distribution LICENSE or LICENSE.txt beside its bin directory.' }
# Consume the complete output before selecting its first line. Closing the pipe early
# can terminate ffmpeg with a nonzero exit code, which GitHub's pwsh wrapper propagates.
$versionLines = @(& $executable -version 2>&1)
if ($LASTEXITCODE -ne 0 -or !$versionLines.Count) { throw 'FFmpeg version probe failed.' }
$version = $versionLines[0].ToString()
$destination = [IO.Path]::GetFullPath((Join-Path $repository 'native/windows-x64/media'))
if (-not $destination.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid destination.' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$binaries = @($executables) + @(Get-ChildItem -LiteralPath $source -Filter '*.dll' -File)
foreach ($binary in $binaries) {
    Copy-Item -LiteralPath $binary.FullName -Destination (Join-Path $destination $binary.Name) -Force
}
Copy-Item -LiteralPath $license -Destination (Join-Path $destination 'FFmpeg-LICENSE.txt') -Force
$provenance = @($version, 'Prepared locally for media decoding, video inspection and playback. Binaries and their license are kept together.')
foreach ($binary in $binaries) {
    $hash = (Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash
    $provenance += "$($binary.Name) SHA256 $hash"
}
$provenance | Set-Content -LiteralPath (Join-Path $destination 'VERSION.txt') -Encoding utf8
Write-Output "Prepared media tools in $destination"
