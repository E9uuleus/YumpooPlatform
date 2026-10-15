#Requires -Version 5.1
[CmdletBinding()]
param([string]$PgBin = '')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'YumpooDeployment.psm1') -Force
function Assert-Test($Condition, $Message) { if (-not $Condition) { throw "Deployment test: $Message" } }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yumpoo-deploy-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    foreach ($scenario in @('success', 'backup', 'switch', 'start', 'validation', 'restore', 'prepare-open', 'open', 'public')) {
        $script:events = New-Object Collections.Generic.List[string]
        $script:startCount = 0
        $script:scenario = $scenario
        $steps = @{
            Preflight = { $script:events.Add('preflight') }
            MaintenanceOn = { $script:events.Add('maintenance') }
            Stop = { $script:events.Add('stop') }
            Backup = { $script:events.Add('backup'); if ($script:scenario -eq 'backup') { throw 'backup fault' } }
            Switch = { $script:events.Add('switch'); if ($script:scenario -eq 'switch') { throw 'migration fault' } }
            Start = { $script:startCount++; $script:events.Add('start'); if ($script:scenario -eq 'start' -and $script:startCount -eq 1) { throw 'start fault' } }
            Validate = { $script:events.Add('validate'); if ($script:scenario -in @('validation','restore')) { throw 'validation fault' } }
            PrepareOpen = { if ($script:scenario -eq 'prepare-open') { throw 'version metadata write fault' } }
            Open = { $script:events.Add('open'); if ($script:scenario -eq 'open') { throw 'opening fault' } }
            PublicValidate = { $script:events.Add('public'); if ($script:scenario -eq 'public') { throw 'public fault' } }
            Success = { $script:events.Add('success') }
            Restore = { $script:events.Add('restore'); if ($script:scenario -eq 'restore') { throw 'restore fault' } }
            ValidateOld = { $script:events.Add('validate-old') }
            OpenOld = { $script:events.Add('open-old') }
            PreOpenFailure = { $script:events.Add('preopen-failure') }
            PostOpenFailure = { $script:events.Add('postopen-failure') }
            RecoveryFailure = { $script:events.Add('maintenance-retained') }
        }
        $result = Invoke-YumpooTransaction $steps
        if ($scenario -eq 'success') { Assert-Test ($result -eq 'succeeded') 'successful transaction failed' }
        elseif ($scenario -eq 'backup') {
            Assert-Test ($result -eq 'failed' -and 'switch' -notin $events -and 'restore' -notin $events -and 'open-old' -in $events) 'backup failure must restart old service without database restore'
        } elseif ($scenario -eq 'public') {
            Assert-Test ($result -eq 'failed' -and 'restore' -notin $events -and 'postopen-failure' -in $events) 'post-open failure must preserve new data'
        } elseif ($scenario -in @('restore', 'open')) {
            Assert-Test ($result -eq 'recovery_required' -and 'open-old' -notin $events -and 'maintenance-retained' -in $events) 'failed restore must retain maintenance'
        } else {
            Assert-Test ($result -eq 'rolled_back' -and 'restore' -in $events -and 'validate-old' -in $events -and 'open-old' -in $events) 'candidate failure must restore and verify the previous version'
        }
    }
    $yaml = Join-Path $testRoot 'config.yml'
    [IO.File]::WriteAllText($yaml, "spring:`n  flyway:`n    password: 'private''value#1'`n    url: jdbc:postgresql://127.0.0.1:5433/yumpoo`n")
    Assert-Test ((Read-YumpooYamlScalar $yaml 'spring.flyway.password') -ceq "private'value#1") 'quoted YAML secret parsing'
    $escaped = $false
    try { $null = Assert-YumpooPath (Join-Path $testRoot '../outside') $testRoot } catch { $escaped = $true }
    Assert-Test $escaped 'path traversal must be rejected'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = Join-Path $testRoot 'bad.zip'
    $zip = [IO.Compression.ZipFile]::Open($archive, 'Create')
    $null = $zip.CreateEntry('../escape.txt'); $zip.Dispose()
    $rejected = $false
    try { Expand-YumpooPackage $archive (Join-Path $testRoot 'expanded') } catch { $rejected = $true }
    Assert-Test $rejected 'zip traversal must be rejected'
    $junctionSource = Join-Path $testRoot 'junction-source'; $junctionDestination = Join-Path $testRoot 'junction-destination'; $junctionOutside = Join-Path $testRoot 'junction-outside'
    foreach ($directory in @($junctionSource, $junctionDestination, $junctionOutside)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    $junction = Join-Path $junctionDestination 'unsafe'
    New-Item -ItemType Junction -Path $junction -Target $junctionOutside | Out-Null
    $rejected = $false
    try { Copy-YumpooTree $junctionSource $junctionDestination } catch { $rejected = $true }
    [IO.Directory]::Delete($junction)
    Assert-Test $rejected 'snapshot destination junction must be rejected'

    if ($PgBin) {
        $pgData = Join-Path $testRoot 'postgres'
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
        $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
        & (Join-Path $PgBin 'initdb.exe') -D $pgData -U postgres --auth=trust --locale=C --encoding=UTF8 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize isolated PostgreSQL test cluster.' }
        $pgStart = Start-Process -FilePath (Join-Path $PgBin 'pg_ctl.exe') -ArgumentList ('-D "' + $pgData + '" -l "' + (Join-Path $testRoot 'pg.log') + '" -o "-h 127.0.0.1 -p ' + $port + '" -w start') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $testRoot 'pg-start.out') -RedirectStandardError (Join-Path $testRoot 'pg-start.err')
        $null = $pgStart.Handle
        if (-not $pgStart.WaitForExit(60000)) { throw 'Isolated PostgreSQL startup timed out.' }
        $pgStart.Refresh()
        if ($pgStart.ExitCode -ne 0) { throw ('Cannot start isolated PostgreSQL test cluster: ' + [IO.File]::ReadAllText((Join-Path $testRoot 'pg-start.err'))) }
        try {
            $settings = @{ pgBin = $PgBin; diagnostics = (Join-Path $testRoot 'database-error.txt') }
            $db = @{ Host = '127.0.0.1'; Port = [string]$port; User = 'postgres'; Database = 'postgres'; Password = '' }
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c','CREATE ROLE yumpoo_migrator LOGIN; CREATE ROLE yumpoo_app LOGIN;') | Out-Null
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c','CREATE DATABASE yumpoo OWNER yumpoo_migrator;') | Out-Null
            $db.Database = 'yumpoo'; $db.User = 'yumpoo_migrator'
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c', "CREATE SCHEMA yumpoo AUTHORIZATION yumpoo_migrator; CREATE TABLE yumpoo.item(id int PRIMARY KEY, body text); INSERT INTO yumpoo.item VALUES(1,'before'); GRANT USAGE ON SCHEMA yumpoo TO yumpoo_app; GRANT SELECT ON yumpoo.item TO yumpoo_app;") | Out-Null
            Test-YumpooRestorePermissions $settings $db
            $dump = Join-Path $testRoot 'snapshot.dump'
            Backup-YumpooDatabase $settings $db $dump
            $attachments = Join-Path $testRoot 'attachments'; New-Item -ItemType Directory -Path $attachments | Out-Null
            [IO.File]::WriteAllText((Join-Path $attachments 'before.txt'), 'before')
            Copy-YumpooTree $attachments (Join-Path $testRoot 'attachment-snapshot')
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c', "UPDATE yumpoo.item SET body='after'; CREATE TABLE yumpoo.candidate_only(id int); ALTER TABLE yumpoo.item ADD COLUMN candidate int;") | Out-Null
            [IO.File]::WriteAllText((Join-Path $attachments 'before.txt'), 'after')
            [IO.File]::WriteAllText((Join-Path $attachments 'candidate.txt'), 'candidate')
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c','CREATE VIEW public.external_dependency AS SELECT * FROM yumpoo.item;') | Out-Null
            $rejected = $false
            try { Restore-YumpooDatabase $settings $db $dump } catch { $rejected = $true }
            Assert-Test $rejected 'cross-schema dependencies must block destructive restore'
            $actual = (Invoke-YumpooPg $settings $db 'psql' @('-X','-At','-c','SELECT body FROM public.external_dependency;') | Out-String).Trim()
            Assert-Test ($actual -eq 'after') 'rejected restore must leave external objects and application data intact'
            Invoke-YumpooPg $settings $db 'psql' @('-X','-v','ON_ERROR_STOP=1','-c','DROP VIEW public.external_dependency;') | Out-Null
            Restore-YumpooDatabase $settings $db $dump
            Copy-YumpooTree (Join-Path $testRoot 'attachment-snapshot') $attachments
            $actual = (Invoke-YumpooPg $settings $db 'psql' @('-X','-At','-c',"SELECT body || ':' || (to_regclass('yumpoo.candidate_only') IS NULL)::text FROM yumpoo.item;") | Out-String).Trim()
            Assert-Test ($actual -eq 'before:true') 'restore must remove candidate objects and restore original rows'
            Assert-Test (([IO.File]::ReadAllText((Join-Path $attachments 'before.txt'))) -eq 'before' -and -not (Test-Path -LiteralPath (Join-Path $attachments 'candidate.txt'))) 'attachments must restore with the database'
            $db.User = 'yumpoo_app'
            $actual = (Invoke-YumpooPg $settings $db 'psql' @('-X','-At','-c','SELECT body FROM yumpoo.item;') | Out-String).Trim()
            Assert-Test ($actual -eq 'before') 'application grants must survive restore'
            Write-Output 'Isolated PostgreSQL schema and attachment restore passed.'
        } finally {
            & (Join-Path $PgBin 'pg_ctl.exe') -D $pgData -m immediate -w stop | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL cluster failed to stop.' }
        }
    }
    Write-Output 'Deployment transaction, failure boundaries, YAML and archive checks passed.'
} catch { Write-Output ('TEST FAILURE: ' + $_.Exception.Message + "`n" + $_.ScriptStackTrace); if (Test-Path -LiteralPath (Join-Path $testRoot 'database-error.txt')) { Get-Content -LiteralPath (Join-Path $testRoot 'database-error.txt') }; throw } finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -like 'yumpoo-deploy-test-*') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Continue
    }
}
