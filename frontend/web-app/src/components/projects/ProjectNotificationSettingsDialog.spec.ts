import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProjectNotificationSettingsDialog from './ProjectNotificationSettingsDialog.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
vi.mock('../../api/client', () => ({
  notificationsApi: { getMyProjectNotificationPreference: api.get, updateMyProjectNotificationPreference: api.update },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf',
}))

describe('ProjectNotificationSettingsDialog', () => {
  it('读取偏好后选择自定义并取消 @提及，保存整份偏好', async () => {
    const preference = { projectId: 'p1', mode: 'ALL', mention: true, comment: true, assigned: true, connectionCreated: true, updatedAt: null }
    api.get.mockResolvedValue(preference)
    api.update.mockResolvedValue({ ...preference, mode: 'CUSTOM', mention: false })
    const wrapper = mount(ProjectNotificationSettingsDialog, {
      props: { modelValue: true, projectId: 'p1', projectName: '演示项目' },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    const save = () => wrapper.findAll('button').find(button => button.text() === '保存')!
    expect(save().attributes('disabled')).toBeDefined()
    await wrapper.findAll('.yp-choice-card').find(card => card.text().includes('自定义'))!.trigger('click')
    await wrapper.findAllComponents({ name: 'ElCheckbox' })[0]!.vm.$emit('update:modelValue', false)
    await save().trigger('click')
    await flushPromises()
    expect(api.update).toHaveBeenCalledWith({
      projectId: 'p1',
      xXSRFTOKEN: 'csrf',
      projectNotificationPreferenceUpdateRequest: { mode: 'CUSTOM', mention: false, comment: true, assigned: true, connectionCreated: true },
    })
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
})
