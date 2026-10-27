import path from 'node:path'
import { app, type App, type WebContents } from 'electron'

const securedSessions = new WeakSet<object>()
/** navigator.clipboard.writeText; reading the clipboard stays denied. */
const CLIPBOARD_WRITE = 'clipboard-sanitized-write'
const ATTACHMENT_CONTENT = /^\/api\/v1\/attachments\/[0-9a-f-]{36}\/content$/u

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
    // Only attachment content of the web app may be saved, and only where the person picks in the save dialog.
    browserSession.on('will-download', (event, item) => {
      const url = new URL(item.getURL())
      if (url.origin !== allowedOrigin || !ATTACHMENT_CONTENT.test(url.pathname)) {
        event.preventDefault()
        return
      }
      item.setSaveDialogOptions({ defaultPath: path.join(app.getPath('downloads'), item.getFilename()) })
    })
  }
}

export function installAppSecurityGuards(application: App): void {
  application.on('certificate-error', (event, _webContents, _url, _error, _certificate, callback) => {
    event.preventDefault()
    callback(false)
  })
}
