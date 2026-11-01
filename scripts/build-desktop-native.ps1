param(
    [string]$GraalVm,
    [string]$MsvcRoot,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$BuildName = 'native-desktop',
    [ValidatePattern('^[1-9][0-9]*[gGmM]$')][string]$NativeImageHeap = '8g',
    [ValidateRange(1, 32)][int]$NativeImageThreads = 2,
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$AppVersion,
    [switch]$RunTests,
    [switch]$RequireTelegramApi
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$work = Join-Path $workspace "build/$BuildName"
New-Item -ItemType Directory -Force $work, (Join-Path $work 'tmp') | Out-Null

# This environment belongs only to the build process; never changes machine settings.
$env:TEMP = Join-Path $work 'tmp'
$env:TMP = $env:TEMP
if ($GraalVm) {
    $env:GRAALVM_HOME = (Resolve-Path -LiteralPath $GraalVm).Path
    if (!(Test-Path -LiteralPath (Join-Path $env:GRAALVM_HOME 'bin/native-image.cmd'))) { throw 'GraalVM Native Image is missing.' }
}
if ($MsvcRoot) {
    $compilerRoot = (Resolve-Path -LiteralPath $MsvcRoot).Path
    $vc = Get-ChildItem (Join-Path $compilerRoot 'VC/Tools/MSVC') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $sdkRoot = Join-Path $compilerRoot 'Windows Kits/10'
    $sdk = Get-ChildItem (Join-Path $sdkRoot 'Include') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (!$vc -or !$sdk) { throw 'The portable compiler folder must contain MSVC and Windows SDK headers/libraries.' }
    $vcBin = Join-Path $vc.FullName 'bin/Hostx64/x64'
    $sdkBin = Join-Path $sdkRoot "bin/$($sdk.Name)/x64"
    if (!(Test-Path (Join-Path $vcBin 'cl.exe')) -or !(Test-Path (Join-Path $sdkBin 'rc.exe'))) { throw 'Missing x64 compiler or resource compiler.' }
    $env:VSCMD_ARG_HOST_ARCH = 'x64'
    $env:VSCMD_ARG_TGT_ARCH = 'x64'
    $env:VCToolsVersion = $vc.Name
    $env:VCToolsInstallDir = $vc.FullName + '\'
    $env:WindowsSdkDir = $sdkRoot + '\'
    $env:WindowsSDKVersion = $sdk.Name + '\'
    $env:WindowsSdkBinPath = (Join-Path $sdkRoot 'bin') + '\'
    $env:PATH = "$vcBin;$sdkBin;$(Join-Path $sdkBin 'ucrt');$env:PATH"
    $env:INCLUDE = (@((Join-Path $vc.FullName 'include')) + @('ucrt', 'shared', 'um', 'winrt', 'cppwinrt' | ForEach-Object { Join-Path $sdk.FullName $_ })) -join ';'
    $env:LIB = @((Join-Path $vc.FullName 'lib/x64'), (Join-Path $sdkRoot "Lib/$($sdk.Name)/ucrt/x64"), (Join-Path $sdkRoot "Lib/$($sdk.Name)/um/x64")) -join ';'
}

# Both output directories and Gradle execution history are isolated from IDE continuous builds.
$initFile = Join-Path $work 'isolated.init.gradle'
@"
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File(project.projectDir, 'build/$BuildName'))
}
gradle.projectsEvaluated {
    def shared = gradle.rootProject.project(':shared')
    shared.tasks.named('jvmTest') {
        dependsOn(shared.tasks.named('jvmJar'))
        classpath = shared.files(shared.tasks.named('jvmJar').flatMap { it.archiveFile }) + classpath
    }
}
"@ | Set-Content -LiteralPath $initFile -Encoding utf8
$arguments = @(
    "-PnativeImageHeap=$NativeImageHeap", "-PnativeImageThreads=$NativeImageThreads",
    '-Pkotlin.incremental=false', '-Pkotlin.compiler.execution.strategy=in-process',
    '--project-cache-dir', (Join-Path $workspace "build/$BuildName-gradle-cache"),
    '--init-script', $initFile, '--no-configuration-cache', '--no-build-cache', '--console=plain', '--max-workers=2', '--no-daemon'
)
if ($RunTests) { $arguments += @('-PnativeSmoke', '-PnativeAcceptance', '-PmediaSmoke') }
if ($RequireTelegramApi) { $arguments += '-PrequireTelegramApi=true' }
if ($AppVersion) { $arguments += "-PnnggramVersion=$AppVersion" }
if ($MsvcRoot) { $arguments += "-PwindowsRc=$(Join-Path $sdkBin 'rc.exe')" }
Push-Location $workspace
try {
    # Kotlin compilation retains a large heap in its Gradle JVM. Finish that JVM
    # before Native Image starts, leaving the hosted runner room for its builder.
    # Single-use daemons avoid stopping or reconfiguring an IDE's existing daemon.
    $prepareTasks = @(':desktopApp:assemble', ':desktopApp:verifyNativeUberJar')
    if ($RunTests) { $prepareTasks = @(':desktopApp:test', ':shared:jvmTest') + $prepareTasks }
    Write-Host 'Phase 1/2: JVM compilation, requested tests and verified Native Image inputs (Gradle heap 3 GiB).'
    & (Join-Path $workspace 'gradlew.bat') @prepareTasks @arguments '-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw "Native desktop preparation failed ($LASTEXITCODE)." }

    Write-Host "Phase 2/2: Native Image and distribution (builder heap $NativeImageHeap, $NativeImageThreads threads; Gradle heap 1 GiB)."
    & (Join-Path $workspace 'gradlew.bat') ':desktopApp:createGraalvmNativeDistributable' @arguments '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw "Native desktop build failed ($LASTEXITCODE)." }
} finally { Pop-Location }
