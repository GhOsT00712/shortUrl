param(
    [ValidateRange(0, 1000000)][int]$ReadRate = 10000,
    [ValidateRange(0, 100000)][int]$WriteRate = 1000,
    [ValidateRange(1, 3600)][int]$DurationSeconds = 60,
    [ValidateRange(0, 300)][int]$WarmupSeconds = 15,
    [ValidateRange(1, 100000)][int]$SeedCount = 1000,
    [ValidateRange(0, 10000)][int]$ReadVUs = 0,
    [ValidateRange(0, 10000)][int]$WriteVUs = 0,
    [string]$BaseUrl = 'http://nginx'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runId = '{0}-{1}r-{2}w-{3}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $ReadRate, $WriteRate, ([guid]::NewGuid().ToString('N').Substring(0, 6))
$resultDirectory = Join-Path $PSScriptRoot "results/$runId"
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null

function Save-MongoSnapshot([string]$Label) {
    foreach ($member in 'mongo1', 'mongo2', 'mongo3') {
        $snapshot = Get-Content (Join-Path $PSScriptRoot 'mongo-snapshot.js') -Raw |
            docker compose exec -T $member mongosh --quiet --file /dev/stdin
        if ($LASTEXITCODE -ne 0) { throw "Cannot collect MongoDB snapshot from $member" }
        $snapshot | Set-Content (Join-Path $resultDirectory "$Label-$member.json") -Encoding utf8
    }
}

Push-Location $projectRoot
try {
    docker compose ps -a | Set-Content (Join-Path $resultDirectory 'containers.txt')
    docker info --format 'CPUs={{.NCPU}} Memory={{.MemTotal}} Version={{.ServerVersion}}' |
        Set-Content (Join-Path $resultDirectory 'docker-host.txt')
    Save-MongoSnapshot 'before'
    $arguments = @('compose', '--profile', 'loadtest', 'run', '--rm', '--no-deps', '-T',
        '--name', "shorturl-k6-$runId", '-e', "RUN_ID=$runId", '-e', "BASE_URL=$BaseUrl",
        '-e', "READ_RATE=$ReadRate", '-e', "WRITE_RATE=$WriteRate", '-e', "DURATION_SECONDS=$DurationSeconds",
        '-e', "WARMUP_SECONDS=$WarmupSeconds", '-e', "SEED_COUNT=$SeedCount",
        '-e', "TTL_SECONDS=$([Math]::Max(3600, $DurationSeconds + $WarmupSeconds + 600))")
    if ($ReadVUs) { $arguments += @('-e', "READ_VUS=$ReadVUs") }
    if ($WriteVUs) { $arguments += @('-e', "WRITE_VUS=$WriteVUs") }
    $arguments += @('k6', 'run', '--quiet', '/scripts/benchmark.js')
    $started = [DateTime]::UtcNow.ToString('o')
    $process = Start-Process -FilePath 'docker' -ArgumentList $arguments -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $resultDirectory 'k6.stdout.log') `
        -RedirectStandardError (Join-Path $resultDirectory 'k6.stderr.log')
    Write-Host "Running $runId. Results: $resultDirectory"
    $sample = 0
    while (-not $process.HasExited) {
        $time = [DateTime]::UtcNow.ToString('o')
        $stats = @(docker stats --no-stream --format '{{json .}}')
        foreach ($line in $stats) {
            @{ time = $time; stats = ($line | ConvertFrom-Json) } | ConvertTo-Json -Compress |
                Add-Content (Join-Path $resultDirectory 'docker-stats.jsonl') -Encoding utf8
        }
        if ($sample % 3 -eq 0) { Save-MongoSnapshot "sample-$sample" }
        $sample++
        $process.WaitForExit(3000) | Out-Null
        $process.Refresh()
    }
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    Save-MongoSnapshot 'after'
    docker compose logs --no-color --since $started --tail 100 api nginx |
        Set-Content (Join-Path $resultDirectory 'service-tail.log') -Encoding utf8
    if (Test-Path (Join-Path $resultDirectory 'summary.txt')) {
        Get-Content (Join-Path $resultDirectory 'summary.txt') | Write-Host
    } else {
        Get-Content (Join-Path $resultDirectory 'k6.stderr.log') | Write-Host
    }
    Write-Host "k6 exit code: $exitCode (99 normally means a threshold failed). Results: $resultDirectory"
    exit $exitCode
} finally { Pop-Location }
