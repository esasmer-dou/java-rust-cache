param(
    [string] $ReaderImage = "rest-sample-cache-reader:memory-role-20260715",
    [string] $RedisImage = "redis:8.2.1-alpine3.22",
    [string] $WrkImage = "williamyeh/wrk:latest",
    [int] $Concurrency = 64,
    [int] $Threads = 2,
    [int] $DurationSeconds = 10,
    [int] $RepeatCount = 3,
    [string] $MemoryLimit = "128m",
    [double] $CpuLimit = 1.0,
    [string] $ResultsDir = ""
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($RepeatCount -lt 1) {
    throw "RepeatCount must be >= 1."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\access_mode_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$Network = "reactor-cache-access-bench"
$RedisContainer = "reactor-cache-access-redis"
$ReadOnlyContainer = "reactor-cache-reader-ro"
$ReadWriteContainer = "reactor-cache-reader-rw"
$Endpoint = "/api/v1/cache/customers/1001"
$MetricsEndpoint = "/api/v1/cache/customers/cache-metrics"

function Invoke-Docker {
    param([string[]] $Arguments, [switch] $IgnoreFailure)

    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreFailure) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Convert-ToMiB {
    param([string] $Value)

    $text = $Value.Trim()
    if ($text -match "^([0-9.]+)(KiB|MiB|GiB)$") {
        $number = [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
        switch ($Matches[2]) {
            "KiB" { return $number / 1024.0 }
            "MiB" { return $number }
            "GiB" { return $number * 1024.0 }
        }
    }
    throw "Unsupported Docker memory value: $Value"
}

function Get-ContainerMemoryMiB {
    param([string] $Container)

    $usage = (Invoke-Docker @("stats", "--no-stream", "--format", "{{.MemUsage}}", $Container) | Select-Object -First 1)
    return Convert-ToMiB (($usage -split "/")[0].Trim())
}

function Invoke-InNetworkGet {
    param([string] $Container, [string] $Path)

    return (Invoke-Docker @(
        "run", "--rm", "--network", $Network,
        "alpine:3.20", "wget", "-qO-", "http://${Container}:8080${Path}"
    ) | Out-String).Trim()
}

function Wait-Ready {
    param([string] $Container)

    $deadline = (Get-Date).AddSeconds(60)
    do {
        try {
            $health = Invoke-InNetworkGet $Container "/app/health"
            if ($health -match '"status":"UP"') {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Container did not become ready before timeout."
}

function Convert-LatencyToMs {
    param([double] $Value, [string] $Unit)
    switch ($Unit) {
        "us" { return $Value / 1000.0 }
        "ms" { return $Value }
        "s" { return $Value * 1000.0 }
        default { throw "Unsupported latency unit: $Unit" }
    }
}

function Invoke-WrkRun {
    param([string] $Variant, [string] $Container, [int] $Run)

    $stdout = Join-Path $ResultsDir ("{0}-r{1}.txt" -f $Variant, $Run)
    $stderr = Join-Path $ResultsDir ("{0}-r{1}.err.txt" -f $Variant, $Run)
    $arguments = @(
        "run", "--rm", "--network", $Network,
        $WrkImage,
        "-t$Threads", "-c$Concurrency", "-d${DurationSeconds}s", "--latency",
        "http://${Container}:8080${Endpoint}"
    )
    $process = Start-Process -FilePath "docker" -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden
    $maxMemory = 0.0
    while (-not $process.HasExited) {
        try {
            $memory = Get-ContainerMemoryMiB $Container
            if ($memory -gt $maxMemory) {
                $maxMemory = $memory
            }
        } catch {
            # A final stats sample is collected after wrk exits.
        }
        Start-Sleep -Milliseconds 250
        $process.Refresh()
    }
    if ($process.ExitCode -ne 0) {
        throw "wrk failed for $Variant run $Run. See $stderr"
    }
    $maxMemory = [Math]::Max($maxMemory, (Get-ContainerMemoryMiB $Container))
    $text = Get-Content -LiteralPath $stdout -Raw
    $rpsMatch = [regex]::Match($text, "Requests/sec:\s+([0-9.]+)")
    $p99Match = [regex]::Match($text, "(?m)^\s*99%\s+([0-9.]+)(us|ms|s)")
    if (-not $rpsMatch.Success -or -not $p99Match.Success) {
        throw "Unable to parse wrk output: $stdout"
    }
    $non2xxMatch = [regex]::Match($text, "Non-2xx or 3xx responses:\s+([0-9]+)")
    $rps = [double]::Parse($rpsMatch.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    $p99Value = [double]::Parse($p99Match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    return [PSCustomObject]@{
        variant = $Variant
        run = $Run
        concurrency = $Concurrency
        rps = [Math]::Round($rps, 2)
        p99_ms = [Math]::Round((Convert-LatencyToMs $p99Value $p99Match.Groups[2].Value), 3)
        non_2xx = if ($non2xxMatch.Success) { [int64] $non2xxMatch.Groups[1].Value } else { 0L }
        max_memory_mib = [Math]::Round($maxMemory, 3)
    }
}

function Start-Reader {
    param([string] $Name, [string] $AccessMode)

    Invoke-Docker @(
        "run", "-d", "--name", $Name, "--network", $Network,
        "--memory", $MemoryLimit, "--cpus", "$CpuLimit",
        "-e", "REACTOR_CACHE_REDIS_HOST=$RedisContainer",
        "-e", "REACTOR_CACHE_REDIS_PORT=6379",
        "-e", "REACTOR_CACHE_REDIS_ACCESS_MODE=$AccessMode",
        $ReaderImage
    ) | Out-Null
}

$rows = [System.Collections.Generic.List[object]]::new()
try {
    Invoke-Docker @("rm", "-f", $RedisContainer, $ReadOnlyContainer, $ReadWriteContainer) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "rm", $Network) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "create", $Network) | Out-Null
    Invoke-Docker @("run", "-d", "--name", $RedisContainer, "--network", $Network, $RedisImage) | Out-Null

    Invoke-Docker @("exec", $RedisContainer, "redis-cli", "SET", "crm.customer.detail:current", "1|v1") | Out-Null
    Invoke-Docker @(
        "exec", $RedisContainer, "redis-cli", "SET", "crm.customer.detail:v:v1:id:1001",
        '{"id":1001,"customerNo":"C-1001","name":"Ada Lovelace","segment":"premium","status":"active"}'
    ) | Out-Null

    Start-Reader $ReadOnlyContainer "read-only"
    Start-Reader $ReadWriteContainer "read-write"
    Wait-Ready $ReadOnlyContainer
    Wait-Ready $ReadWriteContainer

    1..3 | ForEach-Object {
        Invoke-Docker @("run", "--rm", "--network", $Network, $WrkImage, "-t1", "-c16", "-d2s", "http://${ReadOnlyContainer}:8080${Endpoint}") | Out-Null
        Invoke-Docker @("run", "--rm", "--network", $Network, $WrkImage, "-t1", "-c16", "-d2s", "http://${ReadWriteContainer}:8080${Endpoint}") | Out-Null
    }

    $readOnlyMetrics = Invoke-InNetworkGet $ReadOnlyContainer $MetricsEndpoint
    $readWriteMetrics = Invoke-InNetworkGet $ReadWriteContainer $MetricsEndpoint
    $readOnlyIdleMemory = Get-ContainerMemoryMiB $ReadOnlyContainer
    $readWriteIdleMemory = Get-ContainerMemoryMiB $ReadWriteContainer

    for ($run = 1; $run -le $RepeatCount; $run++) {
        $order = if (($run % 2) -eq 1) {
            @(
                [PSCustomObject]@{ Variant = "read-only"; Container = $ReadOnlyContainer },
                [PSCustomObject]@{ Variant = "read-write"; Container = $ReadWriteContainer }
            )
        } else {
            @(
                [PSCustomObject]@{ Variant = "read-write"; Container = $ReadWriteContainer },
                [PSCustomObject]@{ Variant = "read-only"; Container = $ReadOnlyContainer }
            )
        }
        foreach ($item in $order) {
            $rows.Add((Invoke-WrkRun $item.Variant $item.Container $run))
        }
    }

    $rows | Export-Csv -LiteralPath (Join-Path $ResultsDir "results.csv") -NoTypeInformation -Encoding utf8
    $summary = foreach ($group in ($rows | Group-Object variant)) {
        [PSCustomObject]@{
            variant = $group.Name
            runs = $group.Count
            avg_rps = [Math]::Round((($group.Group | Measure-Object rps -Average).Average), 2)
            avg_p99_ms = [Math]::Round((($group.Group | Measure-Object p99_ms -Average).Average), 3)
            total_non_2xx = ($group.Group | Measure-Object non_2xx -Sum).Sum
            avg_max_memory_mib = [Math]::Round((($group.Group | Measure-Object max_memory_mib -Average).Average), 3)
        }
    }
    $summary | Export-Csv -LiteralPath (Join-Path $ResultsDir "summary.csv") -NoTypeInformation -Encoding utf8

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Redis Access Mode Benchmark")
    $lines.Add("")
    $lines.Add("- Reader image: $ReaderImage")
    $lines.Add("- Redis image: $RedisImage")
    $lines.Add("- Endpoint: $Endpoint")
    $lines.Add("- Concurrency: $Concurrency")
    $lines.Add("- Repeat count: $RepeatCount")
    $lines.Add("")
    $lines.Add("| Mode | Runs | Avg RPS | Avg p99 ms | Non-2xx | Avg max memory MiB |")
    $lines.Add("|---|---:|---:|---:|---:|---:|")
    foreach ($row in ($summary | Sort-Object variant)) {
        $lines.Add("| $($row.variant) | $($row.runs) | $($row.avg_rps) | $($row.avg_p99_ms) | $($row.total_non_2xx) | $($row.avg_max_memory_mib) |")
    }
    $lines.Add("")
    $lines.Add("- Read-only idle memory MiB: $([Math]::Round($readOnlyIdleMemory, 3))")
    $lines.Add("- Read-write idle memory MiB: $([Math]::Round($readWriteIdleMemory, 3))")
    $lines.Add("- Read-only native metrics: $readOnlyMetrics")
    $lines.Add("- Read-write native metrics: $readWriteMetrics")
    $lines | Set-Content -LiteralPath (Join-Path $ResultsDir "report.md") -Encoding utf8
    Write-Host "Redis access mode report: $(Join-Path $ResultsDir 'report.md')"
} finally {
    Invoke-Docker @("rm", "-f", $RedisContainer, $ReadOnlyContainer, $ReadWriteContainer) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "rm", $Network) -IgnoreFailure | Out-Null
}
