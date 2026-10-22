import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import DocsPage from './DocsPage.vue'
import type { DocMeta } from './docsNav'

const meta: DocMeta = {
  slug: 'guide/sample', section: 'guide', title: '示例页', description: '一句话。', order: 1, audience: ['admin'],
  appRoute: 'home', tour: 'welcome', headings: [{ depth: 2, text: '小节', id: '小节' }],
}
const html = '<h2 id="小节">小节</h2><p><a href="/docs/guide/other#x" data-docs-link>站内</a></p>'
  + '<div class="docs-code"><button type="button" class="docs-code__copy" data-docs-copy>复制</button><pre><code class="hljs">pnpm test</code></pre></div>'

vi.mock('./docsNav', async importOriginal => ({
  ...await importOriginal<typeof import('./docsNav')>(),
  findDoc: (slug: string) => slug === meta.slug ? meta : undefined,
  loadDocHtml: vi.fn(async () => html),
  neighbours: () => ({}),
}))

const mounted: Array<ReturnType<typeof mount>> = []
let scrolled: string[] = []

async function render(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/docs/:slug(.*)', name: 'docs-page', component: DocsPage },
      { path: '/', name: 'home', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  scrolled = []
  Element.prototype.scrollIntoView = function scrollIntoView(this: Element) { scrolled.push(this.id) }
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
})
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

describe('文档页', () => {
  it('渲染页头、角标、导览提示与正文', async () => {
    const { wrapper } = await render('/docs/guide/sample')
    expect(wrapper.find('h1').text()).toBe('示例页')
    expect(wrapper.find('.docs-audience').text()).toBe('管理员')
    expect(wrapper.text()).toContain('查看本页引导')
    expect(wrapper.find('.docs-prose h2').text()).toBe('小节')
    expect(document.title).toBe('示例页 · Yumpoo 文档')
  })

  it('未知文档显示 404', async () => {
    const { wrapper } = await render('/docs/guide/missing')
    expect(wrapper.text()).toContain('没有找到这篇文档')
  })

  it('带 hash 打开时滚动到锚点', async () => {
    await render(`/docs/guide/sample#${encodeURIComponent('小节')}`)
    expect(scrolled).toEqual(['小节'])
  })

  it('站内链接走路由，复制按钮写入剪贴板', async () => {
    const writeText = vi.fn(async () => undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const { wrapper, router } = await render('/docs/guide/sample')
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    await wrapper.find('a[data-docs-link]').trigger('click')
    expect(push).toHaveBeenCalledWith('/docs/guide/other#x')
    await wrapper.find('[data-docs-copy]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('pnpm test')
    expect(wrapper.find('[data-docs-copy]').text()).toBe('已复制')
  })

  it('「在应用中打开」按路由名跳转', async () => {
    const { wrapper, router } = await render('/docs/guide/sample')
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    await wrapper.find('.docs-page__open').trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'home' })
  })
})
