#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force
$directory = Join-Path $env:TEMP ('yumpoo-monitor-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $directory 'deployment/engine') -Force | Out-Null
function Assert-Monitor($Condition, $Message) { if (-not $Condition) { throw $Message } }
try {
    $status = [ordered]@{ requestId = [guid]::NewGuid().ToString(); tag = 'server-v2026.10.23.1'; sourceCommit = ('a' * 40); state = 'succeeded'; phase = 'complete' }
    $legacy = [pscustomobject]@{ schemaVersion = 1 }
    $enabled = [pscustomobject]@{ monitoring = [pscustomobject]@{ enabled = $true; environment = 'test' } }
    Assert-Monitor (-not (Get-YumpooMonitorSettings $legacy).enabled) 'Old host configuration must remain disabled.'
    $script:waited = 0
    Wait-YumpooMaintenanceNotice $directory $legacy $status 'deploy' { param($Seconds) $script:waited += $Seconds }
    Assert-Monitor ($script:waited -eq 0) 'Legacy deployments cannot acquire a new countdown.'
    Wait-YumpooMaintenanceNotice $directory $enabled $status 'rollback' { param($Seconds) $script:waited += $Seconds }
    Assert-Monitor ($script:waited -eq 0) 'Rollback cannot wait for a maintenance notice.'
    Wait-YumpooMaintenanceNotice $directory $enabled $status 'deploy' { param($Seconds) $script:waited += $Seconds }
    Assert-Monitor ($script:waited -eq 300) 'Forward deployments must wait 300 seconds independently of remote delivery.'
    $spool = Join-Path $directory 'deployment/monitor-outbox'
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.json').Count -eq 1) 'The announcement must be committed to disk.'
    $null = Add-YumpooMonitorEvent $directory $enabled $status 'maintenance.planned'
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.json').Count -eq 1) 'Repeated local events must not duplicate the spool.'
    $null = Add-YumpooMonitorEvent $directory $enabled $status 'deployment.completed'
    $terminal = Get-Content -LiteralPath (Join-Path $spool "$($status.requestId)-deployment.completed.json") -Raw | ConvertFrom-Json
    Assert-Monitor ($terminal.payload.outcome -ceq 'succeeded' -and $terminal.source -ceq 'windows' -and $terminal.environment -ceq 'test') 'Terminal facts must retain their source and outcome.'
    $signature = Get-YumpooMonitorSignature ('a' * 32) '1800000000000' ('b' * 32) '{"test":true}'
    $expected = '5325b63ea4e6c5e59d8cd9f8e6071e3ed5fb12abe508973201cb3cc70a85e138'
    Assert-Monitor ($signature -ceq $expected) 'PowerShell and Node must sign identical UTF-8 bytes.'
    $badRoot = Join-Path $directory 'not-a-directory'
    [IO.File]::WriteAllText($badRoot, 'file')
    $script:waited = 0
    Wait-YumpooMaintenanceNotice $badRoot $enabled $status 'deploy' { param($Seconds) $script:waited += $Seconds }
    Assert-Monitor ($script:waited -eq 300) 'A spool failure must not skip the approved countdown.'
    New-Item -ItemType Directory -Path (Join-Path $directory 'secrets') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $directory 'monitoring/textfile') -Force | Out-Null
    Write-YumpooJson (Join-Path $directory 'secrets/monitor.json') @{ eventUrl = 'http://127.0.0.1:28788/internal/v1/events'; sourceSecret = ('a' * 64) }
    function Invoke-RestMethod { throw 'Simulated tunnel disconnection' }
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorRelay.ps1') -Root $directory -Once
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.json').Count -eq 2) 'A disconnected tunnel must preserve events.'
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.retry').Count -eq 2) 'Backoff metadata must survive relay restart.'
    foreach ($retryFile in Get-ChildItem -LiteralPath $spool -Filter '*.retry') { Write-YumpooJson $retryFile.FullName @{ attempts = 1; nextAt = '2000-01-01T00:00:00Z' } }
    function Invoke-RestMethod { return @{ accepted = $true } }
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorRelay.ps1') -Root $directory -Once
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.sent').Count -eq 2) 'Reconnection must deliver both retained events.'
    Assert-Monitor (@(Get-ChildItem -LiteralPath $spool -Filter '*.retry').Count -eq 0) 'Acknowledged events must clear their retry metadata.'
    Write-YumpooJson (Join-Path $directory 'secrets/monitor.json') @{ watchdogWebhook = 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test' }
    Write-YumpooJson (Join-Path $spool 'watchdog-state.dat') @{ downSince = [DateTimeOffset]::UtcNow.AddMinutes(-4).ToString('o'); lastSent = ''; notified = $false }
    $watchdogMock = @{ deliveries = 0; pipeline = 'UNKNOWN' }
    function Invoke-RestMethod {
        param($Uri)
        if ($Uri -eq 'http://127.0.0.1:28788/healthz') { return @{ status = 'UP'; monitoring = $watchdogMock.pipeline } }
        $watchdogMock.deliveries++
        return @{ errcode = 0 }
    }
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorWatchdog.ps1') -Root $directory -Once
    Assert-Monitor ($watchdogMock.deliveries -eq 1) 'An available receiver must not hide a failed monitoring pipeline.'
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorWatchdog.ps1') -Root $directory -Once
    Assert-Monitor ($watchdogMock.deliveries -eq 1) 'An ongoing failure cannot notify again within one hour.'
    $watchdogMock.pipeline = 'UP'
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorWatchdog.ps1') -Root $directory -Once
    Assert-Monitor ($watchdogMock.deliveries -eq 2) 'Pipeline recovery must notify independently.'
    $recovered = Get-Content -LiteralPath (Join-Path $spool 'watchdog-state.dat') -Raw | ConvertFrom-Json
    $recovered.downSince = [DateTimeOffset]::UtcNow.AddMinutes(-4).ToString('o')
    Write-YumpooJson (Join-Path $spool 'watchdog-state.dat') $recovered
    $watchdogMock.pipeline = 'UNKNOWN'
    & (Join-Path $PSScriptRoot 'Invoke-YumpooMonitorWatchdog.ps1') -Root $directory -Once
    Assert-Monitor ($watchdogMock.deliveries -eq 3) 'A new incident after recovery must not inherit the previous reminder cooldown.'
    Remove-Item Function:Invoke-RestMethod
    Write-Output 'Monitoring compatibility, countdown, durable events and cross-runtime signatures passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup escaped TEMP.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
