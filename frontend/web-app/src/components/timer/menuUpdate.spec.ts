import { describe, expect, it } from 'vitest'
import type { DesktopUpdateState } from '@yumpoo/preload-contract'
import { menuUpdate, type MenuUpdate } from './menuUpdate'

describe('托盘更新状态', () => {
  it.each<[Partial<DesktopUpdateState>, MenuUpdate]>([
    [{ phase: 'disabled' }, { label: '版本 v1.1.2', detail: '未启用应用内更新', action: null, busy: false, accent: false }],
    [{ phase: 'idle' }, { label: '检查更新', detail: 'v1.1.2', action: 'check-update', busy: false, accent: false }],
    [{ phase: 'idle', checkedAt: 1 }, { label: '检查更新', detail: '已是最新 · v1.1.2', action: 'check-update', busy: false, accent: false }],
    [{ phase: 'checking' }, { label: '正在检查更新…', detail: 'v1.1.2', action: null, busy: true, accent: false }],
    [{ phase: 'available' }, { label: '下载更新', detail: 'v1.1.3', action: 'download-update', busy: false, accent: true }],
    [{ phase: 'downloading', percent: 42 }, { label: '正在下载更新', detail: 'v1.1.3 · 42%', action: null, busy: true, accent: false }],
    [{ phase: 'verifying' }, { label: '正在校验更新', detail: 'v1.1.3', action: null, busy: true, accent: false }],
    [{ phase: 'ready' }, { label: '重启以更新', detail: 'v1.1.3', action: 'restart-update', busy: false, accent: true }],
    [{ phase: 'error' }, { label: '重新检查更新', detail: '更新失败 · v1.1.2', action: 'check-update', busy: false, accent: false }],
  ])('展示 %j', (state, expected) => {
    expect(menuUpdate({ phase: 'idle', currentVersion: '1.1.2', channel: null, version: '1.1.3', ...state })).toEqual(expected)
  })
})
