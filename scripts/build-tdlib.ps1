param(
    [string]$MsysRoot = 'D:\msys2',
    [string]$Jdk = $env:JAVA_HOME,
    [int]$Jobs = 4,
    [switch]$BindingsOnly
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = Join-Path $workspace 'build/native'
$source = Join-Path $buildRoot 'td-source'
$bundle = Join-Path $workspace 'native/windows-x64'
$toolchain = Join-Path $MsysRoot 'ucrt64'
$toolBin = Join-Path $toolchain 'bin'
$pin = ConvertFrom-StringData (Get-Content (Join-Path $workspace 'native/tdlib-source.properties') -Raw)
foreach ($directory in @($buildRoot, $bundle, (Join-Path $buildRoot 'tmp'))) {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar)) { throw 'Build path escaped workspace' }
    New-Item -ItemType Directory -Path $resolved -Force | Out-Null
}
if ([string]::IsNullOrWhiteSpace($Jdk) -or -not (Test-Path (Join-Path $Jdk 'bin/javac.exe'))) { throw 'Pass -Jdk with a JDK 21 x64 directory' }
$Jdk = [IO.Path]::GetFullPath($Jdk).Replace('\', '/')
$env:JAVA_HOME = $Jdk
$env:TEMP = Join-Path $buildRoot 'tmp'
$env:TMP = $env:TEMP
$env:PATH = "$toolBin;$(Join-Path $MsysRoot 'usr/bin');$(Join-Path $Jdk 'bin');$env:PATH"
function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}
if (-not (Test-Path (Join-Path $source '.git'))) {
    Run 'git' @('init', $source)
    Run 'git' @('-C', $source, 'remote', 'add', 'origin', $pin.repository)
    Run 'git' @('-C', $source, 'fetch', '--depth', '1', 'origin', $pin.commit)
    Run 'git' @('-C', $source, 'checkout', '--detach', 'FETCH_HEAD')
}
if ((& git -C $source rev-parse HEAD) -ne $pin.commit) { throw 'Existing source checkout differs from the pinned commit' }
if ((& git -C $source status --porcelain --untracked-files=no)) { throw 'TDLib source checkout has tracked modifications' }
$cmake = Join-Path $toolBin 'cmake.exe'
$jniBuild = Join-Path $buildRoot 'jni-build'
$javaBuild = Join-Path $buildRoot 'java-build'
$javaSource = Join-Path $source 'example/java'
$common = @('-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', "-DCMAKE_PREFIX_PATH=$toolchain", "-DJAVA_HOME=$Jdk")
if (-not $BindingsOnly) {
Run $cmake (@('-S', $source, '-B', $jniBuild, '-DTD_ENABLE_JNI=ON', '-DTD_ENABLE_LTO=OFF', "-DCMAKE_INSTALL_PREFIX=$javaSource/td", "-DOPENSSL_ROOT_DIR=$toolchain") + $common)
Run $cmake @('--build', $jniBuild, '--target', 'install', '--parallel', "$Jobs")
# The upstream Java example declares a suffix-less Ninja dependency on Windows.
# Keep the official CMake file unchanged and provide that build-only alias.
Copy-Item -LiteralPath (Join-Path $javaSource 'td/bin/td_generate_java_api.exe') -Destination (Join-Path $javaSource 'td/bin/td_generate_java_api') -Force
Run $cmake (@('-S', $javaSource, '-B', $javaBuild, "-DTd_DIR=$javaSource/td/lib/cmake/Td", "-DCMAKE_INSTALL_PREFIX=$javaSource") + $common)
Run $cmake @('--build', $javaBuild, '--target', 'install', '--parallel', "$Jobs")
} else {
    Run (Join-Path $jniBuild 'td/generate/td_generate_java_api.exe') @('TdApi', (Join-Path $source 'td/generate/auto/tlo/td_api.tlo'), $javaSource, 'org/drinkless/tdlib')
}

# Preserve all generated types and members. Adapt only the two static loaders
# so both official classes use the validated absolute DLL path.
$staging = Join-Path $buildRoot 'java-staging'
$classes = Join-Path $staging 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$clientText = Get-Content (Join-Path $javaSource 'org/drinkless/tdlib/Client.java') -Raw
$original = '(?s)    static \{\s*try \{\s*System\.loadLibrary\("tdjni"\);\s*\} catch \(UnsatisfiedLinkError e\) \{\s*e\.printStackTrace\(\);\s*\}\s*\}'
if ($clientText -notmatch $original) { throw 'Official Client.java loader changed; review the adaptation before rebuilding' }
$replacement = @'
    static {
        String path = System.getProperty("nnggram.tdlib.library");
        if (path == null || path.isEmpty()) {
            System.loadLibrary("tdjni");
        } else {
            System.load(path);
        }
    }
'@
[IO.File]::WriteAllText((Join-Path $staging 'Client.java'), [regex]::Replace($clientText, $original, $replacement))
$apiText = Get-Content (Join-Path $javaSource 'org/drinkless/tdlib/TdApi.java') -Raw
if ($apiText -notmatch 'GIT_COMMIT_HASH = "([a-f0-9]{40})"') { throw 'Generated API has no commit metadata' }
$apiCommit = $Matches[1]
if ($apiCommit -ne $pin.commit) { throw 'Generated API does not match the pinned commit' }
if ($apiText -notmatch $original) { throw 'Generated TdApi.java loader changed; review before rebuilding' }
[IO.File]::WriteAllText((Join-Path $staging 'TdApi.java'), [regex]::Replace($apiText, $original, $replacement))
$metadata = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Path $metadata -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $metadata 'nnggram-tdlib.properties'), "source.commit=$($pin.commit)`njava.api.commit=$apiCommit`nloader.patch=absolute-path-v1`n")
Run (Join-Path $Jdk 'bin/javac.exe') @('-encoding', 'UTF-8', '--release', '21', '-d', $classes, (Join-Path $staging 'Client.java'), (Join-Path $staging 'TdApi.java'))
Run (Join-Path $Jdk 'bin/jar.exe') @('--create', '--file', (Join-Path $bundle 'tdlib.jar'), '-C', $classes, '.')
# JNI discovers all generated API types at runtime. Refresh the reachability
# declarations and compiled-binding digest whenever this exact JAR is rebuilt.
Run (Join-Path $Jdk 'bin/java.exe') @((Join-Path $PSScriptRoot 'GenerateTdlibNativeMetadata.java'), $workspace)
if ($BindingsOnly) { Write-Output 'Prepared Java bindings only; native bundle is not yet ready'; return }
$nativeFile = @((Join-Path $javaSource 'bin/tdjni.dll'), (Join-Path $javaSource 'bin/libtdjni.dll')) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $nativeFile) { throw 'JNI build did not produce a DLL' }
Copy-Item -LiteralPath $nativeFile -Destination (Join-Path $bundle 'tdjni.dll') -Force

# Walk PE imports and copy every non-system dependency from the same toolchain.
$objdump = Join-Path $toolBin 'objdump.exe'
$copied = @{}
$loadOrder = [Collections.Generic.List[string]]::new()
$systemImports = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
function Collect([string]$Name) {
    if ($copied.ContainsKey($Name)) { return }
    $copied[$Name] = $true
    $path = Join-Path $bundle $Name
    $headers = & $objdump -f $path
    if ($LASTEXITCODE -ne 0 -or ($headers -join ' ') -notmatch 'i386:x86-64') { throw "Not an x64 PE image: $Name" }
    $imports = & $objdump -p $path | Select-String 'DLL Name: (.+)' | ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() }
    foreach ($dependency in $imports) {
        $from = Join-Path $toolBin $dependency
        if (Test-Path -LiteralPath $from) {
            Copy-Item -LiteralPath $from -Destination (Join-Path $bundle $dependency) -Force
            Collect $dependency
        } else { [void]$systemImports.Add($dependency) }
    }
    $loadOrder.Add($Name)
}
Collect 'tdjni.dll'
Copy-Item -LiteralPath (Join-Path $source 'LICENSE_1_0.txt') -Destination (Join-Path $bundle 'TDLib-LICENSE.txt') -Force
$licenses = Join-Path $bundle 'licenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
foreach ($package in @('gcc-libs', 'libwinpthread', 'openssl', 'zlib', 'crt')) {
    $packageLicense = Join-Path $toolchain "share/licenses/$package"
    if (Test-Path -LiteralPath $packageLicense) { Copy-Item -LiteralPath $packageLicense -Destination $licenses -Recurse -Force }
}
$manifest = @(
    'format=1', "source.commit=$($pin.commit)", 'architecture=x64', 'binding=official-java-jni',
    'binding.loader.patch=absolute-path-v1', "load.order=$($loadOrder -join ',')",
    "system.imports=$(($systemImports | Sort-Object) -join ',')",
    "compiler=$((& (Join-Path $toolBin 'g++.exe') -dumpfullversion))",
    "cmake=$((& $cmake --version | Select-Object -First 1))"
)
foreach ($name in @('tdlib.jar') + $loadOrder.ToArray()) {
    $manifest += "sha256.$name=$((Get-FileHash -LiteralPath (Join-Path $bundle $name) -Algorithm SHA256).Hash.ToLowerInvariant())"
}
$manifest | Set-Content -LiteralPath (Join-Path $bundle 'manifest.properties') -Encoding utf8
& (Join-Path $MsysRoot 'usr/bin/pacman.exe') -Q | Select-String 'mingw-w64-ucrt-x86_64-(gcc|openssl|zlib|libwinpthread|cmake|ninja)|^gperf ' | ForEach-Object { $_.Line } | Set-Content -LiteralPath (Join-Path $bundle 'toolchain-versions.txt') -Encoding utf8
Write-Output "Prepared pinned TDLib bundle at $bundle"
