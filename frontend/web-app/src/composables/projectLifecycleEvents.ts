export const PROJECT_LIFECYCLE_CHANGED = 'yumpoo:project-lifecycle-changed'

export function notifyProjectLifecycleChanged(projectId: string): void {
  window.dispatchEvent(new CustomEvent(PROJECT_LIFECYCLE_CHANGED, { detail: { projectId } }))
}
