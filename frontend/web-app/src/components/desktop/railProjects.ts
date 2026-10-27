import type { RailProject } from '@yumpoo/preload-contract'

/** Vue's reactive arrays hand out proxies, which cannot cross the context bridge; IPC gets plain copies. */
export const plainRailProjects = (items: readonly RailProject[]): RailProject[] =>
  items.map(({ id, name, label }) => label ? { id, name, label } : { id, name })
export const sameRailProjects = (a: readonly RailProject[], b: readonly RailProject[]) =>
  a.length === b.length && a.every((item, index) => item.id === b[index]!.id && item.name === b[index]!.name && item.label === b[index]!.label)
