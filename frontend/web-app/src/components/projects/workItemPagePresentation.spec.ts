import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useWorkItemPagePresentation, type WorkItemPagePresentation } from './workItemPagePresentation'

enableAutoUnmount(afterEach)
beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

function presentation(scope = ref('company:user')) {
  let page!: WorkItemPagePresentation
  mount(defineComponent({ setup() { page = useWorkItemPagePresentation(() => scope.value); return () => null } }))
  return page
}

function scrollElement(width: number, content: number) {
  const el = document.createElement('div')
  el.append(document.createElement('div'))
  Object.defineProperties(el, { clientWidth: { value: width, configurable: true }, scrollWidth: { value: content, configurable: true } })
  return el
}

it('同步横移，新挂载、重开和窗口尺寸变化继承或约束全页位置', async () => {
  let resize!: () => void
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resize = callback } observe() {} disconnect() {} })
  const page = presentation()
  const bar = scrollElement(600, 1400), first = scrollElement(600, 1400), second = scrollElement(600, 1400)
  page.scrollbar.value = bar
  bar.addEventListener('scroll', page.onScroll)
  page.register(first)
  const release = page.register(second)
  await flushPromises()
  bar.scrollLeft = 350; bar.dispatchEvent(new Event('scroll'))
  await flushPromises()
  expect([first.scrollLeft, second.scrollLeft, page.position.value]).toEqual([350, 350, 350])
  const late = scrollElement(600, 1400)
  page.register(late); await flushPromises()
  expect(late.scrollLeft).toBe(350)
  release(); second.scrollLeft = 0; page.register(second); await flushPromises()
  expect(second.scrollLeft).toBe(350)
  first.scrollLeft = 700; first.dispatchEvent(new Event('scroll')); await flushPromises()
  expect([bar.scrollLeft, second.scrollLeft, late.scrollLeft]).toEqual([700, 700, 700])
  for (const el of [first, second, late]) Object.defineProperty(el, 'clientWidth', { value: 1100, configurable: true })
  resize(); await flushPromises()
  expect([page.position.value, first.scrollLeft, second.scrollLeft]).toEqual([300, 300, 300])
  expect(page.extent.value).toBe(900)
})

it('本机保存全页布局，主子表顺序和冻结独立，并按企业用户隔离', async () => {
  const scope = ref('company:user')
  const page = presentation(scope)
  page.widths.title = 390
  page.toggleColumn('priority')
  page.collapsed.value.add('status')
  page.order.value = [...page.order.value].reverse()
  page.pinColumns(2)
  page.pinColumns(1, true)
  await flushPromises()
  const restored = presentation()
  expect(restored.widths.title).toBe(390)
  expect(restored.hidden.value.has('priority')).toBe(true)
  expect(restored.collapsed.value.has('status')).toBe(true)
  expect(restored.order.value).toEqual(page.order.value)
  expect(restored.subitemOrder.value).not.toEqual(page.order.value)
  expect([restored.pinned.value, restored.subitemPinned.value]).toEqual([2, 1])
  scope.value = 'company:another-user'; await flushPromises()
  expect(page.widths.title).toBe(320)
  expect(page.hidden.value.size).toBe(0)
  expect(page.pinned.value).toBe(0)
  scope.value = 'another-company:user'; await flushPromises()
  expect(page.widths.title).toBe(320)
  scope.value = 'company:user'; await flushPromises()
  expect(page.widths.title).toBe(390)
})
