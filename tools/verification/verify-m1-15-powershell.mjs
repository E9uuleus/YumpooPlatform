import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import net from 'node:net'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { quotePowerShellLiteral } from './m0-16-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const script = path.join(root, 'deployment', 'windows', 'Invoke-InitialIdentityBootstrap.ps1')
const windowsPowerShell = path.join(
  process.env.SystemRoot ?? 'C:\\Windows',
  'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe',
)
const powerShell = fs.existsSync(windowsPowerShell) ? windowsPowerShell : 'powershell.exe'

if (process.platform !== 'win32') {
  console.log('M1-15 PowerShell 行为门禁仅在 Windows 执行')
  process.exit(0)
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m115-powershell-'))
try {
  const jar = path.join(temporary, 'server.jar')
  fs.writeFileSync(jar, 'test', 'utf8')
  const failedInput = createInput('failed-input.json')
  const failedJava = path.join(temporary, 'java-failed.cmd')
  fs.writeFileSync(failedJava, '@exit /b 7\r\n', 'utf8')
  const failed = invoke(failedJava, jar, failedInput)
  assert(failed.status !== 0, '模拟 Java 失败时脚本必须返回非零')
  assert(fs.existsSync(failedInput), '失败时必须保留输入文件')
  assertNoIdentity(failed)

  const successJava = path.join(temporary, 'java-success.cmd')
  fs.writeFileSync(successJava, '@exit /b 0\r\n', 'utf8')

  const stoppedInput = createInput('service-running-input.json')
  const listener = await listenOnBootstrapPort()
  try {
    const stopped = invoke(successJava, jar, stoppedInput)
    assert(stopped.status !== 0, '8100 正在监听时脚本必须拒绝执行')
    assert(fs.existsSync(stoppedInput), '服务未停止时必须保留输入文件')
    assertNoIdentity(stopped)
  } finally {
    await new Promise((resolve, reject) => listener.close(error => error ? reject(error) : resolve()))
  }

  const successInput = createInput('success-input.json')
  const success = invoke(successJava, jar, successInput)
  assert(success.status === 0, `模拟成功时脚本失败：${success.stderr}`)
  assert(!fs.existsSync(successInput), '成功时必须自动删除输入文件')
  assertNoIdentity(success)

  console.log('M1-15 PowerShell 失败保留、成功删除与输出脱敏行为有效')
} finally {
  fs.rmSync(temporary, { recursive: true, force: true })
}

function createInput(name) {
  const file = path.join(temporary, name)
  fs.writeFileSync(file, JSON.stringify({
    schemaVersion: 1,
    confirmation: 'M1-15_INITIAL_IDENTITY_BOOTSTRAP',
    expectedCorpId: 'ww-powershell-test',
    appManagerWeComUserId: 'm115-private-app-manager',
    companyAdminWeComUserId: 'm115-private-company-admin',
  }), 'utf8')
  const harden = [
    `$m115Acl = New-Object System.Security.AccessControl.FileSecurity`,
    `$m115Sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User`,
    `$m115Rule = New-Object System.Security.AccessControl.FileSystemAccessRule($m115Sid, 'FullControl', 'Allow')`,
    `$m115Acl.SetAccessRuleProtection($true, $false)`,
    `$m115Acl.AddAccessRule($m115Rule)`,
    `[System.IO.File]::SetAccessControl(${quotePowerShellLiteral(file)}, $m115Acl)`,
  ].join('; ')
  const result = spawnSync(powerShell, ['-NoProfile', '-NonInteractive', '-Command', harden], { encoding: 'utf8' })
  assert(result.status === 0, `无法收紧测试输入 ACL：${failureOutput(result)}`)
  return file
}

function invoke(javaPath, jarPath, inputFile) {
  return spawnSync(powerShell, [
    '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script,
    '-JavaPath', javaPath,
    '-JarPath', jarPath,
    '-InputFile', inputFile,
    '-ReasonReference', 'M1-15 verification',
    '-ConfigurationLocation', 'file:C:/ProgramData/Yumpoo/config/',
  ], { encoding: 'utf8' })
}

function listenOnBootstrapPort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(8100, '127.0.0.1', () => resolve(server))
  })
}

function assertNoIdentity(result) {
  const output = `${result.stdout}\n${result.stderr}`
  assert(!output.includes('m115-private-app-manager'), '输出泄露 APP_MANAGER 企微 UserID')
  assert(!output.includes('m115-private-company-admin'), '输出泄露 COMPANY_ADMIN 企微 UserID')
}

function failureOutput(result) {
  return result.error?.message ?? result.stderr ?? result.stdout ?? `exit=${result.status}`
}

function assert(condition, message) {
  if (!condition) throw new Error(`M1-15 PowerShell 验证失败：${message}`)
}
