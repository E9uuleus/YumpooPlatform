import type { DesktopUpdateState, TimerMenuAction } from '@yumpoo/preload-contract'

export interface MenuUpdate { label: string; detail: string; action: TimerMenuAction | null; busy: boolean; accent: boolean }
export function menuUpdate(state: DesktopUpdateState): MenuUpdate {
  const cur = state.currentVersion, ver = state.version
  switch (state.phase) {
    case 'disabled': return { label: `版本 v${cur}`, detail: '未启用应用内更新', action: null, busy: false, accent: false }
    case 'idle': return { label: '检查更新', detail: state.checkedAt ? `已是最新 · v${cur}` : `v${cur}`, action: 'check-update', busy: false, accent: false }
    case 'checking': return { label: '正在检查更新…', detail: `v${cur}`, action: null, busy: true, accent: false }
    case 'available': return { label: '下载更新', detail: `v${ver}`, action: 'download-update', busy: false, accent: true }
    case 'downloading': return { label: '正在下载更新', detail: `v${ver} · ${state.percent ?? 0}%`, action: null, busy: true, accent: false }
    case 'verifying': return { label: '正在校验更新', detail: `v${ver}`, action: null, busy: true, accent: false }
    case 'ready': return { label: '重启以更新', detail: `v${ver}`, action: 'restart-update', busy: false, accent: true }
    case 'error': return { label: '重新检查更新', detail: `更新失败 · v${cur}`, action: 'check-update', busy: false, accent: false }
  }
}
