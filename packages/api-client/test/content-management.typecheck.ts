import { WorkItemLabelColorToken } from '../src/generated/models/index.js'
import type {
  CreateContentRequest,
  DeleteContentRequest,
  UpdateContentRequest,
} from '../src/generated/apis/ContentsApi.js'

const projectId = '29000000-0000-4000-8000-000000000001'
const contentId = '29000000-0000-4000-8000-000000000003'

const create: CreateContentRequest = {
  projectId,
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '29000000-0000-4000-8000-000000000002',
  contentCreateRequest: {
    name: '核心需求',
    colorToken: WorkItemLabelColorToken.BrightBlue,
  },
}

const update: UpdateContentRequest = {
  projectId,
  contentId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  contentUpdateRequest: {
    name: '产品需求',
    colorToken: WorkItemLabelColorToken.BrightBlue,
    active: true,
    sortOrder: 10,
  },
}

const remove: DeleteContentRequest = {
  projectId,
  contentId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
}

// @ts-expect-error Content 不再接受工作项类型。
create.contentCreateRequest.workItemType = 'TASK'
// @ts-expect-error Content 更新必须携带项目作用域。
const missingProject: UpdateContentRequest = {
  contentId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  contentUpdateRequest: update.contentUpdateRequest,
}

void create
void update
void remove
void missingProject
