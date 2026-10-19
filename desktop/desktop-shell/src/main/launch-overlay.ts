import type { BrowserWindow, WebContentsView } from 'electron'

export interface LaunchOverlayOptions {
  /** Creates the overlay view on the local-pages partition, without a preload. */
  readonly createView: () => WebContentsView
  readonly pageUrl: string
}

export interface LaunchOverlay {
  /** Removes the overlay; safe to call more than once. */
  dismiss(): void
}

/**
 * Covers the main window with the packaged launch screen so the window can be shown at once, instead of
 * appearing blank until the remote web app paints. The overlay leaves on the main frame's first finished
 * load; a failed start hides the window and opens diagnostics, which dismiss it as well.
 */
export function attachLaunchOverlay(window: BrowserWindow, options: LaunchOverlayOptions): LaunchOverlay {
  const view = options.createView()
  const mainContents = window.webContents
  let attached = true
  const fit = () => {
    const { width, height } = window.getContentBounds()
    view.setBounds({ x: 0, y: 0, width, height })
  }
  const dismiss = () => {
    if (!attached) return
    attached = false
    window.off('resize', fit)
    mainContents.off('did-finish-load', dismiss)
    if (!window.isDestroyed()) window.contentView.removeChildView(view)
    if (!view.webContents.isDestroyed()) view.webContents.close()
  }
  view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  view.webContents.on('will-navigate', event => event.preventDefault())
  window.contentView.addChildView(view)
  fit()
  window.on('resize', fit)
  mainContents.once('did-finish-load', dismiss)
  window.once('closed', dismiss)
  void view.webContents.loadURL(options.pageUrl).catch(() => undefined)
  return { dismiss }
}
