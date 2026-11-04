import { WorkItemLabelColorToken, WorkItemStatusCategory, type ProjectWorkItemListItem } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkItemKanbanBoard, { type KanbanBoardDraft, type KanbanBoardLane } from './WorkItemKanbanBoard.vue'
import WorkItemKanbanCard from './WorkItemKanbanCard.vue'

vi.mock('../../api/client', () => ({ workItemsApi: {} }))
enableAutoUnmount(afterEach)

function item(id: string, patch: Partial<ProjectWorkItemListItem> = {}): ProjectWorkItemListItem {
  return {
    id, projectId: 'project-1', contentId: 'content-1', contentName: '任务', contentColorToken: WorkItemLabelColorToken.BrightBlue,
    itemNo: id.toUpperCase(), title: `标题 ${id}`, statusCode: 'DOING', statusCategory: WorkItemStatusCategory.InProgress,
    priority: null, assigneeUserId: null, assigneeDisplayName: null, dueDate: null, subitemCount: 0, rowVersion: 1, etag: '"1"',
    updatedAt: new Date('2026-11-01T00:00:00Z'), discussionCount: 0,
    capabilities: { canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: true, canDiscuss: true, canDelete: true,
      canRestore: false, availableTransitions: [] },
    ...patch,
  } as ProjectWorkItemListItem
}

const statuses = [
  { statusCode: 'DOING', displayName: '进行中', colorToken: WorkItemLabelColorToken.Orange, active: true },
  { statusCode: 'OLD', displayName: '旧状态', colorToken: WorkItemLabelColorToken.Gray, active: false },
]

function board(lanes: Record<string, KanbanBoardLane<ProjectWorkItemListItem>>, counts?: Map<string, number>) {
  const drafts: Record<string, KanbanBoardDraft> = {}
  return mount(WorkItemKanbanBoard<ProjectWorkItemListItem>, {
    props: {
      statuses, counts,
      lane: (code: string) => lanes[code] ?? { items: [], nextCursor: null, loading: false },
      createDisabledReason: status => status.active ? '' : '此状态已停用',
      creatingTop: () => false,
      draft: (code: string) => drafts[code] ??= { open: false, title: '', saving: false },
      dragging: false,
    },
    slots: { card: '<template #card="{ item }"><article class="test-card">{{ item.title }}</article></template>' },
  })
}

describe('看板列', () => {
  it('列头显示状态色块、名称与总数，可新建的列才有加号与列尾新建行', () => {
    const wrapper = board({ DOING: { items: [item('a'), item('b')], nextCursor: null, loading: false } }, new Map([['DOING', 12]]))
    const lanes = wrapper.findAll('.kanban-lane')
    expect(lanes).toHaveLength(2)
    expect(lanes[0]!.attributes('style')).toContain('--kanban-lane-color')
    expect(lanes[0]!.get('.kanban-lane__title').text()).toBe('进行中')
    expect(lanes[0]!.get('.kanban-lane__count').text()).toBe('12')
    expect(lanes[0]!.findAll('.test-card').map(card => card.text())).toEqual(['标题 a', '标题 b'])
    expect(lanes[0]!.find('.kanban-lane__create').exists()).toBe(true)
    expect(lanes[0]!.find('.kanban-lane__add').exists()).toBe(true)
    expect(lanes[1]!.find('.kanban-lane__create').exists()).toBe(false)
    expect(lanes[1]!.find('.kanban-lane__add').exists()).toBe(false)
  })

  it('还有下一页时显示懒加载哨兵而不是列尾新建行；无总数时显示已加载数量', () => {
    const wrapper = board({ DOING: { items: [item('a')], nextCursor: 'next', loading: false } })
    const lane = wrapper.findAll('.kanban-lane')[0]!
    expect(lane.get('.kanban-lane__count').text()).toBe('1+')
    expect(lane.find('.lane-cursor-sentinel').exists()).toBe(true)
    expect(lane.find('.kanban-lane__add').exists()).toBe(false)
  })

  it('加号与列尾新建行发出对应事件', async () => {
    const wrapper = board({ DOING: { items: [], nextCursor: null, loading: false } })
    await wrapper.get('.kanban-lane__create').trigger('click')
    await wrapper.get('.kanban-lane__add').trigger('click')
    expect(wrapper.emitted('createTop')).toEqual([['DOING']])
    expect(wrapper.emitted('openDraft')).toEqual([['DOING']])
  })
})

function card(patch: Partial<ProjectWorkItemListItem> = {}, fields = ['content', 'status', 'priority', 'dueDate'] as const) {
  return mount(WorkItemKanbanCard, {
    attachTo: document.body,
    props: {
      item: item('a', patch), projectId: 'project-1', fields: [...fields], showAssignee: true,
      workflowStatuses: [{ ...statuses[0]!, statusCategory: 'IN_PROGRESS', sortOrder: 1 }],
      priorityOptions: [{ code: 'HIGH', displayName: '高', colorToken: WorkItemLabelColorToken.Red, active: true, sortOrder: 1 }],
      activeContents: [{ id: 'content-1', name: '任务', colorToken: WorkItemLabelColorToken.BrightBlue }],
      canCreate: true, busy: false, subitemCount: patch.subitemCount ?? 0, expanded: false,
    },
    global: { stubs: { WorkItemTimerCell: true, WorkItemRowActions: true, YpAssigneeStack: true } },
  })
}

describe('看板卡片', () => {
  it('按可见列顺序显示标签：类别、状态和已设置的优先级带色条，空字段为占位标签', async () => {
    const wrapper = card({ priority: 'HIGH' })
    await flushPromises()
    const tags = wrapper.findAll('.kanban-card__tags .work-item-kanban-tag')
    expect(tags.map(tag => tag.text())).toEqual(['任务', '进行中', '高', '截止日期'])
    expect(tags.slice(0, 3).every(tag => tag.classes().includes('work-item-kanban-tag--bar'))).toBe(true)
    expect(tags[2]!.attributes('style')).toContain('--kanban-tag-color')
    expect(tags[3]!.classes()).toContain('work-item-kanban-tag--placeholder')
    expect(wrapper.findAll('.kanban-card__field--empty')).toHaveLength(2)
  })

  it('点击卡片空白处打开详情，讨论按钮打开讨论，子项按钮展开子项', async () => {
    const wrapper = card({ subitemCount: 2, discussionCount: 3 })
    await wrapper.get('.kanban-card').trigger('click')
    await wrapper.get('.kanban-card__icon-button').trigger('click')
    await wrapper.get('.kanban-card__subitems-button').trigger('click')
    expect(wrapper.emitted('open')?.map(([, tab]) => tab)).toEqual(['details', 'discussion'])
    expect(wrapper.emitted('toggleSubitems')).toHaveLength(1)
    expect(wrapper.get('.kanban-card__subitems-button').text()).toContain('2')
  })

  it('展开时显示子项名称与添加子项行，点击子项打开其详情', async () => {
    const wrapper = card({ subitemCount: 1 })
    await wrapper.setProps({ expanded: true, subitems: { items: [item('child')], loading: false, loaded: true } })
    const children = wrapper.findAll('.kanban-subitem')
    expect(children.map(child => child.text())).toEqual(['标题 child', '+ 添加子项'])
    await children[0]!.trigger('click')
    expect(wrapper.emitted('open')?.[0]?.[0]).toMatchObject({ id: 'child' })
  })
})
