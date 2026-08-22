import {
  ContentSortDirection,
  ContentSortField,
  ContentTableColumn,
  ContentViewType,
} from '../src/generated/models/index.js'
import type {
  ArchiveContentRequest,
  CreateContentRequest,
  UpdateContentRequest,
} from '../src/generated/apis/ContentsApi.js'

const create: CreateContentRequest = {
  projectId: '29000000-0000-4000-8000-000000000001',
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '29000000-0000-4000-8000-000000000002',
  contentCreateRequest: {
    code: 'REQ_CORE', name: '核心需求', description: null, blueprintCode: 'REQUIREMENT',
  },
}

const update: UpdateContentRequest = {
  contentId: '29000000-0000-4000-8000-000000000003',
  xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
  contentUpdateRequest: {
    name: '产品需求', description: null, defaultViewType: ContentViewType.Table,
    viewConfig: {
      table: {
        columnOrder: new Set([ContentTableColumn.Title, ContentTableColumn.Status]),
        hiddenColumns: new Set(),
        sort: [{ field: ContentSortField.UpdatedAt, direction: ContentSortDirection.Desc }],
        filters: { query: null, statusCodes: new Set(), priorities: new Set(),
          assigneeUserIds: new Set(), dueFrom: null, dueTo: null, updatedAfter: null },
      },
      kanban: { statusGroups: [{ name: '待办', statusCodes: new Set(['TODO']) }] },
    },
  },
}

const archive: ArchiveContentRequest = {
  contentId: update.contentId, xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
  idempotencyKey: '29000000-0000-4000-8000-000000000004',
}

// @ts-expect-error Content 类型只由 blueprintCode 派生。
create.contentCreateRequest.workItemType = 'TASK'
// @ts-expect-error 归档同时要求 If-Match。
const missingIfMatch: ArchiveContentRequest = {
  contentId: archive.contentId, xXSRFTOKEN: archive.xXSRFTOKEN,
  idempotencyKey: archive.idempotencyKey,
}

void create
void update
void archive
void missingIfMatch
