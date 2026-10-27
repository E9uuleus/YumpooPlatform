#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$Root = 'C:/ProgramData/Yumpoo', [Parameter(Mandatory)][ValidatePattern('^[a-f0-9-]{36}$')][string]$RequestId)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $Root 'deployment/engine/YumpooDeployment.psm1') -Force
$settings = Get-Content -LiteralPath (Join-Path $Root 'deployment/engine/host.json') -Raw | ConvertFrom-Json
$current = Get-Content -LiteralPath (Join-Path $Root 'deployment/engine/current.json') -Raw | ConvertFrom-Json
Test-YumpooSite $settings -Public -ExpectedCommit $current.sourceCommit -ExpectedTag $current.tag
$status = @{ requestId = $RequestId; state = 'succeeded'; phase = 'administrator_recovered'; tag = $current.tag; sourceCommit = $current.sourceCommit }
if (-not (Add-YumpooMonitorEvent $Root $settings $status 'deployment.recovered' 'recover')) { throw 'Recovery event was not queued.' }
Write-Output 'Recovery notice queued; an offsite health and version check is still required.'
