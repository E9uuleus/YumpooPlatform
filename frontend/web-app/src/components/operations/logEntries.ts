import type { OperationsLogEntry } from '@yumpoo/api-client'

export function mergeLogEntries(
  previous: OperationsLogEntry[],
  incoming: OperationsLogEntry[],
): OperationsLogEntry[] {
  const items = new Map([...previous, ...incoming].map((entry) => [entry.id, entry]))
  return [...items.values()]
    .sort((a, b) => {
      const time = b.record.time.getTime() - a.record.time.getTime()
      if (time) return time
      const prefix = (entry: OperationsLogEntry) => entry.id.slice(0, entry.id.lastIndexOf(':'))
      if (prefix(a) !== prefix(b)) return 0
      const order = (entry: OperationsLogEntry) =>
        BigInt(entry.seq ?? entry.id.slice(entry.id.lastIndexOf(':') + 1))
      const left = order(a),
        right = order(b)
      return left === right ? 0 : left < right ? 1 : -1
    })
    .slice(0, 500)
}
