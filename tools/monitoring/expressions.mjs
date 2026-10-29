export const WINDOWS_VOLUME = 'volume=~"[A-Z]:"'
export const expr = {
  windowsCpu: '1 - avg(rate(windows_cpu_time_seconds_total{mode="idle"}[5m]))',
  windowsMemoryAvailable: 'windows_memory_available_bytes / windows_memory_physical_total_bytes',
  windowsDiskFree: `windows_logical_disk_free_bytes{${WINDOWS_VOLUME}} / windows_logical_disk_size_bytes{${WINDOWS_VOLUME}}`,
  ubuntuCpu: '1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))',
  ubuntuMemoryAvailable: 'node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes',
  ubuntuRootFree: 'node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}',
}

// 保留规则中的字符串字面量，CI 逐字校验表达式与阈值。
export const hostAlerts = [
  { alert: 'WindowsCpuHigh', severity: 'warning', expr: expr.windowsCpu, op: '>', value: '0.85' },
  { alert: 'WindowsCpuHigh', severity: 'critical', expr: expr.windowsCpu, op: '>', value: '0.95' },
  { alert: 'WindowsMemoryLow', severity: 'critical', expr: expr.windowsMemoryAvailable, op: '<', value: '0.10' },
  { alert: 'WindowsDiskLow', severity: 'warning', expr: expr.windowsDiskFree, op: '<', value: '0.20' },
  { alert: 'WindowsDiskLow', severity: 'critical', expr: expr.windowsDiskFree, op: '<', value: '0.10' },
  { alert: 'UbuntuCpuHigh', severity: 'warning', expr: expr.ubuntuCpu, op: '>', value: '0.90' },
  { alert: 'UbuntuMemoryLow', severity: 'critical', expr: expr.ubuntuMemoryAvailable, op: '<', value: '0.10' },
  { alert: 'UbuntuDiskLow', severity: 'critical', expr: expr.ubuntuRootFree, op: '<', value: '0.15' },
]

export const thresholdsFor = key => hostAlerts.filter(alert => alert.expr === expr[key])
