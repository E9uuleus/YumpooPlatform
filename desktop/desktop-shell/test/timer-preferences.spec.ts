import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DEFAULT_TIMER_PREFERENCES, TimerPreferenceStore } from '../src/main/timer-preferences'
import { QUICK_PANEL, QUICK_PANEL_MAX } from '../src/main/timer-geometry'

const created: string[] = []
async function directory() {
  const value = await mkdtemp(path.join(tmpdir(), 'yumpoo-timer-preferences-'))
  created.push(value)
  return value
}
afterEach(async () => {
  await Promise.all(created.splice(0).map(value => rm(value, { recursive: true, force: true })))
})

describe('计时窗口偏好', () => {
  it('快捷工时的固定与边界可往返，越界尺寸被忽略且不暴露给公共偏好', async () => {
    const root = await directory()
    const store = new TimerPreferenceStore(root)
    const quickBounds = { x: -100, y: 20, ...QUICK_PANEL }
    store.update({ quickPinned: true, quickBounds })
    await store.flush()
    expect(new TimerPreferenceStore(root).get()).toMatchObject({ quickPinned: true, quickBounds })
    expect(store.preferences()).not.toHaveProperty('quickPinned')
    expect(store.preferences()).not.toHaveProperty('quickBounds')
    for (const invalid of [
      { ...quickBounds, width: QUICK_PANEL.width - 1 },
      { ...quickBounds, height: QUICK_PANEL_MAX.height + 1 },
      { ...quickBounds, x: Infinity },
    ]) {
      store.update({ quickBounds: invalid })
      expect(store.get().quickBounds).toEqual(quickBounds)
    }
    store.update({ quickBounds: { x: 0, y: 0, ...QUICK_PANEL_MAX } })
    expect(store.get().quickBounds).toEqual({ x: 0, y: 0, ...QUICK_PANEL_MAX })
    store.update({ quickPinned: false, quickBounds: null })
    await store.flush()
    expect(new TimerPreferenceStore(root).get()).toMatchObject({ quickPinned: false, quickBounds: null })
  })
  it('没有文件或未指定目录时使用默认值，且只在内存中保存', async () => {
    expect(new TimerPreferenceStore(await directory()).get()).toEqual(DEFAULT_TIMER_PREFERENCES)
    const memory = new TimerPreferenceStore()
    memory.update({ display: 'dock', pinned: false })
    await memory.flush()
    expect(memory.get()).toMatchObject({ display: 'dock', pinned: false })
    expect(memory.preferences()).toEqual({ display: 'dock', orbSize: 'medium', dockSide: 'right' })
  })

  it('原子写入后可在下次启动恢复，连续写入按顺序完成', async () => {
    const root = await directory()
    const store = new TimerPreferenceStore(root)
    store.update({ display: 'dock', dockSide: 'left' })
    store.update({ dockY: .25, pinned: false })
    store.update({ orbSize: 'small' })
    await store.flush()
    expect(JSON.parse(await readFile(path.join(root, 'timer-preferences.json'), 'utf8'))).toEqual({
      version: 1, display: 'dock', orbSize: 'small', dockSide: 'left', dockY: .25, pinned: false, quickPinned: false, quickBounds: null,
    })
    expect(await readdir(root)).toEqual(['timer-preferences.json'])
    expect(new TimerPreferenceStore(root).get()).toEqual({ ...DEFAULT_TIMER_PREFERENCES, display: 'dock', orbSize: 'small', dockSide: 'left', dockY: .25, pinned: false })
  })

  it('损坏、超大或未知版本的文件回退默认值，非法字段逐项忽略', async () => {
    const root = await directory()
    const file = path.join(root, 'timer-preferences.json')
    await writeFile(file, '{broken')
    expect(new TimerPreferenceStore(root).get()).toEqual(DEFAULT_TIMER_PREFERENCES)
    await writeFile(file, JSON.stringify({ version: 1, display: 'dock', padding: 'x'.repeat(5000) }))
    expect(new TimerPreferenceStore(root).get()).toEqual(DEFAULT_TIMER_PREFERENCES)
    await writeFile(file, JSON.stringify({ version: 2, display: 'dock' }))
    expect(new TimerPreferenceStore(root).get()).toEqual(DEFAULT_TIMER_PREFERENCES)
    await writeFile(file, JSON.stringify({ version: 1, display: 'sidebar', orbSize: 'large', dockSide: 'top', dockY: 4, pinned: 'no' }))
    expect(new TimerPreferenceStore(root).get()).toEqual({ ...DEFAULT_TIMER_PREFERENCES, orbSize: 'large', dockY: 1 })
  })

  it('未变化的更新不写盘', async () => {
    const root = await directory()
    const store = new TimerPreferenceStore(root)
    store.update({ display: 'orb', pinned: true })
    await store.flush()
    expect(await readdir(root)).toEqual([])
  })
})
