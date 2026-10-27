import type { BrowserWindow } from 'electron'
import type { DiagnosticsActionHandlers } from './diagnostics-window'

export const SAFE_MODE_ARGUMENT = '--safe-mode'
export const DIAGNOSTICS_ARGUMENT = '--diagnostics'

export interface DiagnosticsActionDependencies {
  readonly origin: () => string | null
  readonly processArguments: () => readonly string[]
  /** Starts a new instance with these arguments and exits this one. */
  relaunch(args: string[]): void
  resetStartupState(): void
  openExternal(url: string): Promise<void>
  confirm(window: BrowserWindow, message: string, detail: string, confirmLabel: string): Promise<boolean>
  removePreferences(): Promise<void>
  signOut(): Promise<void>
  registerProtocol(): boolean
  /** Resolves to an error message, empty on success (Electron's shell.openPath contract). */
  openLogDirectory(): Promise<string>
  chooseExportPath(window: BrowserWindow, defaultName: string): Promise<string | undefined>
  buildBundle(): Promise<Buffer>
  writeFile(filePath: string, data: Buffer): Promise<void>
  now(): Date
}

/**
 * Relaunch arguments repeat the current command line except one-shot inputs: a protocol callback must
 * never be replayed, and self-check or diagnostics requests belong to the launch that asked for them.
 */
export function relaunchArguments(processArguments: readonly string[], safeMode: boolean): string[] {
  const args = processArguments.slice(1).filter(argument => !argument.toLowerCase().startsWith('yumpoo:')
    && !argument.startsWith('--self-check=') && argument !== DIAGNOSTICS_ARGUMENT && argument !== SAFE_MODE_ARGUMENT)
  return safeMode ? [...args, SAFE_MODE_ARGUMENT] : args
}

export function createDiagnosticsActions(dependencies: DiagnosticsActionDependencies): DiagnosticsActionHandlers {
  return {
    retry: async () => {
      dependencies.relaunch(relaunchArguments(dependencies.processArguments(), dependencies.processArguments().includes(SAFE_MODE_ARGUMENT)))
      return { completed: true, message: '正在重新启动…' }
    },
    'restart-normal': async () => {
      dependencies.resetStartupState()
      dependencies.relaunch(relaunchArguments(dependencies.processArguments(), false))
      return { completed: true, message: '正在以正常模式重新启动…' }
    },
    'open-web': async () => {
      const origin = dependencies.origin()
      if (!origin) return { completed: false, message: '缺少分发配置，无法确定 Web 地址。' }
      await dependencies.openExternal(origin)
      return { completed: true, message: '已在浏览器中打开 Web 版。' }
    },
    'reset-preferences': async window => {
      if (!await dependencies.confirm(window, '重置本机偏好？', '计时窗的显示方式、位置、桌面通知开关和界面本机偏好（如主题、密度）将恢复默认值，登录状态不受影响。', '重置')) {
        return { completed: false }
      }
      await dependencies.removePreferences()
      return { completed: true, message: '已重置。重新启动后生效。' }
    },
    'sign-out': async window => {
      if (!await dependencies.confirm(window, '退出登录并清除本机凭据？', '本机保存的登录状态会被删除，下次打开时需要重新扫码登录。', '退出登录')) {
        return { completed: false }
      }
      await dependencies.signOut()
      return { completed: true, message: '已退出登录并清除本机凭据。' }
    },
    'register-protocol': async () => (dependencies.registerProtocol()
      ? { completed: true, message: '已重新注册登录回调。' }
      : { completed: false, message: '注册失败，请重新安装 Yumpoo Desktop。' }),
    'open-logs': async () => {
      const error = await dependencies.openLogDirectory()
      return error ? { completed: false, message: '无法打开日志文件夹。' } : { completed: true, message: '已打开日志文件夹。' }
    },
    export: async window => {
      const stamp = dependencies.now().toISOString().slice(0, 19).replace(/[-:]/gu, '').replace('T', '-')
      const target = await dependencies.chooseExportPath(window, `yumpoo-diagnostics-${stamp}.zip`)
      if (!target) return { completed: false }
      await dependencies.writeFile(target, await dependencies.buildBundle())
      return { completed: true, message: '诊断包已导出，可以发给管理员。' }
    },
  }
}
