import { ProjectLifecycle } from '@yumpoo/api-client'

export type ProjectStatus = 'ACTIVE' | 'ENDED' | 'ARCHIVED'
type ProjectState = { lifecycle: ProjectLifecycle; endedAt?: Date | string | null }

/** 进行中 / 已结束 / 已归档: an ended project keeps lifecycle ACTIVE and carries endedAt. */
export const projectStatus = (project: ProjectState): ProjectStatus =>
  project.lifecycle === ProjectLifecycle.Archived ? 'ARCHIVED' : project.endedAt ? 'ENDED' : 'ACTIVE'

/** Work items, discussions and time can change only in a running project. */
export const projectWritable = (project: ProjectState) => projectStatus(project) === 'ACTIVE'
