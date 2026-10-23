import { randomUUID } from 'node:crypto'
import type { Config, Observation, ReleaseEvent, Verification } from './model.js'
import { Store } from './store.js'
import { enqueue, safeText } from './delivery.js'

interface Observed { service: string; state: string; at: number; since: number; recovery_since: number | null; incident: string | null; ops: number; public: number }
export interface Alert { fingerprint: string; status: 'firing' | 'resolved'; labels: Record<string, string>; annotations: Record<string, string>; startsAt: string }
const rank = { 'maintenance.planned': 1, 'maintenance.started': 2, 'deployment.completed': 3, 'deployment.recovered': 4, 'workflow.failed': 0, 'workflow.notice': 0 }

export class Hub {
  constructor(public store: Store, public config: Config) {}
  accept(event: ReleaseEvent, now = Date.now()) {
    return this.store.transaction(() => {
      if (this.store.get('SELECT id FROM events WHERE id=?', event.id)) return { duplicate: true }
      this.store.run('INSERT INTO events VALUES(?,?,?,?,?)', event.id, event.correlationId, event.type, now, JSON.stringify(event))
      const existing = this.store.get<{ rank: number }>('SELECT rank FROM deployments WHERE id=?', event.correlationId)
      if (existing && existing.rank >= rank[event.type]) return { obsolete: true }
      this.store.run('INSERT INTO deployments(id,rank,event,verify_until) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET rank=excluded.rank,event=excluded.event,verify_until=excluded.verify_until,verified=0', event.correlationId, rank[event.type], JSON.stringify(event), event.type.startsWith('deployment.') ? now + 600_000 : null)
      const title = event.payload.target === 'server' ? '服务端' : '桌面端'
      const group = `release:${event.correlationId}`
      if (event.type !== 'maintenance.planned') this.store.run("UPDATE deliveries SET state='superseded' WHERE dedupe=? AND state IN ('pending','dead')", `${event.correlationId}:planned`)
      if (event.type === 'maintenance.planned') {
        const expires = Date.parse(event.occurredAt) + 300_000
        if (expires > now) enqueue(this.store, `${event.correlationId}:planned`, 'announcements', 'Yumpoo 服务端计划更新\n预计 5 分钟后进入维护，期间可能暂时无法访问。', now, group, expires)
      } else if (event.type === 'maintenance.started') {
        const start = Date.parse(event.occurredAt)
        if (start + 1800_000 > now) {
          this.store.run("INSERT OR IGNORE INTO maintenance VALUES(?,?,?,?,?,'active',1)", event.correlationId, 'server', '服务端更新维护', start, start + 1800_000)
          enqueue(this.store, `${event.correlationId}:maintenance`, 'announcements', 'Yumpoo 服务端正在维护，完成复核后将通知恢复。', now, group, start + 1800_000)
        }
      } else if (event.type.startsWith('deployment.')) {
        enqueue(this.store, `${event.id}:host-result`, 'operations', `${title}主机结果：${event.payload.outcome}\n版本：${safeText(event.payload.tag ?? '未知')}\n阶段：${event.payload.phase ?? 'complete'}\n异机复核中。`, now)
        if (event.payload.outcome === 'recovery_required') {
          this.store.run("UPDATE maintenance SET state='failed' WHERE id=?", event.correlationId)
          enqueue(this.store, `${event.correlationId}:recovery-required`, 'announcements', 'Yumpoo 服务恢复遇到异常，管理员正在处理。当前可用性以实际访问情况为准。', now, group)
        }
      } else {
        enqueue(this.store, event.id, 'operations', `${event.type === 'workflow.failed' ? '发布流程失败' : '发布流程通知'}\n${safeText(event.payload.summary ?? event.payload.tag ?? '')}\n${event.payload.workflowUrl ?? ''}`, now)
      }
      this.store.audit('event.accepted', event.id, now)
      return { accepted: true }
    })
  }
  maintained(service: string, now: number) {
    return !!this.store.get("SELECT id FROM maintenance WHERE service=? AND state='active' AND starts<=? AND ends>?", service, now, now)
  }
  observe(observation: Observation, now = Date.now()) {
    if (!this.config.services.some(s => s.id === observation.service)) throw new Error('Unknown service')
    this.store.transaction(() => {
      const old = this.store.get<Observed>('SELECT * FROM observations WHERE service=?', observation.service)
      const state = now - observation.at > 90_000 ? 'unknown' : observation.state
      const continuous = old && now - old.at < 90_000
      const since = continuous && old.state === state ? old.since : now
      const row: Observed = { service: observation.service, state, at: observation.at, since, recovery_since: state === 'healthy' ? (continuous ? old.recovery_since ?? now : now) : null, incident: old?.incident ?? null, ops: old?.ops ?? 0, public: old?.public ?? 0 }
      const maintenance = this.maintained(row.service, now)
      const last = this.store.get<{maintained:number}>('SELECT maintained FROM history WHERE service=? ORDER BY id DESC LIMIT 1', row.service)
      if (!old || old.state !== state || last?.maintained !== Number(maintenance)) this.store.run('INSERT INTO history(service,state,at,maintained) VALUES(?,?,?,?)', row.service, state, now, Number(maintenance))
      if (state === 'down' && !maintenance) {
        if (!row.incident) row.incident = randomUUID()
        if (!row.ops && now - since >= 90_000) {
          enqueue(this.store, `${row.incident}:ops`, 'operations', `${this.name(row.service)}持续访问异常，请检查服务与依赖。`, now, row.incident)
          row.ops = 1
        }
        if (!row.public && now - since >= 300_000) {
          enqueue(this.store, `${row.incident}:public`, 'announcements', `${this.name(row.service)}访问异常已持续 5 分钟，管理员正在处理。`, now, row.incident)
          row.public = 1
        }
      }
      if (state === 'healthy' && row.incident && row.recovery_since !== null && now - row.recovery_since >= 60_000) {
        this.store.run("UPDATE deliveries SET state='superseded' WHERE incident=? AND state IN ('pending','dead')", row.incident)
        if (row.ops) enqueue(this.store, `${row.incident}:recovered:ops`, 'operations', `${this.name(row.service)}已连续正常 60 秒，访问恢复。`, now)
        if (row.public) enqueue(this.store, `${row.incident}:recovered:public`, 'announcements', `${this.name(row.service)}已恢复访问。`, now)
        row.incident = null; row.ops = 0; row.public = 0
      }
      this.store.run('INSERT OR REPLACE INTO observations VALUES(?,?,?,?,?,?,?,?)', row.service, row.state, row.at, row.since, row.recovery_since, row.incident, row.ops, row.public)
    })
  }
  name(service: string) { return this.config.services.find(s => s.id === service)?.name ?? '服务' }
  async verifyDeployments(verify: (event: ReleaseEvent) => Promise<Verification>, now = Date.now()) {
    const pending = this.store.all<{ id: string; event: string; verify_until: number }>('SELECT * FROM deployments WHERE rank>=3 AND verified=0')
    for (const row of pending) {
      const event = JSON.parse(row.event) as ReleaseEvent
      let result: Verification
      try { result = await verify(event) } catch { result = { healthy: false } }
      const success = event.payload.outcome === 'succeeded'
      const matches = !success || (result.tag === event.payload.tag && result.commit === event.payload.commit)
      if (result.healthy && matches && event.payload.outcome !== 'recovery_required') {
        this.store.transaction(() => {
          const mode = event.payload.mode
          const actual = safeText(result.tag ?? '未知版本')
          const restored = !success || mode === 'rollback' || mode === 'recover'
          const notes = (result.notes ?? event.payload.notes ?? []).filter(n => !/^[-\s]*运维[｜|]/u.test(n)).map(safeText).join('\n')
          const text = restored ? `Yumpoo 服务已恢复\n当前版本：${actual}` : `${event.payload.target === 'desktop' ? 'Yumpoo 桌面端更新已发布' : 'Yumpoo 服务端更新完成'}\n版本：${actual}\n${notes}`
          const hadMaintenance = !!this.store.get('SELECT id FROM maintenance WHERE id=?', row.id)
          if ((success && event.payload.phase !== 'already_current' && event.payload.phase !== 'already_published') || (hadMaintenance && event.payload.target === 'server')) enqueue(this.store, `${event.id}:verified:public`, 'announcements', text, now)
          const operationsNotes = (event.payload.notes ?? []).filter(n => /^[-\s]*运维[｜|]/u.test(n)).map(safeText).join('\n')
          enqueue(this.store, `${event.id}:verified:ops`, 'operations', `${text}\n${operationsNotes}\n异机健康与版本复核通过。`, now)
          this.store.run('UPDATE deployments SET verified=1 WHERE id=?', row.id)
          this.store.run("UPDATE maintenance SET state='ended' WHERE id=?", row.id)
          this.store.run("UPDATE deliveries SET state='superseded' WHERE incident=? AND state IN ('pending','dead')", `release:${row.id}`)
        })
      } else if (now >= row.verify_until) {
        this.store.transaction(() => {
          enqueue(this.store, `${row.id}:verification-failed`, 'operations', `发布后的异机复核未通过；未公告新版本上线。\n目标：${safeText(event.payload.tag ?? '')}\n请检查健康、公开版本及更新源。`, now)
          this.store.run('UPDATE deployments SET verified=-1 WHERE id=?', row.id)
          this.store.run("UPDATE maintenance SET state='failed' WHERE id=?", row.id)
        })
      }
    }
  }
  receiveAlerts(alerts: Alert[], fresh: (alert: Alert) => boolean, now = Date.now()) {
    this.store.transaction(() => {
      for (const alert of alerts) {
        if (!/^[a-f0-9]{16,64}$/u.test(alert.fingerprint) || !['firing','resolved'].includes(alert.status)) continue
        const id = alert.fingerprint
        const previous = this.store.get<{ state: string; last_notice: number; notified: number; body: string }>('SELECT * FROM alerts WHERE id=?', id)
        if (previous && Date.parse(alert.startsAt) < Date.parse((JSON.parse(previous.body) as Alert).startsAt)) continue
        const isFresh = fresh(alert)
        if (alert.status === 'resolved' && !isFresh) {
          if (previous && previous.state !== 'resolved') this.store.run("UPDATE alerts SET body=?,state='pending_resolved' WHERE id=?", JSON.stringify(alert), id)
          continue
        }
        if (alert.status === 'firing' && this.maintained(alert.labels.service ?? '', now) && alert.labels.expected_maintenance === 'true') {
          this.store.run('INSERT OR REPLACE INTO alerts VALUES(?,?,?,?,?)', id, JSON.stringify(alert), 'suppressed', previous?.last_notice ?? 0, previous?.notified ?? 0)
          continue
        }
        if (alert.status === 'resolved') {
          if (!previous || previous.state === 'resolved') continue
          this.store.run("UPDATE deliveries SET state='superseded' WHERE incident=? AND state IN ('pending','dead')", id)
          if (previous.notified) enqueue(this.store, `${id}:resolved:${previous.last_notice}`, 'operations', `告警恢复：${safeText(alert.labels.alertname ?? '监控告警')}`, now)
        } else if (!previous || !previous.notified || previous.state === 'resolved' || (alert.labels.severity === 'critical' && now - previous.last_notice >= 3600_000)) {
          enqueue(this.store, `${id}:firing:${now}`, 'operations', `监控告警：${safeText(alert.labels.alertname ?? '')}\n等级：${safeText(alert.labels.severity ?? '')}\n${safeText(alert.annotations.summary ?? '')}`, now, id)
        } else {
          this.store.run('UPDATE alerts SET body=?,state=? WHERE id=?', JSON.stringify(alert), alert.status, id)
          continue
        }
        this.store.run('INSERT OR REPLACE INTO alerts VALUES(?,?,?,?,?)', id, JSON.stringify(alert), alert.status, now, alert.status === 'firing' ? 1 : 0)
      }
    })
  }
  reconcileAlerts(fresh: (alert: Alert) => boolean, now = Date.now()) {
    const pending = this.store.all<{body:string}>("SELECT body FROM alerts WHERE state IN ('pending_resolved','suppressed','firing')")
    this.receiveAlerts(pending.map(row => JSON.parse(row.body) as Alert), fresh, now)
  }
  expireMaintenance(now = Date.now()) {
    this.store.transaction(() => {
      for (const row of this.store.all<{ id: string }>("SELECT id FROM maintenance WHERE state='active' AND ends<=?", now)) {
        enqueue(this.store, `${row.id}:expired`, 'operations', '维护窗口已超时，已恢复告警；请检查服务恢复情况。', now)
        this.store.run("UPDATE maintenance SET state='expired' WHERE id=?", row.id)
      }
    })
  }
  status(now = Date.now()) {
    return {
      updatedAt: now,
      services: this.config.services.map(service => {
        const row = this.store.get<Observed>('SELECT * FROM observations WHERE service=?', service.id)
        const observed = row && now - row.at < 90_000 ? row.state : 'unknown'
        return { id: service.id, name: service.name, state: this.maintained(service.id, now) ? 'maintenance' : observed, observed, sampledAt: row?.at ?? null }
      }),
      maintenance: this.store.all("SELECT id,service,title,starts,ends,state,automatic FROM maintenance WHERE ends>? ORDER BY starts", now - 86400_000),
      history: this.store.all('SELECT service,state,at,maintained FROM history ORDER BY id DESC LIMIT 100'),
    }
  }
}
