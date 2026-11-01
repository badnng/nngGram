Set-StrictMode -Version Latest

function Copy-TdlibDependencyLicenses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ToolchainDirectory,
        [Parameter(Mandatory)][string]$BundleDirectory
    )
    $layoutPath = Join-Path $PSScriptRoot '../native/tdlib-license-layout.json'
    $layout = Get-Content -LiteralPath $layoutPath -Raw | ConvertFrom-Json
    $sourceRoot = Join-Path $ToolchainDirectory 'share/licenses'
    $selected = [Collections.Generic.List[object]]::new()
    foreach ($group in $layout.groups) {
        $choice = $null
        foreach ($alternative in $group.alternatives) {
            $candidate = [Collections.Generic.List[object]]::new()
            $complete = $true
            foreach ($package in $alternative) {
                $directory = Join-Path $sourceRoot $package
                if (!(Test-Path -LiteralPath $directory -PathType Container)) { $complete = $false; break }
                if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw "Dependency license directory must not be a link: $package"
                }
                $files = @(Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Name -match ('^' + $layout.filenamePattern + '$') })
                if (!$files.Count) { $complete = $false; break }
                foreach ($file in $files) {
                    if ($file.Length -eq 0 -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                        throw "Dependency license must be a nonempty regular file: $package/$($file.Name)"
                    }
                    $candidate.Add([pscustomobject]@{ Package = $package; File = $file })
                }
            }
            if ($complete) { $choice = $candidate; break }
        }
        if ($null -eq $choice) {
            $expected = @($group.alternatives | ForEach-Object { @($_) -join ' + ' }) -join ' OR '
            throw "Missing dependency license for $($group.name); expected $expected under $sourceRoot"
        }
        foreach ($item in $choice) { $selected.Add($item) }
    }
    # Validate every group before writing; never silently omit an unavailable dependency.
    foreach ($item in $selected) {
        $destination = Join-Path (Join-Path $BundleDirectory 'licenses') $item.Package
        [void][IO.Directory]::CreateDirectory($destination)
        Copy-Item -LiteralPath $item.File.FullName -Destination (Join-Path $destination $item.File.Name) -Force
    }
    $packages = @($selected | ForEach-Object { $_.Package } | Sort-Object -Unique)
    Write-Output ("Prepared dependency licenses: " + ($packages -join ', '))
}

Export-ModuleMember -Function Copy-TdlibDependencyLicenses
