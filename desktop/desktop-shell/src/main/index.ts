import path from 'node:path'
import { app, BrowserWindow } from 'electron'
import { installSecurityGuards } from './security-guards'
import { resolveWebAppUrl } from './url-policy'
import { createWindowOptions } from './window-policy'

const SMOKE_TEST_ARGUMENT = '--smoke-test'
const SMOKE_TEST_TIMEOUT_MS = 20_000

let mainWindow: BrowserWindow | null = null

function failStartup(): never {
  console.error('[YUMPOO_DESKTOP_STARTUP_FAILED]')
  app.exit(1)
  throw new Error('Electron startup failed')
}

async function createMainWindow(): Promise<void> {
  const smokeTest = process.argv.includes(SMOKE_TEST_ARGUMENT)
  const webUrl = resolveWebAppUrl({
    configuredUrl: process.env.YUMPOO_WEB_URL,
    isPackaged: app.isPackaged,
  })
  const preloadPath = path.join(__dirname, '..', 'preload', 'index.js')

  mainWindow = new BrowserWindow(createWindowOptions(preloadPath))
  installSecurityGuards(mainWindow.webContents, webUrl.origin)

  if (smokeTest) {
    const timeout = setTimeout(failStartup, SMOKE_TEST_TIMEOUT_MS)
    mainWindow.webContents.once('did-finish-load', () => {
      clearTimeout(timeout)
      app.exit(0)
    })
    mainWindow.webContents.once(
      'did-fail-load',
      (_event, _errorCode, _errorDescription, _validatedUrl, isMainFrame) => {
        if (isMainFrame !== false) {
          clearTimeout(timeout)
          failStartup()
        }
      },
    )
  } else {
    mainWindow.once('ready-to-show', () => mainWindow?.show())
  }

  mainWindow.on('closed', () => {
    mainWindow = null
  })

  await mainWindow.loadURL(webUrl.href)
}

if (process.argv.includes(SMOKE_TEST_ARGUMENT)) {
  app.disableHardwareAcceleration()
}

void app.whenReady().then(createMainWindow).catch(failStartup)

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    void createMainWindow().catch(failStartup)
  }
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
