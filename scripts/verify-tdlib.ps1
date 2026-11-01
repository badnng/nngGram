param(
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($Jdk) -or !(Test-Path -LiteralPath (Join-Path $Jdk 'bin/java.exe'))) {
    throw 'Provide the JDK 21 directory with -Jdk, or set JAVA_HOME.'
}
if (!(Test-Path -LiteralPath (Join-Path $workspace 'native/windows-x64/manifest.properties'))) {
    throw 'Build the pinned native bundle with scripts/build-tdlib.ps1 first.'
}

$previousDirectory = Get-Location
$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'TEMP', 'TMP', 'JAVA_TOOL_OPTIONS')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $temporaryDirectory = Join-Path $workspace 'build/verification-tmp'
    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    $env:JAVA_HOME = $Jdk
    $env:TEMP = $temporaryDirectory
    $env:TMP = $temporaryDirectory
    $temporaryJvmOption = '"-Djava.io.tmpdir=' + $temporaryDirectory.Replace('\', '/') + '"'
    $env:JAVA_TOOL_OPTIONS = "$($previousEnvironment['JAVA_TOOL_OPTIONS']) $temporaryJvmOption".Trim()
    Set-Location -LiteralPath $workspace
    # Each invocation must exercise fresh JVMs, even when Gradle considers the
    # previous test outputs up to date. Fixtures never read personal configuration.
    & .\gradlew.bat :shared:jvmTest -PnativeSmoke -PnativeAcceptance --rerun-tasks --console=plain --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "TDLib acceptance verification failed (Gradle exit $LASTEXITCODE)." }
    Write-Host 'Verified unit tests, native smoke, ten fresh JVMs and ten same-engine restarts.'
    Write-Host (Join-Path $workspace 'shared/build/reports/tests/jvmTest/index.html')
} finally {
    Set-Location -LiteralPath $previousDirectory
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
