import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import router from '../../router'
import DesktopDownloadView from './DesktopDownloadView.vue'

const release = {
  schemaVersion: 1,
  channel: 'stable',
  version: '1.2.0',
  tag: 'desktop-v1.2.0',
  sourceCommit: 'a'.repeat(40),
  installer: { name: 'YumpooDesktop-Setup-1.2.0-x64.exe', bytes: 104_857_600, sha256: 'b'.repeat(64) },
  notes: ['feat(desktop): 新增深链 (DESK-8)'],
}
const mounted: Array<ReturnType<typeof mount>> = []

function respond(body: unknown, ok = true) {
  const fetch = vi.fn(async () => ({ ok, status: ok ? 200 : 404, json: async () => body }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

async function render() {
  const wrapper = mount(DesktopDownloadView, { global: { stubs: { StatusScene: true } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('desktop download page', () => {
  it('shows the published version, its SHA-256 and a link into the feed', async () => {
    const fetch = respond(release)
    const wrapper = await render()
    expect(fetch).toHaveBeenCalledWith('/desktop/stable/release.json', { cache: 'no-store', credentials: 'omit' })
    expect(wrapper.text()).toContain('Yumpoo Desktop 1.2.0')
    expect(wrapper.text()).toContain('b'.repeat(64))
    expect(wrapper.text()).toContain('100.0 MB')
    expect(wrapper.text()).toContain('新增深链')
    expect(wrapper.text()).toContain('仍要运行')
    expect(wrapper.find('a[download]').attributes('href')).toBe('/desktop/stable/YumpooDesktop-Setup-1.2.0-x64.exe')
  })

  it('offers a retry instead of a link when the feed is unavailable or malformed', async () => {
    for (const [body, ok] of [[release, false], [{ ...release, installer: { ...release.installer, name: '../evil.exe' } }, true]] as const) {
      respond(body, ok)
      const wrapper = await render()
      expect(wrapper.find('a[download]').exists()).toBe(false)
      expect(wrapper.text()).toContain('暂时无法获取桌面客户端版本信息')
      expect(wrapper.text()).toContain('重试')
    }
  })

  it('inside the desktop shell points to the browser, because the shell refuses downloads', async () => {
    respond(release)
    vi.stubGlobal('yumpooDesktop', { client: 'electron' })
    const wrapper = await render()
    expect(wrapper.find('a[download]').exists()).toBe(false)
    expect(wrapper.text()).toContain('请在浏览器中打开')
  })

  it('stays reachable for an anonymous session: neither the guard nor the session watcher redirects to login', async () => {
    respond(release)
    expect(await router.push('/download')).toBeUndefined()
    useSession().phase.value = 'anonymous'
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('desktop-download')
    expect(await router.push('/download?from=menu')).toBeUndefined()
    expect(router.currentRoute.value.fullPath).toBe('/download?from=menu')
  })
})
