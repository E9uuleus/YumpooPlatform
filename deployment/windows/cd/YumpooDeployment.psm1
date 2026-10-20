#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ReleaseTagPattern = '^server-v[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[1-9][0-9]{0,2}$'
$script:DesktopVersionPattern = '(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})'
$script:DesktopTagPattern = '^desktop-v(' + $script:DesktopVersionPattern + ')$'
$script:BlobPattern = '^sha256\\[0-9a-f]{2}\\[0-9a-f]{2}\\([0-9a-f]{64})$'
if (-not ('Yumpoo.NativeLink' -as [type])) {
    Add-Type -Namespace Yumpoo -Name NativeLink -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true, CharSet = System.Runtime.InteropServices.CharSet.Unicode)] public static extern bool CreateHardLink(string lpFileName, string lpExistingFileName, System.IntPtr lpSecurityAttributes);'
}

# host.json schema 1 has none of the newer keys; every reader supplies the backward-compatible default.
function Get-YumpooSetting($Settings, [string]$Name, $Default) {
    $value = $Settings
    foreach ($segment in $Name.Split('.')) {
        if ($null -eq $value) { return $Default }
        if ($value -is [Collections.IDictionary]) {
            if (-not $value.Contains($segment)) { return $Default }
            $value = $value[$segment]
        } else {
            $property = $value.PSObject.Properties[$segment]
            if ($null -eq $property) { return $Default }
            $value = $property.Value
        }
    }
    if ($null -eq $value) { return $Default }
    return $value
}

# Status files are read over SFTP while the task runs; a reader's handle can briefly block replacement.
# Replace atomically and retry within a bound instead of failing the deployment on a transient lock.
function Write-YumpooJson($Path, $Value, [int]$Attempts = 60, [int]$DelayMilliseconds = 500) {
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    try {
        for ($attempt = 1; ; $attempt++) {
            try {
                if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temporary, $Path, [NullString]::Value, $true) }
                else { [IO.File]::Move($temporary, $Path) }
                return
            } catch [IO.IOException], [UnauthorizedAccessException] {
                if ($attempt -ge $Attempts) { throw "Cannot replace $([IO.Path]::GetFileName($Path)) after $Attempts attempts: $($_.Exception.Message)" }
                Start-Sleep -Milliseconds $DelayMilliseconds
            }
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
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

function Copy-YumpooUpload($SourceDirectory, $WorkDirectory, $Request, $Provenance) {
    $bytes = $Provenance.packageBytes
    if (($bytes -isnot [int] -and $bytes -isnot [long]) -or $bytes -lt 1 -or $bytes -gt 1GB) { throw 'Invalid uploaded package size.' }
    $partProperty = $Request.PSObject.Properties['packagePartCount']
    $count = 1
    if ($null -ne $partProperty) {
        $count = $partProperty.Value
        if (($count -isnot [int] -and $count -isnot [long]) -or $count -lt 1 -or $count -gt 512 -or $count -ne [Math]::Ceiling($bytes / [double]2MB)) {
            throw 'Invalid uploaded package part count.'
        }
    }
    $package = Assert-YumpooPath (Join-Path $WorkDirectory 'package.zip') $WorkDirectory
    $output = [IO.File]::Open($package, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        for ($index = 0; $index -lt $count; $index++) {
            $name = if ($null -ne $partProperty) { 'package-{0:D4}.part' -f $index } else { 'package.zip' }
            $expected = if ($null -ne $partProperty) { [Math]::Min(2MB, $bytes - [long]$index * 2MB) } else { $bytes }
            $source = Assert-YumpooPath (Join-Path $SourceDirectory $name) $SourceDirectory
            $inputFile = [IO.File]::Open($source, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            try {
                if ($inputFile.Length -ne $expected) { throw 'Uploaded package part is incomplete or oversized.' }
                $inputFile.CopyTo($output)
            } finally { $inputFile.Dispose() }
        }
        if ($output.Length -ne $bytes) { throw 'Assembled package size does not match provenance.' }
    } finally { $output.Dispose() }
    return $package
}

function Get-YumpooGitHub($Path) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-RestMethod -Uri "https://api.github.com/repos/E9uuleus/YumpooPlatform/$Path" -Headers @{ 'User-Agent' = 'YumpooDeploy'; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' } -TimeoutSec 30
}

function Read-YumpooReleaseProvenance($Asset, $Path) {
    if ($Asset.digest -notmatch '^sha256:[0-9a-f]{64}$' -or
        $Asset.digest -cne ('sha256:' + (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()) -or
        $Asset.size -ne (Get-Item -LiteralPath $Path).Length) { throw 'GitHub Release provenance digest or size mismatch.' }
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

function Get-YumpooRequestMode($Request) {
    if ($Request.schemaVersion -notin @(1, 2) -or $Request.requestId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'Invalid deployment request.' }
    # schemaVersion 1 predates rollback and is always a forward deployment.
    if ($Request.schemaVersion -eq 1) { return 'deploy' }
    $mode = $Request.PSObject.Properties['mode']
    if ($null -eq $mode -or $mode.Value -cnotin @('deploy', 'rollback', 'desktop-publish')) { throw 'Invalid deployment request.' }
    return $mode.Value
}

function Assert-YumpooProvenanceFields($Request, $Provenance) {
    $null = Get-YumpooRequestMode $Request
    if ($Provenance.schemaVersion -ne 1 -or $Provenance.repository -cne 'E9uuleus/YumpooPlatform' -or
        $Provenance.tag -cnotmatch $script:ReleaseTagPattern -or $Provenance.sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $Provenance.packageSha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid provenance.' }
    foreach ($field in @('tag', 'sourceCommit')) {
        if ($Request.$field -cne $Provenance.$field) { throw "Request provenance mismatch: $field" }
    }
}

function Assert-YumpooSource($Settings, $Request, $Provenance, $Package, $Current, $ProvenancePath) {
    Assert-YumpooProvenanceFields $Request $Provenance
    if ($Request.packageSha256 -cne $Provenance.packageSha256) { throw 'Request provenance mismatch: packageSha256' }
    if ((Get-FileHash -LiteralPath $Package -Algorithm SHA256).Hash -ine $Provenance.packageSha256 -or
        (Get-Item -LiteralPath $Package).Length -ne $Provenance.packageBytes) { throw 'Package checksum or size mismatch.' }
    Assert-YumpooTrustedRelease $Settings $Provenance $ProvenancePath
    if ($Current -and $Current.sourceCommit -cne $Provenance.sourceCommit) {
        $forward = Get-YumpooGitHub "compare/$($Current.sourceCommit)...$($Provenance.sourceCommit)?per_page=1&page=2"
        if ($forward.status -cne 'ahead') { throw 'Automatic downgrade or divergent deployment is forbidden.' }
    }
}

function Assert-YumpooRollbackSource($Settings, $Request, $Provenance, $Current, $ProvenancePath) {
    Assert-YumpooProvenanceFields $Request $Provenance
    $previous = $Current.PSObject.Properties['previous']
    if ($null -eq $previous -or $null -eq $previous.Value) { throw 'No retained previous release is available for rollback.' }
    if ($previous.Value.sourceCommit -cne $Provenance.sourceCommit -or $previous.Value.tag -cne $Provenance.tag) { throw 'Rollback is limited to the immediately previous release.' }
    Assert-YumpooTrustedRelease $Settings $Provenance $ProvenancePath
    $behind = Get-YumpooGitHub "compare/$($Provenance.sourceCommit)...$($Current.sourceCommit)?per_page=1&page=2"
    if ($behind.status -cne 'ahead') { throw 'The rollback target is not an ancestor of the current release.' }
}

function Assert-YumpooTrustedRelease($Settings, $Provenance, $ProvenancePath) {
    $branch = [string](Get-YumpooSetting $Settings 'releaseBranch' 'dev')
    if ($branch -cnotmatch '^[a-z][a-z0-9/_-]{0,63}$') { throw 'Invalid release branch setting.' }
    $release = Get-YumpooGitHub "releases/tags/$($Provenance.tag)"
    if ($release.draft -or -not $release.published_at) { throw 'Release is not published.' }
    $immutable = $release.PSObject.Properties['immutable']
    if ((Get-YumpooSetting $Settings 'requireImmutable' $false) -eq $true -and ($null -eq $immutable -or $immutable.Value -ne $true)) { throw 'Release is not immutable.' }
    $asset = @($release.assets | Where-Object name -eq 'provenance.json')
    $packageAsset = @($release.assets | Where-Object name -eq 'yumpoo-windows-server-m1-15.zip')
    if ($asset.Count -ne 1 -or $packageAsset.Count -ne 1) { throw 'Release source assets are missing or ambiguous.' }
    $trusted = Read-YumpooReleaseProvenance $asset[0] $ProvenancePath
    foreach ($field in @('schemaVersion', 'repository', 'tag', 'sourceCommit', 'ciRunId', 'ciRunAttempt', 'artifactName', 'packageSha256', 'packageBytes')) {
        if ([string]$Provenance.$field -cne [string]$trusted.$field) { throw "Release provenance mismatch: $field" }
    }
    if ($packageAsset[0].digest -cne "sha256:$($Provenance.packageSha256)") { throw 'GitHub Release package digest mismatch.' }
    $tagCommit = Get-YumpooGitHub "commits/$($Provenance.tag)"
    if ($tagCommit.sha -cne $Provenance.sourceCommit) { throw 'Release tag moved.' }
    # Only ancestry status is needed; page 2 omits the potentially large changed-file list.
    $line = Get-YumpooGitHub "compare/$($Provenance.sourceCommit)...$($branch)?per_page=1&page=2"
    if ($line.status -notin @('ahead', 'identical')) { throw "Release is not a $branch ancestor." }
    $run = Get-YumpooGitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)"
    if ($run.head_sha -cne $Provenance.sourceCommit -or $run.head_branch -cne $branch -or $run.event -cne 'push' -or
        $run.conclusion -cne 'success' -or $run.path -cne '.github/workflows/m0-18-ci.yml') { throw "CI provenance is not a successful $branch push." }
    $jobs = Get-YumpooGitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)/jobs?per_page=100"
    foreach ($name in @('M0 Portable Gate', 'Windows Delivery', 'M0 Windows x64 Gate')) {
        $matched = @($jobs.jobs | Where-Object name -eq $name)
        if ($matched.Count -ne 1 -or $matched[0].conclusion -ne 'success') { throw "Required CI job failed: $name" }
    }
    if ($Provenance.artifactName -cne "m1-15-server-$($Provenance.sourceCommit)-$($Provenance.ciRunAttempt)") { throw 'CI artifact name does not match attempt.' }
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

function Set-YumpooServiceRelease($ServiceXml, $ReleaseRoot, [string]$Tag) {
    [xml]$xml = [IO.File]::ReadAllText($ServiceXml)
    $xml.service.arguments = '-Dfile.encoding=UTF-8 -jar "' + (Join-Path $ReleaseRoot 'server/yumpoo-server.jar') + '"'
    $xml.SelectSingleNode('/service/workingdirectory').InnerText = [string]$ReleaseRoot
    foreach ($entry in @(@('YUMPOO_DEPLOYMENT_RELEASE_ROOT', [string]$ReleaseRoot), @('YUMPOO_DEPLOYMENT_RELEASE_TAG', $Tag))) {
        $environment = $xml.SelectSingleNode("/service/env[@name=`"$($entry[0])`"]")
        if (-not $environment) { $environment = $xml.CreateElement('env'); $environment.SetAttribute('name', $entry[0]); $null = $xml.DocumentElement.AppendChild($environment) }
        $environment.SetAttribute('value', $entry[1])
    }
    $xml.Save($ServiceXml)
}

function Get-YumpooWebRootInclude($ReleaseRoot, [switch]$ProbeRoutes) {
    $lines = @('root "' + ([string]$ReleaseRoot).Replace('\', '/') + '/web";')
    # Probe routes are written only after an administrator confirms the include sits where location blocks are valid.
    if ($ProbeRoutes) {
        foreach ($probe in @(@('/healthz', '/actuator/health/readiness'), @('/version.json', '/actuator/info'))) {
            $lines += 'location = ' + $probe[0] + ' { proxy_pass http://127.0.0.1:8100' + $probe[1] + '; proxy_http_version 1.1; proxy_set_header Connection ""; proxy_set_header Host $host; proxy_connect_timeout 5s; proxy_read_timeout 10s; }'
        }
    }
    return ($lines -join "`n") + "`n"
}

function Set-YumpooRelease($Settings, $ReleaseRoot, [string]$Tag) {
    Set-YumpooServiceRelease (Join-Path $Settings.runtimeRoot 'service/yumpoo-service.xml') $ReleaseRoot $Tag
    $include = Get-YumpooWebRootInclude $ReleaseRoot -ProbeRoutes:((Get-YumpooSetting $Settings 'probeRoutes' $false) -eq $true)
    [IO.File]::WriteAllText($Settings.webRootInclude, $include, (New-Object Text.UTF8Encoding($false)))
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

function Assert-YumpooReleaseIdentity([string]$IndexHtml, $Info, [string]$Commit, [string]$Tag) {
    $meta = [regex]::Matches($IndexHtml, '<meta name="yumpoo-commit" content="([^"]*)"')
    if ($meta.Count -ne 1 -or $meta[0].Groups[1].Value -cne $Commit) { throw 'Served web assets do not belong to the released commit.' }
    $release = $Info.PSObject.Properties['release']; $build = $Info.PSObject.Properties['build']
    if ($null -eq $release -or $null -eq $build -or [string]$build.Value.commit -cne $Commit) { throw 'Running server does not report the released commit.' }
    if ([string]$release.Value.tag -cne $Tag) { throw 'Running server does not report the released tag.' }
}

function Test-YumpooSite($Settings, [switch]$Public, [string]$ExpectedCommit = '', [string]$ExpectedTag = '') {
    $resolve = @()
    if (-not $Public) { $resolve = @('--resolve', "$($Settings.hostname):443:127.0.0.1") }
    $base = "https://$($Settings.hostname)"
    $index = & curl.exe --silent --show-error --fail --max-time 15 @resolve "$base/"
    if ($LASTEXITCODE -ne 0 -or ($index -join '') -notmatch '<div id="app"') { throw 'HTTPS SPA verification failed.' }
    if ($ExpectedCommit) {
        $info = Invoke-RestMethod -Uri 'http://127.0.0.1:8100/actuator/info' -TimeoutSec 5
        Assert-YumpooReleaseIdentity ($index -join "`n") $info $ExpectedCommit $ExpectedTag
        if ((Get-YumpooSetting $Settings 'probeRoutes' $false) -eq $true) {
            $published = (& curl.exe --silent --show-error --fail --max-time 10 @resolve "$base/version.json") -join ''
            if ($LASTEXITCODE -ne 0) { throw 'Public version probe failed.' }
            Assert-YumpooReleaseIdentity ($index -join "`n") ($published | ConvertFrom-Json) $ExpectedCommit $ExpectedTag
        }
    }
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
        if ($Steps.ContainsKey('Observe')) { & $Steps.Observe }
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

function Read-YumpooLedger($Deployment) {
    $file = Join-Path $Deployment 'engine/releases.json'
    if (-not (Test-Path -LiteralPath $file)) { return @() }
    return @((Get-Content -LiteralPath $file -Raw | ConvertFrom-Json).entries)
}

function Add-YumpooLedgerEntry($Deployment, $Entry) {
    $entries = @(Read-YumpooLedger $Deployment) + @([pscustomobject]$Entry)
    if ($entries.Count -gt 200) { $entries = $entries[($entries.Count - 200)..($entries.Count - 1)] }
    Write-YumpooJson (Join-Path $Deployment 'engine/releases.json') ([ordered]@{ schemaVersion = 1; entries = $entries })
}

function Remove-YumpooManagedTree($Path, $Parent) {
    $full = Assert-YumpooPath $Path $Parent
    if (-not (Test-Path -LiteralPath $full)) { return }
    if ((Get-Item -LiteralPath $full -Force) -is [IO.DirectoryInfo]) {
        # Windows PowerShell 5.1 Remove-Item can follow junctions; refuse any tree that contains one.
        if (@(Get-ChildItem -LiteralPath $full -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
            throw "Retention refuses to delete a tree containing reparse points: $full"
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    } else { Remove-Item -LiteralPath $full -Force }
}

function Get-YumpooProtectedReleases($Settings, $Deployment, $Current) {
    $releases = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $snapshots = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $normalize = { param($path) [IO.Path]::GetFullPath([string]$path).TrimEnd('\', '/') }
    $null = $releases.Add((& $normalize $Current.releaseRoot))
    $requestId = $Current.PSObject.Properties['requestId']
    if ($null -ne $requestId -and $requestId.Value) { $null = $snapshots.Add([string]$requestId.Value) }
    $previous = $Current.PSObject.Properties['previous']
    if ($null -ne $previous -and $null -ne $previous.Value -and $previous.Value.releaseRoot) {
        $null = $releases.Add((& $normalize $previous.Value.releaseRoot))
        $previousRequest = $previous.Value.PSObject.Properties['requestId']
        if ($null -ne $previousRequest -and $previousRequest.Value) { $null = $snapshots.Add([string]$previousRequest.Value) }
    }
    $extra = [int](Get-YumpooSetting $Settings 'retention.extraReleases' 2)
    $ledger = @(Read-YumpooLedger $Deployment | Where-Object { $_.releaseRoot })
    $succeeded = @($ledger | Where-Object { $_.state -eq 'succeeded' } | Sort-Object completedAt -Descending)
    $added = 0
    foreach ($entry in $succeeded) {
        if ($added -ge $extra) { break }
        if ($releases.Add((& $normalize $entry.releaseRoot))) { $added++ }
    }
    # A snapshot is kept exactly as long as the release its deployment produced, whatever that deployment's
    # final state was: an observation_failed release stays online and can be current or previous.
    foreach ($entry in $ledger) {
        if ($entry.mode -eq 'deploy' -and $releases.Contains((& $normalize $entry.releaseRoot))) { $null = $snapshots.Add([string]$entry.requestId) }
    }
    return @{ releases = $releases; snapshots = $snapshots }
}

# Keeps the current release, the immediate rollback target and N more successful releases with their snapshots.
function Invoke-YumpooRetention($Settings, $Deployment) {
    $activeFile = Join-Path $Deployment 'engine/active.json'
    if ((Test-Path -LiteralPath $activeFile) -and (Get-Content -LiteralPath $activeFile -Raw | ConvertFrom-Json).state -in @('running', 'recovery_required')) { return @() }
    $uuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    $cutoff = (Get-Date).ToUniversalTime().AddHours(-24)
    $removed = New-Object Collections.Generic.List[string]
    $failures = New-Object Collections.Generic.List[string]
    # One undeletable item must not stop the rest of the cleanup.
    $remove = {
        param($Path, $Parent, $Label)
        try { Remove-YumpooManagedTree $Path $Parent; $removed.Add($Label) }
        catch { $failures.Add("${Label}: $($_.Exception.Message)") }
    }
    $stateOf = {
        param($id)
        $file = Join-Path $Deployment "status/$id.json"
        if (Test-Path -LiteralPath $file) { return [string](Get-Content -LiteralPath $file -Raw | ConvertFrom-Json).state }
        return ''
    }
    foreach ($area in @('inbox', 'work')) {
        $areaRoot = Join-Path $Deployment $area
        if (-not (Test-Path -LiteralPath $areaRoot)) { continue }
        foreach ($folder in @(Get-ChildItem -LiteralPath $areaRoot -Directory -Force | Where-Object Name -Match $uuid)) {
            $state = & $stateOf $folder.Name
            if ($state -in @('running', 'recovery_required')) { continue }
            if (-not $state) {
                # Committed requests wait for the task; uncommitted uploads may still be in flight.
                if ($area -eq 'inbox' -and (Test-Path -LiteralPath (Join-Path $folder.FullName 'request.json'))) { continue }
                $latest = @(@(Get-ChildItem -LiteralPath $folder.FullName -Recurse -Force -File | ForEach-Object { $_.LastWriteTimeUtc }) + $folder.LastWriteTimeUtc | Sort-Object -Descending)[0]
                if ($latest -gt $cutoff) { continue }
            }
            & $remove $folder.FullName $areaRoot "$area/$($folder.Name)"
        }
    }
    $current = Get-Content -LiteralPath (Join-Path $Deployment 'engine/current.json') -Raw | ConvertFrom-Json
    $protected = Get-YumpooProtectedReleases $Settings $Deployment $current
    if ($Settings.releasesRoot -and (Test-Path -LiteralPath $Settings.releasesRoot)) {
        # Only engine-created candidate directories are eligible; the bootstrap release is never touched.
        foreach ($release in @(Get-ChildItem -LiteralPath $Settings.releasesRoot -Directory -Force | Where-Object Name -Match '^[0-9a-f]{40}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')) {
            # The directory name ends with its request UUID; an unresolved request keeps its candidate for diagnosis.
            if ($protected.releases.Contains($release.FullName.TrimEnd('\', '/')) -or (& $stateOf $release.Name.Substring(41)) -in @('running', 'recovery_required')) { continue }
            & $remove $release.FullName $Settings.releasesRoot "releases/$($release.Name)"
        }
    }
    $backupsRoot = Join-Path $Deployment 'backups'
    if (Test-Path -LiteralPath $backupsRoot) {
        foreach ($backup in @(Get-ChildItem -LiteralPath $backupsRoot -Directory -Force | Where-Object Name -Match $uuid)) {
            if ($protected.snapshots.Contains($backup.Name) -or (& $stateOf $backup.Name) -in @('running', 'recovery_required')) { continue }
            & $remove $backup.FullName $backupsRoot "backups/$($backup.Name)"
        }
    }
    foreach ($failure in @(Get-ChildItem -LiteralPath (Join-Path $Deployment 'engine') -File -Filter 'failure-*.txt' -Force | Where-Object { $_.LastWriteTimeUtc -lt (Get-Date).ToUniversalTime().AddDays(-90) })) {
        & $remove $failure.FullName (Join-Path $Deployment 'engine') "engine/$($failure.Name)"
    }
    Write-YumpooJson (Join-Path $Deployment 'engine/retention-last.json') ([ordered]@{ completedAt = (Get-Date).ToUniversalTime().ToString('o'); removed = @($removed); failures = @($failures) })
    if ($failures.Count) { throw ('Retention could not remove: ' + ($failures -join '; ')) }
    return @($removed)
}

function Assert-YumpooSnapshotTrees($Source, $Destination) {
    foreach ($tree in @($Source, $Destination)) {
        $full = [IO.Path]::GetFullPath($tree)
        if ($full -eq [IO.Path]::GetPathRoot($full)) { throw 'A drive root cannot be a snapshot directory.' }
        $null = Assert-YumpooPath $full ([IO.Path]::GetDirectoryName($full))
        if ((Test-Path -LiteralPath $full) -and @(Get-ChildItem -LiteralPath $full -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Snapshot trees cannot contain reparse points.' }
    }
}

# Published attachment blobs are content-addressed and never rewritten, so a snapshot can share their bytes.
# Hard links share one ACL with the live file; Test-YumpooBlobSnapshot re-hashes them before any restore.
function New-YumpooLinkedSnapshot($Source, $Destination) {
    Assert-YumpooSnapshotTrees $Source $Destination
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    if (-not (Test-Path -LiteralPath $Source)) { return }
    $root = [IO.Path]::GetFullPath($Source).TrimEnd('\', '/')
    foreach ($item in @(Get-ChildItem -LiteralPath $root -Recurse -Force)) {
        $relative = $item.FullName.Substring($root.Length + 1)
        $target = Join-Path $Destination $relative
        if ($item -is [IO.DirectoryInfo]) { New-Item -ItemType Directory -Path $target -Force | Out-Null; continue }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
        if ($relative -match $script:BlobPattern) {
            if (-not [Yumpoo.NativeLink]::CreateHardLink($target, $item.FullName, [IntPtr]::Zero)) {
                throw "Cannot link attachment blob into the snapshot (Win32 $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
            }
        } else { Copy-Item -LiteralPath $item.FullName -Destination $target }
    }
}

function Test-YumpooBlobSnapshot($Snapshot) {
    if (-not (Test-Path -LiteralPath $Snapshot)) { return }
    $root = [IO.Path]::GetFullPath($Snapshot).TrimEnd('\', '/')
    foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -Force -File)) {
        $relative = $file.FullName.Substring($root.Length + 1)
        if ($relative -match $script:BlobPattern -and (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ine $Matches[1]) {
            throw "Attachment snapshot blob no longer matches its content address: $relative"
        }
    }
}

# Best effort: a notification failure never changes the deployment outcome.
function Send-YumpooNotification($Root, $Status, [TimeSpan]$Elapsed, [scriptblock]$Sender = {
    param($Uri, [byte[]]$Body)
    Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json; charset=utf-8' -Body $Body -TimeoutSec 10 | Out-Null
}) {
    try {
        $config = Join-Path $Root 'secrets/deploy-notify.json'
        if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { return $false }
        $raw = [string](Get-Content -LiteralPath $config -Raw | ConvertFrom-Json).wecomWebhook
        # Validate the literal text: [Uri] normalises an explicit :443 away, so only the exact robot URL form is accepted.
        if ($raw -cnotmatch '^https://qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key=[A-Za-z0-9-]+$') {
            throw 'The notification webhook must be a WeCom group robot URL.'
        }
        $labels = @{
            succeeded = [regex]::Unescape('\u6210\u529f')
            failed = [regex]::Unescape('\u5931\u8d25')
            rolled_back = [regex]::Unescape('\u5931\u8d25\uff0c\u5df2\u6062\u590d\u4e3a\u539f\u7248\u672c')
            recovery_required = [regex]::Unescape('\u9700\u8981\u4eba\u5de5\u6062\u590d\uff0c\u7ad9\u70b9\u4fdd\u6301\u7ef4\u62a4\u72b6\u6001')
        }
        $label = $labels[[string]$Status.state]
        if (-not $label) { $label = [string]$Status.state }
        $commit = [string]$Status.sourceCommit
        if ($commit.Length -gt 8) { $commit = $commit.Substring(0, 8) }
        # Desktop feed requests share the engine and its notifications, not the server wording.
        $kind = if ([string]$Status.tag -clike 'desktop-v*') { [regex]::Unescape('\u684c\u9762\u5ba2\u6237\u7aef\u53d1\u5e03') } else { [regex]::Unescape('\u670d\u52a1\u7aef\u53d1\u5e03') }
        $lines = @(
            ('**Yumpoo ' + $kind + [regex]::Unescape('\uff1a') + $label + '**'),
            ([regex]::Unescape('> \u7248\u672c\uff1a') + $Status.tag),
            ([regex]::Unescape('> \u63d0\u4ea4\uff1a') + $commit),
            ([regex]::Unescape('> \u9636\u6bb5\uff1a') + $Status.phase),
            ([regex]::Unescape('> \u8bf7\u6c42\uff1a') + $Status.requestId),
            ([regex]::Unescape('> \u8017\u65f6\uff1a') + [int]$Elapsed.TotalSeconds + [regex]::Unescape(' \u79d2'))
        )
        if ($Status.phase -eq 'observation_failed') {
            $lines += [regex]::Unescape('> \u4e0a\u7ebf\u540e\u89c2\u5bdf\u672a\u901a\u8fc7\uff0c\u4e1a\u52a1\u6570\u636e\u5df2\u4fdd\u7559\u3002\u5982\u9700\u56de\u5230\u4e0a\u4e00\u7248\u672c\uff0c\u8bf7\u8fd0\u884c Server Rollback \u5de5\u4f5c\u6d41\u3002')
        }
        $body = [Text.Encoding]::UTF8.GetBytes((@{ msgtype = 'markdown'; markdown = @{ content = ($lines -join "`n") } } | ConvertTo-Json -Depth 4 -Compress))
        & $Sender $raw $body
        return $true
    } catch {
        try { [IO.File]::WriteAllText((Join-Path $Root 'deployment/engine/notify-error.txt'), $_.Exception.Message) } catch { }
        return $false
    }
}

function New-YumpooLogErrorCounter([string]$LogPath) {
    $state = @{ path = $LogPath; offset = [long]0; errors = 0 }
    if ($LogPath -and (Test-Path -LiteralPath $LogPath -PathType Leaf)) { $state.offset = (Get-Item -LiteralPath $LogPath).Length }
    return {
        if (-not $state.path -or -not (Test-Path -LiteralPath $state.path -PathType Leaf)) { return $state.errors }
        $stream = [IO.File]::Open($state.path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]'ReadWrite, Delete')
        try {
            # A shorter file means it rotated; count the new file from the beginning.
            if ($stream.Length -lt $state.offset) { $state.offset = 0 }
            $null = $stream.Seek($state.offset, [IO.SeekOrigin]::Begin)
            $text = (New-Object IO.StreamReader($stream, (New-Object Text.UTF8Encoding($false)))).ReadToEnd()
            # Only complete lines are consumed; a line still being written is counted next round.
            $complete = $text.LastIndexOf("`n")
            if ($complete -ge 0) {
                $chunk = $text.Substring(0, $complete + 1)
                $state.errors += [regex]::Matches($chunk, '"level"\s*:\s*"ERROR"').Count
                $state.offset += [Text.Encoding]::UTF8.GetByteCount($chunk)
            }
        } finally { $stream.Dispose() }
        return $state.errors
    }.GetNewClosure()
}

# The window is bounded by a deadline, so slow probes cannot shorten it and the last check lands at its end.
function Watch-YumpooRelease($Settings, [scriptblock]$Probe, [scriptblock]$CountErrors,
    [scriptblock]$Wait = { param($Seconds) Start-Sleep -Seconds $Seconds }, [scriptblock]$Clock = { [DateTime]::UtcNow }) {
    $minutes = [int](Get-YumpooSetting $Settings 'observe.minutes' 10)
    $interval = [Math]::Max(5, [int](Get-YumpooSetting $Settings 'observe.intervalSeconds' 30))
    $maxErrors = [int](Get-YumpooSetting $Settings 'observe.maxErrorLines' 20)
    $deadline = ([DateTime](& $Clock)).AddMinutes([Math]::Max(0, $minutes))
    $rounds = 0
    for (;;) {
        & $Probe
        $errors = [int](& $CountErrors)
        $rounds++
        if ($errors -gt $maxErrors) { throw "Observation found $errors application error log lines after opening traffic (limit $maxErrors)." }
        $remaining = ($deadline - [DateTime](& $Clock)).TotalSeconds
        if ($remaining -le 0) { return $rounds }
        & $Wait ([int][Math]::Ceiling([Math]::Min($interval, $remaining)))
    }
}

function Read-YumpooReleaseMetadata($ReleaseRoot) {
    if (-not $ReleaseRoot) { return $null }
    $file = Join-Path $ReleaseRoot 'windows/release-metadata.json'
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $null }
    return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
}

# The previous JAR may only run against the current schema when every migration added since it is expand-only.
function Assert-YumpooRollbackMigrations($Current, $Target) {
    if ($null -eq $Current -or $null -eq $Target) { throw 'Rollback requires release metadata for both versions.' }
    $remaining = @{}
    foreach ($migration in @($Current.migrations)) { $remaining[[string]$migration.file] = $migration }
    foreach ($migration in @($Target.migrations)) {
        $file = [string]$migration.file
        if (-not $remaining.ContainsKey($file) -or $remaining[$file].sha256 -cne $migration.sha256) { throw "Rollback target migration history diverges at $file." }
        $remaining.Remove($file)
    }
    $breaking = @($remaining.Values | Where-Object { $_.classification -cne 'expand' } | ForEach-Object { [string]$_.file } | Sort-Object)
    if ($breaking.Count) { throw ('Rollback is blocked by non-expand migrations: ' + ($breaking -join ', ')) }
}

# ---- Desktop update feed (desktop-publish requests) ----
# The feed is plain static files served by Nginx. Every file is checked against the digest GitHub recorded for the
# published Release asset; each pointer is replaced only after that version's files are complete.

function Get-YumpooDesktopFeedSettings($Settings) {
    $feed = @{
        enabled = (Get-YumpooSetting $Settings 'desktopFeed.enabled' $false) -eq $true
        root = [string](Get-YumpooSetting $Settings 'desktopFeed.root' 'C:/ProgramData/Yumpoo/public')
        channel = [string](Get-YumpooSetting $Settings 'desktopFeed.channel' 'stable')
        retainVersions = [int](Get-YumpooSetting $Settings 'desktopFeed.retainVersions' 3)
    }
    if ($feed.channel -cnotmatch '^[a-z][a-z0-9-]{0,15}$') { throw 'Invalid desktop feed channel.' }
    if ($feed.root -notmatch '^[A-Za-z]:[\\/]' -or $feed.root.Substring(2).Contains(':')) { throw 'The desktop feed root must be an absolute local path.' }
    if ($feed.retainVersions -lt 2 -or $feed.retainVersions -gt 20) { throw 'desktopFeed.retainVersions must be between 2 and 20.' }
    $feed.root = [IO.Path]::GetFullPath($feed.root).TrimEnd('\', '/')
    # Validating settings must not depend on the drive being present (Join-Path resolves PowerShell drives).
    $feed.directory = [IO.Path]::Combine($feed.root, 'desktop', $feed.channel)
    return $feed
}

function Get-YumpooDesktopFileNames([string]$Version) {
    $installer = "YumpooDesktop-Setup-$Version-x64.exe"
    return @($installer, "$installer.blockmap", "$installer.sig.json", 'latest.yml', 'release.json')
}

function Assert-YumpooDesktopRequest($Request, $Provenance) {
    if ($Request.schemaVersion -ne 2 -or (Get-YumpooRequestMode $Request) -cne 'desktop-publish') { throw 'Invalid desktop request.' }
    if ([string]$Request.tag -cnotmatch $script:DesktopTagPattern) { throw 'Invalid desktop release tag.' }
    $version = $Matches[1]
    if ([string]$Request.sourceCommit -cnotmatch '^[0-9a-f]{40}$') { throw 'Invalid desktop source commit.' }
    if ($Provenance.schemaVersion -ne 1 -or $Provenance.repository -cne 'E9uuleus/YumpooPlatform' -or $Provenance.tag -cne $Request.tag -or
        $Provenance.version -cne $version -or $Provenance.sourceCommit -cne $Request.sourceCommit -or
        ($Provenance.ciRunId -isnot [int] -and $Provenance.ciRunId -isnot [long]) -or $Provenance.ciRunId -lt 1 -or
        ($Provenance.ciRunAttempt -isnot [int] -and $Provenance.ciRunAttempt -isnot [long]) -or $Provenance.ciRunAttempt -lt 1 -or
        $Provenance.artifactName -cne "desktop-$($Provenance.sourceCommit)-$($Provenance.ciRunAttempt)" -or
        [string]$Provenance.installerSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid desktop provenance.' }
    $expected = Get-YumpooDesktopFileNames $version
    $files = @($Request.files)
    if ($files.Count -ne $expected.Count) { throw 'The desktop request must carry exactly the feed files of its version.' }
    for ($index = 0; $index -lt $files.Count; $index++) {
        $entry = $files[$index]
        if ([string]$entry.name -cne $expected[$index] -or [string]$entry.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            ($entry.bytes -isnot [int] -and $entry.bytes -isnot [long]) -or $entry.bytes -lt 1 -or $entry.bytes -gt 1GB -or
            $entry.partCount -ne [Math]::Ceiling($entry.bytes / [double]2MB)) { throw "Invalid desktop file entry: $($entry.name)" }
    }
    if ($files[0].sha256 -cne $Provenance.installerSha256 -or $files[0].bytes -ne $Provenance.installerBytes) { throw 'The desktop installer does not match its provenance.' }
    return $version
}

function Copy-YumpooDesktopUpload($SourceDirectory, $WorkDirectory, $Request) {
    $assembled = [ordered]@{}
    $files = @($Request.files)
    for ($fileIndex = 0; $fileIndex -lt $files.Count; $fileIndex++) {
        $entry = $files[$fileIndex]
        $target = Assert-YumpooPath (Join-Path $WorkDirectory $entry.name) $WorkDirectory
        $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            for ($index = 0; $index -lt $entry.partCount; $index++) {
                $source = Assert-YumpooPath (Join-Path $SourceDirectory ('desktop-{0}-{1:D4}.part' -f $fileIndex, $index)) $SourceDirectory
                $expected = [Math]::Min(2MB, [long]$entry.bytes - [long]$index * 2MB)
                $inputFile = [IO.File]::Open($source, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
                try {
                    if ($inputFile.Length -ne $expected) { throw 'Uploaded desktop part is incomplete or oversized.' }
                    $inputFile.CopyTo($output)
                } finally { $inputFile.Dispose() }
            }
            if ($output.Length -ne $entry.bytes) { throw 'Assembled desktop file size does not match the request.' }
        } finally { $output.Dispose() }
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ine $entry.sha256) { throw "Uploaded desktop file checksum mismatch: $($entry.name)" }
        $assembled[[string]$entry.name] = $target
    }
    return $assembled
}

function Assert-YumpooDesktopRelease($Settings, $Request, $Provenance, $ProvenancePath, [scriptblock]$GitHub = { param($Path) Get-YumpooGitHub $Path }) {
    $branch = [string](Get-YumpooSetting $Settings 'releaseBranch' 'dev')
    if ($branch -cnotmatch '^[a-z][a-z0-9/_-]{0,63}$') { throw 'Invalid release branch setting.' }
    $release = & $GitHub "releases/tags/$($Request.tag)"
    if ($release.draft -or -not $release.published_at) { throw 'Desktop Release is not published.' }
    $immutable = $release.PSObject.Properties['immutable']
    if ((Get-YumpooSetting $Settings 'requireImmutable' $false) -eq $true -and ($null -eq $immutable -or $immutable.Value -ne $true)) { throw 'Desktop Release is not immutable.' }
    $asset = @($release.assets | Where-Object name -ceq 'provenance.json')
    if ($asset.Count -ne 1) { throw 'Desktop Release provenance is missing or ambiguous.' }
    $trusted = Read-YumpooReleaseProvenance $asset[0] $ProvenancePath
    foreach ($field in @('schemaVersion', 'repository', 'tag', 'version', 'sourceCommit', 'ciRunId', 'ciRunAttempt', 'artifactName', 'installerSha256', 'installerBytes')) {
        if ([string]$Provenance.$field -cne [string]$trusted.$field) { throw "Desktop Release provenance mismatch: $field" }
    }
    foreach ($entry in @($Request.files)) {
        $matched = @($release.assets | Where-Object name -ceq $entry.name)
        if ($matched.Count -ne 1 -or $matched[0].digest -cne "sha256:$($entry.sha256)" -or $matched[0].size -ne $entry.bytes) { throw "Desktop file differs from its Release asset: $($entry.name)" }
    }
    if ((& $GitHub "commits/$($Request.tag)").sha -cne $Request.sourceCommit) { throw 'Desktop release tag moved.' }
    $line = & $GitHub "compare/$($Request.sourceCommit)...$($branch)?per_page=1&page=2"
    if ($line.status -notin @('ahead', 'identical')) { throw "Desktop release is not a $branch ancestor." }
    $run = & $GitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)"
    if ($run.head_sha -cne $Request.sourceCommit -or $run.head_branch -cne $branch -or $run.event -cne 'push' -or
        $run.conclusion -cne 'success' -or $run.path -cne '.github/workflows/m0-18-ci.yml') { throw "CI provenance is not a successful $branch push." }
    $jobs = & $GitHub "actions/runs/$($Provenance.ciRunId)/attempts/$($Provenance.ciRunAttempt)/jobs?per_page=100"
    foreach ($name in @('M0 Portable Gate', 'Windows Delivery', 'M0 Windows x64 Gate')) {
        $jobMatches = @($jobs.jobs | Where-Object name -eq $name)
        if ($jobMatches.Count -ne 1 -or $jobMatches[0].conclusion -ne 'success') { throw "Required CI job failed: $name" }
    }
}

# Replaces a feed file atomically within its directory; Nginx may hold it open while serving a download.
function Set-YumpooFeedFile($Source, $Target, [int]$Attempts = 60, [int]$DelayMilliseconds = 500) {
    $temporary = "$Target.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::Copy($Source, $temporary)
    try {
        for ($attempt = 1; ; $attempt++) {
            try {
                if ([IO.File]::Exists($Target)) { [IO.File]::Replace($temporary, $Target, [NullString]::Value, $true) }
                else { [IO.File]::Move($temporary, $Target) }
                return
            } catch [IO.IOException], [UnauthorizedAccessException] {
                if ($attempt -ge $Attempts) { throw "Cannot replace $([IO.Path]::GetFileName($Target)) after $Attempts attempts: $($_.Exception.Message)" }
                Start-Sleep -Milliseconds $DelayMilliseconds
            }
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

# Checked up to the drive root: neither the feed directory nor any ancestor may be a junction or symbolic link.
function Assert-YumpooFeedPath($Path) {
    $full = [IO.Path]::GetFullPath($Path)
    for ($ancestor = $full; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Reparse points are forbidden in deployment paths.' }
    }
    return $full
}

function Publish-YumpooDesktopFeed($Settings, $Request, $Files, [string]$Version) {
    $feed = Get-YumpooDesktopFeedSettings $Settings
    if (-not $feed.enabled) { throw 'The desktop feed is disabled on this host; enable it with Update-YumpooEngine.ps1 -EnableDesktopFeed.' }
    $directory = Assert-YumpooFeedPath $feed.directory
    if (-not (Test-Path -LiteralPath $feed.root)) { New-Item -ItemType Directory -Path $feed.root | Out-Null; Set-YumpooAcl $feed.root }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $directory = Assert-YumpooFeedPath $directory
    $currentFile = Assert-YumpooPath (Join-Path $directory 'release.json') $directory
    $latestFile = Assert-YumpooPath (Join-Path $directory 'latest.yml') $directory
    $pointerVersions = @{}
    if (Test-Path -LiteralPath $currentFile) {
        $current = Get-Content -LiteralPath $currentFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ([string]$current.version -cnotmatch ('^' + $script:DesktopVersionPattern + '$')) { throw 'The live feed release.json is invalid; reconcile it as an administrator.' }
        $pointerVersions['release.json'] = [string]$current.version
        if ($Version -ceq [string]$current.version -and [string]$current.sourceCommit -cne $Request.sourceCommit) { throw 'Desktop version reuse is forbidden.' }
    }
    if (Test-Path -LiteralPath $latestFile) {
        $latestVersion = Read-YumpooYamlScalar $latestFile 'version'
        if ($latestVersion -cnotmatch ('^' + $script:DesktopVersionPattern + '$')) { throw 'The live feed latest.yml is invalid; reconcile it as an administrator.' }
        $pointerVersions['latest.yml'] = $latestVersion
        if ($Version -ceq $latestVersion -and (Get-FileHash -LiteralPath $latestFile -Algorithm SHA256).Hash -ine (Get-FileHash -LiteralPath $Files['latest.yml'] -Algorithm SHA256).Hash) { throw 'Desktop version reuse is forbidden: latest.yml already exposes other content.' }
    }
    # The two pointer replacements are not a transaction. Either may be the highest version already exposed to
    # users after an interruption, including a first publication with no release.json yet. Never move it back.
    foreach ($name in $pointerVersions.Keys) {
        if (([version]$Version).CompareTo([version]$pointerVersions[$name]) -lt 0) { throw "Desktop downgrade is forbidden ($name has $($pointerVersions[$name]))." }
    }
    $names = Get-YumpooDesktopFileNames $Version
    $changed = $false
    # Versioned files first: an identical file from an interrupted attempt is kept, a different one is never overwritten.
    foreach ($name in $names[0..2]) {
        $target = Assert-YumpooPath (Join-Path $directory $name) $directory
        if (Test-Path -LiteralPath $target) {
            if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ine (Get-FileHash -LiteralPath $Files[$name] -Algorithm SHA256).Hash) { throw "Feed file $name already exists with other content." }
            continue
        }
        Set-YumpooFeedFile $Files[$name] $target
        $changed = $true
    }
    foreach ($name in $names[3..4]) {
        $target = Assert-YumpooPath (Join-Path $directory $name) $directory
        if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ieq (Get-FileHash -LiteralPath $Files[$name] -Algorithm SHA256).Hash) { continue }
        Set-YumpooFeedFile $Files[$name] $target
        $changed = $true
    }
    $null = Invoke-YumpooDesktopFeedRetention $directory $feed.retainVersions
    if (-not $changed) { return 'already_current' }
    return 'complete'
}

# Keeps installers and authorizations of the newest N versions; blockmaps stay longer because clients on older
# versions download their own version's blockmap for a differential update.
function Invoke-YumpooDesktopFeedRetention([string]$Directory, [int]$Keep, [int]$KeepBlockmaps = 20) {
    $pattern = '^YumpooDesktop-Setup-(' + $script:DesktopVersionPattern + ')-x64\.exe(\.blockmap|\.sig\.json)?$'
    $files = @(Get-ChildItem -LiteralPath $Directory -File -Force | Where-Object { $_.Name -cmatch $pattern })
    $versions = @($files | ForEach-Object { if ($_.Name -cmatch $pattern) { $Matches[1] } } | Sort-Object -Unique { [version]$_ } -Descending)
    $removed = New-Object Collections.Generic.List[string]
    foreach ($file in $files) {
        $null = $file.Name -cmatch $pattern
        $rank = [array]::IndexOf($versions, $Matches[1])
        $limit = if ($Matches[2] -ceq '.blockmap') { $KeepBlockmaps } else { $Keep }
        if ($rank -ge $limit) { Remove-YumpooManagedTree $file.FullName $Directory; $removed.Add($file.Name) }
    }
    return @($removed)
}

function Install-YumpooEngine([string]$PackagePath, [string]$PackageSha256, [string]$Root, [hashtable]$Options, [string]$ExpectedModule = '') {
    if ($PackageSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or (Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash -ine $PackageSha256) {
        throw 'The package SHA-256 does not match the verified Release value.'
    }
    $engine = Join-Path $Root 'deployment/engine'
    $files = @('YumpooDeployment.psm1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1')
    $lock = $null; $staging = $null
    try {
        try { $lock = [IO.File]::Open((Join-Path $engine 'deploy.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch [IO.IOException] { throw 'A deployment is running; retry after it finishes.' }
        $activeFile = Join-Path $engine 'active.json'
        if ((Test-Path -LiteralPath $activeFile) -and (Get-Content -LiteralPath $activeFile -Raw | ConvertFrom-Json).state -in @('running', 'recovery_required')) {
            throw 'Recover the interrupted deployment before updating the engine.'
        }
        $staging = Join-Path $engine ('update-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($PackagePath)
        try {
            foreach ($name in @($files | ForEach-Object { "windows/cd/$_" }) + 'windows/release-metadata.json') {
                $entries = @($zip.Entries | Where-Object { $_.FullName.Replace('\', '/') -ceq $name })
                if ($entries.Count -ne 1) { throw "The package does not contain exactly one $name." }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $staging ([IO.Path]::GetFileName($name))), $false)
            }
        } finally { $zip.Dispose() }
        if ($ExpectedModule -and (Get-FileHash -LiteralPath $ExpectedModule).Hash -cne (Get-FileHash -LiteralPath (Join-Path $staging 'YumpooDeployment.psm1')).Hash) {
            throw 'Run the updater from the same verified package.'
        }
        $metadata = Get-Content -LiteralPath (Join-Path $staging 'release-metadata.json') -Raw | ConvertFrom-Json
        if ($metadata.sourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'The package release metadata is invalid.' }
        $settings = [ordered]@{}
        foreach ($property in (Get-Content -LiteralPath (Join-Path $engine 'host.json') -Raw | ConvertFrom-Json).PSObject.Properties) { $settings[$property.Name] = $property.Value }
        $settings.schemaVersion = 2
        $settings.releaseBranch = [string]$Options.releaseBranch
        $settings.requireImmutable = [bool]$Options.requireImmutable
        $settings.probeRoutes = [bool]$Options.probeRoutes
        $settings.retention = [ordered]@{ extraReleases = [int]$Options.extraReleases }
        $settings.observe = [ordered]@{ minutes = [int]$Options.observeMinutes; intervalSeconds = 30; maxErrorLines = [int]$Options.maxErrorLines }
        # Absent options (older callers) keep the feed disabled; Get-YumpooDesktopFeedSettings validates the values.
        $feedRoot = if ($Options.ContainsKey('desktopFeedRoot') -and $Options.desktopFeedRoot) { [string]$Options.desktopFeedRoot } else { 'C:/ProgramData/Yumpoo/public' }
        $feedRetain = if ($Options.ContainsKey('desktopRetainVersions')) { [int]$Options.desktopRetainVersions } else { 3 }
        $feedEnabled = $Options.ContainsKey('desktopFeedEnabled') -and [bool]$Options['desktopFeedEnabled']
        $settings.desktopFeed = [ordered]@{ enabled = $feedEnabled; root = $feedRoot; channel = 'stable'; retainVersions = $feedRetain }
        $null = Get-YumpooDesktopFeedSettings ([pscustomobject]$settings)
        if ($settings.releaseBranch -cnotin @('dev', 'main')) { throw 'The release branch must be dev or main.' }
        $backup = Join-Path $engine ('backup-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ'))
        New-Item -ItemType Directory -Path $backup | Out-Null
        foreach ($file in $files + 'host.json' + 'engine.json') {
            if (Test-Path -LiteralPath (Join-Path $engine $file)) { Copy-Item -LiteralPath (Join-Path $engine $file) -Destination (Join-Path $backup $file) }
        }
        try {
            foreach ($file in $files) { Copy-Item -LiteralPath (Join-Path $staging $file) -Destination (Join-Path $engine $file) -Force }
            Write-YumpooJson (Join-Path $engine 'host.json') $settings
            $hashes = [ordered]@{}
            foreach ($file in $files) { $hashes[$file] = (Get-FileHash -LiteralPath (Join-Path $engine $file)).Hash.ToLowerInvariant() }
            Write-YumpooJson (Join-Path $engine 'engine.json') ([ordered]@{
                schemaVersion = 1; sourceCommit = $metadata.sourceCommit; packageSha256 = $PackageSha256.ToLowerInvariant()
                installedAt = (Get-Date).ToUniversalTime().ToString('o'); files = $hashes
            })
        } catch {
            foreach ($file in $files + 'host.json' + 'engine.json') {
                if (Test-Path -LiteralPath (Join-Path $backup $file)) { Copy-Item -LiteralPath (Join-Path $backup $file) -Destination (Join-Path $engine $file) -Force }
            }
            throw
        }
        return [pscustomobject]@{ sourceCommit = $metadata.sourceCommit; backup = $backup }
    } finally {
        if ($null -ne $lock) { $lock.Dispose() }
        if ($staging -and (Test-Path -LiteralPath $staging)) { Remove-Item -LiteralPath $staging -Recurse -Force }
    }
}

Export-ModuleMember -Function *-Yumpoo*
