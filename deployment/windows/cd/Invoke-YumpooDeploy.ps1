#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$Root = 'C:/ProgramData/Yumpoo')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force

$deployment = Join-Path $Root 'deployment'
$settings = Get-Content -LiteralPath (Join-Path $deployment 'engine/host.json') -Raw | ConvertFrom-Json
$lock = $null
try {
    try { $lock = [IO.File]::Open((Join-Path $deployment 'engine/deploy.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { exit 0 }
    foreach ($folder in @(Get-ChildItem -LiteralPath (Join-Path $deployment 'inbox') -Directory | Sort-Object CreationTimeUtc)) {
        if ($folder.Name -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { continue }
        $null = Assert-YumpooPath $folder.FullName (Join-Path $deployment 'inbox')
        $requestFile = Join-Path $folder.FullName 'request.json'
        if (-not (Test-Path -LiteralPath $requestFile)) { continue }
        $statusPath = Join-Path $deployment "status/$($folder.Name).json"
        if (Test-Path -LiteralPath $statusPath) { continue }
        $startedAt = Get-Date
        $status = [ordered]@{ schemaVersion = 1; requestId = $folder.Name; tag = ''; sourceCommit = ''; state = 'running'; phase = 'preflight'; updatedAt = '' }
        $record = @{ mode = 'deploy'; releaseRoot = $null; snapshot = $null }
        # Ledger and notification are bookkeeping; their failure must not turn a finished deployment into a failed one.
        function Complete-Request {
            try {
                Add-YumpooLedgerEntry $deployment @{
                    requestId = $status.requestId; mode = $record.mode; tag = $status.tag; sourceCommit = $status.sourceCommit
                    releaseRoot = $record.releaseRoot; snapshot = $record.snapshot; state = $status.state; phase = $status.phase; completedAt = $status.updatedAt
                }
            } catch { [IO.File]::WriteAllText((Join-Path $deployment 'engine/ledger-error.txt'), $_.Exception.Message) }
            $null = Send-YumpooNotification $Root $status ((Get-Date) - $startedAt)
        }
        function Publish-Status([string]$State, [string]$Phase, [string]$Failure = '') {
            $status.state = $State; $status.phase = $Phase; $status.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
            if ($Failure) {
                [IO.File]::WriteAllText((Join-Path $deployment "engine/failure-$($folder.Name).txt"), $Failure)
                $status.error = 'Deployment failed; inspect administrator-owned diagnostics.'
            }
            Write-YumpooJson $statusPath $status
            Write-YumpooJson (Join-Path $deployment 'engine/active.json') $status
            if ($State -ne 'running') { Complete-Request }
        }
        try {
            $activeFile = Join-Path $deployment 'engine/active.json'
            if (Test-Path -LiteralPath $activeFile) {
                $active = Get-Content -LiteralPath $activeFile -Raw | ConvertFrom-Json
                if ($active.state -in @('running', 'recovery_required')) { throw 'An interrupted deployment requires administrator recovery before another deployment.' }
            }
            $work = Join-Path $deployment "work/$($folder.Name)"
            New-Item -ItemType Directory -Path $work | Out-Null
            foreach ($name in @('request.json', 'provenance.json')) {
                $source = Assert-YumpooPath (Join-Path $folder.FullName $name) (Join-Path $deployment 'inbox')
                if ((Get-Item -LiteralPath $source).Length -gt 1MB) { throw 'Uploaded file exceeds the size limit.' }
                Copy-Item -LiteralPath $source -Destination (Join-Path $work $name)
            }
            $request = Get-Content -LiteralPath (Join-Path $work 'request.json') -Raw | ConvertFrom-Json
            $provenance = Get-Content -LiteralPath (Join-Path $work 'provenance.json') -Raw | ConvertFrom-Json
            if ($request.requestId -cne $folder.Name) { throw 'Request ID does not match the inbox directory.' }
            $record.mode = Get-YumpooRequestMode $request
            $status.tag = $request.tag; $status.sourceCommit = $request.sourceCommit
            $currentFile = Join-Path $deployment 'engine/current.json'
            $current = Get-Content -LiteralPath $currentFile -Raw | ConvertFrom-Json
            if ($record.mode -eq 'deploy') {
                $package = Copy-YumpooUpload $folder.FullName $work $request $provenance
                Assert-YumpooSource $settings $request $provenance $package $current (Join-Path $work 'provenance.json')
            } else {
                Assert-YumpooRollbackSource $settings $request $provenance $current (Join-Path $work 'provenance.json')
            }
            if ($current.sourceCommit -ceq $provenance.sourceCommit) {
                Test-YumpooSite $settings -Public
                Publish-Status 'succeeded' 'already_current'
                continue
            }
            $snapshot = Join-Path $deployment "backups/$($folder.Name)"
            $record.snapshot = $snapshot
            $identity = @{}
            $logPath = ''
            try { $logPath = Read-YumpooYamlScalar (Join-Path $Root 'config/application-prod.yml') 'logging.file.name' } catch { }
            $observation = @{ counter = { 0 }; observing = $false; passed = $false }
            $previousRelease = [ordered]@{ sourceCommit = $current.sourceCommit; tag = $current.tag; releaseRoot = $current.releaseRoot; requestId = $null }
            if ($null -ne $current.PSObject.Properties['requestId']) { $previousRelease.requestId = $current.requestId }
            # Shared post-switch steps: identical for forward deployment and code-only rollback.
            $common = @{
                Start = { Start-YumpooServer }
                Validate = { Publish-Status 'running' 'validate_local'; Test-YumpooSite $settings @identity }
                Open = { $observation.counter = New-YumpooLogErrorCounter $logPath; Set-YumpooMaintenance $settings $false }
                PublicValidate = { Publish-Status 'running' 'validate_public'; Test-YumpooSite $settings -Public @identity }
                Observe = {
                    Publish-Status 'running' 'observe'
                    $observation.observing = $true
                    $null = Watch-YumpooRelease $settings {
                        Test-YumpooSite $settings -Public
                        if ((Invoke-RestMethod -Uri 'http://127.0.0.1:8100/actuator/health/readiness' -TimeoutSec 5).status -ne 'UP') { throw 'Readiness dropped during observation.' }
                    } $observation.counter
                    $observation.passed = $true
                }
                Success = { Publish-Status 'succeeded' 'complete' }
                ValidateOld = { Test-YumpooSite $settings }
                OpenOld = { Set-YumpooMaintenance $settings $false }
                PostOpenFailure = {
                    param($failure)
                    # A failure after a passed observation is a bookkeeping failure, not a release problem.
                    $phase = if ($observation.passed) { 'completion_record_failed' } elseif ($observation.observing) { 'observation_failed' } else { 'public_verification_failed_no_data_restore' }
                    Publish-Status 'failed' $phase $failure.Exception.Message
                }
                RecoveryFailure = { param($failure) Set-YumpooMaintenance $settings $true; Publish-Status 'recovery_required' 'restore_failed_maintenance_retained' $failure.Exception.Message }
                MaintenanceOn = { Publish-Status 'running' 'maintenance'; Set-YumpooMaintenance $settings $true; Start-Sleep -Seconds 3 }
                Stop = { Stop-YumpooServer }
            }
            if ($record.mode -eq 'deploy') {
                $database = Get-YumpooDatabase $settings
                $candidate = Assert-YumpooPath (Join-Path $settings.releasesRoot "$($provenance.sourceCommit)-$($folder.Name)") $settings.releasesRoot
                $record.releaseRoot = $candidate
                $steps = $common + @{
                    Preflight = {
                        Publish-Status 'running' 'preflight'
                        Test-YumpooRestorePermissions $settings $database
                        $drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($Root).Substring(0, 1))
                        $runtimeBytes = (Get-ChildItem -LiteralPath (Join-Path $Root 'data') -File -Recurse | Measure-Object Length -Sum).Sum
                        $dbBytes = [long]((Invoke-YumpooPg $settings $database 'psql' @('-X','-At','-c','SELECT pg_database_size(current_database());') | Out-String).Trim())
                        if ($drive.Free -lt (4GB + 2 * ([long]$runtimeBytes + $dbBytes))) { throw 'Insufficient free disk space for deployment and recovery.' }
                        Expand-YumpooPackage (Join-Path $work 'package.zip') $candidate
                        Set-YumpooAcl $candidate -ReaderSid 'S-1-5-19'
                        # Packages built before release metadata existed cannot report their identity.
                        if (Read-YumpooReleaseMetadata $candidate) { $identity.ExpectedCommit = $provenance.sourceCommit; $identity.ExpectedTag = $provenance.tag }
                        Invoke-YumpooNginx $settings
                    }
                    Backup = {
                        Publish-Status 'running' 'snapshot'
                        New-Item -ItemType Directory -Path $snapshot | Out-Null
                        Backup-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
                        New-YumpooLinkedSnapshot (Join-Path $Root 'data/attachments') (Join-Path $snapshot 'data/attachments')
                        foreach ($relative in @('config', 'secrets', 'temp/uploads')) {
                            Copy-YumpooTree (Join-Path $Root $relative) (Join-Path $snapshot $relative)
                        }
                        Copy-Item -LiteralPath (Join-Path $Root 'service/yumpoo-service.xml') -Destination (Join-Path $snapshot 'service.xml')
                        Copy-Item -LiteralPath $settings.webRootInclude -Destination (Join-Path $snapshot 'web-root.conf')
                        Copy-Item -LiteralPath $currentFile -Destination (Join-Path $snapshot 'current.json')
                        Write-YumpooJson (Join-Path $snapshot 'snapshot.json') @{ schemaVersion = 1; mode = 'deploy'; requestId = $request.requestId; sourceCommit = $current.sourceCommit; candidateCommit = $provenance.sourceCommit; stoppedAt = (Get-Date).ToUniversalTime().ToString('o') }
                    }
                    Switch = { Publish-Status 'running' 'switch'; Set-YumpooRelease $settings $candidate $provenance.tag }
                    PrepareOpen = {
                        Publish-Status 'running' 'opening_traffic'
                        Write-YumpooJson $currentFile ([ordered]@{ sourceCommit = $provenance.sourceCommit; tag = $provenance.tag; releaseRoot = $candidate; requestId = $request.requestId; deployedAt = (Get-Date).ToUniversalTime().ToString('o'); previous = $previousRelease })
                    }
                    Restore = {
                        Publish-Status 'running' 'restore'
                        Restore-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
                        Test-YumpooBlobSnapshot (Join-Path $snapshot 'data/attachments')
                        foreach ($relative in @('config', 'secrets', 'data/attachments', 'temp/uploads')) {
                            Copy-YumpooTree (Join-Path $snapshot $relative) (Join-Path $Root $relative)
                        }
                        Copy-Item -LiteralPath (Join-Path $snapshot 'service.xml') -Destination (Join-Path $Root 'service/yumpoo-service.xml') -Force
                        Copy-Item -LiteralPath (Join-Path $snapshot 'web-root.conf') -Destination $settings.webRootInclude -Force
                        Copy-Item -LiteralPath (Join-Path $snapshot 'current.json') -Destination $currentFile -Force
                        Invoke-YumpooNginx $settings -Reload
                    }
                    PreOpenFailure = { param($failure, $restored) if ($restored) { Publish-Status 'rolled_back' 'previous_version_restored' $failure.Exception.Message } else { Publish-Status 'failed' 'candidate_not_started' $failure.Exception.Message } }
                }
            } else {
                $target = Assert-YumpooPath ([string]$current.previous.releaseRoot) $settings.releasesRoot
                $record.releaseRoot = $target
                $identity.ExpectedCommit = $provenance.sourceCommit; $identity.ExpectedTag = $provenance.tag
                $steps = $common + @{
                    Preflight = {
                        Publish-Status 'running' 'preflight'
                        if (-not (Test-Path -LiteralPath (Join-Path $target 'server/yumpoo-server.jar') -PathType Leaf)) { throw 'The retained rollback target is incomplete.' }
                        Assert-YumpooRollbackMigrations (Read-YumpooReleaseMetadata $current.releaseRoot) (Read-YumpooReleaseMetadata $target)
                        Invoke-YumpooNginx $settings
                    }
                    # Code-only rollback never touches the database; only version pointers need a restore point.
                    Backup = {
                        Publish-Status 'running' 'snapshot'
                        New-Item -ItemType Directory -Path $snapshot | Out-Null
                        Copy-Item -LiteralPath (Join-Path $Root 'service/yumpoo-service.xml') -Destination (Join-Path $snapshot 'service.xml')
                        Copy-Item -LiteralPath $settings.webRootInclude -Destination (Join-Path $snapshot 'web-root.conf')
                        Copy-Item -LiteralPath $currentFile -Destination (Join-Path $snapshot 'current.json')
                        Write-YumpooJson (Join-Path $snapshot 'snapshot.json') @{ schemaVersion = 1; mode = 'rollback'; requestId = $request.requestId; sourceCommit = $current.sourceCommit; targetCommit = $provenance.sourceCommit; stoppedAt = (Get-Date).ToUniversalTime().ToString('o') }
                    }
                    Switch = { Publish-Status 'running' 'switch'; Set-YumpooRelease $settings $target $provenance.tag }
                    PrepareOpen = {
                        Publish-Status 'running' 'opening_traffic'
                        Write-YumpooJson $currentFile ([ordered]@{ sourceCommit = $provenance.sourceCommit; tag = $provenance.tag; releaseRoot = $target; requestId = $request.requestId; deployedAt = (Get-Date).ToUniversalTime().ToString('o'); previous = $null; rolledBackFrom = $previousRelease })
                    }
                    Restore = {
                        Publish-Status 'running' 'restore'
                        Copy-Item -LiteralPath (Join-Path $snapshot 'service.xml') -Destination (Join-Path $Root 'service/yumpoo-service.xml') -Force
                        Copy-Item -LiteralPath (Join-Path $snapshot 'web-root.conf') -Destination $settings.webRootInclude -Force
                        Copy-Item -LiteralPath (Join-Path $snapshot 'current.json') -Destination $currentFile -Force
                        Invoke-YumpooNginx $settings -Reload
                    }
                    PreOpenFailure = { param($failure, $restored) if ($restored) { Publish-Status 'failed' 'rollback_aborted_current_restored' $failure.Exception.Message } else { Publish-Status 'failed' 'rollback_not_started' $failure.Exception.Message } }
                }
            }
            $null = Invoke-YumpooTransaction $steps
        } catch {
            [IO.File]::WriteAllText((Join-Path $deployment "engine/failure-$($folder.Name).txt"), $_.Exception.Message)
            # A rejected request must not overwrite active.json: it may be the record that blocks after an interrupted deployment.
            $status.state = 'failed'; $status.phase = 'rejected'; $status.error = 'Request rejected; inspect administrator-owned diagnostics.'; $status.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
            Write-YumpooJson $statusPath $status
            Complete-Request
        } finally {
            try { $null = Invoke-YumpooRetention $settings $deployment }
            catch { [IO.File]::WriteAllText((Join-Path $deployment 'engine/retention-error.txt'), $_.Exception.Message) }
        }
    }
} finally { if ($null -ne $lock) { $lock.Dispose() } }
