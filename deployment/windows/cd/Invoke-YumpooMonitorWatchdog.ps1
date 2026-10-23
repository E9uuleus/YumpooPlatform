#Requires -Version 5.1
[CmdletBinding()]
param([string]$Root = 'C:/ProgramData/Yumpoo', [switch]$Once)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force
$stateFile = Join-Path $Root 'deployment/monitor-outbox/watchdog-state.dat'
do {
    try {
        $state = @{ downSince = ''; lastSent = ''; notified = $false }
        if (Test-Path -LiteralPath $stateFile) {
            $saved = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
            $state.downSince = $saved.downSince; $state.lastSent = $saved.lastSent; $state.notified = $saved.notified
        }
        $up = $false
        try { $up = (Invoke-RestMethod -Uri 'http://127.0.0.1:28788/healthz' -TimeoutSec 8).status -ceq 'UP' } catch { }
        $now = [DateTimeOffset]::UtcNow
        $text = ''
        if ($up) {
            $state.downSince = ''
            if ($state.notified) { $text = 'Yumpoo monitoring connection restored. This message is from the independent Windows watchdog.' }
        } else {
            if (-not $state.downSince) { $state.downSince = $now.ToString('o') }
            if (($now - [DateTimeOffset]::Parse($state.downSince)).TotalMinutes -ge 3 -and (-not $state.lastSent -or ($now - [DateTimeOffset]::Parse($state.lastSent)).TotalHours -ge 1)) {
                $text = 'Yumpoo monitoring connection has been unreachable for 3 minutes. Check the monitoring host and SSH tunnel. Business availability is not confirmed by this alert.'
            }
        }
        if ($text) {
            $config = Get-Content -LiteralPath (Join-Path $Root 'secrets/monitor.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $url = [string](Get-YumpooSetting $config 'watchdogWebhook' '')
            if ($url -cmatch '^https://qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key=[A-Za-z0-9-]+$') {
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                $body = [Text.Encoding]::UTF8.GetBytes((@{ msgtype = 'markdown'; markdown = @{ content = $text } } | ConvertTo-Json -Depth 4))
                $response = Invoke-RestMethod -Method Post -Uri $url -ContentType 'application/json' -Body $body -TimeoutSec 10
                if ($response.errcode -eq 0) { $state.notified = -not $up; $state.lastSent = $now.ToString('o') }
            }
        }
        Write-YumpooJson $stateFile $state -Attempts 2 -DelayMilliseconds 100
    } catch { }
    if (-not $Once) { Start-Sleep -Seconds 30 }
} while (-not $Once)
