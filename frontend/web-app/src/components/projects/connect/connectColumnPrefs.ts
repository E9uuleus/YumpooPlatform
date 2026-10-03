import { connectColumnDefaultWidth, connectColumnMinWidth, type ConnectColumnKey } from './connectColumnKeys'

export interface ConnectColumnPrefs { hidden: ConnectColumnKey[]; widths: Partial<Record<ConnectColumnKey, number>> }
export function connectColumnPrefsKey(companyId: string, userId: string, projectId: string): string {
  return `yumpoo:project-work-items:connect:v1:${companyId}:${userId}:${projectId}`
}
export function readConnectColumnPrefs(key: string, keys: ConnectColumnKey[]): ConnectColumnPrefs {
  const defaults: ConnectColumnPrefs = { hidden: [], widths: Object.fromEntries(keys.map(key => [key, connectColumnDefaultWidth(key)])) }
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return defaults
    const parsed = value as { hidden?: unknown; widths?: unknown }
    if (Array.isArray(parsed.hidden)) defaults.hidden = keys.filter(key => (parsed.hidden as unknown[]).includes(key))
    if (parsed.widths && typeof parsed.widths === 'object' && !Array.isArray(parsed.widths)) {
      const widths = parsed.widths as Record<string, unknown>
      for (const key of keys) {
        const width = widths[key]
        if (typeof width === 'number' && Number.isFinite(width)) defaults.widths[key] = Math.max(connectColumnMinWidth(key), Math.round(width))
      }
    }
  } catch { /* A damaged or unavailable preference store does not block the table. */ }
  return defaults
}
export function saveConnectColumnPrefs(key: string, prefs: ConnectColumnPrefs): void {
  try { localStorage.setItem(key, JSON.stringify(prefs)) } catch { /* Keep preferences in memory when storage is unavailable. */ }
}
