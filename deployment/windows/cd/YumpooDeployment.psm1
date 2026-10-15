#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-YumpooJson($Path, $Value) {
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Assert-YumpooPath($Path, $Parent) {
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Path escaped its managed directory.' }
    $item = $full
    while ($item.Length -ge $prefix.Length) {
        if ((Test-Path -LiteralPath $item) -and ((Get-Item -LiteralPath $item -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Reparse points are forbidden in deployment paths.'
        }
        $item = [IO.Path]::GetDirectoryName($item)
    }
    return $full
}

function Set-YumpooAcl($Path, [string]$ReaderSid = '', [string]$WriterSid = '', [switch]$TopLevelOnly) {
    if ($Path -notmatch '^[A-Za-z]:[\\/]') { throw 'ACL targets must be absolute local paths.' }
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.TrimEnd('\', '/') -eq [IO.Path]::GetPathRoot($full).TrimEnd('\', '/')) { throw 'A drive root cannot be an ACL target.' }
    for ($ancestor = $full; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'ACL targets cannot have reparse-point ancestors.' }
    }
    $entries = New-Object Collections.Generic.List[IO.FileSystemInfo]
    $pending = New-Object Collections.Generic.Queue[IO.FileSystemInfo]
    $pending.Enqueue((Get-Item -LiteralPath $full -Force))
    while ($pending.Count) {
        $item = $pending.Dequeue()
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'ACL targets cannot contain reparse points.' }
        $entries.Add($item)
        if (-not $TopLevelOnly -and $item -is [IO.DirectoryInfo]) {
            foreach ($child in @(Get-ChildItem -LiteralPath $item.FullName -Force)) { $pending.Enqueue($child) }
        }
    }
    $owner = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')
    foreach ($item in $entries) {
        if ($item -is [IO.DirectoryInfo]) { $acl = New-Object Security.AccessControl.DirectorySecurity }
        else { $acl = New-Object Security.AccessControl.FileSecurity }
        $acl.SetAccessRuleProtection($true, $false)
        $acl.SetOwner($owner)
        foreach ($entry in @(@('S-1-5-18', 'FullControl'), @('S-1-5-32-544', 'FullControl'), @($ReaderSid, 'ReadAndExecute'), @($WriterSid, 'Modify'))) {
            if ($entry[0]) {
                $sid = New-Object Security.Principal.SecurityIdentifier($entry[0])
                if ($item -is [IO.DirectoryInfo]) {
                    $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, $entry[1], 'ContainerInherit,ObjectInherit', 'None', 'Allow')
                } else { $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, $entry[1], 'Allow') }
                $acl.AddAccessRule($rule)
            }
        }
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
    }
}

function Register-YumpooTasks($Settings, [string]$DeploySid) {
    $sid = New-Object Security.Principal.SecurityIdentifier($DeploySid)
    $deployment = Join-Path $Settings.runtimeRoot 'deployment'
    foreach ($file in @('Invoke-YumpooDeploy.ps1', 'YumpooDeployment.psm1', 'Start-YumpooNginx.ps1', 'host.json', 'current.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $deployment "engine/$file") -PathType Leaf)) { throw 'The administrator-owned deployment engine is incomplete.' }
    }
    $powershell = 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
    $principal = New-ScheduledTaskPrincipal -UserId SYSTEM -LogonType ServiceAccount -RunLevel Highest
    $deploySettings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1) -StartWhenAvailable
    $deployAction = New-ScheduledTaskAction -Execute $powershell -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $deployment 'engine/Invoke-YumpooDeploy.ps1') + '" -Root "' + $Settings.runtimeRoot + '"')
    $triggers = @((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)))
    Register-ScheduledTask -TaskName YumpooDeploy -Action $deployAction -Principal $principal -Settings $deploySettings -Trigger $triggers -Force | Out-Null
    $scheduler = New-Object -ComObject 'Schedule.Service'; $scheduler.Connect()
    $scheduler.GetFolder('\').GetTask('YumpooDeploy').SetSecurityDescriptor("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGX;;;$($sid.Value))", 0)
    $nginxAction = New-ScheduledTaskAction -Execute $powershell -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $deployment 'engine/Start-YumpooNginx.ps1') + '" -NginxRoot "' + $Settings.nginxRoot + '"')
    $nginxSettings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -StartWhenAvailable
    Register-ScheduledTask -TaskName YumpooNginxStartup -Action $nginxAction -Principal $principal -Settings $nginxSettings -Trigger (New-ScheduledTaskTrigger -AtStartup) -Force | Out-Null
}

function Copy-YumpooTree($Source, $Destination) {
    foreach ($tree in @($Source, $Destination)) {
        $full = [IO.Path]::GetFullPath($tree)
        if ($full -eq [IO.Path]::GetPathRoot($full)) { throw 'A drive root cannot be a snapshot directory.' }
        $null = Assert-YumpooPath $full ([IO.Path]::GetDirectoryName($full))
        if (Test-Path -LiteralPath $full) {
            if (@(Get-ChildItem -LiteralPath $full -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Snapshot trees cannot contain reparse points.' }
        }
    }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $items = @(Get-ChildItem -LiteralPath $Source -Recurse -Force)
    if (@($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Snapshot cannot include reparse points.' }
    & robocopy.exe $Source $Destination /MIR /XJ /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'Snapshot file copy failed.' }
}

function Read-YumpooYamlScalar($Path, [string]$Key) {
    $parents = New-Object Collections.Generic.List[string]
    $indents = New-Object Collections.Generic.List[int]
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^(\s*)([A-Za-z][A-Za-z0-9-]*):(?:\s+(.*))?$') {
            $indent = $Matches[1].Length
            $name = $Matches[2]
            $value = $Matches[3]
            while ($indents.Count -and $indents[$indents.Count - 1] -ge $indent) {
                $parents.RemoveAt($parents.Count - 1); $indents.RemoveAt($indents.Count - 1)
            }
            $parents.Add($name); $indents.Add($indent)
            if (($parents -join '.') -eq $Key) {
                $value = $value.Trim()
                if ($value.StartsWith("'")) { return $value.Substring(1, $value.Length - 2).Replace("''", "'") }
                if ($value.StartsWith('"')) { return ($value | ConvertFrom-Json) }
                if ($value -match '\s+#|^[!&*|>]|\$\{') { throw "Unsupported YAML scalar at $Key; use a quoted literal." }
                return $value
            }
        }
    }
    throw "Missing configuration key: $Key"
}

function Get-YumpooDatabase($Settings) {
    $config = Join-Path $Settings.runtimeRoot 'config/application-prod.yml'
    $url = Read-YumpooYamlScalar $config 'spring.flyway.url'
    if ($url -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):(\d+)/(yumpoo)(?:\?.*)?$') { throw 'Database must be the local Yumpoo database.' }
    $connection = @{ Host = $Matches[1]; Port = $Matches[2]; Database = $Matches[3] }
    $connection.User = Read-YumpooYamlScalar $config 'spring.flyway.user'
    $connection.Password = Read-YumpooYamlScalar (Join-Path $Settings.runtimeRoot 'secrets/application-secrets.yml') 'spring.flyway.password'
    return $connection
}

function Invoke-YumpooPg($Settings, $Database, $Tool, [string[]]$Arguments) {
    $previous = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $Database.Password
        $nativePreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $result = & (Join-Path $Settings.pgBin "$Tool.exe") -h $Database.Host -p $Database.Port -U $Database.User -d $Database.Database -w @Arguments 2>&1
        $ErrorActionPreference = $nativePreference
        if ($LASTEXITCODE -ne 0) {
            if ($Settings.diagnostics) { [IO.File]::WriteAllText($Settings.diagnostics, ($result | Out-String)) }
            throw "PostgreSQL $Tool failed (exit $LASTEXITCODE). Inspect the protected database diagnostics."
        }
        return $result
    } finally { $env:PGPASSWORD = $previous }
}

function Get-YumpooBoundarySql {
    return @'
DO $boundary$
BEGIN
  IF EXISTS (
    WITH RECURSIVE dependent(classid, objid, objsubid) AS (
      SELECT 'pg_namespace'::regclass::oid, oid, 0 FROM pg_namespace WHERE nspname = 'yumpoo'
      UNION
      SELECT d.classid, d.objid, d.objsubid FROM pg_depend d JOIN dependent x
        ON d.refclassid = x.classid AND d.refobjid = x.objid AND (x.objsubid = 0 OR x.objsubid = d.refobjsubid)
    )
    SELECT 1 FROM dependent d CROSS JOIN LATERAL pg_identify_object(d.classid, d.objid, d.objsubid) i
      LEFT JOIN pg_rewrite r ON d.classid = 'pg_rewrite'::regclass AND r.oid = d.objid
      LEFT JOIN pg_trigger t ON d.classid = 'pg_trigger'::regclass AND t.oid = d.objid
      LEFT JOIN pg_constraint c ON d.classid = 'pg_constraint'::regclass AND c.oid = d.objid
      LEFT JOIN pg_attrdef a ON d.classid = 'pg_attrdef'::regclass AND a.oid = d.objid
      LEFT JOIN pg_policy p ON d.classid = 'pg_policy'::regclass AND p.oid = d.objid
      LEFT JOIN pg_class owner_relation ON owner_relation.oid = COALESCE(r.ev_class, t.tgrelid, c.conrelid, a.adrelid, p.polrelid)
      LEFT JOIN pg_namespace owner_schema ON owner_schema.oid = owner_relation.relnamespace
      WHERE COALESCE(i.schema, owner_schema.nspname) IS NOT NULL
        AND COALESCE(i.schema, owner_schema.nspname) NOT IN ('yumpoo', 'pg_toast')
  ) THEN
    RAISE EXCEPTION 'Cross-schema dependencies forbid automatic Yumpoo schema restoration';
  END IF;
END $boundary$;
'@
}

function Test-YumpooRestorePermissions($Settings, $Database) {
    $query = "SELECT (pg_get_userbyid(nspowner)=current_user)::text FROM pg_namespace WHERE nspname='yumpoo';"
    $owner = (Invoke-YumpooPg $Settings $Database 'psql' @('-X', '-At', '-v', 'ON_ERROR_STOP=1', '-c', $query) | Out-String).Trim()
    if ($owner -ne 'true') { throw 'Migration account must own the complete yumpoo schema.' }
    Invoke-YumpooPg $Settings $Database 'psql' @('-X', '-v', 'ON_ERROR_STOP=1', '-c', (Get-YumpooBoundarySql)) | Out-Null
    # The rolled-back probe verifies DDL authority without touching application objects.
    $probe = 'yumpoo_restore_probe_' + [guid]::NewGuid().ToString('N')
    Invoke-YumpooPg $Settings $Database 'psql' @('-X', '-v', 'ON_ERROR_STOP=1', '-c', "BEGIN; CREATE SCHEMA $probe; CREATE TABLE $probe.checkpoint(id int); DROP SCHEMA $probe CASCADE; ROLLBACK;") | Out-Null
}

function Backup-YumpooDatabase($Settings, $Database, $Destination) {
    Invoke-YumpooPg $Settings $Database 'pg_dump' @('--format=custom', '--schema=yumpoo', '--file', $Destination) | Out-Null
    if ((Get-Item -LiteralPath $Destination).Length -lt 100) { throw 'Database snapshot is empty.' }
    & (Join-Path $Settings.pgBin 'pg_restore.exe') --list $Destination | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Database snapshot cannot be read.' }
}

function Restore-YumpooDatabase($Settings, $Database, $Snapshot) {
    $sql = "$Snapshot.restore.sql"
    $drop = "$Snapshot.drop.sql"
    try {
        & (Join-Path $Settings.pgBin 'pg_restore.exe') --no-owner --file $sql $Snapshot
        if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare the full schema restore.' }
        [IO.File]::WriteAllText($drop, ((Get-YumpooBoundarySql) + "`nDROP SCHEMA IF EXISTS yumpoo CASCADE;"), (New-Object Text.UTF8Encoding($false)))
        Invoke-YumpooPg $Settings $Database 'psql' @('-X', '--single-transaction', '-v', 'ON_ERROR_STOP=1', '-f', $drop, '-f', $sql) | Out-Null
    } finally {
        foreach ($file in @($sql, $drop)) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file } }
    }
}

function Expand-YumpooPackage($Archive, $Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $seen = @{}; [long]$total = 0
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ($name -match '(^/|:|(^|/)\.\.?(/|$))' -or $seen.ContainsKey($name.ToLowerInvariant())) { throw 'Unsafe or duplicate archive entry.' }
            $seen[$name.ToLowerInvariant()] = $true
            if (($entry.ExternalAttributes -band 0xF0000000) -eq 0xA0000000) { throw 'Archive links are forbidden.' }
            $total += $entry.Length
            if ($total -gt 4GB -or $zip.Entries.Count -gt 50000) { throw 'Archive exceeds deployment limits.' }
            $null = Assert-YumpooPath (Join-Path $Destination $name) $Destination
        }
        if (-not $seen.ContainsKey('server/yumpoo-server.jar') -or -not $seen.ContainsKey('web/index.html')) { throw 'Server package is incomplete.' }
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            # Bundled administrative scripts are retained for review, never executed by the engine.
            if ($name.StartsWith('server/') -or $name.StartsWith('web/') -or $name.StartsWith('windows/')) {
                $target = Assert-YumpooPath (Join-Path $Destination $name) $Destination
                if ($name.EndsWith('/')) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
                else {
                    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false)
                }
            }
        }
    } finally { $zip.Dispose() }
}

function Get-YumpooGitHub($Path) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-RestMethod -Uri "https://api.github.com/repos/E9uuleus/YumpooPlatform/$Path" -Headers @{ 'User-Agent' = 'YumpooDeploy'; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' } -TimeoutSec 30
}

function Assert-YumpooSource($Request, $Provenance, $Package, $Current) {
    if ($Request.schemaVersion -ne 1 -or $Request.requestId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'Invalid deployment request.' }
    if ($Provenance.schemaVersion -ne 1 -or $Provenance.repository -cne 'E9uuleus/YumpooPlatform' -or
        $Provenance.tag -notmatch '^server-v[A-Za-z0-9][A-Za-z0-9._-]{0,95}$' -or $Provenance.sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $Provenance.packageSha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid provenance.' }
    foreach ($field in @('tag', 'sourceCommit', 'packageSha256')) {
        if ($Request.$field -cne $Provenance.$field) { throw "Request provenance mismatch: $field" }
    }
    if ((Get-FileHash -LiteralPath $Package -Algorithm SHA256).Hash -ine $Provenance.packageSha256 -or
        (Get-Item -LiteralPath $Package).Length -ne $Provenance.packageBytes) { throw 'Package checksum or size mismatch.' }
    $release = Get-YumpooGitHub "releases/tags/$($Provenance.tag)"
    if ($release.draft -or -not $release.published_at) { throw 'Release is not published.' }
    $asset = @($release.assets | Where-Object name -eq 'provenance.json')
    $packageAsset = @($release.assets | Where-Object name -eq 'yumpoo-windows-server-m1-15.zip')
    if ($asset.Count -ne 1 -or $packageAsset.Count -ne 1) { throw 'Release source assets are missing or ambiguous.' }
    $trusted = Invoke-RestMethod -Uri $asset[0].browser_download_url -TimeoutSec 30
    if ($trusted -is [string]) { $trusted = $trusted | ConvertFrom-Json }
    foreach ($field in @('schemaVersion', 'repository', 'tag', 'sourceCommit', 'ciRunId', 'ciRunAttempt', 'artifactName', 'packageSha256', 'packageBytes')) {
        if ([string]$Provenance.$field -cne [string]$trusted.$field) { throw "Release provenance mismatch: $field" }
    }
    if ($packageAsset[0].digest -cne "sha256:$($Provenance.packageSha256)") { throw 'GitHub Release package digest mismatch.' }
    $tagCommit = Get-YumpooGitHub "commits/$($Provenance.tag)"
    if ($tagCommit.sha -cne $Provenance.sourceCommit) { throw 'Release tag moved.' }
    $dev = Get-YumpooGitHub "compare/$($Provenance.sourceCommit)...dev"
    if ($dev.status -notin @('ahead', 'identical')) { throw 'Release is not a dev ancestor.' }
    $run = Get-YumpooGitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)"
    if ($run.head_sha -cne $Provenance.sourceCommit -or $run.head_branch -cne 'dev' -or $run.event -cne 'push' -or
        $run.conclusion -cne 'success' -or $run.path -cne '.github/workflows/m0-18-ci.yml') { throw 'CI provenance is not a successful dev push.' }
    $jobs = Get-YumpooGitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)/jobs?per_page=100"
    foreach ($name in @('M0 Portable Gate', 'Windows Delivery', 'M0 Windows x64 Gate')) {
        $matched = @($jobs.jobs | Where-Object name -eq $name)
        if ($matched.Count -ne 1 -or $matched[0].conclusion -ne 'success') { throw "Required CI job failed: $name" }
    }
    if ($Provenance.artifactName -cne "m1-15-server-$($Provenance.sourceCommit)-$($Provenance.ciRunAttempt)") { throw 'CI artifact name does not match attempt.' }
    if ($Current -and $Current.sourceCommit -cne $Provenance.sourceCommit) {
        $forward = Get-YumpooGitHub "compare/$($Current.sourceCommit)...$($Provenance.sourceCommit)"
        if ($forward.status -cne 'ahead') { throw 'Automatic downgrade or divergent deployment is forbidden.' }
    }
}

function Set-YumpooMaintenance($Settings, [bool]$Enabled) {
    $flag = Join-Path $Settings.runtimeRoot 'deployment/maintenance.flag'
    if ($Enabled) { [IO.File]::WriteAllText($flag, 'maintenance') }
    elseif (Test-Path -LiteralPath $flag) { Remove-Item -LiteralPath $flag }
}

function Invoke-YumpooNginx($Settings, [switch]$Reload) {
    Push-Location $Settings.nginxRoot
    try {
        $nativePreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & (Join-Path $Settings.nginxRoot 'nginx.exe') -p ($Settings.nginxRoot.Replace('\', '/') + '/') -t 2>&1 | Out-String | Write-Verbose
        $ErrorActionPreference = $nativePreference
        if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration validation failed.' }
        if ($Reload) {
            $ErrorActionPreference = 'Continue'
            & (Join-Path $Settings.nginxRoot 'nginx.exe') -p ($Settings.nginxRoot.Replace('\', '/') + '/') -s reload 2>&1 | Out-String | Write-Verbose
            $ErrorActionPreference = $nativePreference
            if ($LASTEXITCODE -ne 0) { throw 'Nginx reload failed.' }
        }
    } finally { Pop-Location }
}

function Set-YumpooRelease($Settings, $ReleaseRoot) {
    [xml]$xml = [IO.File]::ReadAllText((Join-Path $Settings.runtimeRoot 'service/yumpoo-service.xml'))
    $xml.service.arguments = '-Dfile.encoding=UTF-8 -jar "' + (Join-Path $ReleaseRoot 'server/yumpoo-server.jar') + '"'
    $xml.service.workingdirectory = $ReleaseRoot
    $releaseEnvironment = $xml.SelectSingleNode('/service/env[@name="YUMPOO_DEPLOYMENT_RELEASE_ROOT"]')
    if (-not $releaseEnvironment) { $releaseEnvironment = $xml.CreateElement('env'); $releaseEnvironment.SetAttribute('name', 'YUMPOO_DEPLOYMENT_RELEASE_ROOT'); $null = $xml.DocumentElement.AppendChild($releaseEnvironment) }
    $releaseEnvironment.SetAttribute('value', $ReleaseRoot)
    $xml.Save((Join-Path $Settings.runtimeRoot 'service/yumpoo-service.xml'))
    [IO.File]::WriteAllText($Settings.webRootInclude, ('root "' + $ReleaseRoot.Replace('\', '/') + '/web";' + "`n"), (New-Object Text.UTF8Encoding($false)))
    Invoke-YumpooNginx $Settings -Reload
}

function Stop-YumpooServer {
    $service = Get-Service -Name YumpooServer
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name YumpooServer
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
    }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $listener = Get-NetTCPConnection -LocalPort 8100 -State Listen -ErrorAction SilentlyContinue
        if (-not $listener) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw 'Yumpoo port remains occupied after stopping the service.'
}

function Start-YumpooServer {
    Start-Service -Name YumpooServer
    $deadline = (Get-Date).AddSeconds(150)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8100/actuator/health/readiness' -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Yumpoo readiness timed out.'
}

function Test-YumpooSite($Settings, [switch]$Public) {
    $resolve = @()
    if (-not $Public) { $resolve = @('--resolve', "$($Settings.hostname):443:127.0.0.1") }
    $base = "https://$($Settings.hostname)"
    $index = & curl.exe --silent --show-error --fail --max-time 15 @resolve "$base/"
    if ($LASTEXITCODE -ne 0 -or ($index -join '') -notmatch '<div id="app"') { throw 'HTTPS SPA verification failed.' }
    $assets = [regex]::Matches(($index -join "`n"), '(?:src|href)="(/assets/[^"?]+\.(?:js|css))"')
    if ($assets.Count -lt 2) { throw 'SPA assets are missing.' }
    foreach ($asset in $assets) {
        & curl.exe --silent --show-error --fail --max-time 15 -o NUL @resolve ($base + $asset.Groups[1].Value)
        if ($LASTEXITCODE -ne 0) { throw 'HTTPS static asset verification failed.' }
    }
    for ($i = 0; $i -lt 5; $i++) {
        $status = & curl.exe --silent --show-error --max-time 10 -o NUL -w '%{http_code}' @resolve "$base/api/v1/auth/me"
        if ($LASTEXITCODE -ne 0 -or $status -ne '401') { throw 'Authentication request did not complete with 401.' }
    }
}

function Invoke-YumpooTransaction([hashtable]$Steps) {
    $transactionMaintenance = $false; $transactionStopped = $false; $transactionSnapshot = $false; $transactionCandidate = $false; $transactionOpened = $false; $transactionOpenConfirmed = $false
    try {
        & $Steps.Preflight
        $transactionMaintenance = $true; & $Steps.MaintenanceOn
        $transactionStopped = $true; & $Steps.Stop
        & $Steps.Backup; $transactionSnapshot = $true
        $transactionCandidate = $true; & $Steps.Switch
        & $Steps.Start
        & $Steps.Validate
        if ($Steps.ContainsKey('PrepareOpen')) { & $Steps.PrepareOpen }
        # After this boundary, an ambiguous network failure must never rewind business data.
        $transactionOpened = $true; & $Steps.Open
        $transactionOpenConfirmed = $true
        & $Steps.PublicValidate
        & $Steps.Success
        return 'succeeded'
    } catch {
        $failure = $_
        if ($transactionOpened) {
            if (-not $transactionOpenConfirmed) { & $Steps.RecoveryFailure $failure; return 'recovery_required' }
            & $Steps.PostOpenFailure $failure; return 'failed'
        }
        try {
            if ($transactionCandidate -and $transactionSnapshot) { & $Steps.Stop; & $Steps.Restore }
            if ($transactionStopped) { & $Steps.Start; & $Steps.ValidateOld }
            if ($transactionMaintenance) { & $Steps.OpenOld }
            & $Steps.PreOpenFailure $failure $transactionCandidate
            if ($transactionCandidate) { return 'rolled_back' }
            return 'failed'
        } catch { & $Steps.RecoveryFailure $_; return 'recovery_required' }
    }
}

Export-ModuleMember -Function *-Yumpoo*
