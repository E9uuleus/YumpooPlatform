import type { BrowserWindowConstructorOptions } from 'electron'

/**
 * Persistent so the web app's local preferences survive restarts. The HTTP cache is off (see index.ts) and the
 * session cookies carry no expiry, so credentials persist only in the safeStorage store and are re-injected at launch.
 */
export const AUTHENTICATED_PARTITION = 'persist:yumpoo-authenticated'

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
      partition: AUTHENTICATED_PARTITION,
    },
  }
}
