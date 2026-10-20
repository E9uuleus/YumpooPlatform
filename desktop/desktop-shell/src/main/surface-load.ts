import type { BrowserWindow, Event } from 'electron'

/**
 * Loads a shell-only page into its window. An HTTP error page, such as nginx's 404, still finishes loading and would be
 * shown as the page itself, so it rejects like a network failure and the caller's fallback takes over.
 */
export async function loadSurface(window: BrowserWindow, url: string): Promise<void> {
  let status = 0
  const navigated = (_event: Event, _url: string, httpResponseCode: number) => { status = httpResponseCode }
  window.webContents.once('did-navigate', navigated)
  try {
    await window.loadURL(url)
  } finally {
    window.webContents.removeListener('did-navigate', navigated)
  }
  if (status >= 400) throw new Error(`HTTP ${status}`)
}
