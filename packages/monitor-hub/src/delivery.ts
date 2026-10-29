import type { Channel, Config } from './model.js'
import { Store } from './store.js'
import { createHash } from 'node:crypto'

export function boundedText(value: string, max = 3900): string {
  let text = ''; let bytes = 0
  for (const point of value) {
    const size = Buffer.byteLength(point)
    if (bytes + size > max) return text + '\n…'
    bytes += size; text += point
  }
  return text
}
export function safeText(value: string) { return value.replace(/[<>`*\[\]\\]/gu, '').replace(/\r/gu, '') }
export function enqueue(store: Store, key: string, channel: Channel, content: string, now: number, incident: string | null = null, expires: number | null = null) {
  store.run('INSERT OR IGNORE INTO deliveries(dedupe,incident,channel,content,next_at,expires,created) VALUES(?,?,?,?,?,?,?)', key, incident, channel, boundedText(content), now, expires, now)
}
export class DeliveryWorker {
  running = false
  constructor(public store: Store, public channels: Config['channels'], public send: typeof fetch = fetch) {}
  async tick(now = Date.now()) {
    if (this.running) return
    this.running = true
    try {
      this.store.run("UPDATE deliveries SET state='expired' WHERE state IN ('pending','dead') AND expires IS NOT NULL AND expires <= ?", now)
      for (const channel of ['operations', 'announcements'] as const) {
        const url = this.channels[channel]
        if (!url) continue
        const bot = createHash('sha256').update(url).digest('hex')
        const count = this.store.get<{ n: number }>('SELECT count(*) n FROM attempts WHERE channel=? AND at>?', bot, now - 60_000)!.n
        if (count >= 15) continue
        const row = this.store.get<{ id: number; content: string; attempts: number }>("SELECT * FROM deliveries WHERE channel=? AND state='pending' AND next_at<=? ORDER BY id LIMIT 1", channel, now)
        if (!row) continue
        this.store.run('INSERT INTO attempts(channel,at) VALUES(?,?)', bot, now)
        let error = ''
        try {
          const response = await this.send(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ msgtype: 'markdown', markdown: { content: row.content } }), signal: AbortSignal.timeout(10_000), redirect: 'error' })
          const body = await response.json() as { errcode?: number }
          if (!response.ok || body.errcode !== 0) error = `HTTP ${response.status}; errcode ${typeof body.errcode === 'number' ? body.errcode : 'invalid'}`
        } catch { error = 'Transport failure or invalid response' }
        if (!error) this.store.run("UPDATE deliveries SET state='sent',sent=?,attempts=attempts+1,error=NULL WHERE id=? AND state='pending'", now, row.id)
        else this.store.run('UPDATE deliveries SET state=?,attempts=attempts+1,next_at=?,error=? WHERE id=? AND state=\'pending\'', row.attempts >= 11 ? 'dead' : 'pending', now + Math.min(3600_000, 5000 * 2 ** row.attempts), error, row.id)
      }
    } finally { this.running = false }
  }
}
