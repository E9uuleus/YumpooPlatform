import type { BrowserWindow } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import { createDiagnosticsActions, relaunchArguments, type DiagnosticsActionDependencies } from '../src/main/diagnostics-actions'

const window = {} as BrowserWindow

function dependencies(overrides: Partial<DiagnosticsActionDependencies> = {}) {
  const deps = {
    origin: () => 'https://yumpoo.example.com',
    processArguments: () => ['YumpooDesktop.exe', '--safe-mode', 'yumpoo://auth/callback?code=x&state=y'],
    relaunch: vi.fn(),
    resetStartupState: vi.fn(),
    openExternal: vi.fn(async () => undefined),
    confirm: vi.fn(async () => true),
    removePreferences: vi.fn(),
    signOut: vi.fn(async () => undefined),
    registerProtocol: vi.fn(() => true),
    openLogDirectory: vi.fn(async () => ''),
    chooseExportPath: vi.fn(async () => 'C:\\Users\\me\\Downloads\\bundle.zip'),
    buildBundle: vi.fn(async () => Buffer.from('zip')),
    writeFile: vi.fn(async () => undefined),
    now: () => new Date('2026-10-17T06:07:08.000Z'),
    ...overrides,
  }
  return { deps, actions: createDiagnosticsActions(deps) }
}

describe('诊断修复动作', () => {
  it('重启参数不重放协议回调和一次性参数', () => {
    expect(relaunchArguments(['electron.exe', '.', 'yumpoo://auth/callback?code=1', '--self-check=C:\\r.json', '--diagnostics', '--safe-mode'], false)).toEqual(['.'])
    expect(relaunchArguments(['YumpooDesktop.exe'], true)).toEqual(['--safe-mode'])
  })

  it('重试保持当前模式，正常重启清零失败计数并去掉安全模式', async () => {
    const { deps, actions } = dependencies()
    await actions.retry(window)
    expect(deps.relaunch).toHaveBeenLastCalledWith(['--safe-mode'])
    await actions['restart-normal'](window)
    expect(deps.resetStartupState).toHaveBeenCalledOnce()
    expect(deps.relaunch).toHaveBeenLastCalledWith([])
  })

  it('破坏性动作先确认，取消时不执行', async () => {
    const { deps, actions } = dependencies({ confirm: vi.fn(async () => false) })
    await expect(actions['sign-out'](window)).resolves.toEqual({ completed: false })
    await expect(actions['reset-preferences'](window)).resolves.toEqual({ completed: false })
    expect(deps.signOut).not.toHaveBeenCalled()
    expect(deps.removePreferences).not.toHaveBeenCalled()
  })

  it('确认后退出登录或重置偏好', async () => {
    const { deps, actions } = dependencies()
    await expect(actions['sign-out'](window)).resolves.toMatchObject({ completed: true })
    await expect(actions['reset-preferences'](window)).resolves.toMatchObject({ completed: true })
    expect(deps.signOut).toHaveBeenCalledOnce()
    expect(deps.removePreferences).toHaveBeenCalledOnce()
  })

  it('导出到用户选择的位置，取消选择时不构建诊断包', async () => {
    const { deps, actions } = dependencies()
    await expect(actions.export(window)).resolves.toMatchObject({ completed: true })
    expect(deps.chooseExportPath).toHaveBeenCalledWith(window, 'yumpoo-diagnostics-20261017-060708.zip')
    expect(deps.writeFile).toHaveBeenCalledWith('C:\\Users\\me\\Downloads\\bundle.zip', Buffer.from('zip'))

    const cancelled = dependencies({ chooseExportPath: vi.fn(async () => undefined) })
    await expect(cancelled.actions.export(window)).resolves.toEqual({ completed: false })
    expect(cancelled.deps.buildBundle).not.toHaveBeenCalled()
  })

  it('缺少分发配置时不能打开 Web 版；协议和日志目录失败如实返回', async () => {
    const { deps, actions } = dependencies({
      origin: () => null,
      registerProtocol: vi.fn(() => false),
      openLogDirectory: vi.fn(async () => 'Access denied'),
    })
    await expect(actions['open-web'](window)).resolves.toMatchObject({ completed: false })
    expect(deps.openExternal).not.toHaveBeenCalled()
    await expect(actions['register-protocol'](window)).resolves.toMatchObject({ completed: false })
    await expect(actions['open-logs'](window)).resolves.toMatchObject({ completed: false })
  })
})
