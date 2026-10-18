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
    foreach ($scenario in @('success', 'backup', 'switch', 'start', 'validation', 'restore', 'prepare-open', 'open', 'public', 'observe')) {
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
            Observe = { $script:events.Add('observe'); if ($script:scenario -eq 'observe') { throw 'observation fault' } }
            Success = { $script:events.Add('success') }
            Restore = { $script:events.Add('restore'); if ($script:scenario -eq 'restore') { throw 'restore fault' } }
            ValidateOld = { $script:events.Add('validate-old') }
            OpenOld = { $script:events.Add('open-old') }
            PreOpenFailure = { $script:events.Add('preopen-failure') }
            PostOpenFailure = { $script:events.Add('postopen-failure') }
            RecoveryFailure = { $script:events.Add('maintenance-retained') }
        }
        $result = Invoke-YumpooTransaction $steps
        if ($scenario -eq 'success') { Assert-Test ($result -eq 'succeeded' -and ($events -join ',') -like '*public,observe,success') 'successful transaction must observe after public validation' }
        elseif ($scenario -eq 'backup') {
            Assert-Test ($result -eq 'failed' -and 'switch' -notin $events -and 'restore' -notin $events -and 'open-old' -in $events) 'backup failure must restart old service without database restore'
        } elseif ($scenario -in @('public', 'observe')) {
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

    $upload = Join-Path $testRoot 'upload'; New-Item -ItemType Directory -Path $upload | Out-Null
    $payload = New-Object byte[] (2MB + 37)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($payload) } finally { $random.Dispose() }
    [IO.File]::WriteAllBytes((Join-Path $upload 'package.zip'), $payload)
    [IO.File]::WriteAllBytes((Join-Path $upload 'package-0000.part'), $payload[0..(2MB - 1)])
    [IO.File]::WriteAllBytes((Join-Path $upload 'package-0001.part'), $payload[2MB..($payload.Length - 1)])
    $receipt = [pscustomobject]@{ packageBytes = $payload.Length }
    $partsRequest = [pscustomobject]@{ packagePartCount = 2 }
    foreach ($mode in @('parts', 'legacy')) {
        $work = Join-Path $testRoot "upload-$mode"; New-Item -ItemType Directory -Path $work | Out-Null
        $inputRequest = if ($mode -eq 'parts') { $partsRequest } else { [pscustomobject]@{} }
        $assembled = Copy-YumpooUpload $upload $work $inputRequest $receipt
        Assert-Test ((Get-FileHash -LiteralPath $assembled).Hash -eq (Get-FileHash -LiteralPath (Join-Path $upload 'package.zip')).Hash) 'upload assembly must preserve the original CI bytes'
    }
    foreach ($fault in @('count', 'missing', 'oversized')) {
        $work = Join-Path $testRoot "upload-$fault"; New-Item -ItemType Directory -Path $work | Out-Null
        if ($fault -eq 'count') { $partsRequest.packagePartCount = 3 }
        elseif ($fault -eq 'missing') { Remove-Item -LiteralPath (Join-Path $upload 'package-0001.part') }
        else { [IO.File]::WriteAllBytes((Join-Path $upload 'package-0001.part'), (New-Object byte[] 38)) }
        $rejected = $false
        try { $null = Copy-YumpooUpload $upload $work $partsRequest $receipt } catch { $rejected = $true }
        Assert-Test $rejected 'invalid upload parts must fail before source verification or service changes'
        $partsRequest.packagePartCount = 2
    }
    $receipt | Add-Member -NotePropertyMembers @{ schemaVersion = 1; repository = 'E9uuleus/YumpooPlatform'; tag = 'server-v2026.10.15.9'; sourceCommit = ('a' * 40); packageSha256 = (Get-FileHash -LiteralPath (Join-Path $upload 'package.zip')).Hash.ToLowerInvariant() }
    $partsRequest | Add-Member -NotePropertyMembers @{ schemaVersion = 1; requestId = [guid]::NewGuid().ToString(); tag = $receipt.tag; sourceCommit = $receipt.sourceCommit; packageSha256 = $receipt.packageSha256 }
    [IO.File]::WriteAllBytes((Join-Path $upload 'package-0001.part'), (New-Object byte[] 37))
    $work = Join-Path $testRoot 'upload-tampered'; New-Item -ItemType Directory -Path $work | Out-Null
    $tampered = Copy-YumpooUpload $upload $work $partsRequest $receipt
    $rejected = $false
    try { Assert-YumpooSource @{} $partsRequest $receipt $tampered $null $null } catch { $rejected = $_.Exception.Message -like '*checksum*' }
    Assert-Test $rejected 'equal-sized modified parts must fail the original package checksum before any network or deployment action'
    Write-Host 'Multipart and legacy upload assembly, missing/oversized parts and tamper rejection passed.'

    $provenanceFile = Join-Path $testRoot 'provenance.json'
    [IO.File]::WriteAllText($provenanceFile, '{"schemaVersion":1,"tag":"server-v1"}')
    $asset = [pscustomobject]@{ digest = ('sha256:' + (Get-FileHash -LiteralPath $provenanceFile).Hash.ToLowerInvariant()); size = (Get-Item -LiteralPath $provenanceFile).Length }
    $trusted = Read-YumpooReleaseProvenance $asset $provenanceFile
    Assert-Test ($trusted.tag -ceq 'server-v1') 'provenance must match the Release asset digest and preserve its JSON contents'
    foreach ($invalidAsset in @([pscustomobject]@{ size = $asset.size }, [pscustomobject]@{ digest = ''; size = $asset.size }, [pscustomobject]@{ digest = $asset.digest; size = ($asset.size + 1) })) {
        $rejected = $false
        try { $null = Read-YumpooReleaseProvenance $invalidAsset $provenanceFile } catch { $rejected = $true }
        Assert-Test $rejected 'missing/malformed digest or incorrect asset size must fail closed'
    }
    [IO.File]::WriteAllText($provenanceFile, '{"schemaVersion":1,"tag":"server-v2"}')
    $rejected = $false
    try { $null = Read-YumpooReleaseProvenance $asset $provenanceFile } catch { $rejected = $true }
    Assert-Test $rejected 'equal-sized modified provenance must fail the trusted Release asset digest'

    # ---- Status replacement while an SFTP-like reader holds the file (REL-P4 F13) ----
    $statusFile = Join-Path $testRoot 'status.json'
    Write-YumpooJson $statusFile @{ state = 'running'; phase = 'observe' }
    $readerReady = Join-Path $testRoot 'reader-ready'
    $reader = Start-Job -ScriptBlock {
        param($file, $ready, $holdMilliseconds)
        $handle = [IO.File]::Open($file, 'Open', 'Read', 'Read')
        try { [IO.File]::WriteAllText($ready, 'open'); Start-Sleep -Milliseconds $holdMilliseconds } finally { $handle.Dispose() }
    } -ArgumentList $statusFile, $readerReady, 4000
    try {
        $deadline = (Get-Date).AddSeconds(60)
        while (-not (Test-Path -LiteralPath $readerReady) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
        Assert-Test (Test-Path -LiteralPath $readerReady) 'the reader job must hold the status file before replacement'
        $rejected = $false
        try { Write-YumpooJson $statusFile @{ state = 'failed'; phase = 'observation_failed' } 2 50 } catch { $rejected = $true }
        Assert-Test ($rejected -and (Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json).state -eq 'running' -and
            @(Get-ChildItem -LiteralPath $testRoot -Filter 'status.json.*.tmp').Count -eq 0) 'exhausted retries keep the previous record and leave no temporary file'
        Write-YumpooJson $statusFile @{ state = 'succeeded'; phase = 'complete' } 60 250
        $final = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
        Assert-Test ($final.state -eq 'succeeded' -and $final.phase -eq 'complete' -and @(Get-ChildItem -LiteralPath $testRoot -Filter 'status.json.*.tmp').Count -eq 0) 'the terminal record is written once the reader releases the file'
    } finally {
        $null = $reader | Wait-Job -Timeout 30
        $reader | Remove-Job -Force
    }
    Write-Output 'Status replacement under a concurrent reader lock passed.'

    # ---- Engine v2: settings, request modes, release identity ----
    $hostV1 = [pscustomobject]@{ retention = [pscustomobject]@{ extraReleases = 5 } }
    Assert-Test ((Get-YumpooSetting $hostV1 'retention.extraReleases' 2) -eq 5 -and (Get-YumpooSetting $hostV1 'observe.minutes' 10) -eq 10 -and
        (Get-YumpooSetting @{ releaseBranch = 'main' } 'releaseBranch' 'dev') -eq 'main' -and (Get-YumpooSetting @{} 'releaseBranch' 'dev') -eq 'dev') 'schema 1 host settings must fall back to defaults'
    $requestId = [guid]::NewGuid().ToString()
    Assert-Test ((Get-YumpooRequestMode ([pscustomobject]@{ schemaVersion = 1; requestId = $requestId })) -eq 'deploy') 'schema 1 requests are forward deployments'
    Assert-Test ((Get-YumpooRequestMode ([pscustomobject]@{ schemaVersion = 2; requestId = $requestId; mode = 'rollback' })) -eq 'rollback') 'schema 2 rollback mode'
    foreach ($invalid in @([pscustomobject]@{ schemaVersion = 2; requestId = $requestId }, [pscustomobject]@{ schemaVersion = 2; requestId = $requestId; mode = 'Rollback' }, [pscustomobject]@{ schemaVersion = 3; requestId = $requestId; mode = 'deploy' })) {
        $rejected = $false
        try { $null = Get-YumpooRequestMode $invalid } catch { $rejected = $true }
        Assert-Test $rejected 'unknown request schema or mode must be rejected'
    }
    $tagRejected = $false
    try { Assert-YumpooProvenanceFields ([pscustomobject]@{ schemaVersion = 1; requestId = $requestId; tag = 'server-vlatest'; sourceCommit = ('a' * 40) }) ([pscustomobject]@{ schemaVersion = 1; repository = 'E9uuleus/YumpooPlatform'; tag = 'server-vlatest'; sourceCommit = ('a' * 40); packageSha256 = ('b' * 64) }) } catch { $tagRejected = $true }
    Assert-Test $tagRejected 'release tags must use the server-vYYYY.MM.DD.N format'

    $serviceXml = Join-Path $testRoot 'service.xml'
    [IO.File]::WriteAllText($serviceXml, '<service><id>YumpooServer</id><arguments>x</arguments><workingdirectory>y</workingdirectory><env name="YUMPOO_DEPLOYMENT_RELEASE_ROOT" value="old"/></service>')
    foreach ($tag in @('server-v2026.10.20.1', 'server-v2026.10.20.2')) { Set-YumpooServiceRelease $serviceXml 'C:\Program Files\Yumpoo\releases\candidate' $tag }
    [xml]$service = [IO.File]::ReadAllText($serviceXml)
    Assert-Test (@($service.SelectNodes('/service/env[@name="YUMPOO_DEPLOYMENT_RELEASE_TAG"]')).Count -eq 1 -and
        $service.SelectSingleNode('/service/env[@name="YUMPOO_DEPLOYMENT_RELEASE_TAG"]').value -ceq 'server-v2026.10.20.2' -and
        $service.SelectSingleNode('/service/env[@name="YUMPOO_DEPLOYMENT_RELEASE_ROOT"]').value -ceq 'C:\Program Files\Yumpoo\releases\candidate' -and
        $service.service.arguments -like '*releases\candidate\server\yumpoo-server.jar*') 'service switch must inject release root and tag exactly once'
    Assert-Test ((Get-YumpooWebRootInclude 'C:\R') -ceq "root `"C:/R/web`";`n") 'default include contains only the release root'
    $probeInclude = Get-YumpooWebRootInclude 'C:\R' -ProbeRoutes
    Assert-Test ($probeInclude.StartsWith("root `"C:/R/web`";`n") -and $probeInclude.Contains('location = /healthz { proxy_pass http://127.0.0.1:8100/actuator/health/readiness;') -and
        $probeInclude.Contains('location = /version.json { proxy_pass http://127.0.0.1:8100/actuator/info;')) 'probe routes follow the root line only when enabled'

    $commit = 'c' * 40
    $html = '<html><head><meta name="yumpoo-commit" content="' + $commit + '"></head><body><div id="app"></div></body></html>'
    $info = [pscustomobject]@{ release = [pscustomobject]@{ tag = 'server-v2026.10.20.1' }; build = [pscustomobject]@{ commit = $commit } }
    Assert-YumpooReleaseIdentity $html $info $commit 'server-v2026.10.20.1'
    foreach ($case in @(
        @($html.Replace($commit, ('d' * 40)), $info, 'server-v2026.10.20.1'),
        ('<div id="app"></div>', $info, 'server-v2026.10.20.1'),
        @($html, [pscustomobject]@{ release = $info.release; build = [pscustomobject]@{ commit = ('d' * 40) } }, 'server-v2026.10.20.1'),
        @($html, $info, 'server-v2026.10.20.2'),
        @($html, [pscustomobject]@{ build = $info.build }, 'server-v2026.10.20.1'))) {
        $rejected = $false
        try { Assert-YumpooReleaseIdentity $case[0] $case[1] $commit $case[2] } catch { $rejected = $true }
        Assert-Test $rejected 'web meta, running commit and injected tag must all match the release'
    }
    Write-Output 'Settings defaults, request modes, service switch and release identity checks passed.'

    # ---- Observation window and error log counting ----
    $script:now = [DateTime]'2026-10-20T00:00:00Z'
    $clock = { $script:now }
    $virtualWait = { param($seconds) $script:waits.Add($seconds); $script:now = $script:now.AddSeconds($seconds) }
    $script:probes = 0; $script:waits = New-Object Collections.Generic.List[int]
    $rounds = Watch-YumpooRelease @{ observe = @{ minutes = 1; intervalSeconds = 20; maxErrorLines = 2 } } { $script:probes++ } { 1 } $virtualWait $clock
    Assert-Test ($rounds -eq 4 -and $script:probes -eq 4 -and ($script:waits -join ',') -eq '20,20,20') 'observation checks at the start, every interval and at the end of the window'
    $script:waits.Clear()
    $rounds = Watch-YumpooRelease @{ observe = @{ minutes = 1; intervalSeconds = 25; maxErrorLines = 2 } } { } { 0 } $virtualWait $clock
    Assert-Test ($rounds -eq 4 -and ($script:waits -join ',') -eq '25,25,10') 'the final wait is shortened to land exactly on the deadline'
    Assert-Test ((Watch-YumpooRelease @{ observe = @{ minutes = 0 } } { } { 0 } { throw 'must not wait' } $clock) -eq 1) 'a zero-minute window still verifies once'
    # REL-P2 F8: errors that appear late in the window must still be caught by the final check.
    $script:waits.Clear(); $observeStart = $script:now
    $rejected = $false
    try { $null = Watch-YumpooRelease @{ observe = @{ minutes = 1; intervalSeconds = 30; maxErrorLines = 20 } } { } { if (($script:now - $observeStart).TotalSeconds -ge 45) { 21 } else { 0 } } $virtualWait $clock } catch { $rejected = $true }
    Assert-Test ($rejected -and ($script:waits -join ',') -eq '30,30') 'errors near the end of the window fail the observation'
    foreach ($case in @(@{ probe = { $script:probes++; if ($script:probes -eq 2) { throw 'probe fault' } }; errors = { 0 } }, @{ probe = { }; errors = { 3 } })) {
        $script:probes = 0
        $rejected = $false
        try { $null = Watch-YumpooRelease @{ observe = @{ minutes = 1; intervalSeconds = 20; maxErrorLines = 2 } } $case.probe $case.errors $virtualWait $clock } catch { $rejected = $true }
        Assert-Test $rejected 'a failed probe or too many error log lines fails the observation'
    }
    $log = Join-Path $testRoot 'yumpoo-server.log'
    [IO.File]::WriteAllText($log, '{"level":"ERROR","message":"before opening"}' + "`n")
    $counter = New-YumpooLogErrorCounter $log
    Assert-Test ((& $counter) -eq 0) 'errors written before opening traffic are excluded'
    [IO.File]::AppendAllText($log, '{"level":"ERROR","message":"a"}' + "`n" + '{"level":"INFO","message":"b"}' + "`n" + '{"level":"ERROR","message":"partial')
    Assert-Test ((& $counter) -eq 1) 'only complete error lines are counted'
    [IO.File]::AppendAllText($log, '"}' + "`n")
    Assert-Test ((& $counter) -eq 2) 'a completed line is counted on the next round'
    [IO.File]::WriteAllText($log, '{"level":"ERROR"}' + "`n")
    Assert-Test ((& $counter) -eq 3) 'a rotated log is counted from the beginning'
    Assert-Test ((& (New-YumpooLogErrorCounter '')) -eq 0) 'a missing log path counts nothing'
    Write-Output 'Observation window and error log counting passed.'

    # ---- Notifications ----
    $notifyRoot = Join-Path $testRoot 'notify'
    foreach ($directory in @('secrets', 'deployment/engine')) { New-Item -ItemType Directory -Path (Join-Path $notifyRoot $directory) -Force | Out-Null }
    $notifyStatus = [ordered]@{ requestId = $requestId; tag = 'server-v2026.10.20.1'; sourceCommit = ('b' * 40); state = 'failed'; phase = 'observation_failed' }
    $script:sent = $null
    $capture = { param($Uri, [byte[]]$Body) $script:sent = @{ uri = $Uri; body = [Text.Encoding]::UTF8.GetString($Body) } }
    Assert-Test (-not (Send-YumpooNotification $notifyRoot $notifyStatus ([TimeSpan]::FromSeconds(65)) $capture) -and $null -eq $script:sent) 'notification is skipped without a webhook configuration'
    $notifyConfig = Join-Path $notifyRoot 'secrets/deploy-notify.json'
    foreach ($webhook in @(
        'https://hooks.example.invalid/cgi-bin/webhook/send?key=test-key', 'http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key',
        'https://qyapi.weixin.qq.com:443/cgi-bin/webhook/send?key=test-key', 'https://qyapi.weixin.qq.com:8443/cgi-bin/webhook/send?key=test-key',
        'https://qyapi.weixin.qq.com/cgi-bin/other?key=test-key', 'https://qyapi.weixin.qq.com.evil.invalid/cgi-bin/webhook/send?key=test-key')) {
        [IO.File]::WriteAllText($notifyConfig, ('{"wecomWebhook":"' + $webhook + '"}'))
        Assert-Test (-not (Send-YumpooNotification $notifyRoot $notifyStatus ([TimeSpan]::FromSeconds(65)) $capture) -and $null -eq $script:sent -and
            (Test-Path -LiteralPath (Join-Path $notifyRoot 'deployment/engine/notify-error.txt'))) "only the exact WeCom robot URL form may receive deployment status: $webhook"
    }
    [IO.File]::WriteAllText($notifyConfig, '{"wecomWebhook":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key"}')
    Assert-Test (Send-YumpooNotification $notifyRoot $notifyStatus ([TimeSpan]::FromSeconds(65)) $capture) 'a valid webhook receives the status'
    $message = ($script:sent.body | ConvertFrom-Json)
    Assert-Test ($message.msgtype -eq 'markdown' -and $message.markdown.content.Contains('server-v2026.10.20.1') -and $message.markdown.content.Contains('bbbbbbbb') -and
        -not $message.markdown.content.Contains('b' * 9) -and $message.markdown.content.Contains('observation_failed') -and $message.markdown.content.Contains('Server Rollback') -and
        $message.markdown.content.Contains([regex]::Unescape('\u5931\u8d25')) -and -not $script:sent.body.Contains('test-key')) 'notification carries release, short commit, phase and rollback hint without the webhook key'
    Assert-Test (-not (Send-YumpooNotification $notifyRoot $notifyStatus ([TimeSpan]::FromSeconds(1)) { throw 'network down' })) 'a sender failure never escapes'
    Write-Output 'Notification filtering, content and failure isolation passed.'

    # ---- Rollback migration safety ----
    $migration = { param($file, $sha, $class) [pscustomobject]@{ file = $file; sha256 = $sha; classification = $class } }
    $targetMetadata = [pscustomobject]@{ migrations = @((& $migration 'a/V1__base.sql' '1' 'breaking')) }
    $currentMetadata = [pscustomobject]@{ migrations = @((& $migration 'a/V1__base.sql' '1' 'breaking'), (& $migration 'a/V2__add.sql' '2' 'expand')) }
    Assert-YumpooRollbackMigrations $currentMetadata $targetMetadata
    foreach ($case in @(
        @([pscustomobject]@{ migrations = @($currentMetadata.migrations + (& $migration 'a/V3__drop.sql' '3' 'breaking')) }, $targetMetadata),
        @($currentMetadata, [pscustomobject]@{ migrations = @((& $migration 'a/V1__base.sql' 'changed' 'breaking')) }),
        @($currentMetadata, [pscustomobject]@{ migrations = @((& $migration 'a/V9__only_target.sql' '9' 'expand')) }),
        @($currentMetadata, $null))) {
        $rejected = $false
        try { Assert-YumpooRollbackMigrations $case[0] $case[1] } catch { $rejected = $true }
        Assert-Test $rejected 'rollback requires both metadata files, identical shared history and expand-only additions'
    }
    Write-Output 'Rollback migration safety checks passed.'

    # ---- Linked attachment snapshots ----
    $liveAttachments = Join-Path $testRoot 'live-attachments'
    $blobBytes = [Text.Encoding]::UTF8.GetBytes('immutable attachment blob')
    $blobHash = -join ([Security.Cryptography.SHA256]::Create().ComputeHash($blobBytes) | ForEach-Object { $_.ToString('x2') })
    $blobRelative = "sha256\$($blobHash.Substring(0, 2))\$($blobHash.Substring(2, 2))\$blobHash"
    New-Item -ItemType Directory -Path (Split-Path (Join-Path $liveAttachments $blobRelative)) -Force | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $liveAttachments $blobRelative), $blobBytes)
    [IO.File]::WriteAllText((Join-Path $liveAttachments 'maintenance.tmp'), 'mutable')
    $linkedSnapshot = Join-Path $testRoot 'linked-snapshot'
    New-YumpooLinkedSnapshot $liveAttachments $linkedSnapshot
    Test-YumpooBlobSnapshot $linkedSnapshot
    [IO.File]::WriteAllText((Join-Path $liveAttachments 'maintenance.tmp'), 'changed after snapshot')
    Remove-Item -LiteralPath (Join-Path $liveAttachments $blobRelative)
    Assert-Test ([IO.File]::ReadAllText((Join-Path $linkedSnapshot 'maintenance.tmp')) -ceq 'mutable' -and
        [IO.File]::Exists((Join-Path $linkedSnapshot $blobRelative))) 'non-blob files are copied and linked blobs survive deletion of the live file'
    Copy-YumpooTree $linkedSnapshot $liveAttachments
    Assert-Test ([IO.File]::ReadAllText((Join-Path $liveAttachments $blobRelative)) -ceq 'immutable attachment blob') 'restore brings back deleted blobs'
    $snapshotBlob = [IO.File]::Open((Join-Path $linkedSnapshot $blobRelative), 'Open', 'Write', 'ReadWrite')
    try { $snapshotBlob.WriteByte(0x58) } finally { $snapshotBlob.Dispose() }
    $rejected = $false
    try { Test-YumpooBlobSnapshot $linkedSnapshot } catch { $rejected = $true }
    Assert-Test $rejected 'a blob rewritten in place must fail its content address before restore'
    Write-Output 'Linked attachment snapshot, restore and tamper detection passed.'

    # ---- Retention ----
    $retention = Join-Path $testRoot 'retention'
    $retentionDeployment = Join-Path $retention 'deployment'; $retentionReleases = Join-Path $retention 'releases'
    foreach ($directory in @('engine', 'inbox', 'work', 'status', 'backups')) { New-Item -ItemType Directory -Path (Join-Path $retentionDeployment $directory) -Force | Out-Null }
    New-Item -ItemType Directory -Path (Join-Path $retentionReleases 'bootstrap-release') -Force | Out-Null
    $requests = @(1..7 | ForEach-Object { [guid]::NewGuid().ToString() })
    $releaseOf = @{}
    for ($index = 0; $index -lt 7; $index++) {
        $id = $requests[$index]
        $releaseOf[$id] = Join-Path $retentionReleases (([string]$index * 40) + "-$id")
        New-Item -ItemType Directory -Path (Join-Path $releaseOf[$id] 'server'), (Join-Path $retentionDeployment "backups/$id") -Force | Out-Null
        $state = if ($index -eq 6) { 'rolled_back' } else { 'succeeded' }
        Add-YumpooLedgerEntry $retentionDeployment @{ requestId = $id; mode = 'deploy'; tag = "server-v2026.10.20.$($index + 1)"; sourceCommit = ''; releaseRoot = $releaseOf[$id]; snapshot = (Join-Path $retentionDeployment "backups/$id"); state = $state; phase = 'complete'; completedAt = ('2026-10-20T00:0{0}:00Z' -f $index) }
        Write-YumpooJson (Join-Path $retentionDeployment "status/$id.json") @{ state = $state }
    }
    Write-YumpooJson (Join-Path $retentionDeployment 'engine/current.json') ([ordered]@{ sourceCommit = ''; tag = 'server-v2026.10.20.6'; releaseRoot = $releaseOf[$requests[5]]; requestId = $requests[5]; previous = [ordered]@{ releaseRoot = $releaseOf[$requests[4]] } })
    $finished = $requests[6]; $pending = [guid]::NewGuid().ToString(); $fresh = [guid]::NewGuid().ToString(); $stale = [guid]::NewGuid().ToString()
    foreach ($id in @($finished, $pending, $fresh, $stale)) { New-Item -ItemType Directory -Path (Join-Path $retentionDeployment "inbox/$id") -Force | Out-Null; [IO.File]::WriteAllText((Join-Path $retentionDeployment "inbox/$id/package-0000.part"), 'part') }
    New-Item -ItemType Directory -Path (Join-Path $retentionDeployment "work/$finished") -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $retentionDeployment "inbox/$pending/request.json"), '{}')
    $old = (Get-Date).ToUniversalTime().AddDays(-2)
    (Get-Item -LiteralPath (Join-Path $retentionDeployment "inbox/$stale/package-0000.part")).LastWriteTimeUtc = $old
    (Get-Item -LiteralPath (Join-Path $retentionDeployment "inbox/$stale")).LastWriteTimeUtc = $old
    $outside = Join-Path $retention 'outside'; New-Item -ItemType Directory -Path $outside | Out-Null; [IO.File]::WriteAllText((Join-Path $outside 'keep.txt'), 'keep')
    New-Item -ItemType Junction -Path (Join-Path $releaseOf[$requests[0]] 'link') -Target $outside | Out-Null
    $retentionSettings = @{ releasesRoot = $retentionReleases; retention = @{ extraReleases = 2 } }

    Write-YumpooJson (Join-Path $retentionDeployment 'engine/active.json') @{ state = 'recovery_required' }
    Assert-Test (@(Invoke-YumpooRetention $retentionSettings $retentionDeployment).Count -eq 0 -and (Test-Path -LiteralPath $releaseOf[$requests[0]])) 'retention never runs while recovery is required'
    Write-YumpooJson (Join-Path $retentionDeployment 'engine/active.json') @{ state = 'succeeded' }
    $rejected = $false
    try { $null = Invoke-YumpooRetention $retentionSettings $retentionDeployment } catch { $rejected = $_.Exception.Message -like '*reparse*' }
    Assert-Test ($rejected -and (Test-Path -LiteralPath (Join-Path $outside 'keep.txt')) -and (Test-Path -LiteralPath $releaseOf[$requests[0]])) 'retention refuses trees with junctions and never follows them'
    [IO.Directory]::Delete((Join-Path $releaseOf[$requests[0]] 'link'))
    $null = Invoke-YumpooRetention $retentionSettings $retentionDeployment
    $keptReleases = @(Get-ChildItem -LiteralPath $retentionReleases -Directory | ForEach-Object Name | Sort-Object)
    $expectedReleases = @('bootstrap-release') + @(2..5 | ForEach-Object { Split-Path $releaseOf[$requests[$_]] -Leaf }) | Sort-Object
    Assert-Test (($keptReleases -join ',') -ceq ($expectedReleases -join ',')) "retention keeps current, previous, two more successful releases and the bootstrap release (kept: $($keptReleases -join ','))"
    $keptBackups = @(Get-ChildItem -LiteralPath (Join-Path $retentionDeployment 'backups') -Directory | ForEach-Object Name | Sort-Object)
    Assert-Test (($keptBackups -join ',') -ceq ((@(2..5 | ForEach-Object { $requests[$_] }) | Sort-Object) -join ',')) 'snapshots are kept exactly as long as their releases'
    $keptInbox = @(Get-ChildItem -LiteralPath (Join-Path $retentionDeployment 'inbox') -Directory | ForEach-Object Name | Sort-Object)
    Assert-Test (($keptInbox -join ',') -ceq ((@($pending, $fresh) | Sort-Object) -join ',') -and -not (Test-Path -LiteralPath (Join-Path $retentionDeployment "work/$finished"))) 'finished and abandoned uploads are removed while pending and in-flight ones stay'
    # REL-P2 F6/F7: an observation_failed previous release keeps its snapshot, and an unresolved
    # recovery_required request keeps its candidate even after active.json moved on.
    $edge = Join-Path $testRoot 'retention-edge'
    $edgeDeployment = Join-Path $edge 'deployment'; $edgeReleases = Join-Path $edge 'releases'
    foreach ($directory in @('engine', 'status', 'backups')) { New-Item -ItemType Directory -Path (Join-Path $edgeDeployment $directory) -Force | Out-Null }
    $edgeRequests = @{ observed = [guid]::NewGuid().ToString(); current = [guid]::NewGuid().ToString(); recovering = [guid]::NewGuid().ToString() }
    $edgeRelease = @{}
    $index = 0
    foreach ($name in @('observed', 'current', 'recovering')) {
        $id = $edgeRequests[$name]; $index++
        $edgeRelease[$name] = Join-Path $edgeReleases (([string]$index * 40) + "-$id")
        New-Item -ItemType Directory -Path $edgeRelease[$name], (Join-Path $edgeDeployment "backups/$id") -Force | Out-Null
        $state = @{ observed = 'failed'; current = 'succeeded'; recovering = 'recovery_required' }[$name]
        $phase = @{ observed = 'observation_failed'; current = 'complete'; recovering = 'restore_failed_maintenance_retained' }[$name]
        Add-YumpooLedgerEntry $edgeDeployment @{ requestId = $id; mode = 'deploy'; tag = ''; sourceCommit = ''; releaseRoot = $edgeRelease[$name]; snapshot = ''; state = $state; phase = $phase; completedAt = ('2026-10-21T00:0{0}:00Z' -f $index) }
        Write-YumpooJson (Join-Path $edgeDeployment "status/$id.json") @{ state = $state; phase = $phase }
    }
    Write-YumpooJson (Join-Path $edgeDeployment 'engine/current.json') ([ordered]@{ sourceCommit = ''; tag = ''; releaseRoot = $edgeRelease.current; requestId = $edgeRequests.current; previous = [ordered]@{ releaseRoot = $edgeRelease.observed; requestId = $edgeRequests.observed } })
    Write-YumpooJson (Join-Path $edgeDeployment 'engine/active.json') @{ state = 'succeeded' }
    $null = Invoke-YumpooRetention @{ releasesRoot = $edgeReleases; retention = @{ extraReleases = 0 } } $edgeDeployment
    foreach ($name in @('observed', 'current', 'recovering')) {
        Assert-Test ((Test-Path -LiteralPath $edgeRelease[$name]) -and (Test-Path -LiteralPath (Join-Path $edgeDeployment "backups/$($edgeRequests[$name])"))) "retention must keep the $name release and its snapshot"
    }
    Write-Output 'Retention selection, recovery guard and junction safety passed.'

    # ---- Engine installation ----
    $engineRoot = Join-Path $testRoot 'engine-install'
    $engineDirectory = Join-Path $engineRoot 'deployment/engine'
    New-Item -ItemType Directory -Path $engineDirectory -Force | Out-Null
    foreach ($file in @('YumpooDeployment.psm1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1')) { [IO.File]::WriteAllText((Join-Path $engineDirectory $file), "old $file") }
    Write-YumpooJson (Join-Path $engineDirectory 'host.json') ([ordered]@{ schemaVersion = 1; hostname = 'wecom-dev.yumpoo.com'; releasesRoot = 'C:/R' })
    $newPackage = Join-Path $testRoot 'engine-package.zip'
    $writeZip = {
        param($Path, [hashtable]$Entries)
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path }
        $archive = [IO.Compression.ZipFile]::Open($Path, 'Create')
        try {
            foreach ($name in $Entries.Keys) {
                $writer = New-Object IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }
    $engineEntries = @{
        'windows/cd/YumpooDeployment.psm1' = 'new module'; 'windows/cd/Invoke-YumpooDeploy.ps1' = 'new deploy'; 'windows/cd/Start-YumpooNginx.ps1' = 'new nginx'
        'windows/release-metadata.json' = ('{"schemaVersion":1,"sourceCommit":"' + ('e' * 40) + '","migrations":[]}')
    }
    & $writeZip $newPackage $engineEntries
    $packageHash = (Get-FileHash -LiteralPath $newPackage).Hash
    $installOptions = @{ releaseBranch = 'main'; requireImmutable = $true; probeRoutes = $false; extraReleases = 3; observeMinutes = 10; maxErrorLines = 20 }
    foreach ($case in @(@{ sha = ('0' * 64); active = $null }, @{ sha = $packageHash; active = 'running' })) {
        if ($case.active) { Write-YumpooJson (Join-Path $engineDirectory 'active.json') @{ state = $case.active } }
        $rejected = $false
        try { $null = Install-YumpooEngine $newPackage $case.sha $engineRoot $installOptions } catch { $rejected = $true }
        Assert-Test ($rejected -and [IO.File]::ReadAllText((Join-Path $engineDirectory 'YumpooDeployment.psm1')) -ceq 'old YumpooDeployment.psm1') 'engine install requires the verified package hash and no interrupted deployment'
    }
    Remove-Item -LiteralPath (Join-Path $engineDirectory 'active.json')
    $expectedModule = Join-Path $testRoot 'expected-module.psm1'; [IO.File]::WriteAllText($expectedModule, 'another module')
    $rejected = $false
    try { $null = Install-YumpooEngine $newPackage $packageHash $engineRoot $installOptions $expectedModule } catch { $rejected = $true }
    Assert-Test $rejected 'the updater must come from the same package as the installed engine'
    $installed = Install-YumpooEngine $newPackage $packageHash $engineRoot $installOptions
    $installedHost = Get-Content -LiteralPath (Join-Path $engineDirectory 'host.json') -Raw | ConvertFrom-Json
    $engineRecord = Get-Content -LiteralPath (Join-Path $engineDirectory 'engine.json') -Raw | ConvertFrom-Json
    Assert-Test ([IO.File]::ReadAllText((Join-Path $engineDirectory 'YumpooDeployment.psm1')) -ceq 'new module' -and $installed.sourceCommit -ceq ('e' * 40) -and
        $installedHost.schemaVersion -eq 2 -and $installedHost.releaseBranch -ceq 'main' -and $installedHost.requireImmutable -eq $true -and $installedHost.hostname -ceq 'wecom-dev.yumpoo.com' -and
        $installedHost.retention.extraReleases -eq 3 -and $engineRecord.sourceCommit -ceq ('e' * 40) -and
        [IO.File]::ReadAllText((Join-Path $installed.backup 'YumpooDeployment.psm1')) -ceq 'old YumpooDeployment.psm1' -and
        @(Get-ChildItem -LiteralPath $engineDirectory -Directory -Filter 'update-*').Count -eq 0) 'engine install replaces engine files, migrates host settings and keeps a backup'
    $engineEntries.Remove('windows/release-metadata.json')
    & $writeZip $newPackage $engineEntries
    $rejected = $false
    try { $null = Install-YumpooEngine $newPackage (Get-FileHash -LiteralPath $newPackage).Hash $engineRoot $installOptions } catch { $rejected = $true }
    Assert-Test $rejected 'a package without release metadata cannot install an engine'
    Assert-Test ($installedHost.desktopFeed.enabled -eq $false -and $installedHost.desktopFeed.root -ceq 'C:/ProgramData/Yumpoo/public') 'callers without feed options keep the desktop feed disabled'
    & $writeZip $newPackage ($engineEntries + @{ 'windows/release-metadata.json' = ('{"schemaVersion":1,"sourceCommit":"' + ('e' * 40) + '","migrations":[]}') })
    $null = Install-YumpooEngine $newPackage (Get-FileHash -LiteralPath $newPackage).Hash $engineRoot ($installOptions + @{ desktopFeedEnabled = $true; desktopFeedRoot = 'D:/Feed'; desktopRetainVersions = 5 })
    $installedHost = Get-Content -LiteralPath (Join-Path $engineDirectory 'host.json') -Raw | ConvertFrom-Json
    Assert-Test ($installedHost.desktopFeed.enabled -eq $true -and $installedHost.desktopFeed.root -ceq 'D:/Feed' -and $installedHost.desktopFeed.retainVersions -eq 5) 'the updater enables the desktop feed explicitly'
    $rejected = $false
    try { $null = Install-YumpooEngine $newPackage (Get-FileHash -LiteralPath $newPackage).Hash $engineRoot ($installOptions + @{ desktopFeedRoot = 'relative/feed' }) } catch { $rejected = $true }
    Assert-Test $rejected 'a relative desktop feed root is rejected before any engine file changes'
    Write-Output 'Engine installation verification, settings migration and backup passed.'

    # ---- Desktop update feed (desktop-publish) ----
    $desktopRoot = Join-Path $testRoot 'desktop'
    $feedRoot = Join-Path $desktopRoot 'public'
    New-Item -ItemType Directory -Path $feedRoot -Force | Out-Null
    $feedDirectory = Join-Path $feedRoot 'desktop/stable'
    $desktopCommit = 'c' * 40
    $desktopSettings = [pscustomobject]@{ releaseBranch = 'main'; requireImmutable = $true; desktopFeed = [pscustomobject]@{ enabled = $true; root = $feedRoot; channel = 'stable'; retainVersions = 3 } }
    # Builds the uploaded parts, request and provenance exactly as ssh-deploy.mjs and the engine exchange them (JSON round trip).
    $newDesktopUpload = {
        param([string]$Version, [string]$Commit = $desktopCommit)
        $source = Join-Path $desktopRoot ("inbox-$Version-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $source | Out-Null
        $names = Get-YumpooDesktopFileNames $Version
        $entries = @()
        for ($fileIndex = 0; $fileIndex -lt $names.Count; $fileIndex++) {
            $bytes = [Text.Encoding]::UTF8.GetBytes("$($names[$fileIndex]) of $Version")
            if ($fileIndex -eq 0) { $bytes = [byte[]]($bytes + (New-Object byte[] (2MB))) }
            if ($names[$fileIndex] -ceq 'release.json') { $bytes = [Text.Encoding]::UTF8.GetBytes((@{ schemaVersion = 1; version = $Version; sourceCommit = $Commit } | ConvertTo-Json)) }
            for ($index = 0; $index * 2MB -lt $bytes.Length; $index++) {
                $length = [Math]::Min(2MB, $bytes.Length - $index * 2MB)
                $part = New-Object byte[] $length
                [Array]::Copy($bytes, $index * 2MB, $part, 0, $length)
                [IO.File]::WriteAllBytes((Join-Path $source ('desktop-{0}-{1:D4}.part' -f $fileIndex, $index)), $part)
            }
            $hash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
            $entries += [ordered]@{ name = $names[$fileIndex]; bytes = $bytes.Length; sha256 = $hash; partCount = [int][Math]::Ceiling($bytes.Length / [double]2MB) }
        }
        $request = [ordered]@{ schemaVersion = 2; mode = 'desktop-publish'; requestId = [guid]::NewGuid().ToString(); tag = "desktop-v$Version"; sourceCommit = $Commit; files = $entries }
        $provenance = [ordered]@{ schemaVersion = 1; repository = 'E9uuleus/YumpooPlatform'; tag = "desktop-v$Version"; version = $Version; sourceCommit = $Commit
            ciRunId = 3001; ciRunAttempt = 1; artifactName = "desktop-$Commit-1"; installerSha256 = $entries[0].sha256; installerBytes = $entries[0].bytes }
        $work = Join-Path $desktopRoot ("work-$Version-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $work | Out-Null
        $provenancePath = Join-Path $work 'provenance.json'
        [IO.File]::WriteAllText($provenancePath, ($provenance | ConvertTo-Json -Depth 5))
        return @{ source = $source; work = $work; provenancePath = $provenancePath
            request = ($request | ConvertTo-Json -Depth 6 | ConvertFrom-Json); provenance = (Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json) }
    }
    $releaseFor = {
        param($Upload)
        $assets = @([pscustomobject]@{ name = 'provenance.json'; size = (Get-Item -LiteralPath $Upload.provenancePath).Length
            digest = ('sha256:' + (Get-FileHash -LiteralPath $Upload.provenancePath).Hash.ToLowerInvariant()) })
        $assets += @($Upload.request.files | ForEach-Object { [pscustomobject]@{ name = $_.name; size = $_.bytes; digest = "sha256:$($_.sha256)" } })
        return [pscustomobject]@{ draft = $false; published_at = '2026-10-18T00:00:00Z'; immutable = $true; assets = $assets }
    }
    $script:desktopTagCommit = $desktopCommit
    $fakeGitHub = {
        param($Path)
        if ($Path -like 'releases/tags/*') { return $script:desktopRelease }
        if ($Path -like 'commits/*') { return [pscustomobject]@{ sha = $script:desktopTagCommit } }
        if ($Path -like 'compare/*') { return [pscustomobject]@{ status = 'ahead' } }
        if ($Path -like '*/jobs?per_page=100') { return [pscustomobject]@{ jobs = @(@('M0 Portable Gate', 'Windows Delivery', 'M0 Windows x64 Gate') | ForEach-Object { [pscustomobject]@{ name = $_; conclusion = 'success' } }) } }
        if ($Path -ceq 'actions/runs/3001/attempts/1') { return [pscustomobject]@{ head_sha = $desktopCommit; head_branch = 'main'; event = 'push'; conclusion = 'success'; path = '.github/workflows/m0-18-ci.yml' } }
        throw "Unexpected GitHub request: $Path"
    }

    $first = & $newDesktopUpload '1.1.0'
    Assert-Test ((Get-YumpooRequestMode $first.request) -ceq 'desktop-publish' -and (Assert-YumpooDesktopRequest $first.request $first.provenance) -ceq '1.1.0') 'desktop requests validate against their provenance'
    foreach ($mutate in @(
        { param($r, $p) $r.files[3].name = '../latest.yml' },
        { param($r, $p) $r.files = @($r.files[1], $r.files[0], $r.files[2], $r.files[3], $r.files[4]) },
        { param($r, $p) $r.files[0].partCount = 1 },
        { param($r, $p) $p.installerSha256 = ('0' * 64) },
        { param($r, $p) $r.tag = 'server-v2026.10.18.1' },
        { param($r, $p) $p.artifactName = "m1-15-server-$desktopCommit-1" }
    )) {
        $request = $first.request | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $provenance = $first.provenance | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        & $mutate $request $provenance
        $rejected = $false
        try { $null = Assert-YumpooDesktopRequest $request $provenance } catch { $rejected = $true }
        Assert-Test $rejected 'malformed desktop requests are rejected before reading any upload'
    }
    $firstFiles = Copy-YumpooDesktopUpload $first.source $first.work $first.request
    Assert-Test ((Get-FileHash -LiteralPath $firstFiles['latest.yml']).Hash -ieq $first.request.files[3].sha256 -and (Get-Item -LiteralPath $firstFiles[$first.request.files[0].name]).Length -eq (2MB + $first.request.files[0].bytes - 2MB)) 'desktop parts reassemble each file'
    $tampered = & $newDesktopUpload '1.1.0'
    $partPath = Join-Path $tampered.source 'desktop-3-0000.part'
    $partBytes = [IO.File]::ReadAllBytes($partPath); $partBytes[0] = $partBytes[0] -bxor 1; [IO.File]::WriteAllBytes($partPath, $partBytes)
    $rejected = $false
    try { $null = Copy-YumpooDesktopUpload $tampered.source $tampered.work $tampered.request } catch { $rejected = $_.Exception.Message -like '*checksum*' }
    Assert-Test $rejected 'an equal-sized modified desktop part fails the request digest'

    $script:desktopRelease = & $releaseFor $first
    Assert-YumpooDesktopRelease $desktopSettings $first.request $first.provenance $first.provenancePath $fakeGitHub
    foreach ($fault in @('digest', 'draft', 'mutable', 'moved')) {
        $script:desktopRelease = & $releaseFor $first
        if ($fault -eq 'digest') { $script:desktopRelease.assets[2].digest = 'sha256:' + ('0' * 64) }
        elseif ($fault -eq 'draft') { $script:desktopRelease.draft = $true }
        elseif ($fault -eq 'mutable') { $script:desktopRelease.immutable = $false }
        else { $script:desktopTagCommit = 'd' * 40 }
        $rejected = $false
        try { Assert-YumpooDesktopRelease $desktopSettings $first.request $first.provenance $first.provenancePath $fakeGitHub } catch { $rejected = $true }
        Assert-Test $rejected "desktop files must match the published immutable Release ($fault)"
        $script:desktopTagCommit = $desktopCommit
    }

    $disabled = [pscustomobject]@{ desktopFeed = [pscustomobject]@{ enabled = $false; root = $feedRoot } }
    $rejected = $false
    try { $null = Publish-YumpooDesktopFeed $disabled $first.request $firstFiles '1.1.0' } catch { $rejected = $_.Exception.Message -like '*disabled*' }
    Assert-Test ($rejected -and -not (Test-Path -LiteralPath $feedDirectory)) 'a disabled feed writes nothing'
    Assert-Test ((Publish-YumpooDesktopFeed $desktopSettings $first.request $firstFiles '1.1.0') -ceq 'complete') 'the first version is published'
    Assert-Test ((Get-ChildItem -LiteralPath $feedDirectory -File).Count -eq 5 -and (Get-Content -LiteralPath (Join-Path $feedDirectory 'release.json') -Raw | ConvertFrom-Json).version -ceq '1.1.0') 'every feed file of the version is in place'
    Assert-Test ((Publish-YumpooDesktopFeed $desktopSettings $first.request $firstFiles '1.1.0') -ceq 'already_current') 'republishing the live version is a no-op'
    $older = & $newDesktopUpload '1.0.9'
    $olderFiles = Copy-YumpooDesktopUpload $older.source $older.work $older.request
    $rejected = $false
    try { $null = Publish-YumpooDesktopFeed $desktopSettings $older.request $olderFiles '1.0.9' } catch { $rejected = $_.Exception.Message -like '*downgrade*' }
    Assert-Test $rejected 'a lower version never replaces the feed'
    $next = & $newDesktopUpload '1.2.0'
    $nextFiles = Copy-YumpooDesktopUpload $next.source $next.work $next.request
    $conflict = Join-Path $feedDirectory $next.request.files[1].name
    [IO.File]::WriteAllText($conflict, 'stale blockmap from another build')
    $rejected = $false
    try { $null = Publish-YumpooDesktopFeed $desktopSettings $next.request $nextFiles '1.2.0' } catch { $rejected = $_.Exception.Message -like '*other content*' }
    Assert-Test ($rejected -and (Get-Content -LiteralPath (Join-Path $feedDirectory 'latest.yml') -Raw) -ceq 'latest.yml of 1.1.0') 'latest.yml is replaced only after every versioned file is in place'
    Remove-Item -LiteralPath $conflict
    Assert-Test ((Publish-YumpooDesktopFeed $desktopSettings $next.request $nextFiles '1.2.0') -ceq 'complete' -and (Get-Content -LiteralPath (Join-Path $feedDirectory 'latest.yml') -Raw) -ceq 'latest.yml of 1.2.0') 'the next version replaces latest.yml last'
    foreach ($version in @('1.3.0', '1.10.0')) {
        $upload = & $newDesktopUpload $version
        $null = Publish-YumpooDesktopFeed $desktopSettings $upload.request (Copy-YumpooDesktopUpload $upload.source $upload.work $upload.request) $version
    }
    $remaining = @(Get-ChildItem -LiteralPath $feedDirectory -File | Select-Object -ExpandProperty Name)
    Assert-Test (@($remaining | Where-Object { $_ -like '*.exe' }).Count -eq 3 -and 'YumpooDesktop-Setup-1.1.0-x64.exe' -notin $remaining -and
        'YumpooDesktop-Setup-1.1.0-x64.exe.blockmap' -in $remaining -and 'YumpooDesktop-Setup-1.10.0-x64.exe' -in $remaining -and
        @($remaining | Where-Object { $_ -like '*.sig.json' }).Count -eq 3) 'retention keeps three installers by version order and older blockmaps for differential updates'
    $junctionTarget = Join-Path $desktopRoot 'outside'; New-Item -ItemType Directory -Path $junctionTarget | Out-Null
    $junctionFeed = Join-Path $desktopRoot 'junction-feed'; New-Item -ItemType Junction -Path $junctionFeed -Target $junctionTarget | Out-Null
    $rejected = $false
    try { $null = Publish-YumpooDesktopFeed ([pscustomobject]@{ desktopFeed = [pscustomobject]@{ enabled = $true; root = $junctionFeed } }) $first.request $firstFiles '1.1.0' } catch { $rejected = $_.Exception.Message -like '*Reparse*' }
    [IO.Directory]::Delete($junctionFeed)
    Assert-Test ($rejected -and @(Get-ChildItem -LiteralPath $junctionTarget -Force).Count -eq 0) 'a junction anywhere above the feed is refused'
    Write-Output 'Desktop feed request validation, Release digests, write order, downgrade, retention and junction safety passed.'

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
            New-YumpooLinkedSnapshot $attachments (Join-Path $testRoot 'attachment-snapshot')
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
            Test-YumpooBlobSnapshot (Join-Path $testRoot 'attachment-snapshot')
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
