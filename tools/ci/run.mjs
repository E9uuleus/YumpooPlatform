import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { verificationEnvironment } from './environment.mjs'
import { checkHistory } from './history-policy.mjs'
import { plan } from './plan.mjs'
import { assertStageReport } from './reports.mjs'
import { resolveGitCommit } from '../verification/m0-18-utils.mjs'
import { runPnpmSync, runSync } from '../verification/process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const output = path.join(root, 'out', 'ci')
const baseRef = resolveGitCommit(root, process.env.YUMPOO_CI_BASE_REF || 'origin/dev')
const environment = {
  ...verificationEnvironment(process.env),
  AGENT_NOTE_ARCHIVE_BASE_REF: baseRef,
  YUMPOO_M018_HEAD_COMMIT: process.env.YUMPOO_M018_HEAD_COMMIT || git(['rev-parse', 'HEAD']).trim(),
  YUMPOO_M018_TESTED_COMMIT: git(['rev-parse', 'HEAD']).trim(),
  YUMPOO_M018_HANDOFF_ROOT: path.join(root, 'out/m0-18/portable-handoff'),
  YUMPOO_M017_OUTPUT_ROOT: path.join(root, 'out/m0-17'),
  YUMPOO_M018_VALIDATION_MODE: 'WINDOWS_X64_CI_STAGE',
  YUMPOO_M018_HANDOFF_ARTIFACT_NAME: process.env.YUMPOO_M018_HANDOFF_ARTIFACT_NAME || '',
  YUMPOO_M018_HANDOFF_ARTIFACT_DIGEST: process.env.YUMPOO_M018_HANDOFF_ARTIFACT_DIGEST || '',
}

function git(args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`无法读取 Git 状态：${result.error?.message || result.stderr}`)
  return result.stdout
}

function fingerprint() {
  const hash = createHash('sha256')
  for (const file of git(['ls-files', '-c', '-o', '--exclude-standard', '-z']).split('\0').filter(Boolean).sort()) {
    hash.update(`${file}\0`)
    hash.update(fs.existsSync(path.join(root, file)) ? fs.readFileSync(path.join(root, file)) : '<deleted>')
  }
  return hash.digest('hex')
}

function prerequisite(stage, sourceHash) {
  const file = path.join(output, `${stage}.json`)
  const report = fs.existsSync(file) && JSON.parse(fs.readFileSync(file, 'utf8'))
  assertStageReport(report, { stage, sourceHash, sourceCommit: environment.YUMPOO_M018_TESTED_COMMIT,
    baseRef, steps: plan(stage, baseRef) })
  return report
}

function runStage(stage) {
  if (stage === 'windows' && (process.platform !== 'win32' || process.arch !== 'x64')) {
    throw new Error('Windows 交付必须在 Windows x64 执行')
  }
  const steps = plan(stage, baseRef)
  const sourceHash = fingerprint()
  const report = { stage, baseRef, sourceCommit: environment.YUMPOO_M018_TESTED_COMMIT,
    sourceHash, startedAt: new Date().toISOString(), status: 'RUNNING', steps: [] }
  fs.mkdirSync(output, { recursive: true })
  const save = () => fs.writeFileSync(path.join(output, `${stage}.json`), `${JSON.stringify(report, null, 2)}\n`)
  save()
  try {
    if (['contracts', 'static', 'backend'].includes(stage)) checkHistory(root, baseRef)
    if (stage === 'portable') {
      prerequisite('static', sourceHash)
      const backend = prerequisite('backend', sourceHash)
      environment.YUMPOO_M018_STARTED_AT = backend.startedAt
      environment.YUMPOO_M113_STARTED_AT = report.startedAt
      for (const step of steps) step.args = step.args.map(arg => arg === '$BACKEND_STARTED' ? String(Date.parse(backend.startedAt)) : arg)
    }
    for (const step of steps) {
      const result = { id: step.id, status: 'RUNNING', startedAt: new Date().toISOString() }
      report.steps.push(result)
      console.log(`\n[ci:${stage}] ${step.id}`)
      if (process.env.GITHUB_ACTIONS === 'true') console.log(`::group::${step.id}`)
      try {
        const options = { cwd: root, env: environment, windowsHide: true }
        if (step.tool === 'pnpm') runPnpmSync(step.args, options)
        else if (step.tool === 'maven') {
          const args = step.args
          if (process.platform === 'win32') runSync('cmd.exe', ['/d', '/s', '/c', `mvnw.cmd ${args.join(' ')}`], { ...options, cwd: path.join(root, 'backend') })
          else runSync('./mvnw', args, { ...options, cwd: path.join(root, 'backend') })
        } else runSync(process.execPath, step.args, options)
        result.status = 'PASS'
      } catch (error) {
        result.status = 'FAIL'
        throw new Error(`[ci:${stage}] ${step.id} 失败：${error.message}`, { cause: error })
      } finally {
        result.completedAt = new Date().toISOString()
        save()
        if (process.env.GITHUB_ACTIONS === 'true') console.log('::endgroup::')
      }
    }
    if (fingerprint() !== sourceHash) throw new Error('验证期间源码发生变化；请重跑，不能将旧结果绑定到新源码')
    report.status = 'PASS'
  } catch (error) {
    report.status = 'FAIL'
    throw error
  } finally {
    report.completedAt = new Date().toISOString()
    save()
  }
}

try {
  const stage = process.argv[2] || 'all'
  for (const item of stage === 'all' ? ['static', 'backend', 'portable', ...(process.platform === 'win32' ? ['windows'] : [])] : [stage]) runStage(item)
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
