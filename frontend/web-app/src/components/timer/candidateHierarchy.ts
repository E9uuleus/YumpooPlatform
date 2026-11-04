import type { TimerCandidate } from '@yumpoo/api-client'

export interface HierarchyParent { key: string; title: string; completed: boolean }

/** `item` is null for the read-only title row standing in for a parent that is not in the list. */
export interface HierarchyRow<T> { key: string; item: T | null; parent: HierarchyParent | null; child: boolean; last: boolean }

/** Children follow their parent; each block sits where the parent or any of its children first appears. */
export function withHierarchy<T>(items: readonly T[], keyOf: (item: T) => string, parentOf: (item: T) => HierarchyParent | null): HierarchyRow<T>[] {
  const byKey = new Map(items.map(item => [keyOf(item), item]))
  const children = new Map<string, T[]>()
  for (const item of items) {
    const parent = parentOf(item)
    if (parent) children.set(parent.key, [...children.get(parent.key) ?? [], item])
  }
  const placed = new Set<string>()
  const rows: HierarchyRow<T>[] = []
  for (const item of items) {
    const parent = parentOf(item)
    const rootKey = parent?.key ?? keyOf(item)
    if (placed.has(rootKey)) continue
    placed.add(rootKey)
    const root = byKey.get(rootKey)
    rows.push(root
      ? { key: rootKey, item: root, parent: null, child: false, last: false }
      : { key: `parent:${rootKey}`, item: null, parent: parent!, child: false, last: false })
    const branch = children.get(rootKey) ?? []
    branch.forEach((child, index) => rows.push({ key: keyOf(child), item: child, parent: null, child: true, last: index === branch.length - 1 }))
  }
  return rows
}

export function candidateParent(item: TimerCandidate): HierarchyParent | null {
  return item.parentWorkItemId
    ? { key: item.parentWorkItemId, title: item.parentTitle ?? '', completed: item.parentCompleted === true }
    : null
}
