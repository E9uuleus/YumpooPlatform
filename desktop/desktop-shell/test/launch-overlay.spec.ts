import { EventEmitter } from 'node:events'
import type { BrowserWindow, WebContentsView } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import { attachLaunchOverlay } from '../src/main/launch-overlay'

function fixture() {
  const mainContents = new EventEmitter()
  const window = Object.assign(new EventEmitter(), {
    webContents: mainContents,
    destroyed: false,
    bounds: { x: 0, y: 0, width: 1280, height: 800 },
    contentView: { addChildView: vi.fn(), removeChildView: vi.fn() },
    getContentBounds() { return this.bounds },
    isDestroyed() { return this.destroyed },
  })
  const viewContents = Object.assign(new EventEmitter(), {
    closed: false,
    loadURL: vi.fn(async () => undefined),
    setWindowOpenHandler: vi.fn(),
    close: vi.fn(function (this: { closed: boolean }) { this.closed = true }),
    isDestroyed() { return this.closed },
  })
  const view = { webContents: viewContents, setBounds: vi.fn() }
  const overlay = attachLaunchOverlay(window as unknown as BrowserWindow, {
    pageUrl: 'yumpoo-diagnostics://app/launch.html',
    createView: () => view as unknown as WebContentsView,
  })
  return { window, mainContents, view, viewContents, overlay }
}

describe('启动遮罩', () => {
  it('铺满内容区并加载打包的启动页，拒绝弹窗与导航', () => {
    const { window, view, viewContents } = fixture()
    expect(window.contentView.addChildView).toHaveBeenCalledWith(view)
    expect(view.setBounds).toHaveBeenLastCalledWith({ x: 0, y: 0, width: 1280, height: 800 })
    expect(viewContents.loadURL).toHaveBeenCalledWith('yumpoo-diagnostics://app/launch.html')
    expect(viewContents.setWindowOpenHandler.mock.calls[0]?.[0]()).toEqual({ action: 'deny' })
    const navigation = { preventDefault: vi.fn() }
    viewContents.emit('will-navigate', navigation)
    expect(navigation.preventDefault).toHaveBeenCalled()
  })

  it('窗口缩放时同步尺寸', () => {
    const { window, view } = fixture()
    window.bounds = { x: 10, y: 10, width: 960, height: 640 }
    window.emit('resize')
    expect(view.setBounds).toHaveBeenLastCalledWith({ x: 0, y: 0, width: 960, height: 640 })
  })

  it('主窗首次加载完成后移除并关闭，之后不再响应', () => {
    const { window, mainContents, view, viewContents } = fixture()
    mainContents.emit('did-finish-load')
    expect(window.contentView.removeChildView).toHaveBeenCalledWith(view)
    expect(viewContents.close).toHaveBeenCalledOnce()
    window.emit('resize')
    mainContents.emit('did-finish-load')
    expect(view.setBounds).toHaveBeenCalledOnce()
    expect(window.contentView.removeChildView).toHaveBeenCalledOnce()
  })

  it('启动失败时可提前撤下，重复调用无害', () => {
    const { window, viewContents, overlay } = fixture()
    overlay.dismiss()
    overlay.dismiss()
    expect(window.contentView.removeChildView).toHaveBeenCalledOnce()
    expect(viewContents.close).toHaveBeenCalledOnce()
  })

  it('窗口关闭后只关闭遮罩自身的页面', () => {
    const { window, viewContents } = fixture()
    window.destroyed = true
    window.emit('closed')
    expect(window.contentView.removeChildView).not.toHaveBeenCalled()
    expect(viewContents.close).toHaveBeenCalledOnce()
  })
})
