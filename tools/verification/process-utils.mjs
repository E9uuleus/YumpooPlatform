import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'

function isPnpmExecutable(candidate) {
  return candidate && path.basename(candidate).toLowerCase().includes('pnpm')
}

export function pnpmInvocation(args) {
  const npmExecPath = process.env.npm_execpath
  if (isPnpmExecutable(npmExecPath)) {
    const extension = path.extname(npmExecPath).toLowerCase()
    if (['.js', '.cjs', '.mjs'].includes(extension)) {
      return {
        command: process.env.npm_node_execpath || process.execPath,
        args: [npmExecPath, ...args],
      }
    }
    return { command: npmExecPath, args }
  }

  return {
    command: process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm',
    args,
  }
}

export function runSync(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: 'inherit',
    ...options,
  })
  if (result.error) {
    throw result.error
  }
  if (result.status !== 0) {
    throw new Error(`${command} 执行失败，退出码：${result.status ?? 'unknown'}`)
  }
}

export function runPnpmSync(args, options = {}) {
  const invocation = pnpmInvocation(args)
  runSync(invocation.command, invocation.args, options)
}

export function spawnPnpm(args, options = {}) {
  const invocation = pnpmInvocation(args)
  return spawn(invocation.command, invocation.args, {
    stdio: 'inherit',
    ...options,
  })
}

export function stopProcessTree(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null || !child.pid) {
    return
  }
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(child.pid), '/t', '/f'], {
      stdio: 'ignore',
    })
  } else {
    child.kill('SIGTERM')
  }
}
