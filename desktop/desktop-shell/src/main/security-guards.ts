import type { WebContents } from 'electron'

function hasSameOrigin(candidate: string, allowedOrigin: string): boolean {
  try {
    return new URL(candidate).origin === allowedOrigin
  } catch {
    return false
  }
}

export function installSecurityGuards(
  webContents: WebContents,
  allowedOrigin: string,
): void {
  webContents.setWindowOpenHandler(() => ({ action: 'deny' }))

  webContents.on('will-navigate', (event, navigationUrl) => {
    if (!hasSameOrigin(navigationUrl, allowedOrigin)) {
      event.preventDefault()
    }
  })

  webContents.on('will-attach-webview', (event) => {
    event.preventDefault()
  })

  webContents.session.setPermissionRequestHandler(
    (_requestingWebContents, _permission, callback) => callback(false),
  )
}
