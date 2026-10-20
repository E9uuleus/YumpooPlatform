import type { App, WebContents } from 'electron'

const securedSessions = new WeakSet<object>()
/** navigator.clipboard.writeText; reading the clipboard stays denied. */
const CLIPBOARD_WRITE = 'clipboard-sanitized-write'

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
    browserSession.setPermissionCheckHandler(
      (_webContents, permission, requestingOrigin) =>
        permission === CLIPBOARD_WRITE && hasSameOrigin(requestingOrigin, allowedOrigin),
    )
    browserSession.setPermissionRequestHandler(
      (_requestingWebContents, permission, callback, details) =>
        callback(permission === CLIPBOARD_WRITE && hasSameOrigin(details.requestingUrl, allowedOrigin)),
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
