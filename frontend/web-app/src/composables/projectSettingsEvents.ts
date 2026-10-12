import type { ProjectDetail } from '@yumpoo/api-client'

export const PROJECT_SETTINGS_CHANGED = 'yumpoo:project-settings-changed'

export function notifyProjectSettingsChanged(project: ProjectDetail): void {
  window.dispatchEvent(new CustomEvent(PROJECT_SETTINGS_CHANGED, { detail: project }))
}
