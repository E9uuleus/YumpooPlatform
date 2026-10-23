import Fastify from 'fastify'
import staticPlugin from '@fastify/static'
import rateLimit from '@fastify/rate-limit'
import { randomUUID } from 'node:crypto'
import { Hub, type Alert } from './hub.js'
import { parseEvent, type Config } from './model.js'
import { authenticateSource, equal, newToken, verifyPassword } from './security.js'
import { enqueue, safeText } from './delivery.js'

export async function createServers(hub: Hub, fresh: (alert: Alert) => boolean, discovery: () => boolean = () => false) {
  const config: Config = hub.config
  const ui = Fastify({ bodyLimit: 16_384, logger: false, requestTimeout: 10_000 })
  const ingress = Fastify({ bodyLimit: 131_072, logger: false, requestTimeout: 10_000 })
  await ui.register(rateLimit, { global: true, max: 300, timeWindow: 60_000 })
  await ingress.register(rateLimit, { global: true, max: 600, timeWindow: 60_000 })
  const sessions = new Map<string, { csrf: string; expires: number }>()
  let failures = 0; let blockedUntil = 0
  ui.addHook('onSend', async (_req, reply, payload) => {
    reply.header('x-content-type-options', 'nosniff').header('x-frame-options', 'DENY')
      .header('content-security-policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'")
      .header('referrer-policy', 'no-referrer').header('cache-control', 'no-store')
    return payload
  })
  ui.addHook('preHandler', async (req, reply) => {
    if (!req.url.startsWith('/api/v1/admin/')) return
    const token = req.headers.cookie?.split(';').map(v => v.trim()).find(v => v.startsWith('monitor_session='))?.slice(16) ?? ''
    const session = sessions.get(token)
    if (!session || session.expires < Date.now()) { sessions.delete(token); return reply.code(401).send({ error: '需要管理员登录' }) }
    if (req.method !== 'GET' && !equal(session.csrf, String(req.headers['x-csrf-token'] ?? ''))) return reply.code(403).send({ error: 'CSRF 校验失败' })
  })
  ui.post<{ Body: { username?: string; password?: string } }>('/api/v1/login', { config: { rateLimit: { max: 10, timeWindow: 60_000 } } }, async (req, reply) => {
    const now = Date.now()
    if (now < blockedUntil) return reply.code(429).send({ error: '请稍后重试' })
    if (typeof req.body?.password !== 'string' || req.body.password.length > 256 || !verifyPassword(config, String(req.body?.username ?? ''), req.body.password)) {
      failures += 1
      if (failures >= 5) { blockedUntil = now + 60_000; failures = 0 }
      hub.store.audit('login.failed', 'admin', now)
      return reply.code(401).send({ error: '账号或密码错误' })
    }
    failures = 0
    for (const [key, session] of sessions) if (session.expires < now) sessions.delete(key)
    if (sessions.size >= 20) sessions.clear()
    const token = newToken(); const csrf = newToken()
    sessions.set(token, { csrf, expires: now + 8 * 3600_000 })
    reply.header('set-cookie', `monitor_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800`)
    hub.store.audit('login.success', 'admin', now)
    return { csrf }
  })
  ui.get('/api/v1/status', async () => hub.status())
  ui.get('/api/v1/admin/overview', async () => ({
    ...hub.status(),
    channels: (['operations','announcements'] as const).map(channel => ({ channel, configured: Boolean(config.channels[channel]), counts: hub.store.all('SELECT state,count(*) count FROM deliveries WHERE channel=? GROUP BY state', channel) })),
    deliveries: hub.store.all('SELECT id,channel,content,state,attempts,created,sent,error FROM deliveries ORDER BY id DESC LIMIT 200'),
    alerts: hub.store.all("SELECT id,state,last_notice,body FROM alerts WHERE state!='resolved' ORDER BY last_notice DESC LIMIT 100"),
    audit: hub.store.all('SELECT * FROM audit ORDER BY id DESC LIMIT 100'),
  }))
  ui.post<{ Params: { id: string } }>('/api/v1/admin/deliveries/:id/retry', async (req, reply) => {
    const result = hub.store.run("UPDATE deliveries SET state='pending',attempts=0,next_at=?,error=NULL WHERE id=? AND state='dead' AND (expires IS NULL OR expires>?)", Date.now(), Number(req.params.id), Date.now())
    if (!result.changes) return reply.code(409).send({ error: '仅可重试尚未过期的失败消息' })
    hub.store.audit('delivery.retry', req.params.id, Date.now())
    return { ok: true }
  })
  ui.post<{ Body: { title?: string; service?: string; starts?: number; ends?: number } }>('/api/v1/admin/maintenance', async (req, reply) => {
    const b = req.body; const now = Date.now()
    if (!b || typeof b.title !== 'string' || !b.title.trim() || b.title.length > 120 || !config.services.some(s => s.id === b.service)
      || !Number.isSafeInteger(b.starts) || !Number.isSafeInteger(b.ends) || b.starts! < now - 60_000 || b.ends! <= b.starts! || b.ends! - b.starts! > 86400_000) return reply.code(400).send({ error: '维护时间需在未来且不超过 24 小时' })
    const id = randomUUID()
    hub.store.transaction(() => {
      hub.store.run("INSERT INTO maintenance VALUES(?,?,?,?,?,'active',0)", id, b.service!, safeText(b.title!), b.starts!, b.ends!)
      hub.store.audit('maintenance.create', id, now)
      enqueue(hub.store, `${id}:created`, 'announcements', `计划维护：${safeText(b.title!)}\n开始：${new Date(b.starts!).toISOString()}\n结束：${new Date(b.ends!).toISOString()}`, now, null, b.ends!)
    })
    return { id }
  })
  ui.post<{ Params: { id: string }; Body: { action?: string; ends?: number } }>('/api/v1/admin/maintenance/:id', async (req, reply) => {
    const row = hub.store.get<{ automatic: number; service: string; starts: number; state: string }>('SELECT * FROM maintenance WHERE id=?', req.params.id)
    if (!row || row.automatic || row.state !== 'active') return reply.code(409).send({ error: '自动或已结束的维护不可修改' })
    const action = req.body?.action; const now = Date.now()
    if (!['postpone','cancel','end'].includes(action ?? '')) return reply.code(400).send({ error: '无效操作' })
    if (action === 'end') {
      const observed = hub.status(now).services.find(s => s.id === row.service)
      const sample = hub.store.get<{ recovery_since: number | null }>('SELECT recovery_since FROM observations WHERE service=?', row.service)
      if (observed?.observed !== 'healthy' || !sample?.recovery_since || now - sample.recovery_since < 60_000) return reply.code(409).send({ error: '需要连续健康 60 秒后结束维护' })
    }
    if (action === 'postpone' && (!Number.isSafeInteger(req.body.ends) || req.body.ends! <= now || req.body.ends! - row.starts > 86400_000)) return reply.code(400).send({ error: '维护总时长不得超过 24 小时' })
    hub.store.transaction(() => {
      if (action === 'postpone') hub.store.run('UPDATE maintenance SET ends=? WHERE id=?', req.body.ends!, req.params.id)
      else hub.store.run('UPDATE maintenance SET state=? WHERE id=?', action === 'end' ? 'ended' : 'cancelled', req.params.id)
      hub.store.run("UPDATE deliveries SET state='superseded' WHERE dedupe=? AND state IN ('pending','dead')", `${req.params.id}:created`)
      hub.store.audit(`maintenance.${action}`, req.params.id, now)
      enqueue(hub.store, `${req.params.id}:${action}:${now}`, 'announcements', `维护安排${action === 'postpone' ? '已延期' : action === 'end' ? '已结束，服务恢复' : '已取消'}。`, now)
    })
    return { ok: true }
  })
  ui.post<{ Body: { text?: string } }>('/api/v1/admin/preview', async req => ({ text: safeText(String(req.body?.text ?? '')).slice(0, 1000) }))
  ingress.removeContentTypeParser('application/json')
  ingress.addContentTypeParser('application/json', { parseAs: 'string' }, (_req, body, done) => done(null, body))
  ingress.get('/healthz', async () => ({ status: 'UP' }))
  ingress.get('/metrics', async (_req, reply) => {
    const pending = hub.store.get<{ n: number }>("SELECT count(*) n FROM deliveries WHERE state='pending'")!.n
    const dead = hub.store.get<{ n: number }>("SELECT count(*) n FROM deliveries WHERE state='dead'")!.n
    return reply.type('text/plain').send(`monitor_delivery_pending ${pending}\nmonitor_delivery_dead ${dead}\nmonitor_discovery_success ${discovery() ? 1 : 0}\nmonitor_process_timestamp_seconds ${Date.now()/1000}\n`)
  })
  ingress.post('/internal/v1/events', async (req, reply) => {
    try {
      const body = String(req.body)
      const source = authenticateSource(config, hub.store, req.headers, body)
      const event = parseEvent(JSON.parse(body), config.environment)
      if (source !== event.source || !config.sources[source]!.types.includes(event.type) || Date.parse(event.occurredAt) > Date.now() + 300_000) return reply.code(403).send({ error: 'Forbidden event' })
      return hub.accept(event)
    } catch { return reply.code(400).send({ error: 'Rejected event or authentication' }) }
  })
  ingress.post('/internal/v1/alerts', async (req, reply) => {
    if (!equal(String(req.headers.authorization ?? ''), `Bearer ${config.alertToken}`)) return reply.code(401).send({ error: 'Unauthorized' })
    try {
      const payload = JSON.parse(String(req.body)) as { alerts: Alert[] }
      if (!Array.isArray(payload.alerts) || payload.alerts.length > 100 || payload.alerts.some(a => !a.labels || !a.annotations)) throw new Error('Invalid alerts')
      hub.receiveAlerts(payload.alerts, fresh)
      return { ok: true }
    } catch { return reply.code(400).send({ error: 'Invalid alert payload' }) }
  })
  if (config.uiRoot) await ui.register(staticPlugin, { root: config.uiRoot })
  return { ui, ingress }
}
