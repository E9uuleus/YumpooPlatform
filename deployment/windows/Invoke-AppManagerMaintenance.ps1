[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('BOOTSTRAP', 'BREAK_GLASS')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F-]{36}$')]
    [string]$TargetUserId,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 160)]
    [string]$ReasonReference,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$JarPath,

    [string]$ConfigurationLocation = 'C:\ProgramData\Yumpoo\config\application-prod.yml,C:\ProgramData\Yumpoo\secrets\application-secrets.yml'
)

$ErrorActionPreference = 'Stop'
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path

$previousEnabled = $env:YUMPOO_APP_MANAGER_MAINTENANCE_ENABLED
$previousMode = $env:YUMPOO_APP_MANAGER_MAINTENANCE_MODE
$previousTarget = $env:YUMPOO_APP_MANAGER_MAINTENANCE_TARGET_USER_ID
$previousReason = $env:YUMPOO_APP_MANAGER_MAINTENANCE_REASON_REFERENCE

try {
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_ENABLED = 'true'
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_MODE = $Mode
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_TARGET_USER_ID = $TargetUserId
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_REASON_REFERENCE = $ReasonReference.Trim()

    & java -jar $resolvedJar `
        --spring.main.web-application-type=none `
        --yumpoo.outbox.enabled=false `
        "--spring.config.additional-location=$ConfigurationLocation"
    if ($LASTEXITCODE -ne 0) {
        throw "APP_MANAGER maintenance failed with exit code $LASTEXITCODE"
    }
}
finally {
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_ENABLED = $previousEnabled
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_MODE = $previousMode
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_TARGET_USER_ID = $previousTarget
    $env:YUMPOO_APP_MANAGER_MAINTENANCE_REASON_REFERENCE = $previousReason
}
