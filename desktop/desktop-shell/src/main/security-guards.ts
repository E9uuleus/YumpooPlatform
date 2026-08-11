import type { App, WebContents } from 'electron'

const securedSessions = new WeakSet<object>()

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

  webContents.on('will-redirect', (event, navigationUrl) => {
    if (!hasSameOrigin(navigationUrl, allowedOrigin)) {
      event.preventDefault()
    }
  })

  webContents.on('will-attach-webview', (event) => {
    event.preventDefault()
  })

  const browserSession = webContents.session
  if (!securedSessions.has(browserSession)) {
    securedSessions.add(browserSession)
    browserSession.setPermissionCheckHandler(() => false)
    browserSession.setPermissionRequestHandler(
      (_requestingWebContents, _permission, callback) => callback(false),
    )
    browserSession.on('will-download', (event) => {
      event.preventDefault()
    })
  }
}

export function installAppSecurityGuards(application: App): void {
  application.on('certificate-error', (event, _webContents, _url, _error, _certificate, callback) => {
    event.preventDefault()
    callback(false)
  })
}
