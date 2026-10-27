export type Channel = 'operations' | 'announcements'
export type Health = 'healthy' | 'down' | 'unknown'
export type EventType = 'maintenance.planned' | 'maintenance.started' | 'deployment.completed' | 'deployment.recovered' | 'workflow.failed' | 'workflow.notice'
export interface ReleaseEvent {
  schemaVersion: 1
  id: string
  source: 'windows' | 'github'
  environment: string
  type: EventType
  occurredAt: string
  correlationId: string
  payload: {
    target: 'server' | 'desktop'
    mode?: 'deploy' | 'rollback' | 'recover'
    tag?: string
    commit?: string
    outcome?: 'succeeded' | 'failed' | 'rolled_back' | 'recovery_required'
    phase?: string
    workflowUrl?: string
    notes?: string[]
    summary?: string
  }
}
export interface Config {
  environment: string
  database: string
  siteUrl: string
  prometheusUrl: string
  uiRoot: string
  discoveryFile?: string
  admin: { username: string; salt: string; passwordHash: string }
  sources: Record<string, { secret: string; types: EventType[] }>
  alertToken: string
  channels: Partial<Record<Channel, string>>
  services: { id: string; name: string; checks: string[] }[]
}
export interface Observation { service: string; state: Health; at: number }
export interface Verification { healthy: boolean; tag?: string; commit?: string; notes?: string[] }

export function parseEvent(value: unknown, environment: string): ReleaseEvent {
  if (!value || typeof value !== 'object') throw new Error('Invalid event')
  const e = value as ReleaseEvent
  if (Object.keys(e).some(key => !['schemaVersion','id','source','environment','type','occurredAt','correlationId','payload'].includes(key))) throw new Error('Unknown event field')
  const token = /^[a-zA-Z0-9._:-]{1,160}$/u
  if (e.schemaVersion !== 1 || !token.test(e.id ?? '') || !token.test(e.correlationId ?? '')
    || !['windows', 'github'].includes(e.source) || e.environment !== environment
    || !['maintenance.planned', 'maintenance.started', 'deployment.completed', 'deployment.recovered', 'workflow.failed', 'workflow.notice'].includes(e.type)
    || !Number.isFinite(Date.parse(e.occurredAt)) || !e.payload
    || !['server', 'desktop'].includes(e.payload.target)) throw new Error('Invalid event')
  if (Object.keys(e.payload).some(key => !['target','mode','tag','commit','outcome','phase','workflowUrl','notes','summary'].includes(key))) throw new Error('Unknown payload field')
  if (e.source === 'github' && !['workflow.failed','workflow.notice'].includes(e.type)) throw new Error('Source cannot report host state')
  if (e.type.startsWith('maintenance.') && e.payload.target !== 'server') throw new Error('Invalid maintenance target')
  if (e.payload.tag && !/^(server-v\d{4}\.\d{2}\.\d{2}\.\d+|desktop-v\d+\.\d+\.\d+)$/u.test(e.payload.tag)) throw new Error('Invalid release tag')
  if (e.payload.commit && !/^[a-f0-9]{40}$/u.test(e.payload.commit)) throw new Error('Invalid commit')
  if (e.type.startsWith('deployment.') && !['succeeded', 'failed', 'rolled_back', 'recovery_required'].includes(e.payload.outcome ?? '')) throw new Error('Invalid outcome')
  if (e.type === 'deployment.recovered' && (e.payload.mode !== 'recover' || e.payload.outcome !== 'succeeded' || e.payload.target !== 'server')) throw new Error('Invalid recovery event')
  if (e.payload.outcome === 'succeeded' && (!e.payload.commit || !e.payload.tag)) throw new Error('Missing release identity')
  if (e.payload.workflowUrl && !/^https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/actions\/runs\/\d+$/u.test(e.payload.workflowUrl)) throw new Error('Invalid workflow URL')
  if (e.payload.notes && (!Array.isArray(e.payload.notes) || e.payload.notes.length > 30 || e.payload.notes.some(n => typeof n !== 'string' || n.length > 1000))) throw new Error('Invalid release notes')
  if (e.payload.phase && !/^[a-z_]{1,80}$/u.test(e.payload.phase)) throw new Error('Invalid phase')
  if (e.payload.summary && (typeof e.payload.summary !== 'string' || e.payload.summary.length > 2000)) throw new Error('Invalid summary')
  if (e.payload.mode && !['deploy', 'rollback', 'recover'].includes(e.payload.mode)) throw new Error('Invalid mode')
  return e
}
