import { describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'
import { loadSurface } from '../src/main/surface-load'

function surface(status: number | null) {
  const listeners = new Map<string, (...args: unknown[]) => void>()
  const window = {
    webContents: {
      once: vi.fn((name: string, fn: (...args: unknown[]) => void) => listeners.set(name, fn)),
      removeListener: vi.fn((name: string) => listeners.delete(name)),
    },
    loadURL: vi.fn(async (url: string) => {
      if (status === null) throw new Error('ERR_FAILED (-2)')
      listeners.get('did-navigate')?.({}, url, status, '')
    }),
  }
  return { window, listeners }
}

describe('shell surface loading', () => {
  it('resolves when the page loads successfully and drops its listener', async () => {
    const { window, listeners } = surface(200)
    await expect(loadSurface(window as unknown as BrowserWindow, 'https://yumpoo.example/timer/quick')).resolves.toBeUndefined()
    expect(window.loadURL).toHaveBeenCalledWith('https://yumpoo.example/timer/quick')
    expect(listeners.size).toBe(0)
  })

  it('rejects an HTTP error page such as the nginx 404 instead of showing it', async () => {
    const { window } = surface(404)
    await expect(loadSurface(window as unknown as BrowserWindow, 'https://yumpoo.example/timer/rail')).rejects.toThrow('HTTP 404')
  })

  it('keeps network failures as rejections', async () => {
    const { window, listeners } = surface(null)
    await expect(loadSurface(window as unknown as BrowserWindow, 'https://yumpoo.example/timer')).rejects.toThrow('ERR_FAILED')
    expect(listeners.size).toBe(0)
  })
})
