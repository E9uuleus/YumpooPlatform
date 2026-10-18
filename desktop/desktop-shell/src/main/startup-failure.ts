import type { MessageBoxOptions } from 'electron'

export type StartupFailureChoice = 'open-web' | 'retry' | 'quit'

export interface StartupFailureDependencies {
  showMessageBox(options: MessageBoxOptions): Promise<{ response: number }>
  openExternal(url: string): Promise<void>
  relaunch(): void
  exit(code: number): void
}

/**
 * Replaces the former silent exit: the user learns that the shell failed and can fall back to the web app.
 * The diagnostics window that P2 adds supersedes this dialog.
 */
export async function handleStartupFailure(
  dependencies: StartupFailureDependencies,
  webOrigin: string | undefined,
): Promise<StartupFailureChoice> {
  const choices: StartupFailureChoice[] = webOrigin ? ['open-web', 'retry', 'quit'] : ['retry', 'quit']
  const labels: Record<StartupFailureChoice, string> = { 'open-web': '用浏览器打开 Web 版', retry: '重试', quit: '退出' }
  const { response } = await dependencies.showMessageBox({
    type: 'error',
    title: 'Yumpoo Desktop',
    message: 'Yumpoo Desktop 无法启动',
    detail: webOrigin
      ? '无法连接 Yumpoo 或加载桌面客户端。请检查网络后重试，也可以先在浏览器中使用 Web 版。'
      : '桌面客户端的安装文件不完整。请重新安装，或在浏览器中使用 Web 版。',
    buttons: choices.map(choice => labels[choice]),
    defaultId: choices.indexOf('retry'),
    cancelId: choices.indexOf('quit'),
    noLink: true,
  })
  const choice = choices[response] ?? 'quit'
  if (choice === 'open-web' && webOrigin) {
    await dependencies.openExternal(webOrigin).catch(() => undefined)
  }
  if (choice === 'retry') {
    dependencies.relaunch()
    dependencies.exit(0)
  } else {
    dependencies.exit(1)
  }
  return choice
}
