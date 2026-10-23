import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import DocsSearch from './DocsSearch.vue'
import { searchDocs, type DocSearchEntry } from './docsNav'

const index: DocSearchEntry[] = [
  { slug: 'guide/time-tracking', title: '计时与工时', sections: [
    { heading: '计时与工时', id: '', text: '开始计时的三种方式。' },
    { heading: '并行计时', id: '并行计时', text: '同时计时多项时，并行计时按项数平均计入。' },
  ] },
  { slug: 'intro/glossary', title: '术语表', sections: [
    { heading: '术语表', id: '', text: '并行计时：同时给多个工作项计时。工作日历：公司维护的日历。' },
  ] },
  { slug: 'guide/company', title: '公司管理', sections: [
    { heading: '工作日历', id: '工作日历', text: '导入 Excel 后保存才会生效。' },
  ] },
]

vi.mock('./docsNav', async importOriginal => ({
  ...await importOriginal<typeof import('./docsNav')>(),
  loadSearchIndex: vi.fn(async () => index),
}))

const mounted: Array<ReturnType<typeof mount>> = []
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

describe('文档搜索', () => {
  it('按中文子串命中，标题与小节标题优先', () => {
    const hits = searchDocs(index, '并行计时')
    expect(hits.map(hit => `${hit.slug}#${hit.id}`)).toEqual(['guide/time-tracking#并行计时', 'intro/glossary#'])
    expect(hits[0]!.snippet.filter(part => part.hit).map(part => part.text)).toEqual(['并行计时'])
  })

  it('多个词必须同时命中同一小节', () => {
    expect(searchDocs(index, '工作日历 Excel').map(hit => hit.slug)).toEqual(['guide/company'])
    expect(searchDocs(index, '工作日历 并行计时').map(hit => hit.slug)).toEqual(['intro/glossary'])
    expect(searchDocs(index, '   ')).toEqual([])
  })

  it('页面标题命中排在正文命中之前', () => {
    expect(searchDocs(index, '计时')[0]!.slug).toBe('guide/time-tracking')
  })

  it('方向键选择、回车打开对应小节', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }] })
    const push = vi.spyOn(router, 'push')
    const wrapper = mount(DocsSearch, { props: { modelValue: true }, global: { plugins: [router] }, attachTo: document.body })
    mounted.push(wrapper)
    await flushPromises()
    const input = document.querySelector<HTMLInputElement>('input[aria-label="搜索文档"]')!
    input.value = '工作日历'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(document.querySelectorAll('[role="option"]')).toHaveLength(2)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }))
    await flushPromises()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    await flushPromises()
    expect(push).toHaveBeenCalledWith('/docs/intro/glossary')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })
})
