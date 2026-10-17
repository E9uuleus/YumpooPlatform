import { describe, expect, it, vi } from 'vitest'
import { handleStartupFailure } from '../src/main/startup-failure'

function dependencies(response: number) {
  return {
    showMessageBox: vi.fn(async () => ({ response })),
    openExternal: vi.fn(async () => undefined),
    relaunch: vi.fn(),
    exit: vi.fn(),
  }
}

describe('启动失败提示', () => {
  const origin = 'https://yumpoo.example.com'

  it('可以改用浏览器打开 Web 版', async () => {
    const deps = dependencies(0)
    await expect(handleStartupFailure(deps, origin)).resolves.toBe('open-web')
    expect(deps.showMessageBox.mock.calls[0]?.[0]).toMatchObject({ buttons: ['用浏览器打开 Web 版', '重试', '退出'], defaultId: 1, cancelId: 2 })
    expect(deps.openExternal).toHaveBeenCalledWith(origin)
    expect(deps.exit).toHaveBeenCalledWith(1)
    expect(deps.relaunch).not.toHaveBeenCalled()
  })

  it('重试会重新启动应用', async () => {
    const deps = dependencies(1)
    await expect(handleStartupFailure(deps, origin)).resolves.toBe('retry')
    expect(deps.relaunch).toHaveBeenCalledOnce()
    expect(deps.exit).toHaveBeenCalledWith(0)
  })

  it('浏览器打不开时仍然退出', async () => {
    const deps = dependencies(0)
    deps.openExternal.mockRejectedValueOnce(new Error('blocked'))
    await handleStartupFailure(deps, origin)
    expect(deps.exit).toHaveBeenCalledWith(1)
  })

  it('没有分发配置时不提供 Web 入口，关闭对话框等同退出', async () => {
    const deps = dependencies(-1)
    await expect(handleStartupFailure(deps, undefined)).resolves.toBe('quit')
    expect(deps.showMessageBox.mock.calls[0]?.[0]).toMatchObject({ buttons: ['重试', '退出'] })
    expect(deps.openExternal).not.toHaveBeenCalled()
    expect(deps.exit).toHaveBeenCalledWith(1)
  })
})
