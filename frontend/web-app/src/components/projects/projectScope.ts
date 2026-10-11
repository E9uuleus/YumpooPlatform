import { ProjectActorAccess } from '@yumpoo/api-client'

/** 工作台范围：本人负责或加入的项目；公司全部项目只在「公司管理 → 项目」列出。 */
export const MY_PROJECT_ACCESSES: ProjectActorAccess[] = [ProjectActorAccess.Owner, ProjectActorAccess.Member]
