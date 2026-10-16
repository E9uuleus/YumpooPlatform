#Requires -Version 5.1
#Requires -RunAsAdministrator
# Administrator-only maintenance: installs the deployment engine from a verified server package.
# A Release can never upgrade the SYSTEM engine by itself; run this from the extracted copy of the same package.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PackagePath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string]$PackageSha256,
    [string]$Root = 'C:/ProgramData/Yumpoo',
    [ValidateSet('dev', 'main')][string]$ReleaseBranch = 'main',
    [switch]$RequireImmutable,
    [switch]$EnableProbeRoutes,
    [ValidateRange(0, 20)][int]$ExtraReleases = 2,
    [ValidateRange(0, 60)][int]$ObserveMinutes = 10,
    [ValidateRange(0, 10000)][int]$MaxErrorLines = 20
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
$module = Join-Path $PSScriptRoot 'YumpooDeployment.psm1'
Import-Module $module -Force
$result = Install-YumpooEngine -PackagePath $PackagePath -PackageSha256 $PackageSha256 -Root $Root -ExpectedModule $module -Options @{
    releaseBranch = $ReleaseBranch; requireImmutable = [bool]$RequireImmutable; probeRoutes = [bool]$EnableProbeRoutes
    extraReleases = $ExtraReleases; observeMinutes = $ObserveMinutes; maxErrorLines = $MaxErrorLines
}
Write-Output "Deployment engine installed from $($result.sourceCommit); previous engine saved in $($result.backup)."
