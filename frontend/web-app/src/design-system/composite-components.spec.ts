import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import YpDefinitionList from '../components/yp/YpDefinitionList.vue'
import YpEmptyState from '../components/yp/YpEmptyState.vue'
import YpPageHeader from '../components/yp/YpPageHeader.vue'
import YpResultPage from '../components/yp/YpResultPage.vue'
import YpSegmented from '../components/yp/YpSegmented.vue'
import YpSurface from '../components/yp/YpSurface.vue'
import YpTabs from '../components/yp/YpTabs.vue'

describe('复合布局组件', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('页头渲染 eyebrow、说明和前置图标，未传入时不输出空节点', () => {
    const full = mount(YpPageHeader, {
      props: { title: '产品', eyebrow: '产品治理', description: '查看产品状态。' },
      slots: { leading: () => h('span', { class: 'icon' }) },
    })
    expect(full.get('.yp-page-header__eyebrow').text()).toBe('产品治理')
    expect(full.get('.yp-page-header__description').text()).toBe('查看产品状态。')
    expect(full.find('.yp-page-header__leading .icon').exists()).toBe(true)

    const plain = mount(YpPageHeader, { props: { title: '收件箱' } })
    expect(plain.find('.yp-page-header__eyebrow').exists()).toBe(false)
    expect(plain.find('.yp-page-header__description').exists()).toBe(false)
    expect(plain.find('.yp-page-header__leading').exists()).toBe(false)
  })

  it('表面组件组合标题、操作、页脚和危险色调', () => {
    const wrapper = mount(YpSurface, {
      props: { title: '生命周期', description: '归档与恢复', tone: 'danger', flush: true },
      slots: {
        default: () => h('p', '正文'),
        actions: () => h('button', { type: 'button' }, '更多'),
        footer: () => h('span', '共 3 条'),
      },
    })
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['yp-surface--danger', 'yp-surface--flush']))
    expect(wrapper.get('h2').text()).toBe('生命周期')
    expect(wrapper.get('.yp-surface__actions').text()).toBe('更多')
    expect(wrapper.get('.yp-surface__footer').text()).toBe('共 3 条')
    expect(mount(YpSurface, { slots: { default: () => '内容' } }).find('header').exists()).toBe(false)
  })

  it('定义列表显示默认值、空值占位和具名插槽', () => {
    const wrapper = mount(YpDefinitionList, {
      props: {
        columns: 2,
        items: [
          { key: 'timezone', label: '时区', value: 'Asia/Shanghai' },
          { key: 'corpId', label: 'Corp ID', value: '****5678', mono: true },
          { key: 'lastProblem', label: '最近异常', value: null },
          { key: 'enabled', label: '运行开关' },
        ],
      },
      slots: { enabled: () => h('strong', '已启用') },
    })
    expect(wrapper.classes()).toContain('yp-definition-list--2')
    expect(wrapper.findAll('dt').map(term => term.text())).toEqual(['时区', 'Corp ID', '最近异常', '运行开关'])
    const values = wrapper.findAll('dd')
    expect(values.map(value => value.text())).toEqual(['Asia/Shanghai', '****5678', '—', '已启用'])
    expect(values[1]!.classes()).toContain('yp-definition-list__mono')
  })

  it('页签保留外部 id 与 aria，并支持方向键、Home 和 End 切换', async () => {
    const wrapper = mount(YpTabs, {
      attachTo: document.body,
      props: {
        label: '项目视图',
        modelValue: 'content',
        items: [
          { value: 'recent', label: '最近', id: 'recent-tab', controls: 'recent-panel' },
          { value: 'content', label: '内容', id: 'content-tab', controls: 'content-panel', count: 3 },
        ],
        'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
      },
      slots: { 'icon-recent': () => h('svg', { class: 'recent-icon' }) },
    })
    const tabs = wrapper.findAll('[role="tab"]')
    expect(wrapper.get('[role="tablist"]').attributes('aria-label')).toBe('项目视图')
    expect(tabs.map(tab => tab.attributes('aria-selected'))).toEqual(['false', 'true'])
    expect(tabs.map(tab => tab.attributes('tabindex'))).toEqual(['-1', '0'])
    expect(tabs[0]!.attributes('id')).toBe('recent-tab')
    expect(tabs[0]!.attributes('aria-controls')).toBe('recent-panel')
    expect(tabs[0]!.find('.recent-icon').exists()).toBe(true)
    expect(tabs[1]!.get('.yp-tabs__count').text()).toBe('3')

    await tabs[1]!.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.props('modelValue')).toBe('recent')
    expect(document.activeElement).toBe(tabs[0]!.element)
    await tabs[0]!.trigger('keydown', { key: 'End' })
    expect(wrapper.props('modelValue')).toBe('content')
    await tabs[1]!.trigger('keydown', { key: 'Home' })
    expect(wrapper.props('modelValue')).toBe('recent')
    await tabs[0]!.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(3)
  })

  it('分段控件以单选语义切换，重复选择当前值不发事件', async () => {
    const wrapper = mount(YpSegmented, {
      attachTo: document.body,
      props: {
        label: '产品状态',
        modelValue: 'ACTIVE',
        options: [
          { value: 'ACTIVE', label: '进行中' },
          { value: 'ARCHIVED', label: '已归档' },
          { value: 'ALL', label: '全部' },
        ],
      },
    })
    const radios = wrapper.findAll('[role="radio"]')
    expect(wrapper.get('[role="radiogroup"]').attributes('aria-label')).toBe('产品状态')
    expect(radios.map(radio => radio.attributes('aria-checked'))).toEqual(['true', 'false', 'false'])

    await radios[0]!.trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
    await radios[2]!.trigger('click')
    expect(wrapper.emitted('change')).toEqual([['ALL']])
    await wrapper.setProps({ modelValue: 'ALL' })
    await radios[2]!.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['ACTIVE'])
    expect(document.activeElement).toBe(radios[0]!.element)
    await wrapper.setProps({ modelValue: 'ACTIVE' })
    await radios[0]!.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['ALL'])
  })

  it('结果页输出标题、说明和操作，场景仅作装饰', () => {
    const wrapper = mount(YpResultPage, {
      props: { title: '页面不存在', description: '请检查地址。', eyebrow: '404' },
      slots: {
        scene: () => h('canvas'),
        extra: () => h('p', { class: 'problem' }, '请求编号'),
        actions: () => h('button', { type: 'button' }, '返回工作台'),
      },
    })
    expect(wrapper.get('h1').text()).toBe('页面不存在')
    expect(wrapper.get('.yp-result-page__eyebrow').text()).toBe('404')
    expect(wrapper.get('.yp-result-page__scene').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('.yp-result-page__extra .problem').exists()).toBe(true)
    expect(wrapper.get('.yp-result-page__actions button').text()).toBe('返回工作台')
  })

  it('空态允许替换图标', () => {
    const wrapper = mount(YpEmptyState, {
      props: { title: '连接项目' },
      slots: { icon: () => h('i', { class: 'custom-icon' }) },
    })
    expect(wrapper.find('.yp-empty-state__icon .custom-icon').exists()).toBe(true)
    expect(wrapper.text()).toContain('连接项目')
  })
})
