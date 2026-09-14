import { ElLoading } from 'element-plus'
import type { ObjectDirective } from 'vue'
import logo from '../assets/brand/logo.svg?raw'
import './loading.css'

const loading = ElLoading.directive as ObjectDirective<HTMLElement, boolean>
const attributes = new WeakMap<HTMLElement, Map<string, string | null>>()
let nextId = 0

function decorate(el: HTMLElement): void {
  const previous = new Map<string, string | null>()
  const set = (name: string, value: string) => {
    previous.set(name, el.getAttribute(name))
    el.setAttribute(name, value)
  }
  const markup = logo.replace(/^<svg\b[^>]*>/u, '').replace(/<\/svg>\s*$/u, '')
    .replaceAll('yp-brand-', `yp-brand-${++nextId}-`)
  set('element-loading-svg', markup)
  set('element-loading-svg-view-box', '125 123 1187 871')
  set('element-loading-custom-class', [el.getAttribute('element-loading-custom-class'), 'yp-brand-loading'].filter(Boolean).join(' '))
  if (!el.hasAttribute('element-loading-text')) set('element-loading-text', '正在加载…')
  if (!el.hasAttribute('aria-busy')) set('aria-busy', 'false')
  attributes.set(el, previous)
}

function setBusy(el: HTMLElement, busy: boolean): void {
  if (attributes.get(el)?.has('aria-busy')) el.setAttribute('aria-busy', String(busy))
}

export const vBrandLoading: ObjectDirective<HTMLElement, boolean> = {
  mounted(el, binding, vnode, previous) {
    decorate(el)
    setBusy(el, Boolean(binding.value))
    loading.mounted?.(el, binding, vnode, previous)
  },
  updated(el, binding, vnode, previous) {
    setBusy(el, Boolean(binding.value))
    loading.updated?.(el, binding, vnode, previous)
  },
  unmounted(el, binding, vnode, previous) {
    loading.unmounted?.(el, binding, vnode, previous)
    for (const [name, value] of attributes.get(el) ?? []) {
      if (value === null) el.removeAttribute(name)
      else el.setAttribute(name, value)
    }
    attributes.delete(el)
  },
}
