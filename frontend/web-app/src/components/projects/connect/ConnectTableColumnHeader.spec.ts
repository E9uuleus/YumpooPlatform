import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConnectTableColumnHeader from './ConnectTableColumnHeader.vue'
import ConnectColumnHeader from './ConnectColumnHeader.vue'
import ConnectColumnSetupPopover from './ConnectColumnSetupPopover.vue'
import { connectColumn } from './connectTestFixtures'
import { connectionTestContext } from './connectTestSupport'
import type { ConnectTableColumn } from './connectColumnKeys'
import type { ConnectSetupMode } from './useConnectTable'

enableAutoUnmount(afterEach)
function table(setup?: { key: ConnectTableColumn['key']; mode: ConnectSetupMode }) {
  return { canManage: true, canDelete: false, openSettings: vi.fn(), requestDelete: vi.fn(), resizeColumn: vi.fn(), rename: vi.fn(async () => connectColumn),
    columnNames: [connectColumn.name], setup, celebrated: undefined, closeSetup: vi.fn(), connectProjects: vi.fn(async () => connectColumn), projectName: '华东现场实施' }
}
const column: ConnectTableColumn = { key: 'connect:column-1', kind: 'connect', label: connectColumn.name, column: connectColumn, width: 200, minWidth: 140 }
describe('主表、分组与子项共用的连接列表头', () => {
  it('按列键执行设置、重命名与调宽，并将隐藏事件交给表格重新布局', async () => {
    const current = table()
    const wrapper = mount(ConnectTableColumnHeader, { props: { column, table: current, projectId: 'source-project' } })
    const header = wrapper.getComponent(ConnectColumnHeader)
    expect(header.props()).toMatchObject({ canManage: true, canDelete: false, columnKey: 'connect:column-1', width: 200, takenNames: [connectColumn.name] })
    header.vm.$emit('edit'); header.vm.$emit('hide'); header.vm.$emit('delete')
    header.vm.$emit('resize', 260, false); header.vm.$emit('resize', 260, true)
    await header.props('rename')!('现场问题')
    expect(current.openSettings).toHaveBeenCalledWith(connectColumn)
    expect(current.rename).toHaveBeenCalledWith(connectColumn, '现场问题')
    expect(current.requestDelete).toHaveBeenCalledWith(connectColumn)
    expect(wrapper.emitted('hide')).toEqual([['connect:column-1']])
    expect(current.resizeColumn.mock.calls).toEqual([['connect:column-1', 260, false], ['connect:column-1', 260, true]])
    expect(wrapper.findComponent(ConnectColumnSetupPopover).exists()).toBe(false)
  })
  it('只有表头所在的主表头承载设置弹层，关闭时交还表格并移除草稿', async () => {
    const { global } = connectionTestContext()
    const draft: ConnectTableColumn = { key: 'connect-draft', kind: 'draft', label: '新连接', width: 200, minWidth: 140 }
    const current = table({ key: 'connect-draft', mode: 'create' })
    const grouped = mount(ConnectTableColumnHeader, { props: { column: draft, table: current, projectId: 'source-project', primary: false }, global })
    expect(grouped.findComponent(ConnectColumnSetupPopover).exists()).toBe(false)
    const wrapper = mount(ConnectTableColumnHeader, { props: { column: draft, table: current, projectId: 'source-project' }, global })
    const popover = wrapper.getComponent(ConnectColumnSetupPopover)
    expect(popover.props()).toMatchObject({ visible: true, mode: 'create', projectName: '华东现场实施', projectId: 'source-project' })
    await popover.props('submit')([{ id: 'target-project', name: 'Yumpoo 门户', code: 'P003' }])
    expect(current.connectProjects).toHaveBeenCalledWith([{ id: 'target-project', name: 'Yumpoo 门户', code: 'P003' }], undefined)
    popover.vm.$emit('update:visible', false)
    expect(current.closeSetup).toHaveBeenCalledOnce()
    expect(wrapper.getComponent(ConnectColumnHeader).find('.connect-column-header__menu').exists()).toBe(false)
  })
})
