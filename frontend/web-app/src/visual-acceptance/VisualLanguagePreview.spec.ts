import { enableAutoUnmount, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it } from 'vitest'
import VisualLanguagePreview from './VisualLanguagePreview.vue'

enableAutoUnmount(afterEach)
it('视觉验收使用统一项目的状态和表单字段', () => {
  const wrapper = mount(VisualLanguagePreview, { global: { plugins: [ElementPlus] } })
  expect(wrapper.text()).toContain('进行中 2')
  expect(wrapper.text()).toContain('已归档 1')
  expect(wrapper.text()).not.toContain('草稿')
  expect(wrapper.text()).not.toContain('项目类型')
  expect(wrapper.findAll('.el-form-item__label').map(item => item.text())).toContain('项目名称')
})
