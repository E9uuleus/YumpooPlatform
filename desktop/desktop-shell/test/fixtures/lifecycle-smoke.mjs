import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { app, BrowserWindow, dialog, ipcMain, protocol } from 'electron'
import { TimerWindowController } from '../../src/main/timer-window'
import { DesktopPreferenceStore } from '../../src/main/desktop-preferences'
import { TimerPreferenceStore } from '../../src/main/timer-preferences'
import { registerDiagnosticsScheme, MENU_PAGE_URL } from '../../src/main/diagnostics-window'
import { createWindowOptions } from '../../src/main/window-policy'

async function run() {
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
  async function until(check) {
    const deadline = Date.now() + 10_000
    while (!await check()) {
      if (Date.now() > deadline) throw new Error('Lifecycle smoke readiness timeout')
      await delay(50)
    }
  }
  app.setPath('userData', process.env.YUMPOO_SMOKE_PROFILE)
  app.disableHardwareAcceleration()
  registerDiagnosticsScheme(protocol)
  app.on('window-all-closed', () => {})
  let approved = false
  app.on('browser-window-created', () => assert.equal(approved, false, 'Window created after exit approval'))
  process.on('uncaughtException', error => { console.error(error); app.exit(1) })
  const timeout = setTimeout(() => { console.error('Lifecycle smoke timed out'); app.exit(1) }, 25_000)

  await app.whenReady()
  try {
    const origin = process.env.YUMPOO_SMOKE_ORIGIN
    const preload = path.join(__dirname, '../preload/index.js')
    const prefs = new DesktopPreferenceStore()
    prefs.update({ railMode: 'always', menuAppearance: { theme: 'night', density: 'comfortable' } })
    const timerPrefs = new TimerPreferenceStore()
    timerPrefs.update({ display: 'dock', quickPinned: true })
    const main = new BrowserWindow(createWindowOptions(preload, false))
    const controller = new TimerWindowController(() => main, origin, preload, timerPrefs, () => {}, prefs)
    controller.install(); controller.attachMain(main)
    ipcMain.handle('yumpoo:inbox:get-preferences', () => ({ toasts: true }))
    controller.setUpdate({ phase: 'idle', currentVersion: '1.1.2' }, { check() {}, download() {}, restart() {} })
    await main.loadURL(origin)
    const menu = BrowserWindow.getAllWindows().find(window => window.webContents.getURL() === MENU_PAGE_URL)
    assert.ok(menu, 'Packaged menu window exists')
    await until(() => menu.webContents.executeJavaScript('!!document.querySelector(".menu-card")'))
    assert.equal(await menu.webContents.executeJavaScript('typeof window.yumpooDesktop'), 'undefined')
    assert.equal(await menu.webContents.executeJavaScript('document.documentElement.dataset.theme'), 'night')
    controller.menu.open('tray', controller.menuState())
    await until(() => menu.isVisible())
    assert.match(await menu.webContents.executeJavaScript('document.body.innerText'), /启动中/)
    console.log('Capturing startup menu')
    await fs.writeFile(path.join(process.env.YUMPOO_SMOKE_OUTPUT, 'menu-startup.png'), (await menu.webContents.capturePage(undefined, { stayHidden: true, stayAwake: true })).toPNG())
    if (process.env.YUMPOO_SMOKE_SCENARIO === 'startup') {
      dialog.showMessageBox = async () => ({ response: 1, checkboxChecked: false })
    } else {
      const state = { accountId: '11111111-1111-4111-8111-111111111111', rowVersion: 1, connected: false, busy: false, savedAt: 0, running: null, recent: null, timerEnabled: true }
      await main.webContents.executeJavaScript(`window.yumpooDesktop.timer.publishState(${JSON.stringify(state)})`)
      await until(() => BrowserWindow.getAllWindows().some(window => window.webContents.getURL().endsWith('/timer/rail')))
      await until(() => BrowserWindow.getAllWindows().some(window => window.webContents.getURL().endsWith('/timer/quick')))
      await until(() => controller.quick.ready && controller.rail.ready)
      controller.toggleQuick('tray')
      await delay(250)
      for (const theme of ['light', 'dark', 'night']) {
        console.log(`Capturing offline ${theme} menu`)
        prefs.update({ menuAppearance: { theme, density: 'comfortable' } })
        controller.menu.open('pointer', controller.menuState())
        await until(() => menu.webContents.executeJavaScript(`document.documentElement.dataset.theme === '${theme}'`))
        await delay(100)
        assert.ok(menu.isVisible(), 'Menu must remain visible for screenshot')
        const text = await menu.webContents.executeJavaScript('document.body.innerText')
        assert.match(text, /等待连接/); assert.match(text, /诊断/); assert.match(text, /退出/)
        await fs.writeFile(path.join(process.env.YUMPOO_SMOKE_OUTPUT, `menu-offline-${theme}.png`), (await menu.webContents.capturePage(undefined, { stayHidden: true, stayAwake: true })).toPNG())
      }
      await main.webContents.executeJavaScript(`window.yumpooDesktop.timer.onExitRequest(async id => {
        await window.yumpooDesktop.timer.acknowledgeExit(id);
        await window.yumpooDesktop.timer.reportExitPhase(id, 'prompt');
        await window.yumpooDesktop.timer.completeExit(id, true);
      }); undefined`)
    }
    // Wrap only the approval observation; all production shutdown and close callbacks still run.
    const shutdown = controller.shutdown.bind(controller)
    controller.shutdown = () => {
      approved = true
      console.log('YUMPOO_EXIT_APPROVED ' + JSON.stringify({ at: Date.now(), pids: app.getAppMetrics().map(metric => metric.pid) }))
      shutdown()
      assert.equal(BrowserWindow.getAllWindows().length, 1, 'Only main survives surface shutdown')
      controller.rail.sync(); controller.quick.prewarm(); controller.menu.prewarm(); controller.toggleQuick('tray')
      assert.equal(BrowserWindow.getAllWindows().length, 1, 'Late calls cannot recreate surfaces')
    }
    app.on('will-quit', () => { clearTimeout(timeout); console.log('YUMPOO_NORMAL_QUIT') })
    void menu.webContents.executeJavaScript("window.yumpooMenu.runAction('exit')").catch(() => {})
  } catch (error) { console.error(error); app.exit(1) }
}
run().catch(error => { console.error(error); app.exit(1) })
