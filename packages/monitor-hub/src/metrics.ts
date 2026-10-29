import type { Hub } from './hub.js'

const statusCodes: Record<string, number> = { healthy: 0, maintenance: 1, unknown: 2, down: 3 }

export function renderMetrics(hub: Hub, discoverySuccess: boolean, now = Date.now()) {
  const pending = hub.store.get<{ n: number }>("SELECT count(*) n FROM deliveries WHERE state='pending'")!.n
  const dead = hub.store.get<{ n: number }>("SELECT count(*) n FROM deliveries WHERE state='dead'")!.n
  const { services } = hub.status(now)
  const lines = [
    `monitor_delivery_pending ${pending}`,
    `monitor_delivery_dead ${dead}`,
    `monitor_discovery_success ${discoverySuccess ? 1 : 0}`,
    `monitor_process_timestamp_seconds ${now / 1000}`,
    '# HELP monitor_service_status Status page state: 0 healthy, 1 maintenance, 2 unknown, 3 down.',
    '# TYPE monitor_service_status gauge',
    ...services.map(service => `monitor_service_status{service="${service.id}"} ${statusCodes[service.state]}`),
    '# HELP monitor_service_observed_status Observed state ignoring maintenance: 0 healthy, 2 unknown, 3 down.',
    '# TYPE monitor_service_observed_status gauge',
    ...services.map(service => `monitor_service_observed_status{service="${service.id}"} ${statusCodes[service.observed]}`),
    '# HELP monitor_maintenance_active Active maintenance window by kind (automatic release or manual).',
    '# TYPE monitor_maintenance_active gauge',
  ]
  for (const service of services) {
    const active = hub.store.all<{ automatic: number }>("SELECT automatic FROM maintenance WHERE service=? AND state='active' AND starts<=? AND ends>? GROUP BY automatic", service.id, now, now)
    for (const [kind, automatic] of [['automatic', 1], ['manual', 0]] as const) {
      lines.push(`monitor_maintenance_active{service="${service.id}",kind="${kind}"} ${active.some(row => row.automatic === automatic) ? 1 : 0}`)
    }
  }
  return lines.join('\n') + '\n'
}
