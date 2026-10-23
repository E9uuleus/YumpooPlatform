import { createHmac, timingSafeEqual, randomBytes, scryptSync } from 'node:crypto'
import type { Config } from './model.js'
import type { Store } from './store.js'

export function equal(a: string, b: string) {
  const left = Buffer.from(a); const right = Buffer.from(b)
  return left.length === right.length && timingSafeEqual(left, right)
}
export function sign(secret: string, timestamp: string, nonce: string, body: string) {
  return createHmac('sha256', secret).update(`${timestamp}\n${nonce}\n${body}`).digest('hex')
}
export function authenticateSource(config: Config, store: Store, headers: Record<string, unknown>, body: string, now = Date.now()) {
  const source = String(headers['x-monitor-source'] ?? '')
  const timestamp = String(headers['x-monitor-time'] ?? '')
  const nonce = String(headers['x-monitor-nonce'] ?? '')
  const credential = config.sources[source]
  if (!credential || !/^\d{13}$/u.test(timestamp) || Math.abs(Number(timestamp) - now) > 300_000 || !/^[a-zA-Z0-9-]{16,100}$/u.test(nonce)
    || !equal(sign(credential.secret, timestamp, nonce, body), String(headers['x-monitor-signature'] ?? ''))) throw new Error('Unauthorized')
  if (store.get('SELECT id FROM nonces WHERE id=?', `${source}:${nonce}`)) throw new Error('Replay')
  store.run('INSERT INTO nonces VALUES(?,?)', `${source}:${nonce}`, now)
  return source
}
export function verifyPassword(config: Config, username: string, password: string) {
  return equal(username, config.admin.username) && equal(scryptSync(password, config.admin.salt, 64).toString('hex'), config.admin.passwordHash)
}
export function newToken() { return randomBytes(32).toString('hex') }
export function validateConfig(config: Config) {
  if (!config.environment || !/^https:\/\/[^/]+$/u.test(config.siteUrl) || !/^http:\/\/127\.0\.0\.1:\d+$/u.test(config.prometheusUrl)) throw new Error('Invalid endpoint configuration')
  if (!/^[a-f0-9]{128}$/u.test(config.admin.passwordHash) || config.admin.salt.length < 16 || config.alertToken.length < 32) throw new Error('Invalid credentials')
  for (const [source, value] of Object.entries(config.sources)) if (!['windows','github'].includes(source) || value.secret.length < 32) throw new Error('Invalid source credentials')
  for (const url of Object.values(config.channels)) if (url && !/^https:\/\/qyapi\.weixin\.qq\.com\/cgi-bin\/webhook\/send\?key=[a-zA-Z0-9-]+$/u.test(url)) throw new Error('Invalid channel URL')
  if (!config.services.length || config.services.some(s => !/^[a-z-]{1,40}$/u.test(s.id) || s.name.length > 80 || !s.checks.length)) throw new Error('Invalid services')
}
