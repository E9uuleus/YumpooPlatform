#Requires -Version 5.1
#Requires -RunAsAdministrator
param([string]$NginxRoot = 'C:/nginx')
$ErrorActionPreference = 'Stop'
$executable = Join-Path $NginxRoot 'nginx.exe'
$running = @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" | Where-Object { $_.ExecutablePath -ieq $executable.Replace('/', '\') })
if ($running.Count -eq 0) {
    & $executable -p ($NginxRoot.Replace('\','/') + '/') -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration validation failed.' }
    Start-Process -FilePath $executable -ArgumentList @('-p', ($NginxRoot.Replace('\','/') + '/')) -WorkingDirectory $NginxRoot -WindowStyle Hidden
}
