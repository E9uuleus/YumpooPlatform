import type { BrowserWindowConstructorOptions } from 'electron'

export function createWindowOptions(
  preloadPath: string,
  isPackaged = false,
): BrowserWindowConstructorOptions {
  return {
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    autoHideMenuBar: true,
    backgroundColor: '#f4f6f8',
    show: false,
    useContentSize: true,
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      webviewTag: false,
      allowRunningInsecureContent: false,
      devTools: !isPackaged,
      partition: 'yumpoo-authenticated',
    },
  }
}
