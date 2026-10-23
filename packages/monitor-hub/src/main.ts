import { readFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import type { Config } from './model.js'
import { validateConfig } from './security.js'
import { Store } from './store.js'
import { Hub } from './hub.js'
import { Probes } from './probes.js'
import { DeliveryWorker } from './delivery.js'
import { createServers } from './server.js'

const file = process.env.MONITOR_CONFIG ?? '/etc/yumpoo-monitor/secrets.json'
const config = JSON.parse(readFileSync(file, 'utf8')) as Config
validateConfig(config)
mkdirSync(dirname(config.database), { recursive: true, mode: 0o700 })
const store = new Store(config.database)
const hub = new Hub(store, config)
const probes = new Probes(config)
const worker = new DeliveryWorker(store, config.channels)
const { ui, ingress } = await createServers(hub, alert => probes.fresh(alert), () => probes.discoverySuccess)
await ui.listen({ host: '127.0.0.1', port: 8787 })
await ingress.listen({ host: '127.0.0.1', port: 8788 })
let polling = false
const poll = async () => {
  if (polling) return
  polling = true
  try { hub.expireMaintenance(); await probes.discover(); await probes.poll(hub); hub.reconcileAlerts(alert => probes.fresh(alert)); await hub.verifyDeployments(event => probes.verify(event)); store.prune(Date.now()) }
  catch { console.error('Monitoring cycle failed; retained data is subject to freshness checks.') }
  finally { polling = false }
}
await poll()
const sampling = setInterval(() => { void poll() }, 30_000)
const delivery = setInterval(() => { void worker.tick().catch(() => console.error('Delivery cycle failed')) }, 4000)
// Only channel URLs can hot-reload; source credentials and listener settings require an explicit restart.
const reload = setInterval(() => {
  try {
    const next = JSON.parse(readFileSync(file, 'utf8')) as Config
    validateConfig(next)
    config.channels = next.channels; worker.channels = next.channels
  } catch { console.error('Configuration reload rejected') }
}, 30_000)
for (const signal of ['SIGTERM','SIGINT'] as const) process.on(signal, () => {
  clearInterval(sampling); clearInterval(delivery); clearInterval(reload)
  void Promise.all([ui.close(), ingress.close()]).then(() => process.exit(0))
})
