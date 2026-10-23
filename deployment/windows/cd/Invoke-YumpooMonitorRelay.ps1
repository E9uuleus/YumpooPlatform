#Requires -Version 5.1
[CmdletBinding()]
param([string]$Root = 'C:/ProgramData/Yumpoo', [switch]$Once)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force
$directory = Join-Path $Root 'deployment/monitor-outbox'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$lock = $null
try {
    try { $lock = [IO.File]::Open((Join-Path $directory 'relay.lock'), 'OpenOrCreate', 'ReadWrite', 'None') } catch [IO.IOException] { exit 0 }
    do {
        try {
            $config = Get-Content -LiteralPath (Join-Path $Root 'secrets/monitor.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($config.eventUrl -cne 'http://127.0.0.1:28788/internal/v1/events' -or ([string]$config.sourceSecret).Length -lt 32) { throw 'Invalid monitoring configuration.' }
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File | Sort-Object Name | Select-Object -First 30)) {
                $retryFile = $file.FullName + '.retry'
                $retry = @{ attempts = 0; nextAt = '' }
                if (Test-Path -LiteralPath $retryFile) {
                    $saved = Get-Content -LiteralPath $retryFile -Raw | ConvertFrom-Json
                    $retry.attempts = [int]$saved.attempts; $retry.nextAt = [string]$saved.nextAt
                    if ($retry.nextAt -and [DateTimeOffset]::Parse($retry.nextAt) -gt [DateTimeOffset]::UtcNow) { continue }
                }
                $body = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
                try { $event = $body | ConvertFrom-Json } catch { Move-Item -LiteralPath $file.FullName -Destination ($file.FullName + '.dead'); continue }
                if ($event.type -eq 'maintenance.planned' -and [DateTimeOffset]::Parse($event.occurredAt).AddMinutes(5) -lt [DateTimeOffset]::UtcNow) {
                    Move-Item -LiteralPath $file.FullName -Destination ($file.FullName + '.expired')
                    continue
                }
                $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
                $nonce = [guid]::NewGuid().ToString('N')
                $headers = @{ 'X-Monitor-Source' = 'windows'; 'X-Monitor-Time' = $timestamp; 'X-Monitor-Nonce' = $nonce; 'X-Monitor-Signature' = (Get-YumpooMonitorSignature $config.sourceSecret $timestamp $nonce $body) }
                try {
                    $response = Invoke-RestMethod -Method Post -Uri $config.eventUrl -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10
                    if (-not ((Get-YumpooSetting $response 'accepted' $false) -or (Get-YumpooSetting $response 'duplicate' $false) -or (Get-YumpooSetting $response 'obsolete' $false))) { throw 'Event was not acknowledged.' }
                    Move-Item -LiteralPath $file.FullName -Destination ($file.FullName + '.sent')
                    if (Test-Path -LiteralPath $retryFile) { Remove-Item -LiteralPath $retryFile }
                } catch {
                    $status = 0
                    $responseProperty = $_.Exception.PSObject.Properties['Response']
                    if ($null -ne $responseProperty -and $null -ne $responseProperty.Value) { $status = [int]$responseProperty.Value.StatusCode }
                    if ($status -in @(400,403,404,413,422)) {
                        Move-Item -LiteralPath $file.FullName -Destination ($file.FullName + '.dead')
                    } else {
                        $retry.attempts++
                        $delay = [Math]::Min(300, 5 * [Math]::Pow(2, [Math]::Min($retry.attempts, 6)))
                        $retry.nextAt = [DateTimeOffset]::UtcNow.AddSeconds($delay).ToString('o')
                        Write-YumpooJson $retryFile $retry -Attempts 2 -DelayMilliseconds 100
                    }
                    [IO.File]::WriteAllText((Join-Path $directory 'last-error.txt'), 'Event relay failed; queued events remain on disk. Check the tunnel and source credential.')
                }
            }
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Name -match '\.json\.(sent|expired)$' -and $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-180) })) {
                $null = Assert-YumpooPath $file.FullName $directory
                Remove-Item -LiteralPath $file.FullName
            }
        } catch {
            [IO.File]::WriteAllText((Join-Path $directory 'last-error.txt'), 'Monitoring relay configuration is unavailable or invalid.')
        }
        $metrics = Join-Path $Root 'monitoring/textfile'
        if (Test-Path -LiteralPath $metrics) {
            $pending = @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File).Count
            $dead = @(Get-ChildItem -LiteralPath $directory -Filter '*.dead' -File).Count
            $text = "yumpoo_monitor_relay_timestamp_seconds $([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())`nyumpoo_monitor_relay_pending $pending`nyumpoo_monitor_relay_dead $dead`n"
            $temporary = Join-Path $metrics 'relay.tmp'
            [IO.File]::WriteAllText($temporary, $text, (New-Object Text.UTF8Encoding($false)))
            Move-Item -LiteralPath $temporary -Destination (Join-Path $metrics 'relay.prom') -Force
        }
        if (-not $Once) { Start-Sleep -Seconds 5 }
    } while (-not $Once)
} finally { if ($null -ne $lock) { $lock.Dispose() } }
