import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConnectTableColumnHeader from './ConnectTableColumnHeader.vue'
import ConnectColumnHeader from './ConnectColumnHeader.vue'
import { connectColumn } from './connectTestFixtures'
import type { ConnectTableColumn } from './connectColumnKeys'

enableAutoUnmount(afterEach)
describe('主表、分组与子项共用的连接列表头', () => {
  it('按列键执行管理与调宽，并将隐藏事件交给表格重新布局', () => {
    const table = { canManage: true, canDelete: false, edit: vi.fn(), requestDelete: vi.fn(), resizeColumn: vi.fn() }
    const column: ConnectTableColumn = { key: 'connect:column-1', kind: 'connect', label: connectColumn.name, column: connectColumn, width: 200, minWidth: 140 }
    const wrapper = mount(ConnectTableColumnHeader, { props: { column, table } })
    const header = wrapper.getComponent(ConnectColumnHeader)
    expect(header.props()).toMatchObject({ canManage: true, canDelete: false, columnKey: 'connect:column-1', width: 200 })
    header.vm.$emit('edit'); header.vm.$emit('hide'); header.vm.$emit('delete')
    header.vm.$emit('resize', 260, false); header.vm.$emit('resize', 260, true)
    expect(table.edit).toHaveBeenCalledWith(connectColumn)
    expect(table.requestDelete).toHaveBeenCalledWith(connectColumn)
    expect(wrapper.emitted('hide')).toEqual([['connect:column-1']])
    expect(table.resizeColumn.mock.calls).toEqual([['connect:column-1', 260, false], ['connect:column-1', 260, true]])
  })
})
