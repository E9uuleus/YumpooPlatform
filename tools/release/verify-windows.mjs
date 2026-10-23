import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

if (process.platform !== 'win32') throw new Error('Windows deployment verification requires Windows')
// Windows PowerShell 5.1 must discover its own modules when CI launches Node from PowerShell 7.
const childEnvironment = Object.fromEntries(Object.entries(process.env).filter(([name]) => name.toUpperCase() !== 'PSMODULEPATH'))
const hostSecurity = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
  'tools/release/host-security.test.ps1'], { stdio: 'inherit', env: childEnvironment })
if (hostSecurity.status !== 0) process.exit(hostSecurity.status || 1)
const base = 'C:/Program Files/PostgreSQL'
const installed = fs.existsSync(base) ? fs.readdirSync(base).filter(name => /^\d+$/.test(name)).sort((a, b) => Number(b) - Number(a)) : []
const pgBin = process.env.YUMPOO_TEST_PG_BIN || (installed[0] ? path.join(base, installed[0], 'bin') : '')
if (process.env.CI && !pgBin) throw new Error('PostgreSQL tools are required for the Windows restore gate')
const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
  'deployment/windows/cd/Test-YumpooDeployment.ps1', ...(pgBin ? ['-PgBin', pgBin] : [])], { stdio: 'inherit', env: childEnvironment })
if (result.status !== 0) process.exit(result.status || 1)
const monitoring = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
  'deployment/windows/cd/Test-YumpooMonitoring.ps1'], { stdio: 'inherit', env: childEnvironment })
if (monitoring.status !== 0) process.exit(monitoring.status || 1)
if (!pgBin) console.log('PostgreSQL integration was not run locally; the Windows CI gate requires it.')
