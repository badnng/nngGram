param()
$ErrorActionPreference = 'Stop'
$archive = Join-Path $env:RUNNER_TEMP 'wix311-binaries.zip'
Invoke-WebRequest 'https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip' -OutFile $archive
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne '2C1888D5D1DBA377FC7FA14444CF556963747FF9A0A289A3599CF09DA03B9E2E') { throw 'WiX checksum mismatch.' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $env:RUNNER_TEMP 'wix311') -Force
