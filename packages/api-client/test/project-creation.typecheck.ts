import type { CreateProjectRequest } from '../src/generated/apis/ProjectsApi.js'

const createProject: CreateProjectRequest = {
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '24000000-0000-4000-8000-000000000001',
  projectCreateRequest: { name: '统一项目', description: null },
}
// @ts-expect-error 编码由服务端生成，不允许客户端提交。
createProject.projectCreateRequest.code = 'P001'
// @ts-expect-error 创建命令必须携带幂等键。
const missingKey: CreateProjectRequest = { xXSRFTOKEN: 'csrf', projectCreateRequest: { name: '项目' } }
void createProject
void missingKey
