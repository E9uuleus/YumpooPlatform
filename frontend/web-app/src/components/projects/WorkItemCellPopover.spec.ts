import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElPopover } from 'element-plus'
import { defineComponent, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { contentsApi, workItemsApi } from '../../api/client'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'

vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
vi.mock('../../api/client', () => ({ contentsApi: { listProjectContents: vi.fn() }, workItemsApi: { updateProjectWorkItemPriorityLabel: vi.fn() } }))
enableAutoUnmount(afterEach)

describe('单元格弹层生命周期', () => {
  it.each(['content', 'priority'] as const)('%s 打开才挂载，保存时关闭仍可收到目录更新', async kind => {
    const entry = { id: 'content', code: 'HIGH', name: '类别', displayName: '高', colorToken: 'BLUE', active: true, sortOrder: kind === 'content' ? 10 : 1 }
    const catalog = { items: [entry], priorities: [entry], statuses: [], etag: '"1"', canManage: true }
    let finish!: (value: unknown) => void
    const pending = new Promise(resolve => { finish = resolve })
    vi.mocked(contentsApi.listProjectContents).mockReturnValue(pending as never)
    vi.mocked(workItemsApi.updateProjectWorkItemPriorityLabel).mockReturnValue(pending as never)
    const updated = vi.fn()
    const Host = defineComponent({ components: { ElPopover, WorkItemContentPopoverContent, WorkItemLabelPopoverContent },
      setup: () => ({ visible: ref(false), busy: ref(false), catalog, kind, updated }),
      template: `<el-popover :visible="visible" :persistent="busy"><template #reference><button>编辑</button></template>
        <work-item-content-popover-content v-if="kind === 'content'" project-id="p" :catalog="catalog" can-manage @busy-change="busy = $event" @updated="updated" />
        <work-item-label-popover-content v-else kind="priority" project-id="p" :catalog="catalog" can-manage @busy-change="busy = $event" @updated="updated" />
      </el-popover>` })
    const wrapper = mount(Host, { attachTo: document.body })
    const component = kind === 'content' ? WorkItemContentPopoverContent : WorkItemLabelPopoverContent
    expect(wrapper.findComponent(component).exists()).toBe(false)
    wrapper.vm.visible = true; await flushPromises()
    const editor = wrapper.getComponent(component)
    await editor.get(kind === 'content' ? '.content-manage' : '.edit-action-btn').trigger('click')
    if (kind === 'priority') await editor.get('input').setValue('新名称')
    await editor.get('.apply-action-btn').trigger('click')
    expect(wrapper.vm.busy).toBe(true)
    wrapper.vm.visible = false; await flushPromises()
    expect(wrapper.findComponent(component).exists()).toBe(true)
    finish(catalog); await flushPromises()
    expect(updated).toHaveBeenCalledWith(catalog)
    expect(wrapper.vm.busy).toBe(false)
  })
})
