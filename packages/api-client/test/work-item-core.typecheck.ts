import { WorkItemPriority } from '../src/generated/models/index.js'
import type {
  CreateWorkItemRequest,
  ListContentWorkItemsRequest,
} from '../src/generated/apis/WorkItemsApi.js'

const create: CreateWorkItemRequest = {
  contentId: '2a000000-0000-4000-8000-000000000401',
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '2a000000-0000-4000-8000-000000000402',
  workItemCreateRequest: {
    title: '实现 M2-10',
    priority: WorkItemPriority.Medium,
    description: null,
    notes: null,
  },
}

const groupedPage: ListContentWorkItemsRequest = {
  contentId: create.contentId,
  page: 0,
  size: 20,
  status: new Set(['BACKLOG', 'IN_PROGRESS']),
}

// @ts-expect-error M2-10 创建不开放处理人。
create.workItemCreateRequest.assigneeUserId = '2a000000-0000-4000-8000-000000000403'

// @ts-expect-error API 要求客户端明确提交优先级。
const missingPriorityBody: CreateWorkItemRequest['workItemCreateRequest'] = {
  title: '缺少优先级', description: null, notes: null,
}

void create
void groupedPage
void missingPriorityBody
