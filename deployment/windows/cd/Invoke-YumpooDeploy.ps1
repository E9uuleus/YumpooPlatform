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
        $status = [ordered]@{ schemaVersion = 1; requestId = $folder.Name; tag = ''; sourceCommit = ''; state = 'running'; phase = 'preflight'; updatedAt = '' }
        function Publish-Status([string]$State, [string]$Phase, [string]$Failure = '') {
            $status.state = $State; $status.phase = $Phase; $status.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
            if ($Failure) {
                [IO.File]::WriteAllText((Join-Path $deployment "engine/failure-$($folder.Name).txt"), $Failure)
                $status.error = 'Deployment failed; inspect administrator-owned diagnostics.'
            }
            Write-YumpooJson $statusPath $status
            Write-YumpooJson (Join-Path $deployment 'engine/active.json') $status
        }
        try {
            $activeFile = Join-Path $deployment 'engine/active.json'
            if (Test-Path -LiteralPath $activeFile) {
                $active = Get-Content -LiteralPath $activeFile -Raw | ConvertFrom-Json
                if ($active.state -in @('running', 'recovery_required')) { throw 'An interrupted deployment requires administrator recovery before another deployment.' }
            }
            $work = Join-Path $deployment "work/$($folder.Name)"
            New-Item -ItemType Directory -Path $work | Out-Null
            foreach ($name in @('request.json', 'provenance.json', 'package.zip')) {
                $source = Assert-YumpooPath (Join-Path $folder.FullName $name) (Join-Path $deployment 'inbox')
                $limit = if ($name -eq 'package.zip') { 1GB } else { 1MB }
                if ((Get-Item -LiteralPath $source).Length -gt $limit) { throw 'Uploaded file exceeds the size limit.' }
                Copy-Item -LiteralPath $source -Destination (Join-Path $work $name)
            }
            $request = Get-Content -LiteralPath (Join-Path $work 'request.json') -Raw | ConvertFrom-Json
            $provenance = Get-Content -LiteralPath (Join-Path $work 'provenance.json') -Raw | ConvertFrom-Json
            if ($request.requestId -cne $folder.Name) { throw 'Request ID does not match the inbox directory.' }
            $status.tag = $request.tag; $status.sourceCommit = $request.sourceCommit
            $currentFile = Join-Path $deployment 'engine/current.json'
            $current = Get-Content -LiteralPath $currentFile -Raw | ConvertFrom-Json
            Assert-YumpooSource $request $provenance (Join-Path $work 'package.zip') $current
            if ($current.sourceCommit -ceq $provenance.sourceCommit) {
                Test-YumpooSite $settings -Public
                Publish-Status 'succeeded' 'already_current'
                continue
            }
            $database = Get-YumpooDatabase $settings
            $snapshot = Join-Path $deployment "backups/$($folder.Name)"
            $candidate = Assert-YumpooPath (Join-Path $settings.releasesRoot "$($provenance.sourceCommit)-$($folder.Name)") $settings.releasesRoot
            $steps = @{
                Preflight = {
                    Publish-Status 'running' 'preflight'
                    Test-YumpooRestorePermissions $settings $database
                    $drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($Root).Substring(0, 1))
                    $runtimeBytes = (Get-ChildItem -LiteralPath (Join-Path $Root 'data') -File -Recurse | Measure-Object Length -Sum).Sum
                    $dbBytes = [long]((Invoke-YumpooPg $settings $database 'psql' @('-X','-At','-c','SELECT pg_database_size(current_database());') | Out-String).Trim())
                    if ($drive.Free -lt (4GB + 2 * ([long]$runtimeBytes + $dbBytes))) { throw 'Insufficient free disk space for deployment and recovery.' }
                    Expand-YumpooPackage (Join-Path $work 'package.zip') $candidate
                    Set-YumpooAcl $candidate -ReaderSid 'S-1-5-19'
                    Invoke-YumpooNginx $settings
                }
                MaintenanceOn = { Publish-Status 'running' 'maintenance'; Set-YumpooMaintenance $settings $true; Start-Sleep -Seconds 3 }
                Stop = { Stop-YumpooServer }
                Backup = {
                    Publish-Status 'running' 'snapshot'
                    New-Item -ItemType Directory -Path $snapshot | Out-Null
                    Backup-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
                    foreach ($relative in @('config', 'secrets', 'data/attachments', 'temp/uploads')) {
                        Copy-YumpooTree (Join-Path $Root $relative) (Join-Path $snapshot $relative)
                    }
                    Copy-Item -LiteralPath (Join-Path $Root 'service/yumpoo-service.xml') -Destination (Join-Path $snapshot 'service.xml')
                    Copy-Item -LiteralPath $settings.webRootInclude -Destination (Join-Path $snapshot 'web-root.conf')
                    Copy-Item -LiteralPath $currentFile -Destination (Join-Path $snapshot 'current.json')
                    Write-YumpooJson (Join-Path $snapshot 'snapshot.json') @{ schemaVersion = 1; requestId = $request.requestId; sourceCommit = $current.sourceCommit; candidateCommit = $provenance.sourceCommit; stoppedAt = (Get-Date).ToUniversalTime().ToString('o') }
                }
                Switch = { Publish-Status 'running' 'switch'; Set-YumpooRelease $settings $candidate }
                Start = { Start-YumpooServer }
                Validate = { Publish-Status 'running' 'validate_local'; Test-YumpooSite $settings }
                PrepareOpen = {
                    Publish-Status 'running' 'opening_traffic'
                    Write-YumpooJson $currentFile @{ sourceCommit = $provenance.sourceCommit; tag = $provenance.tag; releaseRoot = $candidate; requestId = $request.requestId; deployedAt = (Get-Date).ToUniversalTime().ToString('o') }
                }
                Open = { Set-YumpooMaintenance $settings $false }
                PublicValidate = { Publish-Status 'running' 'validate_public'; Test-YumpooSite $settings -Public }
                Success = { Publish-Status 'succeeded' 'complete' }
                Restore = {
                    Publish-Status 'running' 'restore'
                    Restore-YumpooDatabase $settings $database (Join-Path $snapshot 'database.dump')
                    foreach ($relative in @('config', 'secrets', 'data/attachments', 'temp/uploads')) {
                        Copy-YumpooTree (Join-Path $snapshot $relative) (Join-Path $Root $relative)
                    }
                    Copy-Item -LiteralPath (Join-Path $snapshot 'service.xml') -Destination (Join-Path $Root 'service/yumpoo-service.xml') -Force
                    Copy-Item -LiteralPath (Join-Path $snapshot 'web-root.conf') -Destination $settings.webRootInclude -Force
                    Copy-Item -LiteralPath (Join-Path $snapshot 'current.json') -Destination $currentFile -Force
                    Invoke-YumpooNginx $settings -Reload
                }
                ValidateOld = { Test-YumpooSite $settings }
                OpenOld = { Set-YumpooMaintenance $settings $false }
                PreOpenFailure = { param($failure, $restored) if ($restored) { Publish-Status 'rolled_back' 'previous_version_restored' $failure.Exception.Message } else { Publish-Status 'failed' 'candidate_not_started' $failure.Exception.Message } }
                PostOpenFailure = { param($failure) Publish-Status 'failed' 'public_verification_failed_no_data_restore' $failure.Exception.Message }
                RecoveryFailure = { param($failure) Set-YumpooMaintenance $settings $true; Publish-Status 'recovery_required' 'restore_failed_maintenance_retained' $failure.Exception.Message }
            }
            $null = Invoke-YumpooTransaction $steps
        } catch {
            [IO.File]::WriteAllText((Join-Path $deployment "engine/failure-$($folder.Name).txt"), $_.Exception.Message)
            $status.state = 'failed'; $status.phase = 'rejected'; $status.error = 'Request rejected; inspect administrator-owned diagnostics.'; $status.updatedAt = (Get-Date).ToUniversalTime().ToString('o')
            Write-YumpooJson $statusPath $status
        }
    }
} finally { if ($null -ne $lock) { $lock.Dispose() } }
