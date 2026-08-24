import type { ProjectActorAccess, ProjectLifecycle, ProjectType } from '@yumpoo/api-client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const PROJECT_RECENTS_EVENT = 'yumpoo:project-recents-changed'

export interface ProjectRecentSource {
  id: string
  code: string
  name: string
  projectType: ProjectType
  lifecycle: ProjectLifecycle
  ownerUserId: string
  ownerDisplayName: string
  actorAccess: ProjectActorAccess
  createdAt: Date | string
  updatedAt: Date | string
}

export interface ProjectRecentEntry extends ProjectRecentSource {
  createdAt: string
  updatedAt: string
  openedAt: number
  pinned: boolean
}

function storageKey(scope: string): string {
  return `yumpoo.projects.recents.v1.${encodeURIComponent(scope)}`
}

function isRecentProject(value: unknown): value is ProjectRecentEntry {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<ProjectRecentEntry>
  return typeof candidate.id === 'string'
    && typeof candidate.code === 'string'
    && typeof candidate.name === 'string'
    && typeof candidate.projectType === 'string'
    && typeof candidate.lifecycle === 'string'
    && typeof candidate.ownerUserId === 'string'
    && typeof candidate.ownerDisplayName === 'string'
    && typeof candidate.actorAccess === 'string'
    && typeof candidate.createdAt === 'string'
    && typeof candidate.updatedAt === 'string'
    && typeof candidate.openedAt === 'number'
    && typeof candidate.pinned === 'boolean'
}

function ordered(items: ProjectRecentEntry[]): ProjectRecentEntry[] {
  return [...items].sort((left, right) => {
    if (left.pinned !== right.pinned) return left.pinned ? -1 : 1
    return right.openedAt - left.openedAt
  })
}

function read(scope: string | undefined): ProjectRecentEntry[] {
  if (!scope || typeof window === 'undefined') return []
  try {
    const stored = JSON.parse(window.localStorage.getItem(storageKey(scope)) ?? '[]') as unknown
    return Array.isArray(stored) ? ordered(stored.filter(isRecentProject)) : []
  } catch {
    return []
  }
}

function isoDate(value: Date | string): string {
  return (value instanceof Date ? value : new Date(value)).toISOString()
}

export function useProjectRecents(scope: () => string | undefined) {
  const items = ref<ProjectRecentEntry[]>([])

  function refresh(): void {
    items.value = read(scope())
  }

  function persist(next: ProjectRecentEntry[]): void {
    const activeScope = scope()
    items.value = ordered(next)
    if (!activeScope || typeof window === 'undefined') return
    try {
      window.localStorage.setItem(storageKey(activeScope), JSON.stringify(items.value))
      window.dispatchEvent(new CustomEvent(PROJECT_RECENTS_EVENT, { detail: { scope: activeScope } }))
    } catch {
      // 浏览器禁用持久化时仍保留当前页面内的最近项目状态。
    }
  }

  function record(project: ProjectRecentSource): void {
    const previous = items.value.find(item => item.id === project.id)
    persist([
      {
        id: project.id,
        code: project.code,
        name: project.name,
        projectType: project.projectType,
        lifecycle: project.lifecycle,
        ownerUserId: project.ownerUserId,
        ownerDisplayName: project.ownerDisplayName,
        actorAccess: project.actorAccess,
        createdAt: isoDate(project.createdAt),
        updatedAt: isoDate(project.updatedAt),
        openedAt: Date.now(),
        pinned: previous?.pinned ?? false,
      },
      ...items.value.filter(item => item.id !== project.id),
    ])
  }

  function togglePinned(projectId: string): void {
    persist(items.value.map(item => item.id === projectId ? { ...item, pinned: !item.pinned } : item))
  }

  function handleRecentsChanged(event: Event): void {
    const changedScope = (event as CustomEvent<{ scope?: string }>).detail?.scope
    if (changedScope === scope()) refresh()
  }

  function handleStorage(event: StorageEvent): void {
    const activeScope = scope()
    if (activeScope && event.key === storageKey(activeScope)) refresh()
  }

  watch(scope, refresh, { immediate: true })
  onMounted(() => {
    window.addEventListener(PROJECT_RECENTS_EVENT, handleRecentsChanged)
    window.addEventListener('storage', handleStorage)
  })
  onBeforeUnmount(() => {
    window.removeEventListener(PROJECT_RECENTS_EVENT, handleRecentsChanged)
    window.removeEventListener('storage', handleStorage)
  })

  return { items, record, refresh, togglePinned }
}
