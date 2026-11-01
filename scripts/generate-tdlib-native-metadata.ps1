param(
    [Parameter(Mandatory = $true)][string]$Jdk,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJava = Join-Path $Jdk 'bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw 'A JDK 21 or newer is required.' }
$taskArguments = @((Join-Path $PSScriptRoot 'GenerateTdlibNativeMetadata.java'), $taskRoot)
if ($Check) { $taskArguments += '--check' }
& $taskJava @taskArguments
if ($LASTEXITCODE -ne 0) { throw 'TDLib Native Image metadata generation or validation failed.' }
