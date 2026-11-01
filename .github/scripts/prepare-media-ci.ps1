param()
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:RUNNER_TEMP 'nnggram-media-tools'
New-Item -ItemType Directory -Path $root -Force | Out-Null
$archive = Join-Path $root 'ffmpeg.zip'
# Fixed release and SHA256, cross-checked with microsoft/winget-pkgs Gyan.FFmpeg.Shared/7.1.1.
Invoke-WebRequest 'https://github.com/GyanD/codexffmpeg/releases/download/7.1.1/ffmpeg-7.1.1-full_build-shared.zip' -OutFile $archive
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne '9F28727E8B472A04C1D2E520AAA425DCA82721B995139B35710091130EA6E699') { throw 'FFmpeg checksum mismatch.' }
Expand-Archive -LiteralPath $archive -DestinationPath $root -Force
& "$PSScriptRoot/../../scripts/prepare-media.ps1" -FfmpegDirectory (Join-Path $root 'ffmpeg-7.1.1-full_build-shared/bin')
