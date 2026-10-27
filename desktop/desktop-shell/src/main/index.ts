import { TimerWindowController } from './timer-window'
import { TimerPreferenceStore } from './timer-preferences'
import { InboxNotifier, InboxPreferenceStore } from './inbox-notifier'
import { applicationIcon } from './application-icon'
import path from 'node:path'
import { app, BrowserWindow, crashReporter, dialog, ipcMain, nativeTheme, net, powerMonitor, protocol, safeStorage, session, shell, WebContentsView } from 'electron'
import { autoUpdater } from 'electron-updater'
import { createHash } from 'node:crypto'
import { createReadStream, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { rm, writeFile } from 'node:fs/promises'
import type { DesktopAuthStatus, DesktopUpdateState, DiagnosticsStartupFailure } from '@yumpoo/preload-contract'
import { AUTH_STATUS_CHANNEL, installAuthIpc } from './auth-ipc'
import { installExportFileIpc } from './export-files'
import {
  DesktopAuthController,
  exchangeDesktopAuthCode,
  requestDesktopAuthorization,
} from './desktop-auth'
import {
  clearSessionCookies,
  DesktopCredentialStore,
  installSessionCookies,
} from './credential-store'
import {
  ProtocolLaunchDispatcher,
  registerYumpooProtocolClient,
} from './protocol-client'
import {
  installAppSecurityGuards,
  installSecurityGuards,
} from './security-guards'
import { collectBundleEntries, createZip } from './diagnostic-bundle'
import { collectDiagnostics } from './diagnostics'
import { createDiagnosticsActions, DIAGNOSTICS_ARGUMENT, SAFE_MODE_ARGUMENT } from './diagnostics-actions'
import { runtimeDiagnostics } from './diagnostics-runtime'
import {
  DiagnosticsWindow,
  LAUNCH_PAGE_URL,
  LOCAL_PAGES_PARTITION,
  prepareLocalPagesSession,
  registerDiagnosticsScheme,
} from './diagnostics-window'
import { loadDistribution } from './distribution'
import { attachLaunchOverlay, type LaunchOverlay } from './launch-overlay'
import { DesktopLogger } from './logger'
import { runSelfCheck, selfCheckReportPath, UPDATE_CHECK_ARGUMENT } from './self-check'
import { handleStartupFailure } from './startup-failure'
import { CRASH_LOOP_THRESHOLD, LaunchHealth, StartupStateStore } from './startup-state'
import { DesktopPreferenceStore } from './desktop-preferences'
import { DesktopSettingsController, HIDDEN_ARGUMENT } from './desktop-settings'
import { UpdateController, type FileDigest, type UpdaterLike } from './update-controller'
import { installClientHeaders, installUpdateIpc, UPDATE_STATE_CHANNEL } from './update-ipc'
import { resolveWebAppUrl } from './url-policy'
import { AUTHENTICATED_PARTITION, createWindowOptions } from './window-policy'

const SMOKE_TEST_ARGUMENT = '--smoke-test'
const SMOKE_TEST_TIMEOUT_MS = 20_000
const DIAGNOSTICS_PAGE_TIMEOUT_MS = 15_000
const ERR_ABORTED = -3
// Must equal electron-builder appId: the installer stamps it on the shortcuts that Windows notifications resolve.
const APP_USER_MODEL_ID = 'com.yumpoo.desktop'
const PREFERENCE_FILES = ['timer-preferences.json', 'inbox-preferences.json', 'desktop-preferences.json']
const LOCAL_PAGE_DIRECTORY = path.join(__dirname, '..', 'diagnostics')
const launchStartedAt = Date.now()
const smokeTest = process.argv.includes(SMOKE_TEST_ARGUMENT)
const selfCheckPath = app.isPackaged ? selfCheckReportPath(process.argv) : undefined
const updateCheckPath = app.isPackaged && !selfCheckPath ? selfCheckReportPath(process.argv, UPDATE_CHECK_ARGUMENT) : undefined
const UPDATE_CHECK_TIMEOUT_MS = 180_000
const userDataPath = app.getPath('userData')
/** The first call creates the partition, so every call passes the options: no HTTP cache on disk for business responses. */
const authenticatedSession = () => session.fromPartition(AUTHENTICATED_PARTITION, { cache: false })
const logger = new DesktopLogger(path.join(userDataPath, 'logs'))
const startupState = new StartupStateStore(userDataPath)
const launchHealth = new LaunchHealth(startupState)
const protocolDispatcher = new ProtocolLaunchDispatcher()
const desktopPreferences = new DesktopPreferenceStore(userDataPath)
// Start at sign-in passes this: the main window loads but stays hidden, leaving the tray and the rail.
const hiddenLaunch = process.argv.includes(HIDDEN_ARGUMENT)

class StartupError extends Error {
  constructor(readonly reason: DiagnosticsStartupFailure, cause: unknown) {
    super(cause instanceof Error ? cause.message : String(cause))
  }
}

let timerController: TimerWindowController | undefined
let inboxNotifier: InboxNotifier | undefined
let mainWindow: BrowserWindow | null = null
let webAppUrl: URL | undefined
let credentialStore: DesktopCredentialStore | undefined
let diagnosticsWindow: DiagnosticsWindow | undefined
let startupFailure: DiagnosticsStartupFailure | null = null
let certificateRejected = false
let mainLoaded = false
let startupComplete = false
let consecutiveFailures = 0
let crashLoop = false
let safeMode = false
let startupFailureShown = false
let updateController: UpdateController | undefined
let restartAfterUpdate = false
let launchOverlay: LaunchOverlay | undefined

function failStartup(reason: DiagnosticsStartupFailure, error?: unknown): void {
  if (timerController?.isQuitting) return
  logger.error('startup-failed', { reason, error: error instanceof Error ? error.message : undefined })
  console.error('[YUMPOO_DESKTOP_STARTUP_FAILED]', reason)
  if (smokeTest || !app.isReady()) {
    app.exit(1)
    return
  }
  if (!mainLoaded && mainWindow && !mainWindow.isDestroyed()) mainWindow.hide()
  launchOverlay?.dismiss()
  openDiagnostics(reason)
}

/** The diagnostics window replaces the bare startup dialog, which stays as the fallback if it cannot open. */
function openDiagnostics(reason?: DiagnosticsStartupFailure): void {
  if (timerController?.isQuitting) return
  if (reason) startupFailure = reason
  void diagnostics().open().catch(error => {
    logger.error('diagnostics-open-failed', { error: error instanceof Error ? error.message : undefined })
    if (startupFailureShown) return
    startupFailureShown = true
    void handleStartupFailure({
      showMessageBox: options => dialog.showMessageBox(options),
      openExternal: url => shell.openExternal(url, { activate: true }),
      relaunch: () => relaunchOrInstall(),
      exit: code => app.exit(code),
    }, webAppUrl?.origin).catch(() => app.exit(1))
  })
}

function diagnosticsOrigin(): string | null {
  if (webAppUrl) return webAppUrl.origin
  try {
    return app.isPackaged ? loadDistribution().origin : null
  } catch {
    return null
  }
}

function collectReport(offline: boolean) {
  return collectDiagnostics(runtimeDiagnostics({
    origin: diagnosticsOrigin(), safeMode, crashLoop, consecutiveFailures, startupFailure, offline,
    updateState: () => {
      const state = updateState()
      return {
        state: state.phase === 'disabled' ? 'unavailable' : state.phase,
        ...(state.version ? { version: state.version } : {}),
        ...(state.error ? { error: state.error } : {}),
      }
    },
  }))
}

function updateState(): DesktopUpdateState {
  return updateController?.getState() ?? { phase: 'disabled', currentVersion: app.getVersion(), channel: null }
}

function publishUpdateState(state: DesktopUpdateState): void {
  const window = mainWindow
  if (window && !window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send(UPDATE_STATE_CHANNEL, state)
  timerController?.setUpdate(state, { check: () => updateController?.check(), download: () => updateController?.download(), restart: restartForUpdate })
}

/** Goes through the normal quit, including the timer's exit confirmation; the verified installer runs in will-quit. */
function restartForUpdate(): void {
  restartAfterUpdate = true
  logger.info('update-restart-requested', { version: updateController?.readyVersion })
  app.quit()
}

function digestSync(file: string): FileDigest {
  const data = readFileSync(file)
  return { sha512: createHash('sha512').update(data).digest('base64'), size: data.byteLength }
}

function digestFile(file: string): Promise<FileDigest> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha512')
    let size = 0
    createReadStream(file)
      .on('data', chunk => { hash.update(chunk); size += chunk.length })
      .on('error', reject)
      .on('end', () => resolve({ sha512: hash.digest('base64'), size }))
  })
}

async function fetchAuthorization(url: string, signal: AbortSignal): Promise<unknown> {
  const response = await net.fetch(url, { credentials: 'omit', cache: 'no-store', redirect: 'error', signal })
  if (!response.ok) throw new Error(`HTTP_${response.status}`)
  const body = await response.arrayBuffer()
  if (body.byteLength > 64 * 1024) throw new Error('AUTHORIZATION_TOO_LARGE')
  return JSON.parse(Buffer.from(body).toString('utf8'))
}

/** Only packaged builds whose distribution names a feed and keys ever check; safe mode keeps updating so a bad version can be replaced. */
function startUpdates(schedule = true): void {
  if (!app.isPackaged || smokeTest || selfCheckPath || updateController) return
  const update = loadDistribution().update
  if (!update) return
  autoUpdater.logger = {
    info: (message?: unknown) => logger.info('updater', { detail: String(message) }),
    warn: (message?: unknown) => logger.warn('updater', { detail: String(message) }),
    error: (message?: unknown) => logger.error('updater', { detail: String(message) }),
    debug: () => undefined,
  }
  updateController = new UpdateController({
    updater: autoUpdater as unknown as UpdaterLike,
    update,
    currentVersion: app.getVersion(),
    // The headless update check always downloads; it verifies the whole update path.
    autoDownload: !schedule || desktopPreferences.get().autoDownload,
    fetchJson: fetchAuthorization,
    digest: digestFile,
    digestSync,
    removeFile: file => rm(file, { force: true }),
    log: (level, event, fields) => logger[level](event, fields),
    publish: publishUpdateState,
    now: () => Date.now(),
    random: () => Math.random(),
    schedule: (callback, delayMs) => { setTimeout(callback, delayMs).unref() },
  })
  if (!schedule) return
  desktopPreferences.onChange(value => updateController?.setAutoDownload(value.autoDownload))
  powerMonitor.on('resume', () => updateController?.onResume())
  updateController.start()
}

/**
 * Headless single check for the update end-to-end test: the same download, authorization check and
 * quit-time install as a normal launch, without windows, scheduling or the web app.
 */
async function runUpdateCheckNow(reportPath: string): Promise<void> {
  startUpdates(false)
  const controller = updateController
  const deadline = Date.now() + UPDATE_CHECK_TIMEOUT_MS
  let state = updateState()
  if (controller) {
    controller.check()
    let checked = false
    while (Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500))
      state = controller.getState()
      checked ||= state.phase !== 'idle'
      if (state.phase === 'ready' || state.phase === 'error' || (checked && state.phase === 'idle')) break
    }
  }
  const installStarted = state.phase === 'ready' && !!controller?.installOnQuit(false)
  writeFileSync(reportPath, `${JSON.stringify({ ...state, installStarted }, null, 2)}\n`, 'utf8')
  app.exit(installStarted ? 0 : 1)
}

function relaunch(args: string[]): void {
  logger.info('relaunch', { safeMode: args.includes(SAFE_MODE_ARGUMENT) })
  relaunchOrInstall({ args })
  app.exit(0)
}

/**
 * app.exit skips will-quit, where a ready update normally installs, so restarts from diagnostics install it
 * here instead and leave starting the new version to the installer; the old version's arguments are dropped.
 */
function relaunchOrInstall(options?: Electron.RelaunchOptions): void {
  if (updateController?.installOnQuit(true)) return
  app.relaunch(options)
}

async function signOut(): Promise<void> {
  inboxNotifier?.reset()
  await (credentialStore ?? new DesktopCredentialStore(userDataPath, safeStorage)).clear()
  const origin = diagnosticsOrigin()
  if (origin) await clearSessionCookies(authenticatedSession().cookies, origin)
  if (mainWindow && !mainWindow.isDestroyed() && webAppUrl) void mainWindow.loadURL(webAppUrl.href).catch(() => undefined)
}

function diagnostics(offline = false): DiagnosticsWindow {
  if (diagnosticsWindow) return diagnosticsWindow
  diagnosticsWindow = new DiagnosticsWindow({
    pageDirectory: LOCAL_PAGE_DIRECTORY,
    preloadPath: path.join(__dirname, '..', 'preload', 'diagnostics.js'),
    icon: applicationIcon,
    isPackaged: app.isPackaged,
    collect: () => collectReport(offline),
    // Without a loaded main window nothing else is visible; closing diagnostics then ends the launch.
    onClosed: () => { if (!mainLoaded && !selfCheckPath) app.quit() },
    onAction: (action, result) => logger.info('diagnostics-action', {
      action, completed: result instanceof Error ? false : result.completed, error: result instanceof Error ? result.message : undefined,
    }),
    actions: createDiagnosticsActions({
      origin: diagnosticsOrigin,
      processArguments: () => process.argv,
      relaunch,
      resetStartupState: () => startupState.markHealthy(),
      openExternal: url => shell.openExternal(url, { activate: true }),
      confirm: async (window, message, detail, confirmLabel) => (await dialog.showMessageBox(window, {
        type: 'warning', message, detail, buttons: [confirmLabel, '取消'], defaultId: 1, cancelId: 1, noLink: true,
      })).response === 0,
      removePreferences: async () => {
        for (const name of PREFERENCE_FILES) rmSync(path.join(userDataPath, name), { force: true })
        await authenticatedSession().clearStorageData({ storages: ['localstorage'] })
      },
      signOut,
      registerProtocol: () => registerYumpooProtocolClient(app, {
        isPackaged: app.isPackaged, executablePath: process.execPath, processArguments: process.argv,
      }),
      openLogDirectory: () => {
        mkdirSync(logger.logDirectory, { recursive: true })
        return shell.openPath(logger.logDirectory)
      },
      chooseExportPath: async (window, defaultName) => {
        const result = await dialog.showSaveDialog(window, {
          defaultPath: path.join(app.getPath('downloads'), defaultName),
          filters: [{ name: 'ZIP 压缩包', extensions: ['zip'] }],
        })
        return result.canceled ? undefined : result.filePath
      },
      buildBundle: async () => createZip(collectBundleEntries({
        logDirectory: logger.logDirectory,
        crashDirectory: app.getPath('crashDumps'),
        reportJson: `${JSON.stringify(await collectReport(false), null, 2)}\n`,
      })),
      writeFile: (filePath, data) => writeFile(filePath, data),
      now: () => new Date(),
    }),
  })
  diagnosticsWindow.install(ipcMain)
  return diagnosticsWindow
}

async function checkDiagnosticsPage(): Promise<boolean> {
  const window = await diagnostics(true).open(false)
  const deadline = Date.now() + DIAGNOSTICS_PAGE_TIMEOUT_MS
  try {
    while (Date.now() < deadline) {
      const state = await window.webContents.executeJavaScript('document.documentElement.dataset.state', true) as unknown
      if (state === 'ready') return true
      if (state === 'error') return false
      await new Promise(resolve => setTimeout(resolve, 200))
    }
    return false
  } finally {
    window.destroy()
  }
}

function electronAuthEnabled(): boolean {
  const configured = process.env.YUMPOO_ELECTRON_AUTH_ENABLED?.trim().toLowerCase()
  return configured === undefined ? app.isPackaged : configured === 'true'
}

async function clearDesktopSession(): Promise<void> {
  inboxNotifier?.reset()
  await credentialStore?.clear()
  if (mainWindow && !mainWindow.isDestroyed() && webAppUrl) {
    await clearSessionCookies(mainWindow.webContents.session.cookies, webAppUrl.origin)
  }
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

/** Shows the window at once with the packaged launch screen on top, instead of a blank window until the web app paints. */
function showWithLaunchOverlay(window: BrowserWindow): void {
  if (!mainLoaded) {
    prepareLocalPagesSession(LOCAL_PAGE_DIRECTORY)
    launchOverlay = attachLaunchOverlay(window, {
      pageUrl: LAUNCH_PAGE_URL,
      createView: () => {
        const view = new WebContentsView({
          webPreferences: {
            contextIsolation: true,
            sandbox: true,
            nodeIntegration: false,
            webSecurity: true,
            webviewTag: false,
            allowRunningInsecureContent: false,
            devTools: !app.isPackaged,
            partition: LOCAL_PAGES_PARTITION,
            spellcheck: false,
          },
        })
        view.setBackgroundColor(nativeTheme.shouldUseDarkColors ? '#202128' : '#ffffff')
        return view
      },
    })
  }
  window.show()
}

async function createMainWindow(): Promise<void> {
  if (timerController?.isQuitting) return
  if (!webAppUrl) {
    throw new Error('Web application URL is not initialized')
  }
  const preloadPath = path.join(__dirname, '..', 'preload', 'index.js')

  mainWindow = new BrowserWindow({
    ...createWindowOptions(preloadPath, app.isPackaged),
    icon: applicationIcon(),
  })
  installSecurityGuards(mainWindow.webContents, webAppUrl.origin)
  // Safe mode keeps only the main window: the timer surfaces and inbox alerts are the optional extras.
  if (!smokeTest && !safeMode) {
    timerController ??= new TimerWindowController(() => mainWindow, webAppUrl.origin, preloadPath,
      new TimerPreferenceStore(userDataPath), () => openDiagnostics(), desktopPreferences)
    timerController.onExitCancelled(() => { restartAfterUpdate = false })
    timerController.install()
    timerController.attachMain(mainWindow)
    inboxNotifier ??= new InboxNotifier(() => mainWindow, webAppUrl.origin, timerController, new InboxPreferenceStore(userDataPath))
    inboxNotifier.install()
    publishUpdateState(updateState())
  }
  const window = mainWindow
  const restored = await credentialStore?.load(webAppUrl.origin)
  if (timerController?.isQuitting || window.isDestroyed()) return
  if (restored) {
    await installSessionCookies(window.webContents.session.cookies, webAppUrl.origin, restored)
  }

  if (timerController?.isQuitting || window.isDestroyed()) return
  if (smokeTest) {
    const timeout = setTimeout(() => failStartup('LOAD_FAILED'), SMOKE_TEST_TIMEOUT_MS)
    mainWindow.webContents.once('did-finish-load', () => {
      void verifySmokeRendererIsolation(mainWindow as BrowserWindow)
        .then(() => {
          clearTimeout(timeout)
          app.exit(0)
        })
        .catch(error => {
          clearTimeout(timeout)
          failStartup('STARTUP_ERROR', error)
        })
    })
    mainWindow.webContents.once(
      'did-fail-load',
      (_event, _errorCode, _errorDescription, _validatedUrl, isMainFrame) => {
        if (isMainFrame !== false) {
          clearTimeout(timeout)
          failStartup('LOAD_FAILED')
        }
      },
    )
  } else if (!hiddenLaunch) {
    showWithLaunchOverlay(mainWindow)
  }

  window.webContents.on('did-finish-load', () => {
    if (mainLoaded || timerController?.isQuitting) return
    mainLoaded = true
    logger.info('main-loaded', { safeMode, elapsedMs: Date.now() - launchStartedAt })
    // A launch only counts as healthy after surviving a while; crashes right after load still count.
    launchHealth.loaded()
  })
  window.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedUrl, isMainFrame) => {
    if (!isMainFrame || errorCode === ERR_ABORTED) return
    logger.warn('main-load-failed', { errorCode, errorDescription, url: validatedUrl })
    // During startup loadURL rejects and failStartup takes over; afterwards the shell stays up and offers diagnostics.
    if (startupComplete) openDiagnostics(certificateRejected ? 'CERTIFICATE_ERROR' : 'LOAD_FAILED')
  })
  window.on('closed', () => {
    inboxNotifier?.reset()
    if (mainWindow === window) mainWindow = null
  })
  window.webContents.on('render-process-gone', (_event, details) => {
    inboxNotifier?.reset()
    logger.error('renderer-gone', { reason: details.reason, exitCode: details.exitCode })
    if (details.reason === 'clean-exit' || timerController?.isQuitting) return
    launchHealth.failed()
    openDiagnostics('RENDERER_GONE')
  })

  try {
    await window.loadURL(webAppUrl.href)
  } catch (error) {
    throw new StartupError(certificateRejected ? 'CERTIFICATE_ERROR' : 'LOAD_FAILED', error)
  }
  startupComplete = true
}

async function startApplication(): Promise<void> {
  let distributionOrigin: string | undefined
  try {
    distributionOrigin = app.isPackaged ? loadDistribution().origin : undefined
  } catch (error) {
    throw new StartupError('DISTRIBUTION_INVALID', error)
  }
  webAppUrl = resolveWebAppUrl({
    configuredUrl: process.env.YUMPOO_WEB_URL,
    isPackaged: app.isPackaged,
    distributionOrigin,
  })

  // The installer already registered the protocol; refreshing it here only helps unpacked development runs.
  if (
    !smokeTest &&
    !registerYumpooProtocolClient(app, {
      isPackaged: app.isPackaged,
      executablePath: process.execPath,
      processArguments: process.argv,
    })
  ) {
    logger.warn('protocol-registration-failed')
  }

  app.on('certificate-error', (_event, _webContents, url, error) => {
    certificateRejected = true
    logger.warn('certificate-rejected', { url, error })
  })
  installAppSecurityGuards(app)
  installClientHeaders(authenticatedSession(), webAppUrl.origin, app.getVersion())
  credentialStore = new DesktopCredentialStore(userDataPath, safeStorage)
  // Chromium's stack, like the main window: same system proxy, Windows certificate store and no cookies.
  const authRequest = {
    webOrigin: webAppUrl.origin,
    clientVersion: app.getVersion(),
    fetchImplementation: (url: string, init: RequestInit) => net.fetch(url, init),
  }
  const controller = new DesktopAuthController({
    enabled: electronAuthEnabled(),
    authorize: (attempt, remember) => requestDesktopAuthorization(attempt, remember, authRequest),
    openExternal: (url) => shell.openExternal(url, { activate: true }),
    logFailure: (stage, reason) => logger.warn('desktop-auth-failed', { stage, reason }),
    exchange: async (input, remember) => {
      const bundle = await exchangeDesktopAuthCode(input, authRequest)
      // Without the 7-day sign-in the next launch starts signed out: the session lives only in this run's cookies.
      if (remember) await credentialStore?.save(bundle, webAppUrl?.origin ?? '')
      else await credentialStore?.clear()
      if (!mainWindow || mainWindow.isDestroyed() || !webAppUrl) {
        throw new Error('Main window is unavailable')
      }
      await installSessionCookies(mainWindow.webContents.session.cookies, webAppUrl.origin, bundle)
      await mainWindow.loadURL(webAppUrl.href)
    },
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
    clearSession: clearDesktopSession,
  })
  installExportFileIpc({
    ipcMain,
    dialog,
    getMainWindow: () => mainWindow,
    allowedOrigin: webAppUrl.origin,
    downloadsPath: () => app.getPath('downloads'),
    writeFile: (filePath, data) => writeFile(filePath, data),
  })
  installUpdateIpc({
    ipcMain,
    getMainWindow: () => mainWindow,
    allowedOrigin: webAppUrl.origin,
    getState: updateState,
    check: () => updateController?.check(),
    download: () => updateController?.download(),
    restart: restartForUpdate,
  })
  // Safe mode has no quick panel or rail, so the settings bridge and its global shortcut stay off with them.
  if (!smokeTest && !safeMode) {
    new DesktopSettingsController({
      store: desktopPreferences,
      main: () => mainWindow,
      origin: webAppUrl.origin,
      toggleQuick: () => timerController?.toggleQuick('shortcut'),
    }).install()
  }
  startUpdates()
  if (safeMode) openDiagnostics()
  await createMainWindow()
}

// Dumps stay on this machine; the person exports them with the diagnostics bundle.
crashReporter.start({ uploadToServer: false })
registerDiagnosticsScheme(protocol)

if (app.isPackaged) {
  app.setAppUserModelId(APP_USER_MODEL_ID)
}

if (updateCheckPath) {
  app.disableHardwareAcceleration()
  void app.whenReady().then(() => runUpdateCheckNow(updateCheckPath)).catch(() => app.exit(1))
} else if (selfCheckPath) {
  app.disableHardwareAcceleration()
  void app.whenReady().then(async () => {
    const report = await runSelfCheck({
      isPackaged: app.isPackaged,
      version: app.getVersion(),
      electronVersion: process.versions.electron,
      loadDistribution: () => loadDistribution(),
      isDefaultProtocolClient: protocol => app.isDefaultProtocolClient(protocol),
      isEncryptionAvailable: () => safeStorage.isEncryptionAvailable(),
      checkDiagnosticsPage,
    })
    writeFileSync(selfCheckPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
    app.exit(report.status === 'PASS' ? 0 : 1)
  }).catch(() => app.exit(1))
} else if (!app.requestSingleInstanceLock()) {
  if (smokeTest) {
    app.exit(1)
  } else {
    app.quit()
  }
} else {
  if (!smokeTest) {
    consecutiveFailures = launchHealth.begin()
    crashLoop = consecutiveFailures >= CRASH_LOOP_THRESHOLD
    safeMode = crashLoop || process.argv.includes(SAFE_MODE_ARGUMENT)
  }
  if (smokeTest || safeMode) {
    app.disableHardwareAcceleration()
  }
  logger.info('startup', {
    version: app.getVersion(), electron: process.versions.electron, packaged: app.isPackaged, safeMode, crashLoop, consecutiveFailures,
  })

  protocolDispatcher.dispatch(process.argv)
  app.on('second-instance', (_event, processArguments) => {
    if (timerController?.isQuitting) return
    protocolDispatcher.dispatch(processArguments)
    if (processArguments.includes(SAFE_MODE_ARGUMENT) || processArguments.includes(DIAGNOSTICS_ARGUMENT)) {
      openDiagnostics()
      return
    }
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore()
      }
      mainWindow.show()
      mainWindow.focus()
    }
  })
  app.on('child-process-gone', (_event, details) => {
    logger.warn('child-process-gone', { type: details.type, reason: details.reason, exitCode: details.exitCode })
  })
  app.on('before-quit', () => launchHealth.quitting())
  app.on('will-quit', () => {
    // Every orderly quit installs a verified update silently; "restart to update" also relaunches afterwards.
    if (updateController?.readyVersion) updateController.installOnQuit(restartAfterUpdate)
  })

  void app.whenReady().then(startApplication).catch(error => {
    failStartup(error instanceof StartupError ? error.reason : 'STARTUP_ERROR', error)
  })

  app.on('activate', () => {
    if (timerController?.isQuitting) return
    if (BrowserWindow.getAllWindows().length === 0) {
      void createMainWindow().catch(error => failStartup(error instanceof StartupError ? error.reason : 'STARTUP_ERROR', error))
    } else timerController?.showMain()
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit()
    }
  })
}
