import type { DesktopUpdateState } from '@yumpoo/preload-contract'
import { createHash } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { AUTHORIZATION_TIMEOUT_MS, CHECK_INTERVAL_MS, CHECK_JITTER_MS, FIRST_CHECK_DELAY_MS, UpdateController, type UpdaterLike } from '../src/main/update-controller'

const fixture = JSON.parse(readFileSync(path.join(__dirname, 'fixtures', 'signed-update.json'), 'utf8')) as {
  publicKey: string
  installer: string
  signed: { keyId: string }
}
const installer = Buffer.from(fixture.installer)
const goodDigest = { sha512: createHash('sha512').update(installer).digest('base64'), size: installer.byteLength }
const downloadedFile = path.join('C:', 'cache', 'pending', 'YumpooDesktop-Setup-1.0.1-x64.exe')

class FakeUpdater extends EventEmitter implements UpdaterLike {
  autoDownload = false
  autoInstallOnAppQuit = true
  allowDowngrade = true
  disableWebInstaller = false
  feed: string | undefined
  checks = 0
  downloads = 0
  installs: Array<[boolean, boolean]> = []
  setFeedURL(url: string) { this.feed = url }
  checkForUpdates() { this.checks += 1; return Promise.resolve(null) }
  downloadUpdate() { this.downloads += 1; return Promise.resolve(null) }
  install(isSilent: boolean, isForceRunAfter: boolean) { this.installs.push([isSilent, isForceRunAfter]); return true }
}

function setup(overrides: { autoDownload?: boolean; authorization?: unknown; digest?: typeof goodDigest; digestSync?: () => typeof goodDigest } = {}) {
  const updater = new FakeUpdater()
  const states: DesktopUpdateState[] = []
  const scheduled: Array<{ callback: () => void; delayMs: number }> = []
  const fetchJson = vi.fn(async () => overrides.authorization ?? JSON.parse(readFileSync(path.join(__dirname, 'fixtures', 'signed-update.json'), 'utf8')).signed)
  const removeFile = vi.fn(async () => undefined)
  let now = 1_000_000
  const controller = new UpdateController({
    updater,
    update: { feed: 'https://yumpoo.example.com/desktop/stable/', channel: 'stable', publicKeys: [{ keyId: fixture.signed.keyId, publicKey: fixture.publicKey }] },
    currentVersion: '1.0.0',
    autoDownload: overrides.autoDownload ?? true,
    fetchJson,
    digest: async () => overrides.digest ?? goodDigest,
    digestSync: overrides.digestSync ?? (() => goodDigest),
    removeFile,
    log: () => undefined,
    publish: state => states.push(state),
    now: () => now,
    random: () => 0.5,
    schedule: (callback, delayMs) => { scheduled.push({ callback, delayMs }) },
  })
  return { updater, controller, states, scheduled, fetchJson, removeFile, advance: (ms: number) => { now += ms } }
}

async function download(updater: FakeUpdater) {
  updater.emit('checking-for-update')
  updater.emit('update-available', { version: '1.0.1' })
  updater.emit('download-progress', { percent: 42.7 })
  updater.emit('update-downloaded', { version: '1.0.1', downloadedFile })
  await new Promise(resolve => setImmediate(resolve))
}

describe('应用内更新', () => {
  it('检查无更新后记录时间，仅 idle 状态公开', () => {
    const { controller, updater } = setup()
    expect(controller.getState()).not.toHaveProperty('checkedAt')
    updater.emit('update-not-available')
    expect(controller.getState()).toMatchObject({ phase: 'idle', checkedAt: 1_000_000 })
    updater.emit('checking-for-update')
    expect(controller.getState()).not.toHaveProperty('checkedAt')
  })
  it('按偏好自动下载，但从不自动安装，禁止降级', () => {
    expect(setup({ autoDownload: false }).updater.autoDownload).toBe(false)
    const { updater } = setup()
    expect(updater.autoDownload).toBe(true)
    expect(updater.autoInstallOnAppQuit).toBe(false)
    expect(updater.allowDowngrade).toBe(false)
    expect(updater.disableWebInstaller).toBe(true)
    expect(updater.feed).toBe('https://yumpoo.example.com/desktop/stable/')
  })

  it('关闭自动下载时停在可下载，重新开启或手动下载后才下载', () => {
    const { updater, controller } = setup({ autoDownload: false })
    updater.emit('checking-for-update')
    updater.emit('update-available', { version: '1.0.1' })
    expect(controller.getState()).toEqual({ phase: 'available', currentVersion: '1.0.0', channel: 'stable', version: '1.0.1' })
    expect(updater.downloads).toBe(0)
    controller.setAutoDownload(true)
    expect(updater.autoDownload).toBe(true)
    expect(controller.getState()).toMatchObject({ phase: 'downloading', percent: 0, version: '1.0.1' })
    controller.download()
    expect(updater.downloads).toBe(1)
  })

  it('下载完成后从更新源取授权签名，校验通过才进入就绪', async () => {
    const { updater, controller, states, fetchJson } = setup()
    await download(updater)
    expect(fetchJson).toHaveBeenCalledWith('https://yumpoo.example.com/desktop/stable/YumpooDesktop-Setup-1.0.1-x64.exe.sig.json', expect.any(AbortSignal))
    expect(states.map(state => state.phase)).toEqual(['checking', 'downloading', 'downloading', 'verifying', 'ready'])
    expect(states[2]).toMatchObject({ phase: 'downloading', percent: 42, version: '1.0.1' })
    expect(controller.getState()).toEqual({ phase: 'ready', currentVersion: '1.0.0', channel: 'stable', version: '1.0.1' })
    expect(controller.readyVersion).toBe('1.0.1')
  })

  it('篡改的安装包被拒绝、删除，且永不安装', async () => {
    const { updater, controller, removeFile } = setup({ digest: { ...goodDigest, sha512: createHash('sha512').update('evil').digest('base64') } })
    await download(updater)
    expect(controller.getState()).toMatchObject({ phase: 'error', error: 'UPDATE_REJECTED_DIGEST_MISMATCH' })
    expect(removeFile).toHaveBeenCalledWith(downloadedFile)
    expect(removeFile).toHaveBeenCalledWith(path.join('C:', 'cache', 'current.blockmap'))
    expect(controller.installOnQuit(true)).toBe(false)
    expect(updater.installs).toEqual([])
  })

  it('取不到授权签名时同样拒绝', async () => {
    const { updater, controller, fetchJson, removeFile } = setup()
    fetchJson.mockRejectedValueOnce(new Error('HTTP_404'))
    await download(updater)
    expect(controller.getState()).toMatchObject({ phase: 'error', error: 'UPDATE_REJECTED_AUTHORIZATION_UNAVAILABLE' })
    expect(removeFile).toHaveBeenCalledWith(downloadedFile)
    expect(controller.installOnQuit(false)).toBe(false)
    expect(updater.installs).toEqual([])
  })

  it('授权签名请求挂起时超时取消、拒绝并恢复到可重试状态', async () => {
    const { updater, controller, fetchJson, removeFile, scheduled } = setup()
    let signal: AbortSignal | undefined
    fetchJson.mockImplementationOnce((_url: string, abortSignal: AbortSignal) => {
      signal = abortSignal
      return new Promise(() => undefined)
    })
    await download(updater)
    expect(controller.getState().phase).toBe('verifying')
    controller.check()
    expect(updater.checks).toBe(0)
    scheduled.find(entry => entry.delayMs === AUTHORIZATION_TIMEOUT_MS)?.callback()
    await new Promise(resolve => setImmediate(resolve))
    expect(signal?.aborted).toBe(true)
    expect(controller.getState()).toMatchObject({ phase: 'error', error: 'UPDATE_REJECTED_AUTHORIZATION_UNAVAILABLE' })
    expect(removeFile).toHaveBeenCalledWith(downloadedFile)
    expect(controller.installOnQuit(true)).toBe(false)
    controller.check()
    expect(updater.checks).toBe(1)
  })

  it('退出时重新核对文件摘要后静默安装；重启以更新时安装后重新启动', async () => {
    const { updater, controller } = setup()
    await download(updater)
    expect(controller.installOnQuit(false)).toBe(true)
    expect(controller.installOnQuit(true)).toBe(true)
    expect(updater.installs).toEqual([[true, false], [true, true]])
  })

  it('校验后文件被替换则不安装', async () => {
    const { updater, controller } = setup({ digestSync: () => ({ ...goodDigest, size: goodDigest.size + 1 }) })
    await download(updater)
    expect(controller.installOnQuit(false)).toBe(false)
    expect(updater.installs).toEqual([])
  })

  it('启动 30 秒后首次检查，之后每 4 小时加抖动；唤醒时距上次不足 15 分钟不重复检查', () => {
    const { updater, controller, scheduled, advance } = setup()
    controller.start()
    controller.start()
    expect(scheduled).toHaveLength(1)
    expect(scheduled[0]?.delayMs).toBe(FIRST_CHECK_DELAY_MS)
    scheduled[0]?.callback()
    expect(updater.checks).toBe(1)
    expect(scheduled[1]?.delayMs).toBe(CHECK_INTERVAL_MS + CHECK_JITTER_MS / 2)
    updater.emit('update-not-available')
    controller.onResume()
    expect(updater.checks).toBe(1)
    advance(16 * 60 * 1000)
    controller.onResume()
    expect(updater.checks).toBe(2)
  })

  it('检查中、下载中或已就绪时不重复检查；出错后允许重试', async () => {
    const { updater, controller } = setup()
    updater.emit('checking-for-update')
    controller.check()
    expect(updater.checks).toBe(0)
    updater.emit('error', new Error('net::ERR_INTERNET_DISCONNECTED'))
    expect(controller.getState()).toMatchObject({ phase: 'error', error: 'UPDATE_FAILED' })
    controller.check()
    expect(updater.checks).toBe(1)
    await download(updater)
    controller.check()
    expect(updater.checks).toBe(1)
    updater.emit('error', new Error('late failure'))
    expect(controller.getState().phase).toBe('ready')
  })
})
