import { assert } from './event-contract-compat.mjs'

export function assertRegisteredInventory(label, actual, frozen, registered) {
  const missing = [...frozen].filter(type => !actual.has(type)).sort()
  const unknown = [...actual].filter(type => !registered.has(type)).sort()
  assert(missing.length === 0 && unknown.length === 0,
    `${label} 缺少冻结事件=[${missing.join(', ')}]，未登记事件=[${unknown.join(', ')}]`)
}
