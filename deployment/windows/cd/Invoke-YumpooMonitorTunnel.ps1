#Requires -Version 5.1
[CmdletBinding()]
param([string]$Root = 'C:/ProgramData/Yumpoo')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ssh = Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe'
while ($true) {
    try {
        $config = Get-Content -LiteralPath (Join-Path $Root 'secrets/monitor.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($config.sshHost -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$') { throw 'Invalid monitor SSH host.' }
        & $ssh -N -T -F NUL -i (Join-Path $Root 'secrets/monitor-ssh-key') `
            -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityAgent=none -o StrictHostKeyChecking=yes `
            -o "UserKnownHostsFile=$(Join-Path $Root 'secrets/monitor-known-hosts')" -o GlobalKnownHostsFile=NUL `
            -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=2 -o ExitOnForwardFailure=yes `
            -L 127.0.0.1:28788:127.0.0.1:8788 -R 127.0.0.1:19100:127.0.0.1:19100 "yumpoo-monitor-windows@$($config.sshHost)" 2>$null
    } catch { }
    Start-Sleep -Seconds 10
}
