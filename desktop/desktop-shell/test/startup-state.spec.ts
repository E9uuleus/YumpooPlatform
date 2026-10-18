import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { CRASH_LOOP_THRESHOLD, HEALTHY_AFTER_MS, LaunchHealth, STARTUP_STATE_FILE, StartupStateStore } from '../src/main/startup-state'

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

function manualTimers() {
  const pending = new Map<number, () => void>()
  let next = 0
  return {
    set: (callback: () => void, ms: number) => {
      expect(ms).toBe(HEALTHY_AFTER_MS)
      pending.set(++next, callback)
      return next
    },
    clear: (handle: unknown) => { pending.delete(handle as number) },
    elapse: () => { for (const [handle, callback] of [...pending]) { pending.delete(handle); callback() } },
    get pending() { return pending.size },
  }
}

describe('本次启动的健康判定', () => {
  function launches() {
    directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-startup-'))
    const store = new StartupStateStore(directory)
    return () => {
      const timers = manualTimers()
      const health = new LaunchHealth(store, timers)
      return { health, timers, previous: health.begin() }
    }
  }

  it('加载后存活满一分钟清零', () => {
    const launch = launches()
    const { health, timers } = launch()
    health.loaded()
    timers.elapse()
    expect(launch().previous).toBe(0)
  })

  it('加载后渲染进程崩溃并停留诊断页，计时与正常退出都不再清零，连续出现时进入安全模式', () => {
    const launch = launches()
    let previous = 0
    for (let attempt = 0; attempt <= CRASH_LOOP_THRESHOLD; attempt += 1) {
      const current = launch()
      previous = current.previous
      current.health.loaded()
      current.health.failed()
      expect(current.timers.pending).toBe(0)
      current.timers.elapse()
      current.health.quitting()
    }
    expect(previous).toBe(CRASH_LOOP_THRESHOLD)
  })

  it('加载成功后一分钟内正常退出清零；未加载的退出不清零', () => {
    const launch = launches()
    const unloaded = launch()
    unloaded.health.quitting()
    const loaded = launch()
    expect(loaded.previous).toBe(1)
    loaded.health.loaded()
    loaded.health.quitting()
    expect(loaded.timers.pending).toBe(0)
    expect(launch().previous).toBe(0)
  })

  it('已健康后的崩溃重新从 1 开始计数', () => {
    const launch = launches()
    launch()
    const healthy = launch()
    healthy.health.loaded()
    healthy.timers.elapse()
    healthy.health.failed()
    healthy.health.quitting()
    expect(launch().previous).toBe(1)
  })
})
