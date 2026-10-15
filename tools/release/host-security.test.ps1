#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$modulePath = Join-Path $repository 'deployment/windows/cd/YumpooDeployment.psm1'
$initializerPath = Join-Path $repository 'deployment/windows/cd/Initialize-YumpooHost.ps1'
foreach ($source in @($modulePath, $initializerPath)) {
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "PowerShell parse failed: $source" }
    foreach ($name in @('Set-YumpooAcl', 'Register-YumpooTasks', 'Resolve-YumpooHostPath')) {
        $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $false)
        if ($definition) { . ([scriptblock]::Create($definition.Extent.Text)) }
    }
}

function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yumpoo-host-security-' + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
$aclWrites = New-Object Collections.Generic.List[object]
function Set-Acl($LiteralPath, $AclObject) { $script:aclWrites.Add([pscustomobject]@{ Path = $LiteralPath; Acl = $AclObject }) }
try {
    $nested = Join-Path $testRoot 'tree/nested'
    New-Item -ItemType Directory -Path $nested -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $nested 'secret.yml'), 'fixture')
    Set-YumpooAcl (Join-Path $testRoot 'tree') -ReaderSid 'S-1-5-19'
    Assert-True ($aclWrites.Count -eq 3) 'ACL hardening must visit the directory and every existing descendant.'
    foreach ($write in $aclWrites) {
        Assert-True $write.Acl.AreAccessRulesProtected 'Every managed ACL must stop inheriting broader grants.'
        Assert-True ($write.Acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -eq 'S-1-5-32-544') 'Managed files must be owned by Administrators.'
        $rules = @($write.Acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        Assert-True ($rules.Count -eq 3) 'Only SYSTEM, Administrators and the requested reader should retain access.'
        Assert-True (@($rules | Where-Object { $_.IdentityReference.Value -notin @('S-1-5-18', 'S-1-5-32-544', 'S-1-5-19') }).Count -eq 0) 'Broader or pre-existing grants must not survive.'
        $reader = @($rules | Where-Object { $_.IdentityReference.Value -eq 'S-1-5-19' })
        Assert-True (($reader[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -eq 0) 'The service reader cannot write program or secret files.'
    }
    $aclWrites.Clear()
    Set-YumpooAcl (Join-Path $testRoot 'tree') -TopLevelOnly
    Assert-True ($aclWrites.Count -eq 1) 'Top-level hardening must preserve separately scoped descendant permissions.'

    foreach ($invalid in @('relative/path', 'C:\', 'C:\fixture:stream', 'C:\fixture*')) {
        Assert-Throws { Resolve-YumpooHostPath $invalid -AllowMissing } "Unsafe host path was accepted: $invalid"
    }
    $resolved = Resolve-YumpooHostPath (Join-Path $testRoot 'tree/../tree/nested')
    Assert-True ($resolved -eq $nested.Replace('/', '\')) 'Host paths must be normalized before use.'
    $junction = Join-Path $testRoot 'tree/link'
    New-Item -ItemType Junction -Path $junction -Target $nested | Out-Null
    Assert-Throws { Resolve-YumpooHostPath (Join-Path $junction 'secret.yml') 'Leaf' } 'A reparse-point ancestor must be rejected.'
    $aclWrites.Clear()
    Assert-Throws { Set-YumpooAcl (Join-Path $testRoot 'tree') } 'A tree containing a reparse point must be rejected.'
    Assert-True ($aclWrites.Count -eq 0) 'Reparse rejection must happen before any ACL mutation.'
    [IO.Directory]::Delete($junction)

    $engine = Join-Path $testRoot 'deployment/engine'
    New-Item -ItemType Directory -Path $engine -Force | Out-Null
    foreach ($file in @('Invoke-YumpooDeploy.ps1', 'YumpooDeployment.psm1', 'Start-YumpooNginx.ps1', 'host.json', 'current.json')) {
        [IO.File]::WriteAllText((Join-Path $engine $file), 'fixture')
    }
    $taskRegistrations = New-Object Collections.Generic.List[object]
    function New-ScheduledTaskPrincipal($UserId, $LogonType, $RunLevel) { [pscustomobject]$PSBoundParameters }
    function New-ScheduledTaskSettingsSet($MultipleInstances, $ExecutionTimeLimit, [switch]$StartWhenAvailable) { [pscustomobject]$PSBoundParameters }
    function New-ScheduledTaskAction($Execute, $Argument) { [pscustomobject]$PSBoundParameters }
    function New-ScheduledTaskTrigger([switch]$AtStartup, [switch]$Once, $At, $RepetitionInterval) { [pscustomobject]$PSBoundParameters }
    function Register-ScheduledTask($TaskName, $Action, $Principal, $Settings, $Trigger, [switch]$Force) { $script:taskRegistrations.Add([pscustomobject]$PSBoundParameters) }
    $scheduler = [pscustomobject]@{ Descriptor = '' }
    $scheduler | Add-Member ScriptMethod Connect { }
    $scheduler | Add-Member ScriptMethod GetFolder { param($name) $this }
    $scheduler | Add-Member ScriptMethod GetTask { param($name) $this }
    $scheduler | Add-Member ScriptMethod SetSecurityDescriptor { param($descriptor, $flags) $this.Descriptor = $descriptor }
    function New-Object {
        [CmdletBinding(DefaultParameterSetName = 'Type')]
        param(
            [Parameter(Position = 0, ParameterSetName = 'Type')][string]$TypeName,
            [Parameter(Position = 1, ParameterSetName = 'Type')][object[]]$ArgumentList,
            [Parameter(ParameterSetName = 'Com')][string]$ComObject
        )
        if ($PSCmdlet.ParameterSetName -eq 'Com') {
            Assert-True ($ComObject -eq 'Schedule.Service') 'Unexpected COM activation.'
            return $script:scheduler
        }
        Microsoft.PowerShell.Utility\New-Object @PSBoundParameters
    }
    Register-YumpooTasks ([pscustomobject]@{ runtimeRoot = $testRoot; nginxRoot = (Join-Path $testRoot 'nginx') }) 'S-1-5-21-1-2-3-1001'
    Assert-True ($taskRegistrations.Count -eq 2) 'Both fixed tasks must be registered.'
    $deploy = $taskRegistrations | Where-Object TaskName -eq 'YumpooDeploy'
    $nginx = $taskRegistrations | Where-Object TaskName -eq 'YumpooNginxStartup'
    Assert-True ($deploy.Settings.ExecutionTimeLimit -eq [timespan]::FromHours(1)) 'Deploy tasks need a bounded execution time.'
    Assert-True ($nginx.Settings.ExecutionTimeLimit -eq [timespan]::Zero) 'The nginx startup task must not terminate its long-lived child after an hour.'
    Assert-True (@($nginx.Trigger).Count -eq 1 -and $nginx.Trigger.AtStartup) 'Nginx must only be started by its boot trigger.'
    Assert-True ($deploy.Principal.UserId -eq 'SYSTEM' -and $deploy.Settings.MultipleInstances -eq 'IgnoreNew') 'Deployment must retain the fixed SYSTEM principal and serialized task policy.'
    Assert-True ($scheduler.Descriptor -eq 'D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGX;;;S-1-5-21-1-2-3-1001)') 'The deploy identity must only read and run the fixed task.'
    Write-Output 'Windows host security tests passed (recursive ACL construction, path containment, fixed task settings).'
} finally {
    Remove-Item Function:\Set-Acl -ErrorAction SilentlyContinue
    $safePrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\yumpoo-host-security-'
    if (-not $testRoot.StartsWith($safePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup path escaped the temporary directory.' }
    $link = Join-Path $testRoot 'tree/link'
    if (Test-Path -LiteralPath $link) { [IO.Directory]::Delete($link) }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
