import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fork } from 'node:child_process'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { stopProcessTree } from '../verification/process-utils.mjs'

const runner = fileURLToPath(new URL('./run-e2e.mjs', import.meta.url))

async function waitFor(probe) {
  const deadline = Date.now() + 10_000
  while (!probe()) {
    assert(Date.now() < deadline, '等待运行器状态超时')
    await delay(25)
  }
}

function alive(pid) {
  try { process.kill(pid, 0); return true }
  catch (error) { if (error.code === 'ESRCH') return false; throw error }
}

function fixture(t, script, args = []) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-e2e-runner-'))
  const pnpm = path.join(directory, 'pnpm.cjs')
  const marker = path.join(directory, 'started.json')
  const preload = path.join(directory, 'signals.cjs')
  fs.writeFileSync(pnpm, script)
  fs.writeFileSync(preload, "process.on('message', signal => process.emit(signal)); process.channel.unref()")
  const child = fork(runner, args, {
    execArgv: ['--require', preload], silent: true, windowsHide: true,
    env: { ...process.env, npm_execpath: pnpm, npm_node_execpath: process.execPath,
      YUMPOO_E2E_BASE_URL: 'http://127.0.0.1:1', RUNNER_TEST_MARKER: marker },
  })
  const completion = once(child, 'exit')
  let output = ''
  child.stdout.on('data', data => { output += data })
  child.stderr.on('data', data => { output += data })
  t.after(() => {
    stopProcessTree(child)
    if (fs.existsSync(marker)) {
      for (const pid of JSON.parse(fs.readFileSync(marker, 'utf8'))) {
        if (alive(pid)) process.kill(pid)
      }
    }
    fs.rmSync(directory, { recursive: true, force: true })
  })
  return { child, completion, marker, output: () => output }
}

for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  test(`${signal} stops the running command and its descendants`, { timeout: 15_000 }, async t => {
    const { child, completion, marker } = fixture(t, `
      const descendant = require('node:child_process').spawn(process.execPath,
        ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
      require('node:fs').writeFileSync(process.env.RUNNER_TEST_MARKER,
        JSON.stringify([process.pid, descendant.pid]));
      setInterval(() => {}, 1000);
    `)
    await waitFor(() => fs.existsSync(marker))
    const pids = JSON.parse(fs.readFileSync(marker, 'utf8'))
    assert(pids.every(alive))
    child.send(signal)
    assert.deepEqual(await completion, [code, null])
    await waitFor(() => pids.every(pid => !alive(pid)))
  })
}

test('external mode forwards arguments and preserves the command exit code', { timeout: 15_000 }, async t => {
  const { completion, output } = fixture(t, `
    console.log(JSON.stringify(process.argv.slice(2)));
    process.exit(7);
  `, ['--', '--skip-web-build', '--grep', '工作项'])
  assert.deepEqual(await completion, [7, null])
  assert.deepEqual(JSON.parse(output().trim()), ['exec', 'playwright', 'test', '-c',
    'tools/e2e/playwright.config.ts', '--grep', '工作项'])
})
