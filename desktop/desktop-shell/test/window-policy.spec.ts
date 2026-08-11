import { describe, expect, it } from 'vitest'
import { createWindowOptions } from '../src/main/window-policy'

describe('BrowserWindow 安全配置', () => {
  it('明确启用隔离与沙箱并关闭 Node 集成', () => {
    const options = createWindowOptions('C:\\yumpoo\\preload.js')
    expect(options.show).toBe(false)
    expect(options.webPreferences).toMatchObject({
      preload: 'C:\\yumpoo\\preload.js',
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      webviewTag: false,
      allowRunningInsecureContent: false,
      devTools: true,
    })
  })

  it('packaged 模式关闭开发者工具', () => {
    const options = createWindowOptions('C:\\yumpoo\\preload.js', true)
    expect(options.webPreferences?.devTools).toBe(false)
  })
})
