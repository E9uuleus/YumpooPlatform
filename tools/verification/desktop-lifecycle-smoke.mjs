import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { cp, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const desktop = path.join(root, 'desktop/desktop-shell')
const require = createRequire(path.join(desktop, 'package.json'))
const temporary = await mkdtemp(path.join(os.tmpdir(), 'yumpoo-lifecycle-'))
const output = path.join(desktop, 'out/lifecycle-smoke')
await mkdir(output, { recursive: true })
const server = createServer((_request, response) => {
  response.writeHead(200, { 'content-type': 'text/html' })
  response.end('<!doctype html><title>Isolated lifecycle fixture</title>')
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
try {
  await cp(path.join(desktop, 'dist'), path.join(temporary, 'dist'), { recursive: true })
  await cp(path.join(desktop, 'assets'), path.join(temporary, 'assets'), { recursive: true })
  // esbuild bundles the actual controllers; only the main renderer and the startup dialog are fixtures.
  await require('esbuild').build({ entryPoints: [path.join(desktop, 'test/fixtures/lifecycle-smoke.mjs')], outfile: path.join(temporary, 'dist/main/smoke.js'), bundle: true, platform: 'node', format: 'cjs', external: ['electron'], target: 'node24' })
  const results = []
  const environment = { ...process.env }
  delete environment.ELECTRON_RUN_AS_NODE
  for (const scenario of ['startup', 'surfaces']) {
    const child = spawn(require('electron'), [path.join(temporary, 'dist/main/smoke.js'), '--disable-gpu'], { windowsHide: true, env: { ...environment, YUMPOO_SMOKE_PROFILE: path.join(temporary, scenario), YUMPOO_SMOKE_ORIGIN: `http://127.0.0.1:${server.address().port}`, YUMPOO_SMOKE_OUTPUT: output, YUMPOO_SMOKE_SCENARIO: scenario }, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = '', stderr = '', approval
    child.stdout.on('data', data => { stdout += data; const match = stdout.match(/YUMPOO_EXIT_APPROVED (.+)\r?\n/); if (match) approval = JSON.parse(match[1]) })
    child.stderr.on('data', data => { stderr += data })
    const code = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { child.kill(); reject(new Error(`Electron timeout: ${stdout}\n${stderr}`)) }, 30_000)
      child.once('error', reject)
      child.once('close', code => { clearTimeout(timeout); resolve(code) })
    })
    assert.equal(code, 0, `${scenario}: ${stdout}\n${stderr}`)
    assert.ok(approval, 'Exit approval recorded')
    assert.match(stdout, /YUMPOO_NORMAL_QUIT/)
    for (const pid of approval.pids) {
      try { process.kill(pid, 0); assert.fail(`Process ${pid} survived exit`) } catch (error) { if (error.code !== 'ESRCH') throw error }
    }
    const elapsed = Date.now() - approval.at
    assert.ok(elapsed < 5000, `Exit took ${elapsed}ms`)
    results.push({ scenario, exitMs: elapsed, processes: approval.pids.length })
  }
  await writeFile(path.join(output, 'result.json'), JSON.stringify(results, null, 2))
  console.log(JSON.stringify(results))
} finally {
  server.close()
  await rm(temporary, { recursive: true, force: true })
}
