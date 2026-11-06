import { ProjectActorAccess, ProjectLifecycle, type ProjectDetail, type WorkItemDetail } from '@yumpoo/api-client'
import { projectWritable } from './projectStatus'

/** 工作项讨论的发布权限与只读原因。 */
export function workItemDiscussionAccess(project: ProjectDetail | undefined, detail: WorkItemDetail | undefined) {
  const canPublish = Boolean(project && projectWritable(project) && detail?.capabilities?.canDiscuss
    && (project.actorAccess === ProjectActorAccess.Owner || project.actorAccess === ProjectActorAccess.Member))
  const readOnlyReason = detail?.archived ? '工作项已归档，恢复后可继续编辑和讨论。'
    : project?.lifecycle === ProjectLifecycle.Archived ? 'Project 已归档，工作项讨论仅可查看。'
      : project?.endedAt ? '项目已结束，工作项讨论仅可查看。'
        : canPublish ? undefined : '当前角色没有发布讨论的权限。'
  return { canPublish, readOnlyReason }
}
