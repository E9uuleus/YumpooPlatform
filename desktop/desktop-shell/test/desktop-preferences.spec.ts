import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DEFAULT_DESKTOP_PREFERENCES, DesktopPreferenceStore, validAccelerator, validDesktopPreferencesChange } from '../src/main/desktop-preferences'

const created: string[] = []
afterEach(async () => {
  await Promise.all(created.splice(0).map(value => rm(value, { recursive: true, force: true })))
})

describe('桌面偏好', () => {
  it('逐字段校验、原子写入并在下次启动恢复，拒绝越界的修改与快捷键', async () => {
    const root = await mkdtemp(path.join(tmpdir(), 'yumpoo-desktop-preferences-'))
    created.push(root)
    const owner = `${crypto.randomUUID()}:${crypto.randomUUID()}`
    const items = [{ id: crypto.randomUUID(), name: 'Yumpoo 平台' }]
    const store = new DesktopPreferenceStore(root)
    expect(store.get()).toEqual(DEFAULT_DESKTOP_PREFERENCES)
    store.update({ railMode: 'always', railSize: 'small', railProjects: { owner, items }, shortcut: null, autoDownload: false })
    await store.flush()
    expect(JSON.parse(await readFile(path.join(root, 'desktop-preferences.json'), 'utf8'))).toMatchObject({ version: 1, railMode: 'always', shortcut: null })
    expect(new DesktopPreferenceStore(root).get()).toEqual({ ...DEFAULT_DESKTOP_PREFERENCES, railMode: 'always', railSize: 'small', railProjects: { owner, items }, shortcut: null, autoDownload: false })
    const seven = Array.from({ length: 7 }, (_, index) => ({ id: crypto.randomUUID(), name: `项目 ${index}` }))
    for (const change of [null, [], { shortcut: 'Ctrl+K' }, { railMode: 'hidden' }, { railProjects: { owner, items: seven } },
      { railProjects: { owner: 'someone', items } }, { railProjects: { owner, items: [{ id: items[0]!.id, name: 'x'.repeat(65) }] } }]) {
      expect(validDesktopPreferencesChange(change)).toBe(false)
    }
    expect(validDesktopPreferencesChange({ launchAtLogin: true, railProjects: null })).toBe(true)
    expect(['Ctrl+Alt+Y', 'Alt+Shift+F12', 'Super+Space'].every(validAccelerator)).toBe(true)
    expect(['Shift+A', 'Alt+Ctrl+Y', 'Ctrl+Alt', 'Ctrl+Alt+YY'].some(validAccelerator)).toBe(false)
  })
})
