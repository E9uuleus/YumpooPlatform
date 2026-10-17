import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { CRASH_LOOP_THRESHOLD, STARTUP_STATE_FILE, StartupStateStore } from '../src/main/startup-state'

let directory: string | undefined
afterEach(() => { if (directory) rmSync(directory, { recursive: true, force: true }) })

describe('启动失败计数', () => {
  it('未变健康的启动累计，健康后清零', () => {
    directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-startup-'))
    const store = new StartupStateStore(directory)
    expect(store.begin()).toBe(0)
    expect(store.begin()).toBe(1)
    expect(store.begin()).toBe(2)
    expect(new StartupStateStore(directory).begin()).toBe(CRASH_LOOP_THRESHOLD)
    store.markHealthy()
    expect(store.begin()).toBe(0)
  })

  it('首次启动时目录尚不存在也能记录', () => {
    directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-startup-'))
    const store = new StartupStateStore(path.join(directory, 'not-yet-created'))
    expect(store.begin()).toBe(0)
    expect(store.begin()).toBe(1)
  })

  it.each([
    'not json',
    JSON.stringify({ version: 2, consecutiveFailures: 5 }),
    JSON.stringify({ version: 1, consecutiveFailures: -1 }),
    JSON.stringify({ version: 1, consecutiveFailures: 1.5 }),
    'x'.repeat(5000),
  ])('损坏或未知的状态文件按 0 处理：%s', (content) => {
    directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-startup-'))
    writeFileSync(path.join(directory, STARTUP_STATE_FILE), content)
    expect(new StartupStateStore(directory).begin()).toBe(0)
  })
})
