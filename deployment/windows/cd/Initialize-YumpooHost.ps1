#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseRoot,
    [Parameter(Mandatory)][string]$JavaPath,
    [Parameter(Mandatory)][string]$PublicKeyPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$CurrentCommit,
    [int]$LegacyProcessId = 0,
    [string]$Root = 'C:/ProgramData/Yumpoo',
    [string]$PgBin = 'C:/Program Files/PostgreSQL/17/bin',
    [string]$NginxRoot = 'C:/nginx',
    [switch]$CompleteRegistration
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force

function Resolve-YumpooHostPath([string]$Path, [ValidateSet('Container', 'Leaf')][string]$Kind = 'Container', [switch]$AllowMissing) {
    if ($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path -match '[\x00-\x1f"<>|?*]' -or $Path.Substring(2).Contains(':')) { throw 'Host paths must be absolute local paths without alternate streams or wildcard characters.' }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($full -eq [IO.Path]::GetPathRoot($full).TrimEnd('\', '/')) { throw 'A drive root cannot be a Yumpoo host path.' }
    for ($ancestor = $full; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Host paths cannot have reparse-point ancestors.' }
    }
    if (-not $AllowMissing -and -not (Test-Path -LiteralPath $full -PathType $Kind)) { throw "Required host path is missing or has the wrong type: $full" }
    if ((Test-Path -LiteralPath $full) -and -not (Test-Path -LiteralPath $full -PathType $Kind)) { throw "Host path has the wrong type: $full" }
    return $full
}

function Assert-YumpooRegistrationState($RuntimeRoot, $ExpectedRelease, $ExpectedJava, $ExpectedCommit, $ExpectedNginx, $ExpectedPg) {
    $engine = Join-Path $RuntimeRoot 'deployment/engine'
    $wrapper = Join-Path $RuntimeRoot 'service/yumpoo-service.exe'
    $service = Get-CimInstance Win32_Service -Filter "Name='YumpooServer'"
    if (-not $service -or $service.StartName -ine 'NT AUTHORITY\LocalService' -or $service.State -ne 'Running' -or
        $service.PathName.Trim().Trim('"') -ine $wrapper) { throw 'Registration requires the running Yumpoo WinSW service under LocalService.' }
    if (Test-Path -LiteralPath (Join-Path $RuntimeRoot 'deployment/maintenance.flag')) { throw 'Maintenance is still enabled; perform administrator recovery before task registration.' }
    foreach ($file in @('host.json', 'current.json', 'YumpooDeployment.psm1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1')) {
        $null = Resolve-YumpooHostPath (Join-Path $engine $file) 'Leaf'
    }
    $null = Resolve-YumpooHostPath $wrapper 'Leaf'
    if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ine '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da') { throw 'The installed service wrapper is not the pinned WinSW binary.' }
    $xmlPath = Resolve-YumpooHostPath (Join-Path $RuntimeRoot 'service/yumpoo-service.xml') 'Leaf'
    [xml]$serviceXml = [IO.File]::ReadAllText($xmlPath)
    if ($serviceXml.service.id -cne 'YumpooServer' -or $serviceXml.service.serviceaccount.domain -ine 'NT AUTHORITY' -or
        $serviceXml.service.serviceaccount.user -ine 'LocalService' -or $serviceXml.service.executable -ine $ExpectedJava -or
        $serviceXml.service.workingdirectory -ine $ExpectedRelease -or
        $serviceXml.service.arguments -cne ('-Dfile.encoding=UTF-8 -jar "' + (Join-Path $ExpectedRelease 'server/yumpoo-server.jar') + '"')) { throw 'The installed service configuration does not match the supplied baseline.' }
    $current = Get-Content -LiteralPath (Join-Path $engine 'current.json') -Raw | ConvertFrom-Json
    if ($current.sourceCommit -cne $ExpectedCommit -or $current.releaseRoot -ine $ExpectedRelease) { throw 'The current deployment baseline differs from the requested registration.' }
    if (Test-Path -LiteralPath (Join-Path $engine 'active.json')) {
        $active = Get-Content -LiteralPath (Join-Path $engine 'active.json') -Raw | ConvertFrom-Json
        if ($active.state -in @('running', 'recovery_required')) { throw 'An unfinished deployment requires administrator recovery before registration.' }
    }
    $settings = Get-Content -LiteralPath (Join-Path $engine 'host.json') -Raw | ConvertFrom-Json
    if ($settings.runtimeRoot -ine $RuntimeRoot -or $settings.javaPath -ine $ExpectedJava -or $settings.nginxRoot -ine $ExpectedNginx -or
        $settings.pgBin -ine $ExpectedPg -or $settings.releasesRoot -ine (Split-Path -Parent $ExpectedRelease) -or
        $settings.hostname -cne 'wecom-dev.yumpoo.com' -or $settings.webRootInclude -ine (Join-Path $ExpectedNginx 'conf/yumpoo-web-root.conf')) { throw 'The installed host settings do not match the supplied baseline.' }
    $null = Resolve-YumpooHostPath $settings.webRootInclude 'Leaf'
    $owners = @(Get-NetTCPConnection -LocalPort 8100 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -ne 1) { throw 'The Yumpoo listener is missing or ambiguous.' }
    $child = Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
    if (-not $child -or $child.ParentProcessId -ne $service.ProcessId -or $child.ExecutablePath -ine $ExpectedJava -or
        $child.CommandLine.Replace('/', '\') -notlike ('*' + (Join-Path $ExpectedRelease 'server/yumpoo-server.jar') + '*')) { throw 'Port 8100 does not belong to the expected WinSW Java child.' }
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8100/actuator/health/readiness' -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'The current service is not ready for task registration.' }
    Test-YumpooSite $settings -Public
    return $settings
}

$Root = Resolve-YumpooHostPath $Root
$ReleaseRoot = Resolve-YumpooHostPath $ReleaseRoot
$JavaPath = Resolve-YumpooHostPath $JavaPath 'Leaf'
$PublicKeyPath = Resolve-YumpooHostPath $PublicKeyPath 'Leaf'
$PgBin = Resolve-YumpooHostPath $PgBin
$NginxRoot = Resolve-YumpooHostPath $NginxRoot
$deployment = Join-Path $Root 'deployment'
$serviceRoot = Join-Path $Root 'service'
$releasesRoot = Split-Path -Parent $ReleaseRoot
$jar = Join-Path $ReleaseRoot 'server/yumpoo-server.jar'
foreach ($file in @($jar, $JavaPath, $PublicKeyPath, (Join-Path $PgBin 'pg_dump.exe'), (Join-Path $Root 'config/application-prod.yml'), (Join-Path $Root 'secrets/application-secrets.yml'))) {
    $null = Resolve-YumpooHostPath $file 'Leaf'
}
if (Get-Service -Name YumpooServer -ErrorAction SilentlyContinue) {
    if (-not $CompleteRegistration) { throw 'YumpooServer already exists. Use -CompleteRegistration only after the previous service is healthy and maintenance is disabled.' }
    $settings = Assert-YumpooRegistrationState $Root $ReleaseRoot $JavaPath $CurrentCommit $NginxRoot $PgBin
    $deployUser = Get-LocalUser -Name 'yumpoo-deploy'
    $adminGroup = Get-LocalGroup -SID 'S-1-5-32-544'
    if (-not $deployUser.Enabled -or @(Get-LocalGroupMember -Group $adminGroup | Where-Object { $_.SID.Value -eq $deployUser.Sid.Value }).Count) { throw 'The deployment identity must be an enabled non-administrator.' }
    Set-YumpooAcl $Root -ReaderSid 'S-1-5-19' -TopLevelOnly
    Set-YumpooAcl $deployment -ReaderSid $deployUser.Sid.Value -TopLevelOnly
    foreach ($relative in @('engine', 'work', 'backups')) { Set-YumpooAcl (Join-Path $deployment $relative) }
    Set-YumpooAcl (Join-Path $deployment 'inbox') -WriterSid $deployUser.Sid.Value
    Set-YumpooAcl (Join-Path $deployment 'status') -ReaderSid $deployUser.Sid.Value
    foreach ($file in @('YumpooDeployment.psm1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1')) {
        if ((Get-FileHash -LiteralPath (Join-Path $deployment "engine/$file")).Hash -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash) { throw 'Installed engine differs from this reviewed package; reconcile it as an administrator before registration.' }
    }
    Register-YumpooTasks $settings $deployUser.Sid.Value
    Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'registered'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
    Write-Output 'Existing healthy Yumpoo service task registration completed; runtime configuration was not changed.'
    exit 0
}
if ($CompleteRegistration) { throw 'No YumpooServer service exists; run the verified first takeover without -CompleteRegistration.' }
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$LegacyProcessId"
if (-not $process -or $process.Name -ine 'java.exe' -or $process.ExecutablePath -ine $JavaPath.Replace('/', '\') -or
    $process.CommandLine.Replace('/', '\') -notlike ('*' + $jar.Replace('/', '\') + '*')) { throw 'Legacy process does not match the exact Yumpoo JAR and Java executable.' }
$owners = @(Get-NetTCPConnection -LocalPort 8100 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
if ($owners.Count -ne 1 -or $owners[0] -ne $LegacyProcessId) { throw 'Port 8100 ownership does not match the supplied Yumpoo process.' }

foreach ($relative in @('deployment', 'deployment/engine', 'deployment/inbox', 'deployment/status', 'deployment/work', 'deployment/backups', 'service', 'logs', 'data/attachments', 'temp/uploads')) {
    $directory = Resolve-YumpooHostPath (Join-Path $Root $relative) -AllowMissing
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
if (-not (Get-LocalUser -Name 'yumpoo-deploy' -ErrorAction SilentlyContinue)) {
    $random = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($random); $rng.Dispose()
    $password = ConvertTo-SecureString (([Convert]::ToBase64String($random)) + 'aA!7') -AsPlainText -Force
    $null = New-LocalUser -Name 'yumpoo-deploy' -Password $password -AccountNeverExpires -PasswordNeverExpires -UserMayNotChangePassword -Description 'Yumpoo Release deployment'
}
$deploySid = (Get-LocalUser -Name 'yumpoo-deploy').Sid.Value
$adminGroup = Get-LocalGroup -SID 'S-1-5-32-544'
if (@(Get-LocalGroupMember -Group $adminGroup | Where-Object { $_.SID.Value -eq $deploySid }).Count) { throw 'Deployment identity must not be an administrator.' }
Set-YumpooAcl $Root -ReaderSid 'S-1-5-19' -TopLevelOnly
Set-YumpooAcl $deployment -ReaderSid $deploySid -TopLevelOnly
foreach ($relative in @('engine', 'work', 'backups')) { Set-YumpooAcl (Join-Path $deployment $relative) }
Set-YumpooAcl (Join-Path $deployment 'inbox') -WriterSid $deploySid
Set-YumpooAcl (Join-Path $deployment 'status') -ReaderSid $deploySid
Set-YumpooAcl $serviceRoot -ReaderSid 'S-1-5-19'
foreach ($relative in @('config', 'secrets')) { Set-YumpooAcl (Join-Path $Root $relative) -ReaderSid 'S-1-5-19' }
foreach ($relative in @('logs', 'data/attachments', 'temp/uploads')) { Set-YumpooAcl (Join-Path $Root $relative) -WriterSid 'S-1-5-19' }
Set-YumpooAcl $ReleaseRoot -ReaderSid 'S-1-5-19'

$sshDirectory = Resolve-YumpooHostPath (Join-Path $deployment 'ssh') -AllowMissing
New-Item -ItemType Directory -Path $sshDirectory -Force | Out-Null
Set-YumpooAcl $sshDirectory -ReaderSid $deploySid
$publicKey = [IO.File]::ReadAllText($PublicKeyPath).Trim()
if ($publicKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+(?: .*)?$') { throw 'Expected an Ed25519 deployment public key.' }
[IO.File]::WriteAllText((Join-Path $sshDirectory 'authorized_keys'), $publicKey + "`n", (New-Object Text.UTF8Encoding($false)))
$sshd = Resolve-YumpooHostPath 'C:/ProgramData/ssh/sshd_config' 'Leaf'
$sshdText = [IO.File]::ReadAllText($sshd)
if ($sshdText -notmatch '(?m)^Match User yumpoo-deploy\s*$') {
    Copy-Item -LiteralPath $sshd -Destination (Join-Path $deployment 'engine/sshd_config.before')
    $keyLocation = (Join-Path $sshDirectory 'authorized_keys').Replace('\','/')
    Add-Content -LiteralPath $sshd -Encoding ASCII -Value "`nMatch User yumpoo-deploy`n    AuthorizedKeysFile `"$keyLocation`"`n    PasswordAuthentication no`n    AllowTcpForwarding no`n    PermitTTY no`n"
    & 'C:/Windows/System32/OpenSSH/sshd.exe' -t
    if ($LASTEXITCODE -ne 0) { Copy-Item -LiteralPath (Join-Path $deployment 'engine/sshd_config.before') -Destination $sshd -Force; throw 'SSHD configuration rejected.' }
    Restart-Service sshd
}

foreach ($file in @('YumpooDeployment.psm1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $deployment "engine/$file") -Force
}
$settings = [pscustomobject]@{
    schemaVersion = 1; runtimeRoot = $Root; releasesRoot = $releasesRoot; javaPath = $JavaPath; pgBin = $PgBin; nginxRoot = $NginxRoot
    hostname = 'wecom-dev.yumpoo.com'; webRootInclude = (Join-Path $NginxRoot 'conf/yumpoo-web-root.conf')
    diagnostics = (Join-Path $deployment 'engine/database-error.txt')
}
Write-YumpooJson (Join-Path $deployment 'engine/host.json') $settings
$database = Get-YumpooDatabase $settings
Test-YumpooRestorePermissions $settings $database
$sitePath = Resolve-YumpooHostPath (Join-Path $NginxRoot 'conf/conf.d/yumpoo-wecom.conf') 'Leaf'
$null = Resolve-YumpooHostPath $settings.webRootInclude 'Leaf' -AllowMissing
$siteBefore = [IO.File]::ReadAllText($sitePath)
$expectedRoot = 'root "' + $ReleaseRoot.Replace('\', '/') + '/web";'
$managedInclude = 'include "' + $settings.webRootInclude.Replace('\','/') + '";'
$alreadyManaged = $siteBefore.Contains($managedInclude) -and $siteBefore.Contains('$yumpoo_maintenance')
if ($alreadyManaged) {
    if ([IO.File]::ReadAllText($settings.webRootInclude).Trim() -cne $expectedRoot) { throw 'The managed site root does not match the current release.' }
    Invoke-YumpooNginx $settings
} else {
if (-not $siteBefore.Contains($expectedRoot)) { throw 'The site root does not match the current release; explicit reconciliation is required.' }
Copy-Item -LiteralPath $sitePath -Destination (Join-Path $deployment 'engine/nginx-site.before.conf')
[IO.File]::WriteAllText($settings.webRootInclude, $expectedRoot + "`n", (New-Object Text.UTF8Encoding($false)))
$siteAfter = $siteBefore.Replace($expectedRoot, $managedInclude)
$maintenance = @'
    set $yumpoo_maintenance 0;
    if (-f "C:/ProgramData/Yumpoo/deployment/maintenance.flag") { set $yumpoo_maintenance 1; }
    if ($remote_addr = 127.0.0.1) { set $yumpoo_maintenance 0; }
    if ($yumpoo_maintenance = 1) { return 503; }
'@
$maintenance = $maintenance.Replace('C:/ProgramData/Yumpoo', $Root.Replace('\','/'))
$needle = '(?m)(listen 443 ssl;\s*server_name wecom-dev\.yumpoo\.com;)'
if ([regex]::Matches($siteAfter, $needle).Count -ne 1) { throw 'Expected exactly one wecom-dev HTTPS server block.' }
$siteAfter = [regex]::Replace($siteAfter, $needle, [Text.RegularExpressions.MatchEvaluator]{param($m) $m.Value + "`n" + $maintenance})
try {
    [IO.File]::WriteAllText($sitePath, $siteAfter, (New-Object Text.UTF8Encoding($false)))
    Invoke-YumpooNginx $settings -Reload
} catch { [IO.File]::WriteAllText($sitePath, $siteBefore, (New-Object Text.UTF8Encoding($false))); throw }
}

$wrapper = Join-Path $serviceRoot 'yumpoo-service.exe'
if (-not (Test-Path -LiteralPath $wrapper)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe' -OutFile $wrapper -TimeoutSec 180
}
if ((Get-FileHash -LiteralPath $wrapper).Hash -ine '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da') { throw 'WinSW checksum mismatch.' }
[xml]$xml = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../service/yumpoo-service.xml'))
$xml.service.executable = $JavaPath
$xml.service.arguments = '-Dfile.encoding=UTF-8 -jar "' + $jar + '"'
$xml.service.workingdirectory = $ReleaseRoot
$xml.SelectSingleNode('/service/logpath').InnerText = [string](Join-Path $Root 'logs')
$releaseEnvironment = $xml.CreateElement('env'); $releaseEnvironment.SetAttribute('name', 'YUMPOO_DEPLOYMENT_RELEASE_ROOT'); $releaseEnvironment.SetAttribute('value', $ReleaseRoot)
$null = $xml.DocumentElement.AppendChild($releaseEnvironment)
$xml.SelectSingleNode('/service/env[@name="SPRING_CONFIG_ADDITIONAL_LOCATION"]').SetAttribute('value', ('file:' + $Root.Replace('\','/') + '/config/,file:' + $Root.Replace('\','/') + '/secrets/application-secrets.yml'))
$xml.Save((Join-Path $serviceRoot 'yumpoo-service.xml'))
$config = Join-Path $Root 'config/application-prod.yml'
$configBefore = [IO.File]::ReadAllText($config)
Copy-Item -LiteralPath $config -Destination (Join-Path $deployment 'engine/application-prod.before.yml')
if ($configBefore -match '(?m)^logging:\s*$' -and $configBefore -notmatch '(?m)^  console:') {
    $configAfter = [regex]::Replace($configBefore, '(?m)^logging:\s*$', "logging:`n  console:`n    enabled: false")
} elseif ($configBefore -match '(?m)^logging:\s*\r?\n  console:\s*\r?\n    enabled: (?:true|false)\s*$') {
    $configAfter = [regex]::Replace($configBefore, '(?m)(^logging:\s*\r?\n  console:\s*\r?\n    enabled:) (?:true|false)', '$1 false')
} else { throw 'Unrecognized logging.console configuration; reconcile it before takeover.' }

$snapshot = Join-Path $deployment ('backups/bootstrap-' + (Get-Date -Format 'yyyyMMddHHmmss'))
New-Item -ItemType Directory -Path $snapshot | Out-Null
Write-YumpooJson (Join-Path $deployment 'engine/current.json') @{ sourceCommit = $CurrentCommit; releaseRoot = $ReleaseRoot; tag = 'bootstrap'; deployedAt = (Get-Date).ToUniversalTime().ToString('o') }
Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'prepared'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
& $wrapper install
if ($LASTEXITCODE -ne 0) { throw 'WinSW service installation failed; the legacy process remains running.' }
Set-YumpooMaintenance $settings $true
$bootstrapSnapshotReady = $false
$bootstrapOpened = $false
try {
    # Only the PID whose executable, command line and listening port were verified above is stopped.
    Stop-Process -Id $LegacyProcessId -ErrorAction Stop
    $stopDeadline = (Get-Date).AddSeconds(60)
    do {
        $legacyAlive = Get-Process -Id $LegacyProcessId -ErrorAction SilentlyContinue
        $portBusy = Get-NetTCPConnection -LocalPort 8100 -State Listen -ErrorAction SilentlyContinue
        if (-not $legacyAlive -and -not $portBusy) { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $stopDeadline)
    if ($legacyAlive -or $portBusy) { throw 'Legacy Yumpoo process did not stop.' }
    Backup-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
    foreach ($relative in @('config', 'secrets', 'data/attachments', 'temp/uploads')) { Copy-YumpooTree (Join-Path $Root $relative) (Join-Path $snapshot $relative) }
    $bootstrapSnapshotReady = $true
    [IO.File]::WriteAllText($config, $configAfter, (New-Object Text.UTF8Encoding($false)))
    Set-Service -Name postgresql-x64-17 -StartupType Automatic
    Start-YumpooServer
    Test-YumpooSite $settings
    Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'registration_pending'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
    $bootstrapOpened = $true; Set-YumpooMaintenance $settings $false
} catch {
    $bootstrapFailure = $_
    if (-not $bootstrapOpened) {
        try {
            Stop-YumpooServer
            if ($bootstrapSnapshotReady) {
                Restore-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
                foreach ($relative in @('config', 'secrets', 'data/attachments', 'temp/uploads')) { Copy-YumpooTree (Join-Path $snapshot $relative) (Join-Path $Root $relative) }
            } else { [IO.File]::WriteAllText($config, $configBefore, (New-Object Text.UTF8Encoding($false))) }
            Start-YumpooServer; Test-YumpooSite $settings; Set-YumpooMaintenance $settings $false
            Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'registration_pending'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
            Write-Warning 'The previous version is healthy. Correct the takeover failure, then use -CompleteRegistration to register its fixed tasks.'
        } catch {
            Set-YumpooMaintenance $settings $true
            Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'recovery_required'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
            Write-Warning 'Bootstrap recovery failed. Maintenance remains enabled; inspect the protected snapshot and service logs.'
        }
    }
    throw $bootstrapFailure
}

Test-YumpooSite $settings -Public
Register-YumpooTasks $settings $deploySid
Write-YumpooJson (Join-Path $deployment 'engine/bootstrap.json') @{ schemaVersion = 1; state = 'registered'; sourceCommit = $CurrentCommit; updatedAt = (Get-Date).ToUniversalTime().ToString('o') }
Write-Output 'Yumpoo service takeover and fixed deployment task initialized.'
