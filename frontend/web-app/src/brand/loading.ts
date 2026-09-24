import { ElLoading } from 'element-plus'
import type { ObjectDirective } from 'vue'
import logo from '../assets/brand/logo.svg?raw'
import './loading.css'

const loading = ElLoading.directive as ObjectDirective<HTMLElement, boolean>
const attributes = new WeakMap<HTMLElement, Map<string, string | null>>()
let nextId = 0

// 头、身体拆成独立切片，才能分别做弹跳和落地形变；品牌资产本身保持不变。
const logoMarkup = (() => {
  const texture = logo.match(/<image\b[^>]*\/>/u)?.[0] ?? ''
  const shapes = new Map(Array.from(logo.matchAll(/<clipPath id="yp-brand-(\w+)">([\s\S]*?)<\/clipPath>/gu),
    ([, name, body]) => [name, Array.from((body ?? '').matchAll(/<path\b[^>]*\/>/gu), ([path]) => path)]))
  const pieces: Array<[string, string | undefined]> = [['cloud', shapes.get('cloud')?.[0]]]
  for (const person of ['left', 'center', 'right']) {
    const [head, body] = shapes.get(person) ?? []
    pieces.push([`${person}-body`, body], [`${person}-head`, head])
  }
  const clips = pieces.map(([name, path]) => `<clipPath id="yp-brand-${name}">${path ?? ''}</clipPath>`).join('')
  const groups = pieces.map(([name]) => `<g class="yp-loader-piece yp-loader-${name}"><g clip-path="url(#yp-brand-${name})">`
    + '<use href="#yp-brand-texture"/><rect class="yp-loader-shine" x="0" y="100" width="220" height="900" fill="url(#yp-brand-shine)"/></g></g>').join('')
  return `<defs>${texture}${clips}<linearGradient id="yp-brand-shine"><stop offset="0" stop-color="#fff" stop-opacity="0"/>`
    + '<stop offset=".5" stop-color="#fff" stop-opacity=".65"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient></defs>'
    + `<g class="yp-loader-stage">${groups}</g>`
})()

function decorate(el: HTMLElement): void {
  const previous = new Map<string, string | null>()
  const set = (name: string, value: string) => {
    previous.set(name, el.getAttribute(name))
    el.setAttribute(name, value)
  }
  const markup = logoMarkup.replaceAll('yp-brand-', `yp-brand-${++nextId}-`)
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
