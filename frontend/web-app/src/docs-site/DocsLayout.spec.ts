import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import DocsLayout from './DocsLayout.vue'

const mounted: Array<ReturnType<typeof mount>> = []

async function render(path = '/docs') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/docs',
        component: DocsLayout,
        children: [
          { path: '', name: 'docs-home', component: { template: '<div />' } },
          { path: ':slug(.*)', name: 'docs-page', component: { template: '<div />' } },
        ],
      },
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/download', name: 'download', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('帮助中心顶栏', () => {
  it('外观入口是无文字的图标按钮，点击后展示主题与密度选项', async () => {
    const wrapper = await render()
    const trigger = wrapper.get('.docs-topbar button[aria-label="外观"]')
    expect(trigger.classes()).toContain('yp-tool-button')
    expect(trigger.text()).toBe('')
    expect(trigger.classes()).not.toContain('is-active')
    await trigger.trigger('click')
    await vi.waitFor(() => expect(trigger.classes()).toContain('is-active'))
    expect(document.body.querySelector<HTMLElement>('.el-popover')?.style.display).not.toBe('none')
    expect(document.body.querySelectorAll('input[type="radio"]')).toHaveLength(6)
  })

  it('浏览器中提供「下载桌面客户端」链接', async () => {
    const wrapper = await render()
    const link = wrapper.get('.docs-topbar__download')
    expect(link.text()).toContain('下载桌面客户端')
    expect(link.attributes('href')).toBe('/download')
  })

  it('桌面壳中不显示下载链接', async () => {
    vi.stubGlobal('yumpooDesktop', { client: 'electron' })
    const wrapper = await render()
    expect(wrapper.text()).not.toContain('下载桌面客户端')
  })
})
