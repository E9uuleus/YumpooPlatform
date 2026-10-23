#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$Root = 'C:/ProgramData/Yumpoo',
    [Parameter(Mandatory)][string]$ExporterMsi,
    [Parameter(Mandatory)][string]$GatewayConfig
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $Root 'deployment/engine/YumpooDeployment.psm1') -Force
if (-not (Get-Command Register-YumpooMonitorTasks -ErrorAction SilentlyContinue)) { throw 'Upgrade the fixed engine from a verified Release package first.' }
$settings = Get-Content -LiteralPath (Join-Path $Root 'deployment/engine/host.json') -Raw | ConvertFrom-Json
if (-not (Get-YumpooMonitorSettings $settings).enabled) { throw 'Enable monitoring through Update-YumpooEngine.ps1 first.' }
$secrets = Join-Path $Root 'secrets'
foreach ($name in @('monitor.json', 'monitor-ssh-key', 'monitor-known-hosts')) {
    $file = Join-Path $secrets $name
    if (-not (Test-Path -LiteralPath $file)) { throw 'Provision the protected monitoring credentials first.' }
    Set-YumpooAcl $file
}
if ((Get-FileHash -LiteralPath $ExporterMsi -Algorithm SHA256).Hash -ine 'd48ebad7999aac5603fe01007016a0ddbcecbc1aa0ecbc51867a2416b6c60a7d') { throw 'windows_exporter 0.32.0 MSI checksum mismatch.' }
$metrics = Join-Path $Root 'monitoring/textfile'
New-Item -ItemType Directory -Path $metrics -Force | Out-Null
Set-YumpooAcl (Join-Path $Root 'monitoring')
$exporterConfig = Join-Path $Root 'monitoring/windows-exporter.yml'
$yaml = @"
collectors:
  enabled: cpu,memory,logical_disk,service,system,textfile
collector:
  service:
    include: '^(YumpooServer|postgresql-x64-17|sshd|windows_exporter)$'
  textfile:
    directories: ['$($metrics.Replace('\', '/'))']
log:
  level: warn
"@
[IO.File]::WriteAllText($exporterConfig, $yaml, (New-Object Text.UTF8Encoding($false)))
$msiPath = [IO.Path]::GetFullPath($ExporterMsi)
$installerApi = New-Object -ComObject WindowsInstaller.Installer
$database = $installerApi.OpenDatabase($msiPath, 0)
$view = $database.OpenView("SELECT Value FROM Property WHERE Property='ProductCode'")
$view.Execute()
$record = $view.Fetch()
$productCode = $record.StringData(1)
$view.Close()
# REMOVE bypasses this MSI's automatic maintenance branch; explicitly reinstall existing core files and service settings.
$reinstall = if ($installerApi.ProductState($productCode) -eq 5) { ' REINSTALL=DefaultFeature REINSTALLMODE=amus' } else { '' }
$arguments = '/i "' + $msiPath + '" /qn /norestart ADDLOCAL=DefaultFeature REMOVE=FirewallException LISTEN_ADDR=127.0.0.1 LISTEN_PORT=9182 CONFIG_FILE="' + $exporterConfig + '"' + $reinstall
$installer = Start-Process -FilePath msiexec.exe -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
if ($installer.ExitCode -notin @(0,3010)) { throw 'windows_exporter installation failed.' }
Start-Service -Name windows_exporter
$exporterService = Get-Service -Name windows_exporter
$exporterService.WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
$destination = Join-Path $settings.nginxRoot 'conf/yumpoo-monitor.conf'
$main = Join-Path $settings.nginxRoot 'conf/nginx.conf'
$before = [IO.File]::ReadAllText($main)
$include = 'include ' + $destination.Replace('\','/') + ';'
if ($before -notmatch '(?m)^\s*http\s*\{') { throw 'Nginx http block is not recognized; install the gateway include manually.' }
if (-not (Test-Path -LiteralPath ($main + '.before-monitoring'))) { [IO.File]::WriteAllText(($main + '.before-monitoring'), $before, (New-Object Text.UTF8Encoding($false))) }
Copy-Item -LiteralPath $GatewayConfig -Destination $destination -Force
if (-not $before.Contains($include)) {
    $after = ([regex]'(?m)^(\s*http\s*\{)').Replace($before, ('$1' + "`r`n    " + $include), 1)
    [IO.File]::WriteAllText($main, $after, (New-Object Text.UTF8Encoding($false)))
}
try { Invoke-YumpooNginx $settings -Reload } catch {
    [IO.File]::WriteAllText($main, $before, (New-Object Text.UTF8Encoding($false)))
    throw
}
Register-YumpooMonitorTasks $Root
foreach ($name in @('Tunnel','Relay','Watchdog')) {
    Stop-ScheduledTask -TaskName "YumpooMonitor$name" -ErrorAction SilentlyContinue
    Start-ScheduledTask -TaskName "YumpooMonitor$name"
}
$null = Invoke-WebRequest -Uri 'http://127.0.0.1:19100/metrics/windows' -UseBasicParsing -TimeoutSec 15
Write-Output 'Monitoring exporter, loopback gateway and supervised tasks installed.'
