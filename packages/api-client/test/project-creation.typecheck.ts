import type { CreateProjectRequest } from '../src/generated/apis/ProjectsApi.js'
import { ProjectTemplateKey } from '../src/generated/models/ProjectTemplateKey.js'
import { ProjectType } from '../src/generated/models/ProjectType.js'

const createProject: CreateProjectRequest = {
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '24000000-0000-4000-8000-000000000001',
  projectCreateRequest: {
    workspaceId: '24000000-0000-4000-8000-000000000002',
    code: 'M2_04',
    name: 'M2-04 Project',
    description: null,
    projectType: ProjectType.ProductDevelopment,
    ownerUserId: '24000000-0000-4000-8000-000000000003',
    templateKey: ProjectTemplateKey.Rnd,
    templateVersion: 1,
    customerName: 'Yumpoo',
    customerReference: null,
    deliverySite: null,
    contactNote: null,
  },
}

void createProject
