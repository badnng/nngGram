param(
    [Parameter(Mandatory = $true)][string]$ProbeReport,
    [string]$OutputDirectory,
    [ValidateSet('Auto', 'Pdh', 'Cim')][string]$CounterProvider = 'Auto',
    [ValidateRange(5, 300)][int]$ReadyTimeoutSeconds = 60,
    [ValidateRange(250, 5000)][int]$IntervalMilliseconds = 1000
)

$ErrorActionPreference = 'Stop'
$reportPath = [IO.Path]::GetFullPath($ProbeReport)
$readyPath = $reportPath + '.ready.json'
if (Test-Path -LiteralPath $reportPath) { throw 'Use a new probe report path for each measurement.' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path -Parent $reportPath) ('gpu-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
}
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputPath) { throw 'The GPU output directory must be new; existing reports are never replaced.' }
New-Item -ItemType Directory -Path $outputPath | Out-Null

# The application is launched separately. Never attach to a real-account process by name,
# start an application, change foreground focus, or terminate any process from this script.
$deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
while (!(Test-Path -LiteralPath $readyPath)) {
    if ([DateTime]::UtcNow -ge $deadline) { throw 'The explicit synthetic idle probe did not become ready.' }
    Start-Sleep -Milliseconds 200
}
$ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
$probeProcessId = [int]$ready.pid
if ($probeProcessId -le 0 -or $ready.mode -notin @('static', 'dialog', 'animation', 'video')) { throw 'Invalid synthetic probe metadata.' }
$probeProcess = Get-Process -Id $probeProcessId -ErrorAction Stop
$processStartTime = $probeProcess.StartTime.ToUniversalTime()
if ((Get-Item -LiteralPath $readyPath).LastWriteTimeUtc -lt $processStartTime) {
    throw 'The ready file predates this process; refusing a stale or reused PID.'
}
$samples = [Collections.Generic.List[object]]::new()
$engines = [Collections.Generic.List[object]]::new()
$provider = 'unavailable'
$counterFailure = $null
$counterPath = '\GPU Engine(pid_' + $probeProcessId + '_*)\Utilization Percentage'
$initializationTimer = [Diagnostics.Stopwatch]::StartNew()
$windowInteropAvailable = $false
try {
    if (-not ('NngGram.IdleProbeWindows' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace NngGram {
    public sealed class IdleWindowSample {
        public int TargetWindowCount;
        public int VisibleWindowCount;
        public int IconicWindowCount;
        public int VisibleNonIconicWindowCount;
        public string TargetHandles;
        public uint ForegroundProcessId;
        public bool TargetForeground;
        public bool ForegroundVisible;
    }
    public static class IdleProbeWindows {
        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr parameter);
        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
        public static IdleWindowSample Read(uint targetProcessId) {
            var sample = new IdleWindowSample();
            var handles = new List<string>();
            EnumWindows((hwnd, parameter) => {
                uint processId;
                GetWindowThreadProcessId(hwnd, out processId);
                if (processId != targetProcessId) return true;
                sample.TargetWindowCount++;
                handles.Add("0x" + hwnd.ToInt64().ToString("X"));
                bool visible = IsWindowVisible(hwnd);
                bool iconic = IsIconic(hwnd);
                if (visible) sample.VisibleWindowCount++;
                if (iconic) sample.IconicWindowCount++;
                if (visible && !iconic) sample.VisibleNonIconicWindowCount++;
                return true;
            }, IntPtr.Zero);
            sample.TargetHandles = String.Join(";", handles);
            IntPtr foreground = GetForegroundWindow();
            uint foregroundProcessId;
            GetWindowThreadProcessId(foreground, out foregroundProcessId);
            sample.ForegroundProcessId = foregroundProcessId;
            sample.TargetForeground = foreground != IntPtr.Zero && foregroundProcessId == targetProcessId;
            sample.ForegroundVisible = sample.TargetForeground && IsWindowVisible(foreground) && !IsIconic(foreground);
            return sample;
        }
    }
}
'@ -ErrorAction Stop
    }
    $windowInteropAvailable = $true
} catch { $windowInteropAvailable = $false }

if ($CounterProvider -ne 'Cim') {
    try {
        # Request this process only. Initial PDH setup can be costly, so this first
        # sample is discarded and its duration is recorded outside the baseline.
        $null = Get-Counter -Counter $counterPath -SampleInterval 1 -MaxSamples 1 -ErrorAction Stop
        $provider = 'pdh'
    } catch { $counterFailure = 'PDH GPU counters unavailable, inaccessible, or no process instance yet.' }
}
if ($provider -eq 'unavailable' -and $CounterProvider -ne 'Pdh') {
    try {
        # Optional fallback only; some WMI GPU providers time out even when PDH works.
        $null = Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -OperationTimeoutSec 5 -ErrorAction Stop
        $provider = 'cim'
        $counterFailure = $null
    } catch { $counterFailure = 'GPU counters unavailable or inaccessible; CIM fallback failed.' }
}
$initializationMillis = $initializationTimer.ElapsedMilliseconds
# With forced PDH keep retrying after a missing early process instance, without WMI.
if ($CounterProvider -eq 'Pdh') { $provider = 'pdh' }

$probeProcess.Refresh()
$lastCpu = $probeProcess.TotalProcessorTime.TotalSeconds
$lastTimestamp = [DateTimeOffset]::UtcNow
$limit = [DateTime]::UtcNow.AddSeconds([int]$ready.seconds + [int]$ready.warmupSeconds + 45)
while ([DateTime]::UtcNow -lt $limit -and !(Test-Path -LiteralPath $reportPath)) {
    # Get-Counter itself takes a one-second interval; do not add a second sleep.
    $pollDelay = if ($provider -eq 'pdh') { [Math]::Max(0, $IntervalMilliseconds - 1000) } else { $IntervalMilliseconds }
    if ($pollDelay -gt 0) { Start-Sleep -Milliseconds $pollDelay }
    try {
        $probeProcess = Get-Process -Id $probeProcessId -ErrorAction Stop
        if ($probeProcess.StartTime.ToUniversalTime() -ne $processStartTime) { break }
    } catch { break }
    $gpuRows = @()
    $gpuStatus = 'unavailable'
    try {
        if ($provider -eq 'cim') {
            $gpuRows = @(Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -OperationTimeoutSec 5 -ErrorAction Stop |
                Where-Object { $_.Name -match ('^pid_' + $probeProcessId + '_') } |
                ForEach-Object { [pscustomobject]@{ Engine = $_.Name; Utilization = [double]$_.UtilizationPercentage } })
        } elseif ($provider -eq 'pdh') {
            $result = Get-Counter -Counter $counterPath -SampleInterval 1 -MaxSamples 1 -ErrorAction Stop
            $gpuRows = @($result.CounterSamples |
                Where-Object { $_.Status -eq 0 -and $_.InstanceName -match ('^pid_' + $probeProcessId + '_') } |
                ForEach-Object { [pscustomobject]@{ Engine = $_.InstanceName; Utilization = [double]$_.CookedValue } })
        }
        if ($provider -ne 'unavailable') {
            $gpuStatus = if ($gpuRows.Count -gt 0) { 'available' } else { 'no_process_instances' }
        }
    } catch {
        $gpuStatus = 'query_failed'
        # Missing PDH instances can fail immediately; avoid a busy retry loop.
        if ($provider -eq 'pdh') { Start-Sleep -Milliseconds $IntervalMilliseconds }
    }
    try {
        $probeProcess.Refresh()
        $cpu = $probeProcess.TotalProcessorTime.TotalSeconds
    } catch { break }
    $windows = $null
    $windowStatus = 'unavailable'
    if ($windowInteropAvailable) {
        try {
            # Only inspect HWND metadata for the PID from the synthetic ready file.
            # Foreground PID is numeric only; never read window titles or change focus.
            $windows = [NngGram.IdleProbeWindows]::Read([uint32]$probeProcessId)
            $windowStatus = 'available'
        } catch { $windowStatus = 'query_failed' }
    }
    $now = [DateTimeOffset]::UtcNow
    $interval = ($now - $lastTimestamp).TotalSeconds
    $maximum = $null
    $sum = $null
    if ($gpuRows.Count -gt 0) {
        $maximum = ($gpuRows | Measure-Object Utilization -Maximum).Maximum
        $sum = ($gpuRows | Measure-Object Utilization -Sum).Sum
        foreach ($engine in $gpuRows) {
            $engines.Add([pscustomobject]@{ EpochMillis = $now.ToUnixTimeMilliseconds(); Engine = $engine.Engine; UtilizationPercent = $engine.Utilization })
        }
    }
    $samples.Add([pscustomobject]@{
        IntervalStartEpochMillis = $lastTimestamp.ToUnixTimeMilliseconds()
        EpochMillis = $now.ToUnixTimeMilliseconds()
        CpuMachinePercent = if ($interval -gt 0) { [Math]::Max(0.0, ($cpu - $lastCpu) / $interval / [Environment]::ProcessorCount * 100) } else { $null }
        WorkingSetBytes = $probeProcess.WorkingSet64
        GpuStatus = $gpuStatus
        GpuMaxEnginePercent = $maximum
        GpuEngineSumPercent = $sum
        GpuEngineCount = $gpuRows.Count
        WindowStatus = $windowStatus
        TargetWindowHandles = if ($null -ne $windows) { $windows.TargetHandles } else { $null }
        TargetWindowCount = if ($null -ne $windows) { $windows.TargetWindowCount } else { $null }
        VisibleWindowCount = if ($null -ne $windows) { $windows.VisibleWindowCount } else { $null }
        IconicWindowCount = if ($null -ne $windows) { $windows.IconicWindowCount } else { $null }
        WindowVisible = if ($null -ne $windows) { $windows.VisibleNonIconicWindowCount -gt 0 } else { $null }
        ForegroundProcessId = if ($null -ne $windows) { $windows.ForegroundProcessId } else { $null }
        TargetForeground = if ($null -ne $windows) { $windows.TargetForeground } else { $null }
        ForegroundVisible = if ($null -ne $windows) { $windows.ForegroundVisible } else { $null }
    })
    $lastTimestamp = $now
    $lastCpu = $cpu
}

$samples | Export-Csv -LiteralPath (Join-Path $outputPath 'samples.csv') -NoTypeInformation -Encoding utf8
$engines | Export-Csv -LiteralPath (Join-Path $outputPath 'engines.csv') -NoTypeInformation -Encoding utf8
$probe = if (Test-Path -LiteralPath $reportPath) { Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json } else { $null }
$measured = @()
if ($null -ne $probe -and [int]$probe.pid -eq $probeProcessId) {
    $measured = @($samples | Where-Object {
        $_.IntervalStartEpochMillis -ge $probe.measurementStartEpochMillis -and $_.EpochMillis -le $probe.measurementEndEpochMillis
    })
}
$gpuMeasured = @($measured | Where-Object GpuStatus -eq 'available')
$validVisible = $null -ne $probe -and $probe.status -eq 'complete' -and $probe.validForVisibleComparison -eq $true
$visibleFraction = if ($measured.Count) { @($measured | Where-Object WindowVisible -eq $true).Count / [double]$measured.Count } else { $null }
$foregroundFraction = if ($measured.Count) { @($measured | Where-Object TargetForeground -eq $true).Count / [double]$measured.Count } else { $null }
$foregroundVisibleFraction = if ($measured.Count) { @($measured | Where-Object ForegroundVisible -eq $true).Count / [double]$measured.Count } else { $null }
$summary = [ordered]@{
    Schema = 1
    ProcessId = $probeProcessId
    Mode = $ready.mode
    Provider = $provider
    RequestedProvider = $CounterProvider
    CounterInitializationMillis = $initializationMillis
    InitializationSampleExcluded = $true
    CounterFailure = $counterFailure
    CollectedSamples = $samples.Count
    MeasurementSamples = $measured.Count
    AvailableGpuSamples = $gpuMeasured.Count
    AvailableWindowSamples = @($measured | Where-Object WindowStatus -eq 'available').Count
    VisibleSampleFraction = $visibleFraction
    ForegroundSampleFraction = $foregroundFraction
    ForegroundVisibleSampleFraction = $foregroundVisibleFraction
    ValidVisibleProbe = $validVisible
    ValidForGpuComparison = $validVisible -and $measured.Count -ge 3 -and $gpuMeasured.Count -ge [Math]::Ceiling($measured.Count * 0.8) -and $foregroundVisibleFraction -ge 0.9
    CpuMachineMeanPercent = if ($measured.Count) { ($measured | Measure-Object CpuMachinePercent -Average).Average } else { $null }
    GpuMaxEngineMeanPercent = if ($gpuMeasured.Count) { ($gpuMeasured | Measure-Object GpuMaxEnginePercent -Average).Average } else { $null }
    GpuMaxEnginePeakPercent = if ($gpuMeasured.Count) { ($gpuMeasured | Measure-Object GpuMaxEnginePercent -Maximum).Maximum } else { $null }
    Note = 'Per-process GPU engines only. Engine sums may exceed 100%; this is not whole-GPU utilization. Missing counters are null, not zero. Draw callbacks are not compositor presents. Win32 visibility is a window flag, not proof that every pixel is unobscured; foreground is checked independently without changing it.'
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputPath 'summary.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 4
