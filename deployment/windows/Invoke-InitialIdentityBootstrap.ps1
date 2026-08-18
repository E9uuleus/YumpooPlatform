[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$JavaPath,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$JarPath,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$InputFile,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 160)]
    [string]$ReasonReference,

    [string]$ConfigurationLocation = 'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/application-secrets.yml'
)

$ErrorActionPreference = 'Stop'
$resolvedJava = (Resolve-Path -LiteralPath $JavaPath).Path
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$resolvedInput = (Resolve-Path -LiteralPath $InputFile).Path
$inputItem = Get-Item -LiteralPath $resolvedInput -Force

if (($inputItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Initial identity bootstrap input must not be a reparse point'
}
if ($inputItem.Length -lt 1 -or $inputItem.Length -gt 8192) {
    throw 'Initial identity bootstrap input size is invalid'
}
$activeListeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if ($activeListeners | Where-Object { $_.Port -eq 8100 }) {
    throw 'Yumpoo server must be stopped before initial identity bootstrap'
}

$unsafeSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
$readMask = [System.Security.AccessControl.FileSystemRights]::ReadData `
    -bor [System.Security.AccessControl.FileSystemRights]::ReadAttributes `
    -bor [System.Security.AccessControl.FileSystemRights]::ReadExtendedAttributes `
    -bor [System.Security.AccessControl.FileSystemRights]::ReadPermissions
$inputAcl = [System.IO.File]::GetAccessControl($resolvedInput)
$unsafeAccess = $inputAcl.Access | Where-Object {
    if ($_.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow) {
        return $false
    }
    try {
        $sid = $_.IdentityReference.Translate(
            [System.Security.Principal.SecurityIdentifier]
        ).Value
        return $unsafeSids -contains $sid `
            -and ($_.FileSystemRights -band $readMask) -ne 0
    }
    catch {
        return $true
    }
}
if ($unsafeAccess) {
    throw 'Initial identity bootstrap input ACL grants broad read access'
}

$previousEnabled = $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_ENABLED
$previousInput = $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_INPUT_FILE
$previousReason = $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_REASON_REFERENCE
$completed = $false

try {
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_ENABLED = 'true'
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_INPUT_FILE = $resolvedInput
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_REASON_REFERENCE = $ReasonReference.Trim()

    & $resolvedJava '-Dfile.encoding=UTF-8' -jar $resolvedJar `
        '--spring.profiles.active=prod' `
        '--spring.main.web-application-type=none' `
        '--yumpoo.outbox.enabled=false' `
        "--spring.config.additional-location=$ConfigurationLocation"
    if ($LASTEXITCODE -ne 0) {
        throw "Initial identity bootstrap failed with exit code $LASTEXITCODE"
    }
    $completed = $true
}
finally {
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_ENABLED = $previousEnabled
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_INPUT_FILE = $previousInput
    $env:YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_REASON_REFERENCE = $previousReason
}

if ($completed) {
    Remove-Item -LiteralPath $resolvedInput -Force
    if (Test-Path -LiteralPath $resolvedInput) {
        throw 'Initial identity bootstrap succeeded but input cleanup failed'
    }
    Write-Output 'Initial identity bootstrap completed; one-time input removed'
}
