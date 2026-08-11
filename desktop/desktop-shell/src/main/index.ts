import path from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import type { DesktopAuthStatus } from '@yumpoo/preload-contract'
import { AUTH_STATUS_CHANNEL, installAuthIpc } from './auth-ipc'
import {
  DesktopAuthController,
  exchangeDesktopAuthCode,
} from './desktop-auth'
import {
  ProtocolLaunchDispatcher,
  registerYumpooProtocolClient,
} from './protocol-client'
import {
  installAppSecurityGuards,
  installSecurityGuards,
} from './security-guards'
import { resolveWebAppUrl } from './url-policy'
import { createWindowOptions } from './window-policy'

const SMOKE_TEST_ARGUMENT = '--smoke-test'
const SMOKE_TEST_TIMEOUT_MS = 20_000
const protocolDispatcher = new ProtocolLaunchDispatcher()

let mainWindow: BrowserWindow | null = null
let webAppUrl: URL | undefined

function failStartup(): never {
  console.error('[YUMPOO_DESKTOP_STARTUP_FAILED]')
  app.exit(1)
  throw new Error('Electron startup failed')
}

function electronAuthEnabled(): boolean {
  return process.env.YUMPOO_M015_WECOM_ENABLED?.trim().toLowerCase() === 'true'
}

function publishAuthStatus(authStatus: DesktopAuthStatus): void {
  const window = mainWindow
  if (!window || window.isDestroyed() || window.webContents.isDestroyed()) {
    return
  }
  window.webContents.send(AUTH_STATUS_CHANNEL, authStatus)
}

async function verifySmokeRendererIsolation(window: BrowserWindow): Promise<void> {
  const result = (await window.webContents.executeJavaScript(
    `({
      processType: typeof globalThis.process,
      requireType: typeof globalThis.require,
      bridgeClient: globalThis.yumpooDesktop?.client,
      authStartType: typeof globalThis.yumpooDesktop?.auth?.start
    })`,
    true,
  )) as {
    processType?: string
    requireType?: string
    bridgeClient?: string
    authStartType?: string
  }
  if (
    result.processType !== 'undefined' ||
    result.requireType !== 'undefined' ||
    result.bridgeClient !== 'electron' ||
    result.authStartType !== 'function'
  ) {
    throw new Error('Renderer isolation verification failed')
  }
}

async function createMainWindow(): Promise<void> {
  if (!webAppUrl) {
    throw new Error('Web application URL is not initialized')
  }
  const smokeTest = process.argv.includes(SMOKE_TEST_ARGUMENT)
  const preloadPath = path.join(__dirname, '..', 'preload', 'index.js')

  mainWindow = new BrowserWindow(createWindowOptions(preloadPath, app.isPackaged))
  installSecurityGuards(mainWindow.webContents, webAppUrl.origin)

  if (smokeTest) {
    const timeout = setTimeout(failStartup, SMOKE_TEST_TIMEOUT_MS)
    mainWindow.webContents.once('did-finish-load', () => {
      void verifySmokeRendererIsolation(mainWindow as BrowserWindow)
        .then(() => {
          clearTimeout(timeout)
          app.exit(0)
        })
        .catch(() => {
          clearTimeout(timeout)
          failStartup()
        })
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

  await mainWindow.loadURL(webAppUrl.href)
}

async function startApplication(): Promise<void> {
  webAppUrl = resolveWebAppUrl({
    configuredUrl: process.env.YUMPOO_WEB_URL,
    isPackaged: app.isPackaged,
  })

  if (
    !process.argv.includes(SMOKE_TEST_ARGUMENT) &&
    !registerYumpooProtocolClient(app, {
      isPackaged: app.isPackaged,
      executablePath: process.execPath,
      processArguments: process.argv,
    })
  ) {
    throw new Error('Unable to register yumpoo protocol')
  }

  installAppSecurityGuards(app)
  const controller = new DesktopAuthController({
    enabled: electronAuthEnabled(),
    webOrigin: webAppUrl.origin,
    openExternal: (url) => shell.openExternal(url, { activate: true }),
    exchange: (input) =>
      exchangeDesktopAuthCode(input, {
        webOrigin: webAppUrl?.origin ?? '',
        clientVersion: app.getVersion(),
      }),
    publishStatus: publishAuthStatus,
  })
  protocolDispatcher.setHandler((callback) => {
    void controller.handleCallback(callback)
  })
  installAuthIpc({
    ipcMain,
    getMainWindow: () => mainWindow,
    allowedOrigin: webAppUrl.origin,
    controller,
  })
  await createMainWindow()
}

if (process.argv.includes(SMOKE_TEST_ARGUMENT)) {
  app.disableHardwareAcceleration()
}

const ownsSingleInstance = app.requestSingleInstanceLock()
if (!ownsSingleInstance) {
  if (process.argv.includes(SMOKE_TEST_ARGUMENT)) {
    app.exit(1)
  } else {
    app.quit()
  }
} else {
  protocolDispatcher.dispatch(process.argv)
  app.on('second-instance', (_event, processArguments) => {
    protocolDispatcher.dispatch(processArguments)
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore()
      }
      mainWindow.show()
      mainWindow.focus()
    }
  })

  void app.whenReady().then(startApplication).catch(failStartup)

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
}
