/**
 * Pages only the desktop shell loads, each in its own window outside AppShell. They all live under /timer:
 * production nginx serves /desktop/ as the static update feed, so no page may use that prefix.
 * public/appearance-bootstrap.js repeats this list because it runs before the bundle.
 */
export const SHELL_SURFACE = {
  timer: '/timer',
  menu: '/timer/menu',
  quick: '/timer/quick',
  rail: '/timer/rail',
} as const

export const shellSurfacePaths: readonly string[] = Object.values(SHELL_SURFACE)

/** The quick panel and the rail keep the tracker running in their own windows but are never the timer surface. */
export function isPanelSurface(path: string): boolean {
  return path === SHELL_SURFACE.quick || path === SHELL_SURFACE.rail
}
